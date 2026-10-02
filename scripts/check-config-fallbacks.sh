#!/bin/sh
# 配置回退字面量门禁（G-302 第二批）
#
# 失败模式：生产代码用 `getString(key, "400.00")` 之类给出「缺配置时的回退价」。
# 如果这个默认值被删掉（变成 `getString(key)`），缺配置时会静默退化成 0 / 抛异常，
# 而「回退价仍是评审过的数字」这件事在运行期之外没有别的机械判据。
#
# 本门禁只做一件在运行期无法观测的事：断言这些价格键的每次查找都带显式非空默认值。
# 它不是「行为测试的替代」，而是「无法在测试 classpath 里执行 Plugin.getConfig()
# 时，对构建契约的最低保障」。真正解析出来的价格由 rpg-enhancement 的
# MutationConfigurationContractTest（真实 SnakeYAML 解析 config.yml）覆盖。
#
# 用法：sh scripts/check-config-fallbacks.sh；退出码 0 = 全部带默认值
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

ENH=plugins/rpg-enhancement/src/main/java/cn/originalrpg/enhancement
STATUS=0

require_default() {
  # $1 = 文件, $2 = 键片段（如 refinement.price）, $3 = 期望默认值
  file="$1"; key="$2"; expected="$3"
  if ! grep -Fq "\"$key\", \"$expected\"" "$file"; then
    echo "CONFIG_FALLBACK=RED $file 缺少 $key 的显式默认值 \"$expected\"" >&2
    STATUS=1
  fi
}

require_default "$ENH/RefineCommand.java" "refinement.price" "400.00"
require_default "$ENH/LimitBreakCommand.java" "limit-break.base-price" "1200.00"
require_default "$ENH/MutationCommand.java" "price" "3500.00"
require_default "$ENH/MutationCommand.java" "restore-price" "900.00"

if [ "$STATUS" -ne 0 ]; then
  echo "CONFIG_FALLBACK_GATE=RED"
  exit 1
fi
echo "CONFIG_FALLBACK_GATE=GREEN"
