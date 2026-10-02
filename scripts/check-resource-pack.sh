#!/bin/sh
# scripts/check-resource-pack.sh —— 资源包素材门禁（W7-A1）
#
# 失败模式（本门禁要掐死的形态）：resource-pack/ 已经入库，但它的校验只存在于
# 生产机的 resource-pack/build.sh 里 —— 那一版依赖 Paper / server/ / zip / rg /
# jq，在 CNB 里根本跑不起来。结果是**素材可以坏着进 main 而没有任何人知道**：
# 删一个 items 定义、把某张 PNG 改成 16x16、把 HUD 图集裁掉一列、改一个 lang 键，
# 生产机的下一次 build.sh 才会红，而那时包已经合进 main 了。
#
# 判据以本脚本为唯一口径（不复制 build.sh 的逻辑，避免两套漂移）：
#   1. runtime-item-models.txt（去注释去空行）与 assets/originalrpg/items/*.json 一一对应
#   2. 每个 item 定义能闭包到存在的 model → texture（孤儿模型 / 孤儿纹理都要报出来）
#   3. 所有物品 PNG 为 32x32，且 alpha 只有 0 或 255（拒绝抗锯齿的半透明）
#   4. font/hud.json 的 U+E000..U+E00A 十一码位与 99x9 图集严格一致（11 枚 9x9 字形）
#   5. font/journey.json 的 U+E100..U+E107 八码位与八张旅程原图严格一致
#   6. lang/en_us.json 与 lang/zh_cn.json 键集合一致
#   7. 每个 traveler_compass_*.json 的 layer0 纹理与 26.2 的 33 段方向分发一致
#   8. pack.mcmeta 精确声明资源格式 88（min=max=88、supported_formats=[88,88]、无 overlays）
#
# 环境约束（CNB 的 eclipse-temurin:25-jdk 里跑 sh=dash）：
#   * 不依赖生产机、不依赖 Paper；只用 POSIX sh + sed/grep/awk + python3
#   * PNG 解码与 JSON 解析走 python3（stdlib zlib/struct/json，不装 Pillow）
#   * **不用 jq、不用 ImageMagick**：镜像里没有，也不该为素材门禁引入新依赖
#
# 用法：sh scripts/check-resource-pack.sh              # 仓库根由脚本位置解析
#       sh scripts/check-resource-pack.sh --root <dir> # 指定仓库根（负向对照夹具用）
#       sh scripts/check-resource-pack.sh --tests <re> # 只跑名字匹配的判据（夹具/取证用）
# 退出码 0 = 全绿；非 0 = 至少一条判据红（stderr 打印人可读原因）
#
# 负向对照（每条判据都被证明「能真的变红」）在 scripts/selftest-gates.sh §7；
# 那里在 $TMP 的夹具副本上破坏文件，不碰仓库工作区。

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

# --root 覆盖只给自测夹具用；CI 不传，永远校验仓库自身。
while [ $# -gt 0 ]; do
  case "$1" in
    --root) ROOT=$2; shift 2 ;;
    --tests) RESOURCE_PACK_GATE_ONLY=$2; shift 2 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done
# --tests 是「负向对照专用」的收敛开关：只跑匹配的判据，其余跳过。
# 它的存在本身有个隐蔽风险：写错正则会静默变成「一条都没跑」的假绿门禁。
# 因此脚本在收尾时断言「至少有一条判据真的跑过」，否则直接 RED。
ONLY=${RESOURCE_PACK_GATE_ONLY:-}

ASSETS="$ROOT/resource-pack/assets/originalrpg"
MANIFEST="$ROOT/resource-pack/runtime-item-models.txt"

if [ ! -d "$ASSETS" ]; then
  echo "RESOURCE_PACK_GATE=RED 找不到资源包资产目录: $ASSETS" >&2
  exit 2
fi
if [ ! -f "$MANIFEST" ]; then
  echo "RESOURCE_PACK_GATE=RED 找不到模型键清单: $MANIFEST" >&2
  exit 2
fi

command -v python3 >/dev/null 2>&1 || {
  echo "RESOURCE_PACK_GATE=RED 需要 python3 解析 PNG/JSON（CI 由「准备下载器」stage 安装）" >&2
  exit 2
}

# ---- 判据注册：每条判据是一个函数，失败时非零退出 ----
# 用「名字 + 函数」两张清单而不是 case：dash 下不能声明数组，
# 也不能用 ${!name} 间接展开，所以显式 word-split 调用。
GATE_NAMES='manifest closure textures hud journey lang compass packmeta'
GATE_FAILED=''
GATE_RAN=0
GATE_SKIPPED=0

should_run() {
  # should_run <判据名>
  [ -z "$ONLY" ] && return 0
  printf '%s\n' "$ONLY" | grep -qE "(^| )$1( |$)"
}

