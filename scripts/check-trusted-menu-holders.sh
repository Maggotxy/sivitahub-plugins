#!/bin/sh
# 菜单宿主 opt-in 门禁（G-302 第二批）
#
# 失败模式：市场容器转移策略只豁免两类背包 —— 玩家自己的，和显式 opt-in 的
# TrustedMenuHolder。历史上它豁免「类名以 cn.originalrpg. 开头的任何 holder」，
# 于是以后每写一个新 GUI 都自动获得豁免。这条缝已被换成显式标记；
# 本门禁防止它被悄悄改回去：
#   1) TradeRestrictionListener 必须通过 instanceof TrustedMenuHolder 判定；
#   2) 不得再出现命名空间前缀信任（startsWith("cn.originalrpg.")）；
#   3) plugins 下 src/main 里不得有裸 implements InventoryHolder 的类。
#
# 为什么在 scripts/ 而不在 JUnit：第 3 条是**结构扫描**，覆盖的是「还没被任何
# 运行期路径构造出来的菜单类」。这类「新写的类必须 opt-in」的约束在运行期不可观测。
# rpg-market 的 TrustedMenuBoundaryContractTest 会真实执行本脚本，所以它仍参与
# `gradlew test` 的判据。
#
# 用法：sh scripts/check-trusted-menu-holders.sh；退出码 0 = 通过
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

LISTENER=plugins/rpg-market/src/main/java/cn/originalrpg/market/TradeRestrictionListener.java
STATUS=0

if [ ! -f "$LISTENER" ]; then
  echo "TRUSTED_MENU_GATE=RED 找不到 $LISTENER" >&2
  exit 2
fi

if ! grep -Fq 'holder instanceof TrustedMenuHolder' "$LISTENER"; then
  echo "TRUSTED_MENU_GATE=RED isTrustedInventory 必须通过 TrustedMenuHolder 标记判定" >&2
  STATUS=1
fi

if grep -Fq 'startsWith("cn.originalrpg.")' "$LISTENER"; then
  echo "TRUSTED_MENU_GATE=RED 命名空间前缀信任不得回归：$LISTENER" >&2
  STATUS=1
fi

# 裸 implements InventoryHolder（允许 implements 列表里同时出现 TrustedMenuHolder）
OFFENDERS=$(grep -rlE --include=*.java '\bimplements\s+[A-Za-z0-9_., ]*\bInventoryHolder\b' plugins \
  | grep '/src/main/' \
  | while IFS= read -r file; do
      if grep -qE '\bimplements\s+[A-Za-z0-9_., ]*TrustedMenuHolder\b' "$file"; then
        continue
      fi
      echo "$file"
    done || true)

if [ -n "$OFFENDERS" ]; then
  echo "TRUSTED_MENU_GATE=RED 以下菜单宿主必须实现 TrustedMenuHolder，而不是裸 InventoryHolder：" >&2
  echo "$OFFENDERS" >&2
  STATUS=1
fi

if [ "$STATUS" -ne 0 ]; then
  echo "TRUSTED_MENU_GATE=RED"
  exit 1
fi
echo "TRUSTED_MENU_GATE=GREEN"
