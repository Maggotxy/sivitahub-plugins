#!/bin/sh
# scripts/check-repo-artifacts.sh —— 「文档里引用服务端产物，但仓库里不存在」门禁（G-206）
#
# 失败模式（真实发生过）：`server/` 在 .gitignore 里，docs 却直接承诺
#   「运行 server/bin/verify-installation.sh」并把它当作发布链的一环。
# 干净克隆里该文件根本不存在，部署校验在生产机上失败，而且**在 CI 里完全静默** ——
# 文档读起来像已接线，实际是依赖仓外产物。
#
# 判据（对 docs/ 下每一次 `server/<路径>` 引用）：
#   a) 该路径在仓库里存在（例如 server/config-templates/...）              → OK
#   b) 该路径在 .gitignore 里逐条登记为仓外运行时产物                       → OK
#   c) 该路径登记在 docs/DATAPACK_DEPLOYMENT.md 的「仓外产物登记」表里，
#      并给出本仓可复核的替代                                           → OK
#   d) 该路径在 `server/` 下但**本仓没有任何踪迹**                        → RED
#      （d 正是 G-206 的原始形态；`datapacks/` 下的数据包已入库，所以
#        `#minecraft:quick_actions` 不再落进这一类）
#
# 另外强制一条 G-206 的正面结论：
#   datapacks/ 下必须有数据包，`#minecraft:quick_actions` 的 tag 必须声明，
#   且 scripts/install-datapacks.sh 必须存在 —— 数据包不能只是「文档里说它存在」。
#
# 用法：sh scripts/check-repo-artifacts.sh；退出码 0 = 通过

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

ARTIFACT_DOC="docs/DATAPACK_DEPLOYMENT.md"
GITIGNORE=".gitignore"
STATUS=0

echo "REPO_ARTIFACT_GATE: 检查 docs/ 里引用的 server/ 路径"

# 逐级向上找登记点：.gitignore 登记的是目录（server/runtime/），
# 而 docs 可能引用目录里的具体文件（server/runtime/logs/latest.log）。
# 判定「该路径或其任一祖先目录已被登记为仓外产物」即放行。
registered_in() {
  # $1 = 登记文件, $2 = 仓库相对路径
  # 用命令替换把「命中的登记行」输出到 stdout 由调用方捕获。
  # ⚠️ 不能在这里 `grep -q ... && printf ...`：`grep -q` 命中后立即退出并关闭管道，
  #     写端（printf/echo）会拿到 SIGPIPE —— 函数所在子 shell 连带被杀，输出丢失，
  #     门禁会把已登记的路径误判成未登记（实测踩过）。
  target="$2"
  probe="$target"
  while [ -n "$probe" ] && [ "$probe" != "server" ] && [ "$probe" != "." ]; do
    # .gitignore 里登记目录时带尾斜杠（server/runtime/），比对两种写法都要认，
    # 否则目录级登记会被判成未登记（实测踩过：命中路径因此被误报成 G-206 形态）。
    if grep -qxF "$probe" "$1" || grep -qxF "$probe/" "$1"; then
      printf '%s' "$probe"
      return 0
    fi
    case "$probe" in
      */*) probe=$(printf '%s' "$probe" | sed 's|/[^/]*$||') ;;
      *) probe="" ;;
    esac
  done
  return 1
}

check_reference() {
  # $1 = server/ 相对路径（已剥掉行尾标点）
  ref="$1"
  if [ -e "$ref" ]; then
    echo "  [OK  ] $ref —— 仓库内存在"
    return 0
  fi
  if hit=$(registered_in "$GITIGNORE" "$ref"); then
    echo "  [OK  ] $ref —— 仓外运行时产物（$GITIGNORE 登记 $hit）"
    return 0
  fi
  if [ -f "$ARTIFACT_DOC" ]; then
    if grep -qF "$ref" "$ARTIFACT_DOC"; then
      echo "  [OK  ] $ref —— 仓外产物，已在 $ARTIFACT_DOC 登记替代方式"
      return 0
    fi
  fi
  echo "  [FAIL] $ref —— 仓库里没有、$GITIGNORE 没登记、$ARTIFACT_DOC 也没登记" >&2
  echo "         这类引用会让部署校验在生产机上找不到产物（G-206 的原始形态）。" >&2
  echo "         修法：把产物纳入版本控制，或在 $ARTIFACT_DOC 登记并给替代脚本。" >&2
  STATUS=1
  return 1
}

# 只取形如 server/<路径> 的 token：要求下一段含 `/` 或 `.`，
# 这样 `server/` 这种目录统称不会被误判成产物路径。
REFS=$(grep -rhoE 'server/[A-Za-z0-9._-]+(/?[A-Za-z0-9._-]+)*\.[A-Za-z0-9]+' docs 2>/dev/null \
  | sort -u)

if [ -z "$REFS" ]; then
  echo "  [WARN] docs/ 下没有任何 server/ 产物引用（门禁可能已失去覆盖对象）"
fi

for ref in $REFS; do
  check_reference "$ref" || true
done

# ---- 部署文档必须点名脚本 + 数据包 + 目标目录 ----
if [ ! -f "$ARTIFACT_DOC" ]; then
  echo "  [FAIL] 缺少 $ARTIFACT_DOC（部署步骤无本仓出处，G-206 无法复核）" >&2
  STATUS=1
else
  for required in "scripts/install-datapacks.sh" "datapacks/" "world/datapacks"; do
    if grep -qF "$required" "$ARTIFACT_DOC"; then
      echo "  [OK  ] $ARTIFACT_DOC 覆盖 $required"
    else
      echo "  [FAIL] $ARTIFACT_DOC 未写明 $required" >&2
      STATUS=1
    fi
  done
fi

# ---- G-206 正面结论：数据包与 tag 必须在仓库内 ----
if [ ! -d datapacks ]; then
  echo "  [FAIL] 缺少 datapacks/ —— #minecraft:quick_actions 数据包没有纳入版本控制" >&2
  STATUS=1
elif ! find datapacks -path '*/data/minecraft/tags/function/quick_actions.json' | grep -q .; then
  echo "  [FAIL] datapacks/ 下找不到 data/minecraft/tags/function/quick_actions.json" >&2
  STATUS=1
else
  echo "  [OK  ] datapacks/ 内已声明 #minecraft:quick_actions"
fi

if [ ! -f scripts/install-datapacks.sh ]; then
  echo "  [FAIL] 缺少 scripts/install-datapacks.sh（数据包没有本仓部署/校验入口）" >&2
  STATUS=1
else
  echo "  [OK  ] scripts/install-datapacks.sh 存在"
fi

if [ "$STATUS" -ne 0 ]; then
  echo "REPO_ARTIFACT_GATE=RED"
  exit 1
fi
echo "REPO_ARTIFACT_GATE=GREEN"
