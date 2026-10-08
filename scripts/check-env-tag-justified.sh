#!/bin/sh
# scripts/check-env-tag-justified.sh —— 断言每个 @Tag(env) 测试都「确实需要部署环境」
#
# 背景（W32-A1）：门禁按 -PexcludeTags=env 排除这些用例，8 个被排除的用例里有 1 个
#   （rpg-guide HelpTopicTest.everyCustomHelpModelIsDeclaredByTheRuntimeResourcePack）
#   唯一依赖 resource-pack/runtime-item-models.txt —— 该文件早在 W12–W14 就入库了。
#   也就是说它**从来没跑过**，而且没有任何机制会说「这个标签打错了」。
#   过宽的豁免 = 本该生效的检查静默失效，这比门禁变红危险得多。
#
# 判据：对 plugins/**/src/test/**/*.java 里每个带 @Tag(env) 的测试方法，
#   抽取方法体内的字符串字面量里「像仓库内路径」的那些，逐个在**本仓**查存在性：
#     - 至少有一个路径在本仓不存在  ⇒ OK（依赖部署环境，标签正当）
#     - 引用的路径**全部**在本仓存在 ⇒ RED（标签过宽，这个测试本该能在 CI 里跑）
#     - 一个路径字面量都抽不到      ⇒ UNJUSTIFIED_UNKNOWN，计入告警并判 RED
#       （「抽不到就放过」正是这条判据自己会失效的形态，不允许）
#
# 用法：sh scripts/check-env-tag-justified.sh [仓库根]
# 退出码：0 = GREEN；1 = RED
# 判据行：ENV_TAG_JUSTIFIED_GATE / ENV_TAG_METHODS / ENV_TAG_UNJUSTIFIED

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=${1:-$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)}

if [ ! -d "$ROOT/plugins" ]; then
  echo "check-env-tag-justified: 找不到 $ROOT/plugins" >&2
  exit 1
fi

# 必须切到被检查的那棵树再扫描：不切的话 `grep -r plugins` 扫的是调用方的 CWD，
# 于是传进来的夹具根目录会被静默忽略（判据在夹具上恒看真实仓库 —— W32-A2 实测过，
# 脚本因此对夹具恒 GREEN）。
cd "$ROOT"

# 标签写法历史上换过（DeploymentEnvironmentFixtures.TAG / 直接字面量 "env"），用 ERE 覆盖
TAG_ERE='@Tag[ \t]*\([ \t]*([A-Za-z_][A-Za-z0-9_.]*\.TAG[ \t]*|[ \t]*"[^"]*"|[ \t]*[A-Za-z_][A-Za-z0-9_]*\.ENV[ \t]*\))'

# 先按固定标记捞候选文件（grep -E 的 -l 只给文件名，避免把 Java 源码喂给 ERE 解析器），
# 再由 python 用同一份 ERE 精确解析，杜绝 grep/解析器两套语法的漂移。
FILES=$(grep -rlF 'DeploymentEnvironmentFixtures.TAG' plugins 2>/dev/null | grep '/src/test/' | sort || true)

ROOT="$ROOT" TAG_ERE="$TAG_ERE" FILES="$FILES" python3 - <<'PYEOF'
import os
import re
import sys

root = os.environ['ROOT']
tag_re = re.compile(os.environ['TAG_ERE'])
files = [f for f in os.environ['FILES'].splitlines() if f.strip()]

# 起始目录白名单：只有这些前缀下的字面量才可能是「仓库内路径」。
PREFIXES = ('server/', 'plugins/', 'tools/', 'docs/', 'datapacks/', 'resource-pack/',
            'scripts/', 'plan/', 'state/', 'libs/', 'gradle/')
# 显式、可审计的注解键白名单：这些字面量不是「路径断言」，不做存在性判定。
ANNOTATION_KEYS = ('file:', 'classpath:', 'optional:')
# 路径字面量至少得有这种形状（避免把 "world-settings"、"sivita.quick.find" 当路径）
PATH_SHAPE = re.compile(r'^[A-Za-z0-9_.@+-]+(/[A-Za-z0-9_.@+-]+)+$')
STRING_RE = re.compile(r'"((?:[^"\\]|\\.)*)"')


def strip_comments(text):
    """去掉字符串外的 // 与 /* */ 注释，避免注释里的路径把方法体描述成别的样子。"""
    out = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c == '"':
            j = i + 1
            while j < n:
                if text[j] == '\\':
                    j += 2
                    continue
                if text[j] == '"':
                    break
                j += 1
            out.append(text[i:min(j + 1, n)])
            i = j + 1
        elif c == '/' and i + 1 < n and text[i + 1] == '/':
            j = text.find('\n', i)
            i = n if j < 0 else j
        elif c == '/' and i + 1 < n and text[i + 1] == '*':
            j = text.find('*/', i + 2)
            i = n if j < 0 else j + 2
        else:
            out.append(c)
            i += 1
    return ''.join(out)


