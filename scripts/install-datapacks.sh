#!/bin/sh
# scripts/install-datapacks.sh —— 把仓库内的数据包部署到服务端 world/datapacks/
#
# 背景（G-206）：`server/bin/verify-installation.sh` 硬依赖 `#minecraft:quick_actions`
# 数据包，但该数据包过去不在仓库里（`server/` 被 .gitignore 排除），部署校验在生产机上
# 必然找不到产物。现在数据包源码入库到 `datapacks/<名>/`，本脚本是唯一的部署入口：
# 先校验仓库内的数据包，再（默认）复制到服务端。
#
# 用法：
#   sh scripts/install-datapacks.sh --check                 # 只校验仓库内数据包，不写任何东西
#   sh scripts/install-datapacks.sh <服务端目录>             # 校验 + 部署
#   sh scripts/install-datapacks.sh <服务端目录> --check     # 等价 --check（不接受目标目录）
#
# <服务端目录> = 含 world/ 的 Paper 服务端根目录（生产机上是 /home/lamb/minecraft/server）。
# 未给目录时用环境变量 SIVITA_SERVER_DIR。
#
# 退出码：0 = 校验通过（--check）/ 部署成功；1 = 校验失败或部署失败；2 = 用法错误

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
cd "$ROOT"

SOURCE_DIR="$ROOT/datapacks"
CHECK_ONLY=0
SERVER_DIR=""

for arg in "$@"; do
  case "$arg" in
    --check) CHECK_ONLY=1 ;;
    -*) echo "install-datapacks: 未知参数 $arg" >&2; exit 2 ;;
    *) SERVER_DIR="$arg" ;;
  esac
done

[ -n "$SERVER_DIR" ] || SERVER_DIR="${SIVITA_SERVER_DIR:-}"

# Validate the native dialog registry, command targets, translations and 26.2 format.
# The checker does not read a running server or write deployment files.
python3 "$SCRIPT_DIR/check-navigation.py" --root "$ROOT"

if [ "$CHECK_ONLY" -eq 1 ]; then
  echo "install-datapacks: --check 只校验仓库内数据包，未写任何文件"
  exit 0
fi

# ---------------------------------------------------------------- 2. 部署

if [ -z "$SERVER_DIR" ]; then
  echo "install-datapacks: 未指定服务端目录。" >&2
  echo "  用法: sh scripts/install-datapacks.sh <含 world/ 的服务端根目录>" >&2
  echo "  或设置环境变量 SIVITA_SERVER_DIR（生产机: /home/lamb/minecraft/server）" >&2
  echo "  只校验: sh scripts/install-datapacks.sh --check" >&2
  exit 2
fi

if [ ! -d "$SERVER_DIR" ]; then
  echo "install-datapacks: 服务端目录不存在：$SERVER_DIR" >&2
  exit 1
fi

WORLD_DIR="$SERVER_DIR/world"
TARGET_DIR="$WORLD_DIR/datapacks"
if [ ! -d "$WORLD_DIR" ]; then
  echo "install-datapacks: 找不到 $WORLD_DIR（服务端目录必须含 world/）" >&2
  exit 1
fi
mkdir -p "$TARGET_DIR"

for pack in "$SOURCE_DIR"/*/; do
  name=$(basename "$pack")
  [ "$name" = "*" ] && continue
  rm -rf "$TARGET_DIR/$name"
  cp -R "$pack" "$TARGET_DIR/$name"
  echo "install-datapacks: [部署] $name -> $TARGET_DIR/$name"
done

echo "install-datapacks: 部署完成。重启 Paper 后生效；随后运行"
echo "  sh <服务端>/bin/verify-installation.sh   # 会检查 $TARGET_DIR 下的数据包"
