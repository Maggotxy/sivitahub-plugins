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
# 规格目录（W16b-A1）：默认加载 scripts/source-contract-specs/ 下**全部** *.json 并合并
#   —— 后续每个模块自己加一份规格文件（rpg-items.json / rpg-dungeons.json / ...），
#   不必再抢改同一个 all.json。合并按文件名排序，重复运行输出一致。
#   · 同一个 spec 键出现在两个文件里 ⇒ 直接 RED 并点名是两个文件
#     （静默覆盖会让后写的任务把先写的契约整段吞掉，是最危险的失效形态）
#   · 向后兼容：SOURCE_CONTRACT_SPEC=<单个文件> 时只加载该文件
#     （selftest 的夹具与 *ContractTest 的单键用法都走这条路径）
#   · SOURCE_CONTRACT_SPECS_DIR=<目录> 只用于门禁自测，CI 不设置
#
# 用法：sh scripts/check-source-contract.sh <spec-key>|--all [<method>]
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

SPEC=${SOURCE_CONTRACT_SPEC:-}
SPECS_DIR=${SOURCE_CONTRACT_SPECS_DIR:-scripts/source-contract-specs}
if [ -n "$SPEC" ] && [ ! -f "$SPEC" ]; then
  echo "SOURCE_CONTRACT_GATE=RED 找不到规格文件 $SPEC" >&2
  exit 2
fi
if [ -z "$SPEC" ] && [ ! -d "$SPECS_DIR" ]; then
  echo "SOURCE_CONTRACT_GATE=RED 找不到规格目录 $SPECS_DIR" >&2
  exit 2
fi

KEY="${1:---all}"
METHOD="${2:-}"

exec python3 - "$ROOT" "$SPEC" "$SPECS_DIR" "$KEY" "$METHOD" <<'PY'
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


root, spec_path, specs_dir, key, method = sys.argv[1:6]


def load_merged():
    """返回 (合并后的规格, 加载的文件清单)；键冲突时打印 RED 后 exit 2。

    · SOURCE_CONTRACT_SPEC 指定单文件时只加载它（向后兼容）
    · 否则加载规格目录下全部 *.json，按文件名排序（确定性）
    """
    if spec_path:
        sources = [spec_path]
    else:
        directory = specs_dir if os.path.isabs(specs_dir) else os.path.join(root, specs_dir)
        sources = sorted(
            os.path.join(directory, name)
            for name in os.listdir(directory)
            if name.endswith('.json')
        )
        if not sources:
            print(f'SOURCE_CONTRACT_GATE=RED 规格目录里没有任何 *.json: {specs_dir}',
                  file=sys.stderr)
            sys.exit(2)
    merged, origins, conflicts = {}, {}, []
    for source in sources:
        try:
            data = json.load(open(source, encoding='utf-8'))
        except ValueError as error:
            print(f'SOURCE_CONTRACT_GATE=RED 规格文件不是合法 JSON: {source} -> {error}',
                  file=sys.stderr)
            sys.exit(2)
        for spec_key, entry in data.items():
            if spec_key in merged:
                # 点名两个文件：只说「键重复」没法定位是哪个任务抢了哪个键
                conflicts.append((spec_key, origins[spec_key], source))
                continue
            merged[spec_key] = entry
            origins[spec_key] = source
    if conflicts:
        for spec_key, first, second in conflicts:
            print(f'SOURCE_CONTRACT_GATE=RED 规格键冲突: {spec_key!r} 同时出现在 '
                  f'{first} 与 {second}（不允许静默覆盖）', file=sys.stderr)
        sys.exit(2)
    return merged, sources


spec, spec_files = load_merged()
print(f'SOURCE_CONTRACT_SPEC_FILES={len(spec_files)}', file=sys.stderr)

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
