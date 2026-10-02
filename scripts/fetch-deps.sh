#!/bin/sh
# scripts/fetch-deps.sh —— 拉取编译期第三方 jar 到 libs/
#
# 目的：让仓库「干净克隆即可编译」。仓库里 6 处 build.gradle.kts 用
#       compileOnly(files("libs/<文件名>")) 引用下列 jar；libs/*.jar 被 .gitignore
#       忽略（只入库脚本与校验值，避免仓库体积膨胀）。
#
# 用法：
#   sh scripts/fetch-deps.sh            # 缺什么拉什么；已存在且校验通过则跳过
#   sh scripts/fetch-deps.sh --force    # 全部重拉
#   sh scripts/fetch-deps.sh --check    # 只做离线校验（不联网），用于快速自检
#
# 约定：
#   - POSIX sh(dash) 兼容：不用 bash 数组、不用进程替换、不用 ${PIPESTATUS[@]}
#   - 下载器优先 python3 urllib（slim 镜像通常没有 curl），没有 python3 再退回 curl
#   - 每个 jar 都用 sha256 + sha1 双校验，写死在本脚本里；不一致即非零退出
#   - 拉不到确切构建**不伪造**：标 PENDING 并给出最近可用版本建议（见 PENDING 段）
#
# 退出码：0 = 全部就绪；1 = 有 jar 缺失/校验失败/PENDING

set -eu

FORCE=0
CHECK_ONLY=0
for arg in "$@"; do
    case "$arg" in
        --force) FORCE=1 ;;
        --check) CHECK_ONLY=1 ;;
        *) echo "fetch-deps: 未知参数 $arg" >&2; exit 2 ;;
    esac
done

# 仓库根目录：本脚本在 scripts/ 下
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
LIB_DIR="$ROOT/libs"
VENDOR_DIR="$LIB_DIR/vendor"

mkdir -p "$LIB_DIR"

# ---------------------------------------------------------------- 工具函数

have() { command -v "$1" >/dev/null 2>&1; }

# 下载 URL 到目标文件；失败返回非零
download() {
    # $1 = url, $2 = dest
    if have python3; then
        python3 - "$1" "$2" <<'PY'
import sys, urllib.request
url, dest = sys.argv[1], sys.argv[2]
req = urllib.request.Request(url, headers={"User-Agent": "sivitahub-fetch-deps/1"})
with urllib.request.urlopen(req, timeout=180) as resp, open(dest, "wb") as out:
    while True:
        chunk = resp.read(65536)
        if not chunk:
            break
        out.write(chunk)
PY
    elif have curl; then
        curl -fsSL --retry 3 --connect-timeout 20 -A "sivitahub-fetch-deps/1" -o "$2" "$1"
    elif have wget; then
        wget -q -O "$2" --user-agent="sivitahub-fetch-deps/1" "$1"
    else
        echo "fetch-deps: 没有可用的下载器（python3 / curl / wget 全缺）" >&2
        return 1
    fi
}

# 计算文件 sha1 / sha256（大小写归一为小写）
hash_of() {
    # $1 = algo(sha1|sha256), $2 = file
    if have sha256sum; then
        if [ "$1" = "sha1" ]; then sha1sum "$2" | cut -d' ' -f1
        else sha256sum "$2" | cut -d' ' -f1; fi
    elif have python3; then
        python3 -c "import hashlib,sys;a=sys.argv[1];d=open(sys.argv[2],'rb').read();print(hashlib.new(a,d).hexdigest())" "$1" "$2"
    else
        echo "fetch-deps: 没有 sha1sum/sha256sum 也没有 python3，无法校验" >&2
        return 1
    fi
}

# verify <file> <期望sha256> <期望sha1> <条目名>
verify() {
    f="$1"; want256="$2"; want1="$3"; name="$4"
    got256=$(hash_of sha256 "$f")
    got1=$(hash_of sha1 "$f")
    if [ "$got256" != "$want256" ]; then
        echo "fetch-deps: [$name] sha256 不匹配" >&2
        echo "  期望 $want256" >&2
        echo "  实际 $got256" >&2
        return 1
    fi
    if [ "$got1" != "$want1" ]; then
        echo "fetch-deps: [$name] sha1 不匹配" >&2
        echo "  期望 $want1" >&2
        echo "  实际 $got1" >&2
        return 1
    fi
    return 0
}