def brace_body(text, open_idx):
    """从 '{' 起配对大括号（跳过字符串/字符字面量/注释），返回方法体。"""
    depth = 0
    i = open_idx
    n = len(text)
    while i < n:
        c = text[i]
        if c == '"':
            j = i + 1
            while j < n:
                if text[j] == '\\':
                    j += 2
                    continue
                if text[j] == '"':
                    break
                j += 1
            i = j + 1
            continue
        if c == "'":
            j = i + 1
            while j < n:
                if text[j] == '\\':
                    j += 2
                    continue
                if text[j] == "'":
                    break
                j += 1
            i = j + 1
            continue
        if c == '/' and i + 1 < n and text[i + 1] == '/':
            j = text.find('\n', i)
            i = n if j < 0 else j
            continue
        if c == '/' and i + 1 < n and text[i + 1] == '*':
            j = text.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return text[open_idx:i + 1]
        i += 1
    return ''


DECL_RE = re.compile(r'\bvoid\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(')


def method_after_tag(text, tag_end):
    """@Tag 之后的方法声明（允许中间夹其它注解与注释）。"""
    match = DECL_RE.search(text, tag_end)
    if not match:
        return None
    open_idx = text.find('{', match.end())
    if open_idx < 0:
        return None
    return match.group(1), brace_body(text, open_idx)


def looks_like_repo_path(literal):
    if literal.startswith('http') or literal.startswith('/'):
        return False
    if any(literal.startswith(key) for key in ANNOTATION_KEYS):
        return False
    prefix = literal.split('/', 1)[0] + '/'
    if not literal.startswith(PREFIXES) or prefix not in PREFIXES:
        return False
    return bool(PATH_SHAPE.match(literal))


def exists_in_repo(rel):
    """本仓存在性：文件或目录本身（os.path.exists 对目录也为真；
    测试常用目录前缀拼子路径，所以目录也算存在）。"""
    return os.path.exists(os.path.join(root, rel))


methods = []
problems = []
targets = []
for path in files:
    with open(path, encoding='utf-8', errors='replace') as handle:
        raw = handle.read()
    text = strip_comments(raw)
    for match in tag_re.finditer(text):
        found = method_after_tag(text, match.end())
        if not found:
            problems.append(os.path.join(root, path) + ': @Tag(env) 后找不到方法声明（解析失败）')
            continue
        name, body = found
        literals = {m.group(1) for m in STRING_RE.finditer(body)}
        refs = sorted({lit for lit in literals if looks_like_repo_path(lit)})
        present = [rel for rel in refs if exists_in_repo(rel)]
        absent = [rel for rel in refs if not exists_in_repo(rel)]
        methods.append((path, name, refs, present, absent))
        if absent:
            targets.append((path, name, absent))

method_count = len(methods)
unjustified = [m for m in methods if m[2] and not m[4]]
unknown = [m for m in methods if not m[2]]

print('ENV_TAG_METHODS=%d' % method_count)
print('ENV_TAG_JUSTIFIED=%d' % len(targets))
print('ENV_TAG_UNJUSTIFIED=%d' % (len(unjustified) + len(unknown)))

for path, name, refs, present, absent in methods:
    print('  - %s#%s' % (path, name))
    if refs:
        print('      引用路径=%s' % ', '.join(refs))
    else:
        print('      引用路径=(一个都抽不到)')
    for rel in present:
        print('      存在=%s' % rel)
    for rel in absent:
        print('      不存在=%s' % rel)

for path, name, absent in targets:
    print('MISSING_DEP %s#%s -> %s' % (path, name, ', '.join(absent)))
for path, name, refs, present, absent in unjustified:
    print('UNJUSTIFIED %s#%s -> 全部引用路径都在本仓: %s' % (path, name, ', '.join(refs)))
for path, name, refs, present, absent in unknown:
    print('UNJUSTIFIED_UNKNOWN %s#%s -> 方法体里抽不到任何仓库内路径字面量' % (path, name))
for problem in problems:
    print('UNPARSED %s' % problem)

if method_count == 0:
    print('check-env-tag-justified: 一个 @Tag(env) 方法都没抽到 —— 解析逻辑失效，判 RED')
    print('ENV_TAG_JUSTIFIED_GATE=RED')
    sys.exit(1)

if unjustified:
    print('check-env-tag-justified: 过宽的 env 标签 —— 这些方法的引用路径全部在本仓，'
          '本该在门禁里跑，却被打上 env 排除')
if unknown:
    print('check-env-tag-justified: 有 env 测试抽不到任何仓库内路径字面量 —— '
          '不许静默放过（要么补判据，要么显式白名单并说明）')
if problems:
    print('check-env-tag-justified: 有 @Tag(env) 的方法声明解析失败')

if unjustified or unknown or problems:
    print('ENV_TAG_JUSTIFIED_GATE=RED')
    sys.exit(1)

print('ENV_TAG_JUSTIFIED_GATE=GREEN')
sys.exit(0)
PYEOF
