#!/bin/sh
# scripts/check-file-deps.sh —— 断言每个 compileOnly(files(...)) 的文件依赖都在清单里
#
# 背景：仓库曾有 8 处 compileOnly(files("server/runtime/plugins/...")) 指向被 .gitignore
#       忽略的路径，干净克隆无法编译（gap G-401）。本检查是防复发的机械门禁：
#         「build.gradle.kts 里每一个 files(...) 引用的文件，必须出现在
#           scripts/fetch-deps.sh 的拉取清单（PHASE_FETCH）或 vendor 清单（PHASE_VENDOR）里」
#
# 用法：sh scripts/check-file-deps.sh
# 退出码：0 = 通过；1 = 有文件依赖不在清单里

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

# 清单真相源 = fetch-deps.sh 里的 PHASE 行；用 python 榨取（顺手剥引号），
# 避免两处清单各自漂移。
ALL=$(python3 - "$SCRIPT_DIR/fetch-deps.sh" <<'PY'
import re, sys
src = open(sys.argv[1], encoding='utf-8').read()
items = []
for key in ('PHASE_FETCH', 'PHASE_VENDOR'):
    m = re.search(r'^' + key + r'="([^"]*)"', src, re.M)
    if not m:
        sys.stderr.write('check-file-deps: fetch-deps.sh 缺少 ' + key + ' 行\n')
        sys.exit(1)
    items += m.group(1).split()
for item in sorted(set(items)):
    print(item)
PY
) || exit 1

if [ -z "$ALL" ]; then
    echo "check-file-deps: 清单为空（PHASE_FETCH/PHASE_VENDOR 未声明）" >&2
    exit 1
fi

# 收集 build.gradle.kts 里所有 libs/ 文件依赖的仓库相对路径（去重）。
# 用 python 做括号配平解析，兼容 rootProject.file(...) 与跨行 files(...)。
REFS=$(python3 - "$ROOT" <<'PY'
import os, re, sys
root = sys.argv[1]
found = set()
FILES = re.compile(r'\bfiles\s*\(')
LIT = re.compile(r'"([^"]*)"')


def balanced_body(src, open_paren):
    depth = 0
    for i in range(open_paren, len(src)):
        ch = src[i]
        if ch == '(':
            depth += 1
        elif ch == ')':
            depth -= 1
            if depth == 0:
                return src[open_paren + 1:i]
    return ''


for dirpath, dirnames, filenames in os.walk(root):
    dirnames[:] = [d for d in dirnames if d not in ('.git', 'build', '.gradle')]
    for fn in filenames:
        if not fn.endswith('.gradle.kts'):
            continue
        src = open(os.path.join(dirpath, fn), encoding='utf-8').read()
        for m in FILES.finditer(src):
            for path in LIT.findall(balanced_body(src, m.end() - 1)):
                norm = path.replace('${rootProject.projectDir}/', '')
                norm = norm.lstrip('./')
                if norm.startswith('libs/'):
                    found.add(norm)
print('\n'.join(sorted(found)))
PY
)

if [ -z "$REFS" ]; then
    echo "check-file-deps: 未发现任何 libs/ 下的文件依赖（可能解析规则需要更新）" >&2
    exit 1
fi

FAIL=0
echo "check-file-deps: 清单内条目："
printf '%s\n' "$ALL" | sed 's/^/  - /'

echo "check-file-deps: 校验 build.gradle.kts 的 files(...) 引用："
for ref in $REFS; do
    if printf '%s\n' "$ALL" | grep -qx "$ref"; then
        echo "  [OK  ] $ref"
    else
        echo "  [FAIL] $ref —— 不在 fetch-deps.sh 清单里，干净克隆会编译失败" >&2
        FAIL=1
    fi
done

# 反向检查：清单里有、但没人引用的条目（提示，不算失败）
for item in $ALL; do
    if ! printf '%s\n' "$REFS" | grep -qx "$item"; then
        echo "  [WARN] $item 在清单里但无 build.gradle.kts 引用（可能是历史遗留）"
    fi
done

if [ "$FAIL" -ne 0 ]; then
    echo "check-file-deps: FAILED" >&2
    exit 1
fi
echo "check-file-deps: OK"