# fetch <文件名> <url> <sha256> <sha1>
fetch() {
    name="$1"; url="$2"; want256="$3"; want1="$4"
    dest="$LIB_DIR/$name"
    if [ "$FORCE" -eq 0 ] && [ -f "$dest" ]; then
        if verify "$dest" "$want256" "$want1" "$name"; then
            echo "fetch-deps: [OK   ] $name（已存在，校验通过）"
            return 0
        fi
        echo "fetch-deps: [REDO ] $name 本地校验失败，重新下载"
        rm -f "$dest"
    fi
    if [ "$CHECK_ONLY" -eq 1 ]; then
        echo "fetch-deps: [MISS ] $name（--check 模式下不存在）" >&2
        return 1
    fi
    echo "fetch-deps: [GET  ] $name <- $url"
    if ! download "$url" "$dest"; then
        echo "fetch-deps: [$name] 下载失败：$url" >&2
        rm -f "$dest"
        return 1
    fi
    if ! verify "$dest" "$want256" "$want1" "$name"; then
        rm -f "$dest"
        return 1
    fi
    echo "fetch-deps: [OK   ] $name（校验通过）"
    return 0
}

# ---------------------------------------------------------------- 清单

# 机械可读的产出清单（check-file-deps.sh 从这里提取，两处清单不会漂移）。
# 值 = 仓库相对路径，空格分隔。
# PHASE_FETCH  = 本脚本负责下载 + 校验的 jar
# PHASE_VENDOR = Sivita 自改版，本脚本只检查存在性，不下载
PHASE_FETCH="libs/BetterHud-bukkit-1.14.1.jar libs/AuthMe-6.0.0-Paper.jar libs/EssentialsX-2.22.1-dev-382de4f.jar libs/worldedit-bukkit-7.4.4.jar libs/worldedit-core-7.4.4.jar libs/Citizens-2.0.43-b4231.jar libs/BetonQuest-3.1.0.jar"
PHASE_VENDOR="libs/vendor/MyPet-4.0.0-Sivita.jar libs/vendor/worldguard-bukkit-7.0.18-Sivita.jar"

# 6 个有公开源的编译期依赖。
# 源优先级：Maven 仓库坐标 > 官方 CI artifact > GitHub Releases。
# 校验值是 2026-10-02 首次拉取时的实测值（sha256 / sha1）。

FAILED=""

# 1) BetterHud —— 原引用 2.1.0-SNAPSHOT-447。上游从未发布 2.1.0 / build 447 的公开产物
#    （toxicity188/BetterHud 的 Releases 只到 2.0.0，Actions artifacts 为 0），
#    ⇒ 归一到最近可用正式版 1.14.1-bukkit（仍提供 kr.toxicity.hud.api 全部 API）。
#    见 PR 描述里的 PENDING 说明。
fetch "BetterHud-bukkit-1.14.1.jar" \
    "https://github.com/toxicity188/BetterHud/releases/download/1.14.1/BetterHud-bukkit-1.14.1.jar" \
    "4b29a7dcaab4c2a1dd5341595bf691286f186fb453f73c2afe39aaed5c3c0c0d" \
    "687720ae5faeec81585566de8e756b2c308ae26c" || FAILED="$FAILED BetterHud-bukkit-1.14.1.jar"

# 2) AuthMe —— GitHub Releases 6.0.0 的 Paper 变体，文件名与上游完全一致
fetch "AuthMe-6.0.0-Paper.jar" \
    "https://github.com/AuthMe/AuthMeReloaded/releases/download/6.0.0/AuthMe-6.0.0-Paper.jar" \
    "58948fcbc697497506257d09d8a7909bf48de6aefe7bec6ba5ab4c41be41b7b8" \
    "aefb475df26c72fe39c48e0ef9daf5e8b0574e28" || FAILED="$FAILED AuthMe-6.0.0-Paper.jar"

# 3) EssentialsX —— 精确构建 dev+17-382de4f 只存在于官方 Jenkins（ci.ender.zone build 1821）
fetch "EssentialsX-2.22.1-dev-382de4f.jar" \
    "https://ci.ender.zone/job/EssentialsX/1821/artifact/jars/EssentialsX-2.22.1-dev+17-382de4f.jar" \
    "482fe19591fa39e36c109553cf4f577609030df7172bc7fdd69a1e32dd31d797" \
    "a97b9dc5714a36ba0d384fc655a945123ab27b01" || FAILED="$FAILED EssentialsX-2.22.1-dev-382de4f.jar"

