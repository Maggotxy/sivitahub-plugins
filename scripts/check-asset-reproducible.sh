#!/bin/sh
# scripts/check-asset-reproducible.sh —— 生成器可复现性门禁（W13-A1）
#
# 失败模式（本门禁要掐死的形态）：
#   scripts/check-asset-baseline.py 判的是「磁盘像素 == 清单登记的像素」，
#   它挡不住「注册表和生成器一起漂走」—— 有人手改了 PNG、再重跑
#   scripts/gen-asset-manifest.py 把新像素登记进基线，两道门禁全绿，
#   但那 30 张物品纹理从此再也造不出来（生成器与仓库不同步是**静默**的）。
#   本门禁把「再造一次」变成 CI 断言：现场编译并运行 resource-pack/tools/ 下的
#   Java 生成器到**临时目录**，再逐像素比对仓库里的对应 PNG。
#
# 覆盖：GenerateTextures（31 键 + 5 张补进生成器的 skill_*）、GenerateHudAtlas、
#       GenerateSkillHudAtlas、GenerateCompassFrames（32 帧 + 32 模型）。
#       覆盖清单在 resource-pack/asset-reproducible.json 里，不在本脚本里写死。
#
# 为什么不纳入 GenerateSivitaHudPanel：它是 132x44 的**遗留物** ——
#   resource-pack/build.sh 不调用它（现役的 26x91 面板由
#   tools/generate_betterhud_panels.py 产出），它的输出不在
#   resource-pack/assets/** 的基线里（assets-manifest.json 中没有对应条目）。
#   把一张没有对应仓库产物的生成器拖进门禁，只会得到一条恒真的假判据。
#
# 环境：CNB 的 eclipse-temurin:25-jdk 镜像里自带 java/javac，不会走到下载分支；
#   本仓的开发容器没有 JDK，这时按 W12-A3 的做法自取 Temurin 25
#   （清华 Adoptium 镜像，国内可达；比 api.adoptium.net 稳）。
#   下载失败/不可达时脚本**失败**而不是静默跳过 —— 静默跳过就等于门禁消失。
#
# 判据行：ASSET_REPRO_GATE=GREEN|RED
# 退出码：0 绿；1 红；2 环境不可用（找不到或装不上 JDK）
#
# 用法：sh scripts/check-asset-reproducible.sh [--root <仓库根>]
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [ "${1:-}" = "--root" ] && [ -n "${2:-}" ]; then
    ROOT=$(CDPATH= cd -- "$2" && pwd)
fi
cd "$ROOT"

JDK_HOME=${ASSET_REPRO_JDK:-}
TMP_ROOT=$(mktemp -d)
trap 'rm -rf "$TMP_ROOT"' EXIT

find_jdk() {
    if [ -n "$JDK_HOME" ] && [ -x "$JDK_HOME/bin/javac" ]; then
        echo "$JDK_HOME"
        return 0
    fi
    for candidate in "$ROOT/.asset-repro-jdk" /opt/jdk25; do
        if [ -x "$candidate/bin/javac" ]; then
            echo "$candidate"
            return 0
        fi
    done
    if command -v javac >/dev/null 2>&1; then
        dirname "$(dirname "$(command -v javac)")"
        return 0
    fi
    return 1
}

install_jdk() {
    # 清华 Adoptium 镜像：目录页里唯一的 25 GA 包名（写死包名而不是解析 HTML，
    # 解析 HTML 会在镜像改版时变成一个看不懂的失败）。
    mirror="https://mirrors.tuna.tsinghua.edu.cn/Adoptium/25/jdk/x64/linux"
    archive="OpenJDK25U-jdk_x64_linux_hotspot_25.0.4.1_1.tar.gz"
    dest="$TMP_ROOT/jdk"
    mkdir -p "$dest"
    echo "[repro] 本地无 JDK，自取 Temurin 25：$mirror/$archive"
    if command -v curl >/dev/null 2>&1; then
        curl -sSL -o "$TMP_ROOT/jdk.tar.gz" "$mirror/$archive"
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$TMP_ROOT/jdk.tar.gz" "$mirror/$archive"
    else
        echo "ASSET_REPRO_GATE=RED 没有 curl/wget，装不上 JDK（CI 请用 eclipse-temurin:25-jdk）" >&2
        return 1
    fi
    tar xzf "$TMP_ROOT/jdk.tar.gz" -C "$dest" --strip-components=1
    JDK_HOME="$dest"
    echo "[repro] 已安装 $("$JDK_HOME/bin/javac" -version 2>&1)"
}

