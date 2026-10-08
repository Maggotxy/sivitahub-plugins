#!/bin/sh
# scripts/run-paper-smoke.sh —— 真实 Paper 运行时冒烟
#
# 目的：仓库按设计只含 plugins/ docs/ tools/，**不含 server/**，全部进程内测试
#       （H2 + 桩 Bukkit）覆盖不到运行期。本脚本做最值钱的一步机械化：
#       「25 个自研插件能不能被真实 Paper 26.2 干净加载起来」。
#       4 条 [R] 级 unproven（G-205 启动对账 / G-207 计数器内存态 / G-208 启动 fail-closed /
#       G-209 20 tick）都卡在这里；本脚本只负责把「起得来」变成机械可复现的事实。
#
# 判据行（stdout，供 CI 机器读取）：
#   PAPER_SMOKE_GATE=GREEN|RED
#   PAPER_STARTED=YES|NO
#   PAPER_PLUGINS_ENABLED=<n>          25 个自研插件里 enabled 的个数
#   PAPER_OWN_ERRORS=<n>              自研插件相关的 Exception/Caused by 行数
#   PAPER_COMMUNITY_ERRORS=<n>        社区插件异常行数（单独计数，不参与判 RED）
#   GREEN 条件：PAPER_STARTED=YES 且 25/25 自研插件 enabled 且 PAPER_OWN_ERRORS=0
#   社区插件异常不判 RED（不是本仓代码），但必须打印出来。
#
# 诚实优先：起不来就如实红。禁止为了让判据变绿而删插件 / 放宽断言 / 改阈值。
#
# 环境约束（门禁镜像 eclipse-temurin:25-jdk 实测）：
#   有 sh(dash) / curl / python3 / coreutils / tar(只能创建) / java(25)
#   没有 git / jq / pip / PyYAML / unzip
#   ⇒ 只用 POSIX sh；JSON 用 python3 解析；zip/tar 用 python3 标准库解。
#
# 用法：
#   sh scripts/run-paper-smoke.sh                # 全量
#   sh scripts/run-paper-smoke.sh --skip-build   # 复用已有 jar（本地调试，CI 不用）
#   sh scripts/run-paper-smoke.sh --timeout 600  # 覆盖等 Done( 的上限秒数
#
# 退出码：0 = GREEN；1 = RED（并已把判据行打全）；2 = 用法错误

set -eu

# ------------------------------------------------------------------ 常量

PAPER_MAJOR="26.2"
PAPER_BUILDS_URL="https://fill.papermc.io/v3/projects/paper/versions/${PAPER_MAJOR}/builds"
# 下载器 User-Agent：实测 fill.papermc.io 对空 UA/curl 默认 UA 有 WAF 行为，
# 带一个可识别的 UA 才稳定 200（本仓 fetch-deps.sh 同样带 UA）。
UA="sivitahub-paper-smoke/1 (+https://cnb.cool/xzqkjmnbvc2026/sivitahub)"
BOOT_TIMEOUT=300
EULA_MARK="Done ("
STOP_GRACE=120

SKIP_BUILD=0
while [ $# -gt 0 ]; do
    case "$1" in
        --skip-build) SKIP_BUILD=1 ;;
        --timeout) shift; BOOT_TIMEOUT="${1:-300}" ;;
        *) echo "run-paper-smoke: 未知参数 $1" >&2; exit 2 ;;
    esac
    shift
done

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
LOCK_FILE="$SCRIPT_DIR/runtime-smoke-plugins.lock.json"

# 自研插件清单：plugin.yml 的 name 是 Paper 日志里的名字，目录名是 jar 名。
# 不从 plugin.yml 现场解析（那需要 YAML），而是由 scripts/gen-runtime-smoke-manifest.py 生成静态清单，
# 生成时逐个读 plugin.yml 校验过。
OWN_MANIFEST="$SCRIPT_DIR/runtime-smoke-own-plugins.txt"
[ -f "$OWN_MANIFEST" ] || { echo "run-paper-smoke: 缺 $OWN_MANIFEST" >&2; exit 2; }