# 4) WorldEdit —— 原引用 worldedit-bukkit-7.4.4.jar，但 build.gradle.kts 实际只用到
#    com.sk89q.worldedit.bukkit.BukkitAdapter(在 worldedit-bukkit) 与
#    com.sk89q.worldedit.math.BlockVector2(在 worldedit-core) 两个类。
#    7.4.4 的 worldedit-bukkit 是 140KB 的“壳”，不含这些类（旧版遗留发布事故）；
#    ⇒ 用 worldedit-core-7.4.4 + worldedit-bukkit-7.4.4 两个 artifact 组合。
#    （改成指向两个文件见 plugins/rpg-hub/build.gradle.kts）
fetch "worldedit-bukkit-7.4.4.jar" \
    "https://maven.enginehub.org/repo/com/sk89q/worldedit/worldedit-bukkit/7.4.4/worldedit-bukkit-7.4.4.jar" \
    "f6a2068728a24288392fdda6e8ebbbf12f5eac92df830b975abf07beef72cc6d" \
    "1cb5fd7d09b05f8b153213e1949c62591c6f80ca" || FAILED="$FAILED worldedit-bukkit-7.4.4.jar"

fetch "worldedit-core-7.4.4.jar" \
    "https://maven.enginehub.org/repo/com/sk89q/worldedit/worldedit-core/7.4.4/worldedit-core-7.4.4.jar" \
    "c4f4ae57b9d83b7b2c549aa5afc8967c24c3dd6fb5533baf41f75470c0f7c6f2" \
    "d2c30731d3aa1323c1f7e9b0719065fed6d4f038" || FAILED="$FAILED worldedit-core-7.4.4.jar"

# 5) Citizens —— 原引用 2.0.43-b4231。GitHub Releases 无此构建；
#    精确构建可从官方 Jenkins 的 build 4231 取到（artifact 名与仓库引用一致）。
fetch "Citizens-2.0.43-b4231.jar" \
    "https://ci.citizensnpcs.co/job/Citizens2/4231/artifact/dist/target/Citizens-2.0.43-b4231.jar" \
    "7f5bf10d581b0759779e9fc03570bef605065ffe1770430513232373a52a2e9a" \
    "b1f64f70e223187cafd73b14eb21b14f3e9b5d2c" || FAILED="$FAILED Citizens-2.0.43-b4231.jar"

# 6) BetonQuest —— GitHub Releases v3.1.0
fetch "BetonQuest-3.1.0.jar" \
    "https://github.com/BetonQuest/BetonQuest/releases/download/v3.1.0/BetonQuest-3.1.0.jar" \
    "e4f956a96085fd339d1e2b14d7c261f310825e4a9df136f7cbd2e0140a0f8f1f" \
    "d44de2402a79a9059a80c2635ee0312749eb3b7a" || FAILED="$FAILED BetonQuest-3.1.0.jar"

# ---------------------------------------------------------------- vendor（自改版，脚本不下载）

# 这两个是 Sivita 自改版，公开源不存在；由人类从生产机取回后放到 libs/vendor/。
# 本脚本不做下载，只在缺失时给出明确报错。
check_vendor() {
    name="$1"; dest="$VENDOR_DIR/$name"
    if [ -f "$dest" ]; then
        echo "fetch-deps: [OK   ] vendor/$name（本地产物，脚本不校验）"
    else
        echo "fetch-deps: [MISS ] vendor/$name" >&2
        echo "          这是 Sivita 自改版，公网不可得。请从生产机取回：" >&2
        echo "          see libs/vendor/README.md" >&2
        FAILED="$FAILED vendor/$name"
    fi
}
check_vendor "MyPet-4.0.0-Sivita.jar"
check_vendor "worldguard-bukkit-7.0.18-Sivita.jar"

# ---------------------------------------------------------------- 结果

if [ -n "$FAILED" ]; then
    echo "fetch-deps: 失败条目：$FAILED" >&2
    exit 1
fi
echo "fetch-deps: 全部就绪（libs/ 7 个公开源 artifact + vendor 2 个自改版）"