JDK_HOME=$(find_jdk) || { install_jdk || exit 2; }
[ -x "$JDK_HOME/bin/javac" ] || { echo "ASSET_REPRO_GATE=RED $JDK_HOME/bin/javac 不可执行" >&2; exit 2; }

CLASSES="$TMP_ROOT/classes"
GEN="$TMP_ROOT/generated"
mkdir -p "$CLASSES" "$GEN/GenerateTextures" "$GEN/GenerateHudAtlas" \
    "$GEN/GenerateSkillHudAtlas" "$GEN/GenerateCompassFrames/textures" "$GEN/GenerateCompassFrames/models"

echo "[repro] javac -d <tmp>/classes resource-pack/tools/{GenerateTextures,GenerateHudAtlas,GenerateSkillHudAtlas,GenerateCompassFrames}.java"
"$JDK_HOME/bin/javac" -d "$CLASSES" \
    resource-pack/tools/GenerateTextures.java \
    resource-pack/tools/GenerateHudAtlas.java \
    resource-pack/tools/GenerateSkillHudAtlas.java \
    resource-pack/tools/GenerateCompassFrames.java

echo "[repro] java GenerateTextures <tmp>/generated/GenerateTextures --force"
"$JDK_HOME/bin/java" -cp "$CLASSES" GenerateTextures "$GEN/GenerateTextures" --force
echo "[repro] java GenerateHudAtlas <tmp>/generated/GenerateHudAtlas/hud_symbols.png"
"$JDK_HOME/bin/java" -cp "$CLASSES" GenerateHudAtlas "$GEN/GenerateHudAtlas/hud_symbols.png"
echo "[repro] java GenerateSkillHudAtlas <tmp>/generated/GenerateSkillHudAtlas/class_skill_symbols.png"
"$JDK_HOME/bin/java" -cp "$CLASSES" GenerateSkillHudAtlas "$GEN/GenerateSkillHudAtlas/class_skill_symbols.png"
echo "[repro] java GenerateCompassFrames assets/.../traveler_compass.png <tmp>/generated/GenerateCompassFrames/{textures,models}"
"$JDK_HOME/bin/java" -cp "$CLASSES" GenerateCompassFrames \
    resource-pack/assets/originalrpg/textures/item/traveler_compass.png \
    "$GEN/GenerateCompassFrames/textures" "$GEN/GenerateCompassFrames/models"

# 罗盘模型 JSON 是纯文本，逐字节比对（生成器只写「内容变了」的文件，因此必须比内容而非时间戳）
echo "[repro] 逐字节比对 32 个罗盘模型 JSON"
model_diff=0
for model in "$GEN/GenerateCompassFrames/models"/*.json; do
    name=$(basename "$model")
    target="resource-pack/assets/originalrpg/models/item/$name"
    if [ ! -f "$target" ]; then
        echo "    丢掉 $name（仓库里没有）" >&2
        model_diff=$((model_diff + 1))
        continue
    fi
    cmp -s "$model" "$target" || { echo "    ! $name 与仓库不一致" >&2; model_diff=$((model_diff + 1)); }
done
echo "[repro] 罗盘模型 JSON：一致 $((32 - model_diff))/32"
if [ "$model_diff" -ne 0 ]; then
    echo "ASSET_REPRO_GATE=RED 罗盘模型 JSON 有 $model_diff 个不一致"
    exit 1
fi

# 逐像素判据在 python 里（复用 scripts/asset_fingerprint.py，不另写一套 PNG 解码）
exec python3 scripts/check-asset-reproducible.py --root "$ROOT" --generated "$GEN"
