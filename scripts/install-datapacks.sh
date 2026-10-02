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

# ---------------------------------------------------------------- 1. 校验
#
# 判据（任一不满足即 RED）：
#   a) 每个数据包目录都有合法的 pack.mcmeta（JSON 且含 pack.pack_format 整数）
#   b) #minecraft:quick_actions 的 tag 文件存在、是合法 JSON、values 非空
#   c) tag 引用的每个函数都在仓内存在（`<namespace>:<path>` -> data/<ns>/function/<path>.mcfunction）
#   d) 每个函数都至少含一条 `run <已注册命令>`，且被 tag 引用的函数集恰好等于
#      docs/GAME_DESIGN.md 承诺的 8 个快捷动作数（少了 = 入口缺失，多了 = 没人评审的新按钮）
#
# 目标目录必须已经存在（服务端停服状态）；不自动 mkdir，避免把数据包部署进一个
# 路径拼错的新目录里，然后在 Paper 侧「静默没加载」。

python3 - "$ROOT" "$SOURCE_DIR" <<'PY'
import glob, json, os, re, sys

root, source_dir = sys.argv[1], sys.argv[2]
problems = []

TAG_REL = "data/minecraft/tags/function/quick_actions.json"
EXPECTED_BUTTONS = 8

packs = sorted(
    name for name in os.listdir(source_dir)
    if os.path.isdir(os.path.join(source_dir, name)) and not name.startswith('.')
)
if not packs:
    print("DATAPACK_CHECK=RED datapacks/ 下没有任何数据包目录", file=sys.stderr)
    sys.exit(1)

for pack in packs:
    pack_dir = os.path.join(source_dir, pack)

    # a) pack.mcmeta
    meta_path = os.path.join(pack_dir, "pack.mcmeta")
    if not os.path.isfile(meta_path):
        problems.append("%s: 缺少 pack.mcmeta" % pack)
    else:
        try:
            meta = json.load(open(meta_path, encoding="utf-8"))
            fmt = meta["pack"]["pack_format"]
            if not isinstance(fmt, int):
                problems.append("%s: pack.pack_format 必须是整数" % pack)
            else:
                print("  [OK  ] %s pack.mcmeta pack_format=%d" % (pack, fmt))
        except Exception as exc:
            problems.append("%s: pack.mcmeta 不是合法 JSON 或缺少 pack.pack_format（%s）" % (pack, exc))

    # b) quick_actions tag
    tag_path = os.path.join(pack_dir, TAG_REL)
    if not os.path.isfile(tag_path):
        problems.append("%s: 缺少 %s（#minecraft:quick_actions 未注册）" % (pack, TAG_REL))
        continue
    try:
        values = json.load(open(tag_path, encoding="utf-8"))["values"]
    except Exception as exc:
        problems.append("%s: %s 不是合法 JSON（%s）" % (pack, TAG_REL, exc))
        continue
    if not isinstance(values, list) or not values:
        problems.append("%s: %s values 必须是非空数组" % (pack, TAG_REL))
        continue
    print("  [OK  ] %s #minecraft:quick_actions 成员 %d 个" % (pack, len(values)))

    # c) tag 成员必须在仓内存在
    for entry in values:
        if not isinstance(entry, str) or ":" not in entry:
            problems.append("%s: tag 成员 %r 不是 <namespace>:<path> 形式" % (pack, entry))
            continue
        namespace, path = entry.split(":", 1)
        rel = os.path.join(pack_dir, "data", namespace, "function", path + ".mcfunction")
        if not os.path.isfile(rel):
            problems.append("%s: tag 成员 %s 没有对应文件 %s" % (pack, entry, os.path.relpath(rel, root)))

    # d) 每个函数体必须至少有一条 run <命令>；只统计被 tag 引用的那些
    run_re = re.compile(r"^\s*run\s+\S", re.M)
    for entry in values:
        if not isinstance(entry, str) or ":" not in entry:
            continue
        namespace, path = entry.split(":", 1)
        rel = os.path.join(pack_dir, "data", namespace, "function", path + ".mcfunction")
        if not os.path.isfile(rel):
            continue
        body = open(rel, encoding="utf-8").read()
        if not run_re.search(body):
            problems.append("%s: %s 没有任何 run <命令>（空按钮会被客户端显示但点了没反应）"
                            % (pack, os.path.relpath(rel, root)))

    if len(values) != EXPECTED_BUTTONS:
        problems.append("%s: 快捷动作 %d 个，docs/GAME_DESIGN.md 承诺 %d 个；"
                        "增删按钮必须同时改文档与 data/minecraft/tags/function/quick_actions.json"
                        % (pack, len(values), EXPECTED_BUTTONS))

    # e) 函数里 `run /<cmd>` 引用的命令必须真的被某个 plugin.yml 注册。
    #    数据包只负责「打开入口」，命令不存在时按钮点下去只会得到 Unknown command，
    #    而这类失效在数据包侧完全静默（与 check-ddl-consumers.sh 的指令引用门禁同一类病）。
    registered = set()
    for manifest in glob.glob(os.path.join(root, "plugins", "*", "src", "main", "resources", "plugin.yml")):
        text = open(manifest, encoding="utf-8").read()
        if not re.search(r"(?m)^commands:", text):
            continue
        for line in text[text.index("commands:"):].splitlines()[1:]:
            if line and not line.startswith(" "):
                break
            m = re.match(r"^  ([A-Za-z0-9_-]+):\s*$", line)
            if m:
                registered.add(m.group(1))
    for entry in values:
        if not isinstance(entry, str) or ":" not in entry:
            continue
        namespace, path = entry.split(":", 1)
        rel = os.path.join(pack_dir, "data", namespace, "function", path + ".mcfunction")
        if not os.path.isfile(rel):
            continue
        for used in re.findall(r"^\s*run\s+/([a-z0-9_-]+)", open(rel, encoding="utf-8").read(), re.M):
            if used not in registered:
                problems.append("%s: %s 调用了未注册命令 /%s"
                                % (pack, os.path.relpath(rel, root), used))

if problems:
    print("DATAPACK_CHECK=RED", file=sys.stderr)
    for problem in problems:
        print("  [坏] %s" % problem, file=sys.stderr)
    sys.exit(1)

print("DATAPACK_CHECK=GREEN packs=%s" % ",".join(packs))
PY

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