run_gate() {
  # run_gate <判据名> <函数名>
  if ! should_run "$1"; then
    GATE_SKIPPED=$((GATE_SKIPPED + 1))
    return 0
  fi
  GATE_RAN=$((GATE_RAN + 1))
  if "$2"; then
    echo "  [OK  ] $1"
  else
    echo "  [RED ] $1" >&2
    GATE_FAILED="$GATE_FAILED $1"
  fi
}

# ---------------------------------------------------------------- 判据 1
# manifest 与 items 定义一一对应（集合相等，不只看「清单里的都有定义」）
gate_manifest() {
  sed -e 's/[[:space:]]*#.*$//' -e '/^[[:space:]]*$/d' "$MANIFEST" | LC_ALL=C sort > "$TMP_DIR/manifest.txt"
  find "$ASSETS/items" -maxdepth 1 -type f -name '*.json' -printf '%f\n' 2>/dev/null \
    | sed 's/\.json$//' | LC_ALL=C sort > "$TMP_DIR/items.txt"
  # 空目录会让 diff 变成「单向缺 81 个」而不是「目录没了」，明确报出来
  if [ ! -s "$TMP_DIR/items.txt" ]; then
    echo "assets/originalrpg/items 下没有任何 .json 物品定义" >&2
    return 1
  fi
  # 重复键 / 非法字符与一一对应同源，放在同一条判据里：清单是「权威键集」，
  # 它自己坏了（重复、大小写、路径字符）同样是素材门禁要拦的形态。
  dup=$(LC_ALL=C sort "$TMP_DIR/manifest.txt" | uniq -d)
  if [ -n "$dup" ]; then
    echo "runtime-item-models.txt 有重复键: $(printf '%s' "$dup" | tr '\n' ' ')" >&2
    return 1
  fi
  invalid=$(grep -vE '^[a-z0-9_./-]+$' "$TMP_DIR/manifest.txt" || true)
  if [ -n "$invalid" ]; then
    echo "runtime-item-models.txt 有非法键: $(printf '%s' "$invalid" | tr '\n' ' ')" >&2
    return 1
  fi
  if ! diff -u "$TMP_DIR/manifest.txt" "$TMP_DIR/items.txt" > "$TMP_DIR/manifest.diff" 2>&1; then
    echo "runtime-item-models.txt 与 items/*.json 不是一一对应：" >&2
    sed -n '1,40p' "$TMP_DIR/manifest.diff" >&2
    return 1
  fi
  return 0
}

# ---------------------------------------------------------------- 判据 2
# item 定义 → model →（parent 链）→ texture 闭包；孤儿模型 / 孤儿纹理都要报
gate_closure() {
  python3 "$ROOT/scripts/resource-pack-asset-check.py" --root "$ROOT" --check closure
}

# ---------------------------------------------------------------- 判据 3
gate_textures() {
  python3 "$ROOT/scripts/resource-pack-asset-check.py" --root "$ROOT" --check textures
}

# ---------------------------------------------------------------- 判据 4
gate_hud() {
  python3 "$ROOT/scripts/resource-pack-asset-check.py" --root "$ROOT" --check hud
}

# ---------------------------------------------------------------- 判据 5
gate_journey() {
  python3 "$ROOT/scripts/resource-pack-asset-check.py" --root "$ROOT" --check journey
}

# ---------------------------------------------------------------- 判据 6
gate_lang() {
  python3 "$ROOT/scripts/resource-pack-asset-check.py" --root "$ROOT" --check lang
}

# ---------------------------------------------------------------- 判据 7
gate_compass() {
  python3 "$ROOT/scripts/resource-pack-asset-check.py" --root "$ROOT" --check compass
}

# ---------------------------------------------------------------- 判据 8
gate_packmeta() {
  python3 "$ROOT/scripts/resource-pack-asset-check.py" --root "$ROOT" --check packmeta
}

TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

echo "RESOURCE_PACK_GATE: 校验 $ROOT/resource-pack"

for name in $GATE_NAMES; do
  run_gate "$name" "gate_$name"
done

echo "  （判据：$GATE_NAMES）"
echo "  RUN=$GATE_RAN SKIPPED=$GATE_SKIPPED"

# 防「假绿」：--tests 收敛开关写错一个字母就会一条判据都不跑，
# 日志看起来仍然像「跑过了」。没有判据真跑过 = 门禁没有意义 = RED。
if [ "$GATE_RAN" -eq 0 ]; then
  echo "RESOURCE_PACK_GATE=RED 一条判据都没跑（--tests 过滤写错了？）" >&2
  exit 1
fi

if [ -n "$GATE_FAILED" ]; then
  echo "RESOURCE_PACK_GATE=RED FAILED:$GATE_FAILED"
  exit 1
fi
echo "RESOURCE_PACK_GATE=GREEN"
exit 0