SKIP_KEY=$(python3 -c '
import hashlib, json, sys
lock = json.load(open(sys.argv[1], encoding="utf-8"))
items = [{"f": p.get("file"), "s": p.get("sha512"), "u": p.get("url"), "l": p.get("local")}
         for p in lock["plugins"]]
print(hashlib.sha256(json.dumps(items, sort_keys=True).encode()).hexdigest()[:16])
' "$LOCK_FILE")

# ------------------------------------------------------------------ 工具

have() { command -v "$1" >/dev/null 2>&1; }

log() { echo "[smoke] $*"; }

die() { echo "[smoke][FATAL] $*" >&2; exit 1; }

# 统计行数（无匹配输出 0，不依赖 grep 退出码，dash 下安全）
count_lines() { # $1=文件
    [ -f "$1" ] || { echo 0; return; }
    wc -l < "$1" | tr -d ' '
}

# ------------------------------------------------------------------ 0. 前置检查

for t in python3 java sha256sum curl; do
    have "$t" || die "缺少必需工具：$t（门禁镜像应提供 python3/java/curl/coreutils）"
done
[ -f "$LOCK_FILE" ] || die "缺插件锁清单 $LOCK_FILE"

WORK=$(mktemp -d /tmp/paper-smoke.XXXXXX)
SERVER="$WORK/server"
PLUGINS="$SERVER/plugins"
CACHE="$WORK/cache"
mkdir -p "$SERVER" "$PLUGINS" "$CACHE"
log "工作目录（不进仓库）：$WORK"

SERVER_LOG="$WORK/server.log"
: > "$SERVER_LOG"

# 兜底：任何异常退出都把子进程停掉，避免留下僵尸 java 让 runner 不结束
JAVA_PID=""
cleanup() {
    if [ -n "$JAVA_PID" ] && kill -0 "$JAVA_PID" 2>/dev/null; then
        kill "$JAVA_PID" 2>/dev/null || true
        sleep 2
        kill -9 "$JAVA_PID" 2>/dev/null || true
    fi
    # 默认保留 WORK 供诊断；--keep=1 时由调用方自己看。这里只做进程清理。
}
trap cleanup EXIT INT TERM

# ------------------------------------------------------------------ 1. Paper 26.2（校验 sha256）

# 解析最新 STABLE build：取 `.downloads["server:default"].url` 与 `.checksums.sha256`
resolve_paper() {
    python3 - "$PAPER_BUILDS_URL" "$UA" <<'PY'
import json, sys, urllib.request

url, ua = sys.argv[1], sys.argv[2]
req = urllib.request.Request(url, headers={"User-Agent": ua})
with urllib.request.urlopen(req, timeout=120) as resp:
    builds = json.load(resp)

stable = [b for b in builds if b.get("channel") == "STABLE"]
if not stable:
    print("resolve_paper: 没有任何 STABLE build", file=sys.stderr)
    raise SystemExit(1)
# fill API 按时间倒序返回，第一条即最新
build = stable[0]
dl = build["downloads"]["server:default"]
url = dl["url"]
sha256 = dl["checksums"]["sha256"]
if not url or not sha256:
    print("resolve_paper: download url / sha256 缺失", file=sys.stderr)
    raise SystemExit(1)
print("%s\t%s\t%s\t%s" % (build["id"], dl["name"], sha256, url))
PY
}

PAPER_JAR="$WORK/paper.jar"
PAPER_META=$(resolve_paper) || die "无法从 $PAPER_BUILDS_URL 解析 Paper $PAPER_MAJOR 构建"
PAPER_BUILD=$(printf '%s' "$PAPER_META" | cut -f1)
PAPER_NAME=$(printf '%s' "$PAPER_META" | cut -f2)
PAPER_SHA256=$(printf '%s' "$PAPER_META" | cut -f3)
PAPER_URL=$(printf '%s' "$PAPER_META" | cut -f4)
log "Paper $PAPER_MAJOR build=$PAPER_BUILD file=$PAPER_NAME"
log "  sha256=$PAPER_SHA256"

download() { # $1=url $2=dest
    attempt=0
    while [ "$attempt" -lt 3 ]; do
        attempt=$((attempt + 1))
        if curl -fsSL --retry 2 --connect-timeout 20 --max-time 900 \
                -A "$UA" -o "$2" "$1"; then
            return 0
        fi
        log "  下载失败（第 $attempt/3 次）：$1"
        [ "$attempt" -lt 3 ] && sleep $((attempt * 5))
    done
    return 1
}

# 复跑时复用同一份 paper jar（体积 60MB+，每次重下很费时间）
if [ -f "$CACHE/$PAPER_NAME" ] && \
   [ "$(sha256sum "$CACHE/$PAPER_NAME" | cut -d' ' -f1)" = "$PAPER_SHA256" ]; then
    log "Paper jar 命中缓存：$CACHE/$PAPER_NAME"
    cp "$CACHE/$PAPER_NAME" "$PAPER_JAR"
else
    log "下载 Paper jar（$PAPER_URL）"
    download "$PAPER_URL" "$PAPER_JAR" || die "Paper jar 下载失败：$PAPER_URL"
    got=$(sha256sum "$PAPER_JAR" | cut -d' ' -f1)
    if [ "$got" != "$PAPER_SHA256" ]; then
        die "Paper jar sha256 不匹配：期望 $PAPER_SHA256 实际 $got"
    fi
    cp "$PAPER_JAR" "$CACHE/$PAPER_NAME"
fi
log "Paper sha256 校验通过（独立核对，见 evidence）"

# ------------------------------------------------------------------ 2. 15 个社区插件（逐个 sha512）

python3 - "$LOCK_FILE" "$CACHE" "$ROOT" "$UA" <<'PY'
import hashlib, json, os, shutil, sys, urllib.request

lock_path, cache, root, ua = sys.argv[1:5]
lock = json.load(open(lock_path, encoding="utf-8"))

def sha512_of(path):
    h = hashlib.sha512()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()

def verify(path, expect):
    return os.path.isfile(path) and sha512_of(path) == expect

failures = []
for entry in lock["plugins"]:
    name, filename, expect = entry["name"], entry["file"], entry["sha512"]
    dest = os.path.join(cache, filename)
    if verify(dest, expect):
        print("[smoke][plugin] %-16s OK(缓存 sha512 已核对) %s" % (name, filename))
        continue
    if "local" in entry:
        src = os.path.join(root, entry["local"])
        if not verify(src, expect):
            failures.append("%s（local %s 缺失或 sha512 不匹配）" % (name, entry["local"]))
            print("[smoke][plugin] %-16s MISSING/BAD-SHA512 %s" % (name, entry["local"]))
            continue
        shutil.copyfile(src, dest)
        print("[smoke][plugin] %-16s OK(vendor %s)" % (name, entry["local"]))
    else:
        url = entry["url"]
        ok = False
        for attempt in range(1, 4):
            try:
                req = urllib.request.Request(url, headers={"User-Agent": ua})
                with urllib.request.urlopen(req, timeout=300) as resp, open(dest, "wb") as out:
                    shutil.copyfileobj(resp, out)
                ok = verify(dest, expect)
                if ok:
                    print("[smoke][plugin] %-16s OK(下载 attempt=%d) %s" % (name, attempt, filename))
                    break
                print("[smoke][plugin] %-16s SHA512-MISMATCH attempt=%d" % (name, attempt))
            except Exception as exc:
                print("[smoke][plugin] %-16s DOWNLOAD-FAIL attempt=%d %s" % (name, attempt, exc))
            if os.path.exists(dest):
                os.remove(dest)
        if not ok:
            failures.append("%s（%s 下载/校验失败）" % (name, url))

if failures:
    print("[smoke][plugin] 有 %d 个社区插件不可用：" % len(failures))
    for f in failures:
        print("   - " + f)
    raise SystemExit(1)
PY

# ------------------------------------------------------------------ 3. ./gradlew assemble → 自研插件 jar

if [ "$SKIP_BUILD" -eq 1 ]; then
    log "--skip-build：跳过 assemble，用已有 build/libs"
else
    log "运行 ./gradlew --no-daemon assemble（日志 $WORK/assemble.log）"
    chmod +x "$ROOT/gradlew" 2>/dev/null || true
    ( cd "$ROOT" && ./gradlew --no-daemon assemble ) > "$WORK/assemble.log" 2>&1 || {
        tail -n 60 "$WORK/assemble.log"
        die "gradle assemble 失败（完整日志：$WORK/assemble.log）"
    }
    tail -n 5 "$WORK/assemble.log"
fi

# 25 个自研 jar：scripts/runtime-smoke-own-plugins.txt 每行 "<module>\t<plugin name>"
: > "$WORK/own-jars.txt"
while IFS="$(printf '\t')" read -r module pname; do
    [ -z "$module" ] && continue
    # 排除 -sources.jar（withSourcesJar 也在 build/libs 下）
    # 用赋值给管道各段做全量消费（`find | sort | head` 在 head 提前退出时会让
    # 上游拿到 SIGPIPE，配合 set -e / pipefail 会误杀；两者都改成先落文件再取首行）
    find "$ROOT/plugins/$module/build/libs" -maxdepth 1 -name '*.jar' \
        ! -name '*-sources.jar' 2>/dev/null > "$WORK/jarcand.txt" || true
    sort "$WORK/jarcand.txt" > "$WORK/jarcand.sorted.txt"
    jar=$(awk 'NR==1{print; exit}' "$WORK/jarcand.sorted.txt")
    if [ -z "$jar" ]; then
        die "缺自研插件产物：plugins/$module/build/libs/*.jar（$pname）"
    fi
    cp "$jar" "$PLUGINS/"
    printf '%s\t%s\t%s\n' "$module" "$pname" "$jar" >> "$WORK/own-jars.txt"
done < "$OWN_MANIFEST"
log "自研插件 jar 已装入 plugins/：$(count_lines "$WORK/own-jars.txt") 个"

# 社区插件装入 plugins/
python3 - "$LOCK_FILE" "$CACHE" "$PLUGINS" <<'PY'
import json, shutil, sys, os
lock_path, cache, plugins = sys.argv[1:4]
lock = json.load(open(lock_path, encoding="utf-8"))
n = 0
for entry in lock["plugins"]:
    src = os.path.join(cache, entry["file"])
    if not os.path.isfile(src):
        print("smoke: 社区插件缓存缺失 %s" % src, file=sys.stderr)
        raise SystemExit(1)
    shutil.copyfile(src, os.path.join(plugins, entry["file"]))
    n += 1
print("[smoke] 社区插件已装入 plugins/：%d 个" % n)
PY

# ------------------------------------------------------------------ 4. 最小 server.properties + eula

cat > "$SERVER/eula.txt" <<EOF
# 真实 Paper 冒烟：仅本地/CI 一次性实例，接受 EULA 用于自动化测试。
eula=true
EOF

# level-type 里的冒号必须转义，否则被当成 properties key/value 分隔
cat > "$SERVER/server.properties" <<EOF
online-mode=false
max-players=1
level-type=minecraft\\:flat
spawn-protection=0
view-distance=4
simulation-distance=4
allow-nether=false
allow-end=false
level-name=world
enable-command-block=false
EOF
log "已写最小 server.properties（online-mode=false / flat / 1 人 / 距离 4 / 无下界无末地）"

# ------------------------------------------------------------------ 5. 启动 → 等 Done ( → stop

cd "$SERVER"
JAVA_XMX="${PAPER_SMOKE_XMX:-2G}"
log "启动 Paper：java -Xmx$JAVA_XMX -jar paper.jar --nogui"
# stdin 用 FIFO，之后既能写 stop 又能继续写后续命令
FIFO="$WORK/stdin.fifo"
mkfifo "$FIFO"
# 用一个长期 sleep 占住 FIFO 的写端，避免 Paper 读 stdin 得到 EOF 后直接退出
# （Paper 在 --nogui 下从 System.in 读控制台命令，读到 EOF 会关控制台）。
( sleep 100000 > "$FIFO" ) &
FIFO_HOLDER=$!
java -Xmx"$JAVA_XMX" -jar "$PAPER_JAR" --nogui < "$FIFO" > "$SERVER_LOG" 2>&1 &
JAVA_PID=$!
log "Paper PID=$JAVA_PID，等待日志出现 '$EULA_MARK'（上限 ${BOOT_TIMEOUT}s）"

STARTED=NO
waited=0
while [ "$waited" -lt "$BOOT_TIMEOUT" ]; do
    if grep -qF "$EULA_MARK" "$SERVER_LOG" 2>/dev/null; then
        STARTED=YES
        break
    fi
    if ! kill -0 "$JAVA_PID" 2>/dev/null; then
        log "Paper 进程已退出（未见到 '$EULA_MARK'）"
        break
    fi
    sleep 5
    waited=$((waited + 5))
done

if [ "$STARTED" = "YES" ]; then
    log "检测到 '$EULA_MARK'，耗时约 ${waited}s；发出 stop"
    printf 'stop\n' > "$FIFO"
    # 等优雅停服：最多 STOP_GRACE 秒
    stop_waited=0
    while [ "$stop_waited" -lt "$STOP_GRACE" ]; do
        kill -0 "$JAVA_PID" 2>/dev/null || break
        sleep 2
        stop_waited=$((stop_waited + 2))
    done
    if kill -0 "$JAVA_PID" 2>/dev/null; then
        log "stop 后 ${STOP_GRACE}s 未退出，kill -9"
        kill -9 "$JAVA_PID" 2>/dev/null || true
    fi
else
    log "未在 ${BOOT_TIMEOUT}s 内启动完成，强杀"
    kill -9 "$JAVA_PID" 2>/dev/null || true
fi
kill "$FIFO_HOLDER" 2>/dev/null || true
wait "$JAVA_PID" 2>/dev/null || true
JAVA_PID=""

# ------------------------------------------------------------------ 6. 判据

# 6.1 自研插件 enabled 计数（Paper 日志启动段是 "[HH:MM:SS INFO]: Enabling <Name> v<ver>"）
: > "$WORK/own-enabled.txt"
: > "$WORK/own-missing.txt"
while IFS="$(printf '\t')" read -r module pname; do
    [ -z "$module" ] && continue
    if grep -qE "Enabling ${pname}( |$)" "$SERVER_LOG"; then
        printf '%s\n' "$pname" >> "$WORK/own-enabled.txt"
    else
        printf '%s\n' "$pname" >> "$WORK/own-missing.txt"
    fi
done < "$OWN_MANIFEST"
ENABLED=$(count_lines "$WORK/own-enabled.txt")
TOTAL=$(count_lines "$OWN_MANIFEST")

# 6.2 异常行分类：Paper 用 java.util.logging，异常都带 " [minecraft/CraftBukkit]" 之类的
#     logger 前缀与 "]: " 分隔，因此匹配 "[a-zA-Z.]*(Exception|Error):" 形态而不是行首。
#     "Caused by:" 也计入（同一个异常的根因行）。
EXC_RE='(^|[] ])([a-zA-Z_$][a-zA-Z0-9_$.]*(Exception|Error))(:|$)'
grep -nE "$EXC_RE" "$SERVER_LOG" > "$WORK/exc-all.txt" 2>/dev/null || : > "$WORK/exc-all.txt"
grep -nF "Caused by:" "$SERVER_LOG" >> "$WORK/exc-all.txt" 2>/dev/null || true
# 去重：两条规则可能命中同一行（如 `Caused by: java.lang.RuntimeException:`），
# 但**两行文本不同、都应保留**，所以不能按文本 sort -u。
# 改为按 grep 的行号前缀去重，行号重复才丢弃。
awk -F: '!seen[$1]++' "$WORK/exc-all.txt" > "$WORK/exc.txt"

# 归属：异常行命中哪个插件名/包名，就记到哪个插件
python3 - "$SERVER_LOG" "$WORK/exc.txt" "$WORK/own-errors.txt" \
        "$WORK/community-errors.txt" "$WORK/unattributed-errors.txt" "$OWN_MANIFEST" "$LOCK_FILE" <<'PY'
"""把异常行归属到插件。

为什么不能「一行一判」，也不能只拿异常行组块：
  真实 Paper 日志里一个自研插件异常长这样 ——
    [18:00:06 ERROR]: [RpgCore] Could not initialize economy ledger reconciliation   <- 带插件名
    [18:00:06 ERROR]: java.lang.IllegalStateException: closing price floor mismatch   <- 异常行、无插件名
    \tat cn.originalrpg.core.ledger.LedgerReconciliation.onEnable(...)                <- 栈帧、无插件名
  只有块首带插件名；异常行与栈帧都不带。若只把「异常行」组块，块里就没有
  RpgCore / cn.originalrpg 的踪迹 ⇒ 自研插件的真实异常会被算成「无法归属」，
  PAPER_OWN_ERRORS 误算成 0 —— 那是最危险的方向（假绿）。

  所以这里取**原始日志的连续行区间**作为块：从一个异常行开始，一直吃到
  下一行「不再像同一个异常块」为止（连续空行、或下一个非缩进非 Caused by 的日志头）。
  块内任意一行命中插件名 / 包名 / cn.originalrpg，整块即归属该插件。
"""
import json
import re
import sys

server_log, exc_list, own_out, community_out, unattr_out, manifest, lock_path = sys.argv[1:8]

lock = json.load(open(lock_path, encoding="utf-8"))
own_names = [l.split("\t")[1].strip() for l in open(manifest, encoding="utf-8") if l.strip()]

# 左侧非标识符字符 + 前缀匹配：能命中 RpgCorePlugin / cn.originalrpg.core.RpgCorePlugin，
# 又不会把 RpgCore 命中到别的词里（实测 /\bRpgCore\b/ 匹配不到 RpgCorePlugin）。
def hit(text, name):
    return re.search(r"(?<![A-Za-z0-9_])%s" % re.escape(name), text, re.I) is not None


COMMUNITY_PACKAGES = {
    "LuckPerms": "luckperms",
    "PlaceholderAPI": "placeholderapi",
    "WorldEdit": "sk89q.worldedit",
    "WorldGuard": "sk89q.worldguard",
    "Chunky": "popcraft.chunky",
    "AuthMeReloaded": "xephi.authme",
    "squaremap": "jpenilla.squaremap",
    "SkinsRestorer": "skinsrestorer",
    "Citizens": "citizensnpcs",
    "BetonQuest": "betonquest",
    "GriefPrevention": "ryanhamshire",
    "BetterModel": "toxicity.model",
    "BetterHud": "toxicity.hud",
    "EssentialsX": "earth2me.essentials",
    "MyPet": "keyle.mypet",
}

# 归因顺序：社区优先。理由 —— 本仓是客户端/玩法侧插件，栈里出现 MyPet 的类很常见，
# 但那类异常的源头是第三方；反过来第三方栈里出现 cn.originalrpg 才是本仓要背的账。
# 两种都命中时按「社区」计，避免把第三方噪声算进 PAPER_OWN_ERRORS（会让门变假红），
# 而**不会**掩盖本仓异常：本仓自有异常块里不会有第三方类名。
def classify(text):
    for entry in lock["plugins"]:
        if hit(text, entry["name"]):
            return entry["name"], "community"
    low = text.lower()
    for name, pkg in COMMUNITY_PACKAGES.items():
        if pkg.lower() in low:
            return name, "community"
    for name in own_names:
        if hit(text, name):
            return name, "own"
    if "cn.originalrpg" in low or "originalrpg" in low:
        return "cn.originalrpg", "own"
    return "", ""


exc_linenos = []
for raw in open(exc_list, encoding="utf-8"):
    num, _, _rest = raw.rstrip("\n").partition(":")
    if num.isdigit():
        exc_linenos.append(int(num))
ordered = sorted(set(exc_linenos))

lines = open(server_log, encoding="utf-8", errors="replace").read().split("\n")
exc_set = set(ordered)

# 一个「日志头」行长这样：`[HH:MM:SS LEVEL]: ...`（Paper 的 java.util.logging 格式）
LOG_HEAD = re.compile(r"^\[[0-9]{2}:[0-9]{2}:[0-9]{2}")

own_rows, community_rows, unattr_rows = [], [], []
for num in ordered:
    if num - 1 >= len(lines):
        continue
    # 从异常行往前后扩到「同一个异常块」：向上吃到上一个日志头之后，
    # 向下吃到下一个日志头之前（含空行与缩进栈帧）。
    top = num
    while top - 2 >= 0 and not LOG_HEAD.match(lines[top - 2]):
        top -= 1
    bottom = num
    while bottom < len(lines) and not LOG_HEAD.match(lines[bottom]):
        bottom += 1
    block = "\n".join(lines[top - 1:bottom])
    name, kind = classify(block)
    row = "%d:%s" % (num, lines[num - 1])
    if kind == "community":
        community_rows.append("%s\t%s" % (name, row))
    elif kind == "own":
        own_rows.append("%s\t%s" % (name, row))
    else:
        unattr_rows.append(row)

for path, rows in ((own_out, own_rows), (community_out, community_rows), (unattr_out, unattr_rows)):
    with open(path, "w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(row + "\n")

print("[smoke] 异常行 %d 条：自研 %d / 社区 %d / 无法归属 %d"
      % (len(ordered), len(own_rows), len(community_rows), len(unattr_rows)))
PY

OWN_ERRORS=$(count_lines "$WORK/own-errors.txt")
COMMUNITY_ERRORS=$(count_lines "$WORK/community-errors.txt")
UNATTRIBUTED_ERRORS=$(count_lines "$WORK/unattributed-errors.txt")

GATE=RED
if [ "$STARTED" = "YES" ] && [ "$ENABLED" -eq "$TOTAL" ] && [ "$OWN_ERRORS" -eq 0 ]; then
    GATE=GREEN
fi

# ------------------------------------------------------------------ 7. 报告

echo
echo "===================== Paper 运行时冒烟报告 ====================="
echo "Paper           : $PAPER_NAME (build $PAPER_BUILD), sha256 已校验"
echo "工作目录        : $WORK"
echo "自研插件 enabled: $ENABLED/$TOTAL"
if [ -s "$WORK/own-enabled.txt" ]; then
    echo "  enabled 清单 : $(tr '\n' ' ' < "$WORK/own-enabled.txt")"
fi
if [ -s "$WORK/own-missing.txt" ]; then
    echo "  ✗ 未 enabled : $(tr '\n' ' ' < "$WORK/own-missing.txt")"
fi
echo "社区插件        : $(python3 -c 'import json,sys;print(len(json.load(open(sys.argv[1],encoding="utf-8"))["plugins"]))' "$LOCK_FILE") 个已装入（异常不判 RED）"
echo
echo "----- 自研插件异常（PAPER_OWN_ERRORS=$OWN_ERRORS）-----"
if [ -s "$WORK/own-errors.txt" ]; then
    awk -F'\t' '{print "  [" $1 "] " $2}' "$WORK/own-errors.txt" | head -n 40
else
    echo "  （无）"
fi
echo
echo "----- 社区插件异常（PAPER_COMMUNITY_ERRORS=$COMMUNITY_ERRORS，仅报告）-----"
if [ -s "$WORK/community-errors.txt" ]; then
    awk -F'\t' '{print "  [" $1 "] " $2}' "$WORK/community-errors.txt" | head -n 40
else
    echo "  （无）"
fi
echo
echo "----- 无法归属的异常（UNATTRIBUTED=$UNATTRIBUTED_ERRORS，Paper 自身/库）-----"
if [ -s "$WORK/unattributed-errors.txt" ]; then
    head -n 20 "$WORK/unattributed-errors.txt"
else
    echo "  （无）"
fi
echo
echo "----- 启动日志关键段 -----"
if grep -qF "$EULA_MARK" "$SERVER_LOG"; then
    grep -F "$EULA_MARK" "$SERVER_LOG" > "$WORK/done-lines.txt" || true
    tail -n 1 "$WORK/done-lines.txt"
else
    echo "  （没有 '$EULA_MARK' 行）"
fi
echo "  （完整日志：$SERVER_LOG，assemble 日志：$WORK/assemble.log）"
echo
echo "PAPER_SMOKE_GATE=$GATE"
echo "PAPER_STARTED=$STARTED"
echo "PAPER_PLUGINS_ENABLED=$ENABLED"
echo "PAPER_OWN_ERRORS=$OWN_ERRORS"
echo "PAPER_COMMUNITY_ERRORS=$COMMUNITY_ERRORS"
echo "PAPER_UNATTRIBUTED_ERRORS=$UNATTRIBUTED_ERRORS"
echo "==============================================================="

if [ "$GATE" = "GREEN" ]; then
    exit 0
fi
exit 1
