#!/bin/sh
# 源码结构/顺序契约门禁（G-302 第二批）
#
# 背景：本仓有一批测试断言的**不是运行期行为**，而是「生产代码里某段调用序列的先后」
# 或「某个字面量必须/不得出现」。典型例子：异步回调必须先判插件启用、换季提交必须先落
# 恢复信封再移除输入。这类断言无法在 JUnit 里行为化：生产类是 JavaPlugin / 需要真实
# Bukkit 服务器，而本仓不含 MockBukkit 或 Mockito（见 PR 说明的负向证据）。
#
# 本门禁把这些断言从「散落在各测试类的字符串扫描」收敛成**一份可审计的声明式规格**
# scripts/source-contract-specs/all.json，由本脚本统一执行。规格由原测试的断言逐条迁移
# 而来（required / forbidden / ordered 三类），覆盖不降级：
#   required  : 该片段必须出现在本类声明的某个 main 文件里
#   forbidden : 该片段不得出现在本类声明的任何 main 文件里
#   ordered   : 该序列必须在**同一个**文件里按顺序出现（等价原 indexOf 链）
#
# 每个契约仍由对应的 JUnit 类真实执行本脚本（见 *ContractTest 包装器），
# 因此「改坏生产代码 ⇒ 门禁 RED ⇒ 那个测试 RED」依然成立。
#
# 用法：sh scripts/check-source-contract.sh <spec-key>|--all [<method>]
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

SPEC=${SOURCE_CONTRACT_SPEC:-scripts/source-contract-specs/all.json}
if [ ! -f "$SPEC" ]; then
  echo "SOURCE_CONTRACT_GATE=RED 找不到规格文件 $SPEC" >&2
  exit 2
fi

KEY="${1:---all}"
METHOD="${2:-}"

exec python3 - "$SPEC" "$KEY" "$METHOD" <<'PY'
import json, sys, os


def ordered_in(text, sequence):
    """序列是否按顺序出现在同一段文本里。"""
    cursor = 0
    for needle in sequence:
        found = text.find(needle, cursor)
        if found < 0:
            return False
        cursor = found + len(needle)
    return True


spec_path, key, method = sys.argv[1], sys.argv[2], sys.argv[3]
spec = json.load(open(spec_path, encoding='utf-8'))

if key == '--all':
    keys = sorted(spec)
else:
    keys = [key] if key in spec else [k for k in spec if k.endswith(key)]
    if not keys:
        print(f'SOURCE_CONTRACT_GATE=RED 规格里没有 {key}', file=sys.stderr)
        sys.exit(2)

failures = []
checked = 0
for cls in keys:
    entry = spec[cls]
    contents = {}
    for rel in entry['files']:
        if not os.path.isfile(rel):
            failures.append(f'{cls}: 缺少被断言的生产文件 {rel}')
            continue
        contents[rel] = open(rel, encoding='utf-8').read()
    for check in entry['checks']:
        if method and check['method'] != method:
            continue
        label = f"{os.path.basename(cls)}::{check['method']}"
        for needle in check['required']:
            checked += 1
            if not any(needle in text for text in contents.values()):
                failures.append(f'{label}: 必须出现但缺失 -> {needle!r}')
        for needle in check['forbidden']:
            checked += 1
            for rel, text in contents.items():
                if needle in text:
                    failures.append(f'{label}: 不得出现但命中 {rel} -> {needle!r}')
        if check['ordered']:
            checked += 1
            sequence = check['ordered']
            if not any(ordered_in(text, sequence) for text in contents.values()):
                failures.append(
                    f"{label}: 顺序契约不成立（必须按序出现在同一个文件）-> {sequence!r}")
print(f'SOURCE_CONTRACT_CHECKS={checked} SPECS={len(keys)}')
if failures:
    for failure in failures:
        print('SOURCE_CONTRACT_GATE=RED ' + failure, file=sys.stderr)
    sys.exit(1)
print('SOURCE_CONTRACT_GATE=GREEN')
sys.exit(0)


PY
