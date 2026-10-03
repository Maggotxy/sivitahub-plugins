#!/bin/sh
# G-302-RESIDUAL 判据门禁：还有哪些测试类在「读本仓生产源码 + 做源码字符串断言」？
#
# 判据（GOAL.md §2 第 2 条）：一个 *Test 方法体内同时出现
#   ① Files.readString/Files.readAllBytes 读**本仓生产源码或清单/构建文件**
#      （路径前缀 plugins/ 或 tools/），以及
#   ② .contains(...) / .indexOf(...) 断言
# 即为「源码字符串伪测试」。读 server/*、resource-pack/* 的部署产物断言不算（本仓按设计
# 不含 server/，那些测试带 @Tag(env) 且被 .cnb.yml 的 excludeTags 显式分类）。
#
# 用法：sh scripts/check-source-string-tests.sh [--json]
# 退出码：0 = 本仓生产源码里已无此类断言；1 = 仍有命中（打印文件:行:方法）
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

exec python3 - "$@" <<'PY'
import json, re, sys, glob

PROD_PREFIXES = ('plugins/', 'tools/')


def method_bodies(src):
    out, lines, i = [], src.split('\n'), 0
    while i < len(lines):
        if re.match(r'\s*@Test\b', lines[i]):
            j = i
            while j < len(lines) and 'void ' not in lines[j]:
                j += 1
            if j >= len(lines):
                break
            name = re.search(r'void\s+(\w+)', lines[j]).group(1)
            body, depth, started, k = [], 0, False, j
            while k < len(lines):
                body.append(lines[k])
                depth += lines[k].count('{') - lines[k].count('}')
                if '{' in lines[k]:
                    started = True
                if started and depth <= 0:
                    break
                k += 1
            out.append((name, i + 1, '\n'.join(body)))
            i = k + 1
        else:
            i += 1
    return out


hits = []
for path in sorted(glob.glob('plugins/**/src/test/**/*.java', recursive=True)
                   + glob.glob('tools/**/src/test/**/*.java', recursive=True)):
    src = open(path, encoding='utf-8').read()
    if 'Files.readString' not in src and 'Files.readAllBytes' not in src:
        continue
    for name, line, body in method_bodies(src):
        if 'Files.readString' not in body and 'Files.readAllBytes' not in body:
            continue
        if not re.search(r'\.(contains|x?indexOf)\(', body):
            continue
        prod = [p for p in re.findall(r'"((?:plugins|tools)/[^"]+)"', body)
                if p.startswith(PROD_PREFIXES)]
        if prod:
            hits.append({'file': path, 'line': line, 'method': name,
                         'production_paths': sorted(set(prod))})

if '--json' in sys.argv:
    print(json.dumps({'hits': hits, 'count': len(hits)}, ensure_ascii=False, indent=1))

print(f'SOURCE_STRING_TESTS={len(hits)}')
for hit in hits:
    print(f"  {hit['file']}:{hit['line']} {hit['method']}() -> {hit['production_paths'][:2]}")
if hits:
    print('SOURCE_STRING_GATE=RED 仍有测试类在「读本仓生产源码 + 源码字符串断言」')
    sys.exit(1)
print('SOURCE_STRING_GATE=GREEN 本仓生产源码已无源码字符串断言（部署产物豁免见脚本头部口径）')
sys.exit(0)
PY
