#!/bin/sh
# scripts/selftest-gates.sh —— 质量门「自身逻辑」的自测（负向对照）
#
# 背景：门禁最危险的失效不是「红了」，而是「永远绿」或「永远红」——
#   永远绿 = 漏判（假门禁），永远红 = 假红（没人再信门禁）。
# 本脚本用最小夹具逐个验证三件事：
#   1) scripts/dump-test-results.py 的四个判据分支都会给出预期退出码
#   2) scripts/check-plugin-jars.py 的四个判据分支都会给出预期退出码
#   3) scripts/list-env-tests.sh 在真实源码上能抽出非空清单
#   4) scripts/check-ddl-consumers.sh 的第 7 节（DDL 注册门禁/反向检查）在四种形态下
#      给出预期退出码：正常 → 0；代码引用不存在的表 → 1；前缀误报 → 1；显式豁免 → 0
#   4b) scripts/check-source-contract.sh 在不可能满足的顺序契约上必须红、真实顺序必须绿
#   4c) scripts/install-datapacks.sh --check 在真实数据包上必须绿，函数调用未注册命令后必须红
#   5) scripts/check-repo-artifacts.sh 在真实文档上必须绿，且把数据包目录藏起来后必须红
#   5b) resource-pack 的 HUD 尺寸门禁（W7-A4）必须能真的变红，且 build.sh 常量与唯一来源一致
#   6) G-206 接线自证：§4c/§5 两段门禁必须被本脚本真实调用、且被 .cnb.yml 执行
#   7) scripts/check-resource-pack.sh（W7-A1 素材门禁）的八条判据都能真的变红
#   8) W7-A1 接线自证：素材门禁必须真的在 CI 里跑
#   9) W12-A1 素材逐像素基线门禁的四组负向对照（改 1 像素 / 删图 / 加未登记图 / 还原）
#
# 用法：sh scripts/selftest-gates.sh     （退出码 0 = 全部通过）
#
# G-206 接线说明（要求④「把这类问题在 CI 拦下来」）：
#   §4c / §5 这两段就是「仓库内数据包 + docs 产物引用」的 CI 拦截点。
#   .cnb.yml 的「门禁自测（负向对照）」stage 直接执行本脚本（push 与 api_trigger_gate
#   共用同一套 stages），因此这两段判据每次推送都会真实运行、退出码直接决定质量门。
#   这样接线不需要改 .cnb.yml，也不需要往 plugins/ 里塞测试类。
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

PASS=0
FAIL=0
check() {
  # check <描述> <期望退出码> <实际退出码>
  if [ "$2" = "$3" ]; then
    echo "  [OK  ] $1 (exit=$3)"
    PASS=$((PASS + 1))
  else
    echo "  [FAIL] $1 期望 exit=$2 实际 exit=$3" >&2
    FAIL=$((FAIL + 1))
  fi
}

xml() {
  # xml <目标目录> <tests> <failures> <errors>
  mkdir -p "$1"
  printf '<testsuite tests="%s" failures="%s" errors="%s" skipped="0"></testsuite>' "$2" "$3" "$4" \
    > "$1/TEST-selftest.xml"
}

echo "== 1. dump-test-results.py 判定分支 =="
WS=$TMP/ws
mkdir -p "$WS/scripts"
cp scripts/dump-test-results.py "$WS/scripts/"
(
  cd "$WS"
  xml plugins/rpg-core/build/test-results/test 3 0 0
  xml plugins/rpg-guide/build/test-results/test 2 0 0
) >/dev/null
run_dump() {
  # run_dump <workdir> <期望码> <描述>
  set +e
  (cd "$1" && python3 scripts/dump-test-results.py --min-modules "$2" >/dev/null 2>&1)
  rc=$?
  set -e
  check "$3" "$4" "$rc"
}
run_dump "$WS" 2 "全绿 → 0" 0

rm -rf "$WS/plugins"
(cd "$WS" && xml plugins/rpg-core/build/test-results/test 3 1 0) >/dev/null
run_dump "$WS" 1 "有用例失败 → 1" 1

rm -rf "$WS/plugins"
(cd "$WS" && xml plugins/rpg-core/build/test-results/test 3 0 0 && mkdir -p plugins/rpg-guide/build/test-results/test) >/dev/null
run_dump "$WS" 1 "模块漏跑(NO_XML) → 1" 1

rm -rf "$WS/plugins"
(cd "$WS" && mkdir -p plugins/rpg-core/build/test-results/test) >/dev/null
run_dump "$WS" 1 "一个 XML 都没有 → 2" 2

rm -rf "$WS/plugins"
(cd "$WS" && xml plugins/rpg-core/build/test-results/test 3 0 0) >/dev/null
run_dump "$WS" 26 "模块数不足 → 1" 1

echo "== 2. check-plugin-jars.py 判定分支 =="
JW=$TMP/jw
mkdir -p "$JW/scripts"
cp scripts/check-plugin-jars.py "$JW/scripts/"
make_jar() {
  # make_jar <路径> <是否有 plugin.yml>
  python3 - "$1" "$2" <<'PY'
import os, sys, zipfile
path, healthy = sys.argv[1], sys.argv[2] == 'yes'
os.makedirs(os.path.dirname(path), exist_ok=True)
with zipfile.ZipFile(path, 'w') as z:
    if healthy:
        z.writestr('plugin.yml', 'name: X\n')
PY
}
run_jar() {
  # run_jar <清单文件> <期望码> <描述>
  set +e
  (cd "$JW" && python3 scripts/check-plugin-jars.py "$1" >/dev/null 2>&1)
  rc=$?
  set -e
  check "$3" "$2" "$rc"
}

cd "$JW"
make_jar plugins/rpg-core/build/libs/rpg-core-0.1.0.jar yes
make_jar plugins/rpg-guide/build/libs/rpg-guide-0.1.0.jar yes
printf '%s\n' plugins/rpg-core/build/libs/rpg-core-0.1.0.jar \
  plugins/rpg-guide/build/libs/rpg-guide-0.1.0.jar > list_ok.txt
run_jar list_ok.txt 0 "插件 jar 都含 plugin.yml → 0"

make_jar plugins/rpg-broken/build/libs/rpg-broken-0.1.0.jar no
printf '%s\n' plugins/rpg-broken/build/libs/rpg-broken-0.1.0.jar > list_bad.txt
run_jar list_bad.txt 1 "插件 jar 缺 plugin.yml → 1"

# sources jar 与 tools/ 下的 jar 不算「插件产物」，不该被检查
make_jar plugins/rpg-guide/build/libs/rpg-guide-0.1.0-sources.jar no
make_jar tools/db-migration/build/libs/db-migration-0.1.0.jar no
printf '%s\n' plugins/rpg-guide/build/libs/rpg-guide-0.1.0-sources.jar \
  tools/db-migration/build/libs/db-migration-0.1.0.jar > list_skip.txt
run_jar list_skip.txt 1 "只有非插件 jar 时不得判绿（JAR_CHECKED=0）→ 1"
cd "$ROOT"

echo "== 3. list-env-tests.sh 在真实源码上必须抽出非空清单 =="
set +e
ENV_LIST=$(sh scripts/list-env-tests.sh)
rc=$?
set -e
check "抽取命令退出码 0" 0 "$rc"
env_count=$(printf '%s\n' "$ENV_LIST" | sed '/^$/d' | wc -l | tr -d ' ')
if [ "$env_count" -ge 1 ]; then
  echo "  [OK  ] 抽出 $env_count 个 @Tag(env) 测试方法"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] 抽出 0 个 @Tag(env) 测试方法（抽取逻辑失效，门禁的排除清单会变空）" >&2
  FAIL=$((FAIL + 1))
fi

echo "== 4. check-ddl-consumers.sh 第 7 节（DDL 注册门禁：代码引用的表必须在 DDL 里）=="
# W4-A2 的 blocker 就是这个形态：代码写了 `INSERT INTO rpg_skill_point_grants`，
# DDL 里却没有这张表 —— 编译与单测（自带 H2 夹具）全绿，真机启动后任何授予经验
# 都以 Table not found 回滚。这里用最小夹具把反向检查的每个分支复跑一遍。
#
# 夹具的两个纪律（都踩过）：
#   1) 夹具必须由门禁自己的加载器构造（scripts/test-frames.py build ddl-consumer-root）：
#      第一次手搭夹具漏拷业务源码，第 1 节把 75 张表全报成未接线、门禁直接红，
#      那次自测的红/绿毫无解释力。
#   2) 夹具里每个扫描根都要有**真实源码**（构造器从 HEAD 取 Repository.java 一类文件
#      占位，而不是塞探针）：探针只引用 1 张表会让第 1 节必红，第 7 节的红线就分不清
#      「门禁真的抓到了」还是「被上一节的假红带崩的」。
GW=$TMP/gw
reinit() {
  # reinit [--remove-table <t>]：重建夹具（构造器拒绝覆盖非空目录，先删）
  rm -rf "$GW"
  python3 scripts/test-frames.py build ddl-consumer-root "$GW" "$@" >/dev/null 2>&1
  printf '%s' "$?"
}
probe() {
  # probe <Java 源码文件>：注入一份引用表名的探针（唯一变量）
  mkdir -p "$GW/plugins/rpg-core/src/main/java/cn/originalrpg/core/consumer"
  cp "$1" "$GW/plugins/rpg-core/src/main/java/cn/originalrpg/core/consumer/Consumer.java"
}
run_gate() {
  # run_gate <探针源码文件> <期望判据行> <描述>
  probe "$1"
  set +e
  (cd "$GW" && sh scripts/check-ddl-consumers.sh >"$GW/out.txt" 2>&1)
  rc=$?
  set -e
  if grep -qF "$2" "$GW/out.txt"; then
    echo "  [OK  ] $3 → 命中判据行「$2」（gate exit=$rc）"
    PASS=$((PASS + 1))
  else
    echo "  [FAIL] $3（输出里没有判据行：$2，gate exit=$rc）" >&2
    FAIL=$((FAIL + 1))
  fi
}

cat > "$TMP/clean.java" <<'JAVA'
package cn.originalrpg.core.consumer;
final class Consumer {
    void clean() { io("SELECT 1"); }
    static void io(String sql) { }
}
JAVA
cat > "$TMP/real.java" <<'JAVA'
package cn.originalrpg.core.consumer;
final class Consumer {
    void real() { io("SELECT player_uuid FROM rpg_profiles WHERE player_uuid = ?"); }
    static void io(String sql) { }
}
JAVA
cat > "$TMP/ghost.java" <<'JAVA'
package cn.originalrpg.core.consumer;
final class Consumer {
    void bad() { io("INSERT INTO rpg_ghost_table (a) VALUES (?)"); }
    static void io(String sql) { }
}
JAVA
cat > "$TMP/prefix.java" <<'JAVA'
package cn.originalrpg.core.consumer;
final class Consumer {
    void bad() { io("DELETE FROM rpg_ghost_ledger WHERE id = ?"); }
    static void io(String sql) { }
}
JAVA
cat > "$TMP/w4a2.java" <<'JAVA'
package cn.originalrpg.core.consumer;
final class Consumer {
    void grant() { io("INSERT INTO rpg_skill_point_grants (source) VALUES (?)"); }
    static void io(String sql) { }
}
JAVA
cat > "$TMP/pending.java" <<'JAVA'
package cn.originalrpg.core.consumer;
final class Consumer {
    void pending() { io("SELECT player_uuid FROM rpg_progression_anomalies"); }
    static void io(String sql) { }
}
JAVA

frame_rc=$(reinit)
if [ "$frame_rc" != "0" ]; then
  echo "  [FAIL] 夹具构造失败 frame_rc=$frame_rc" >&2
  FAIL=$((FAIL + 1))
fi
run_gate "$TMP/clean.java" "DDL_CONSUMER_GATE=GREEN" "库内代码引用的表全部已登记 → 整门 GREEN"
run_gate "$TMP/real.java" "DDL_REGISTRATION_GATE=GREEN" "真实表名 rpg_profiles 已登记 → 第 7 节 GREEN"
run_gate "$TMP/ghost.java" "DDL_REGISTRATION_GATE=RED" "代码引用了 DDL 里不存在的表 → 第 7 节 RED"
run_gate "$TMP/prefix.java" "DDL_REGISTRATION_GATE=RED" "其它 SQL 关键词（DELETE FROM）同样抓到缺表"
run_gate "$TMP/w4a2.java" "DDL_REGISTRATION_GATE=GREEN" "W4-A2 的真实表名 rpg_skill_point_grants 已补进 DDL"

echo "== 4b. source-contract 门禁必须能真的变红（负向对照） =="
# 契约门禁最危险的失效是「规格与源码双漂移后仍然全绿」。这里用最小夹具证明：
# 在临时规格里写一条不可能满足的顺序契约，门禁必须 RED；改成真实顺序必须 GREEN。
frag="$TMP/contract-frag"
mkdir -p "$frag"
cat > "$frag/Target.java" <<'JAVA'
class Target { void a() {} void b() {} }
JAVA
SPEC_TMP="$TMP/spec-negative.json"

write_spec() {
  # $1 = ordered 序列的 JSON 数组, $2 = forbidden 的 JSON 数组
  cat > "$SPEC_TMP" <<JSON
{
 "fake/Test.java": {
  "files": ["$frag/Target.java"],
  "checks": [
   {"method": "selftest", "required": [], "forbidden": $2, "ordered": $1}
  ]
 }
}
JSON
}

run_contract() {
  set +e
  SOURCE_CONTRACT_SPEC="$SPEC_TMP" sh scripts/check-source-contract.sh --all >/dev/null 2>&1
  echo $?
}

write_spec '["void b()", "void a()"]' '[]'
check "不可能满足的顺序契约 → 1" 1 "$(run_contract)"

write_spec '["void a()", "void b()"]' '[]'
check "真实顺序契约 → 0" 0 "$(run_contract)"

write_spec '[]' '["void a()"]'
check "禁止片段命中 → 1" 1 "$(run_contract)"

write_spec '[]' '["never-in-this-file"]'
check "禁止片段未命中 → 0" 0 "$(run_contract)"
cd "$ROOT"

echo "== 4c. install-datapacks 门禁（G-206）必须能真的变红（负向对照） =="
# 数据包校验门禁最危险的失效同样是「永远绿」：tag 只声明不引用真实函数、
# 或函数里调用一个不存在的命令时，仓库看起来交付完成，玩家按 G 却什么都打不开。
# 这里用最小夹具证明：真实数据包必须绿，函数改成未注册命令后必须红。
#
# ⚠️ 负向对照**必须在临时副本上做**：早期版本直接改写仓库里的 home.mcfunction 再恢复，
# 一旦脚本中途被中断（CI 超时 / Ctrl-C），工作区会留下一个被改坏的数据包 ——
# 这正是本自测要防的那类「静默损坏」。现在副本放在 $TMP，真实仓库只读。
DATAPACK_FIXTURE="$TMP/datapack-ws"
mkdir -p "$DATAPACK_FIXTURE"
cp -R "$ROOT/datapacks" "$DATAPACK_FIXTURE/datapacks"
# install-datapacks.sh 从脚本自身位置解析 ROOT，所以夹具里也要放一份脚本副本。
# 它还会读 plugins/*/src/main/resources/plugin.yml 校验「run /<命令>」确实被注册，
# 夹具因此必须带上这些清单 —— 否则红跑会因为「找不到 plugins/」而红，
# 那是假红：它证明不了「未注册命令会被拦下」这条真正的判据。
mkdir -p "$DATAPACK_FIXTURE/scripts"
cp "$ROOT/scripts/install-datapacks.sh" "$DATAPACK_FIXTURE/scripts/"
mkdir -p "$DATAPACK_FIXTURE/plugins"
for plugin_dir in "$ROOT"/plugins/*/; do
  plugin_name=$(basename "$plugin_dir")
  manifest="$plugin_dir/src/main/resources/plugin.yml"
  [ -f "$manifest" ] || continue
  mkdir -p "$DATAPACK_FIXTURE/plugins/$plugin_name/src/main/resources"
  cp "$manifest" "$DATAPACK_FIXTURE/plugins/$plugin_name/src/main/resources/plugin.yml"
done

set +e
(cd "$DATAPACK_FIXTURE" && sh scripts/install-datapacks.sh --check >"$TMP/datapack-green.txt" 2>&1)
datapack_green=$?
set -e
check "真实数据包 → 0" 0 "$datapack_green"

if [ "$datapack_green" -ne 0 ]; then
  echo "  --- 绿跑输出尾部 ---" >&2
  tail -n 20 "$TMP/datapack-green.txt" >&2
fi

# 负向：函数体改成调用未注册命令（只改夹具，不碰仓库）。
victim="$DATAPACK_FIXTURE/datapacks/sivita-navigation/data/sivita/function/quick/home.mcfunction"
printf '%s\n' 'run /definitely-not-registered' > "$victim"
set +e
(cd "$DATAPACK_FIXTURE" && sh scripts/install-datapacks.sh --check >"$TMP/datapack-red.txt" 2>&1)
datapack_red=$?
set -e
check "函数调用未注册命令 → 1" 1 "$datapack_red"

if [ "$datapack_red" -ne 1 ]; then
  echo "  --- 红跑输出尾部 ---" >&2
  tail -n 20 "$TMP/datapack-red.txt" >&2
fi

# 自证：负向对照没有污染真实仓库（这正是上面那条注释承诺的不变量）。
set +e
(cd "$ROOT" && sh scripts/install-datapacks.sh --check >/dev/null 2>&1)
datapack_after=$?
set -e
check "负向对照后仓库内数据包仍为绿（工作区未被污染）" 0 "$datapack_after"

echo "== 5. repo-artifact 门禁（G-206）必须能真的变红（负向对照） =="
# 这条门禁要拦的是「docs 引用了仓库里不存在的 server/ 产物」。
# 最危险的失效是「写了就等于绿」——所以在真实文档上跑一次（必须绿），
# 再把 datapacks/ 目录临时藏起来跑一次（必须红）。
set +e
(cd "$ROOT" && sh scripts/check-repo-artifacts.sh >"$TMP/artifact-green.txt" 2>&1)
artifact_green=$?
set -e
check "真实文档 + 真实数据包 → 0" 0 "$artifact_green"

if [ "$artifact_green" -ne 0 ]; then
  echo "  --- 绿跑输出尾部 ---" >&2
  tail -n 20 "$TMP/artifact-green.txt" >&2
fi

mv "$ROOT/datapacks" "$TMP/datapacks-hidden"
set +e
(cd "$ROOT" && sh scripts/check-repo-artifacts.sh >"$TMP/artifact-red.txt" 2>&1)
artifact_red=$?
set -e
mv "$TMP/datapacks-hidden" "$ROOT/datapacks"
check "把 datapacks/ 藏起来 → 1" 1 "$artifact_red"

cd "$ROOT"

echo "== 5b. HUD 尺寸门禁（W7-A4）必须能真的变红（负向对照） =="
# HUD 尺寸契约最危险的失效是「唯一来源与素材双漂移后仍然全绿」：
# 设计基准写 26x91、实际素材却是别的尺寸，门禁却因为只读了一边而不报。
# 这里用**临时副本**做负向对照（不改仓库：见 §4c 的教训）：
#   ① 真实仓库必须绿；
#   ② 把唯一来源里的轨道宽度改掉（素材不动）必须红；
#   ③ 把一张 18x18 图标改成 17 宽必须红；
#   ④ 把 build.sh 里的 HUD_PANEL_WIDTH 改掉（唯一来源不动）必须红。
HUD_FIXTURE="$TMP/hud-ws"
mkdir -p "$HUD_FIXTURE/docs/reference" "$HUD_FIXTURE/resource-pack/tools"
cp -R "$ROOT/resource-pack/assets" "$HUD_FIXTURE/resource-pack/assets"
cp "$ROOT/docs/reference/hud-layout.json" "$HUD_FIXTURE/docs/reference/"
cp "$ROOT/resource-pack/tools/verify_hud_layout.py" "$HUD_FIXTURE/resource-pack/tools/"
cp "$ROOT/resource-pack/build.sh" "$HUD_FIXTURE/resource-pack/"

run_hud_py() {
  # 额外参数用于把「build.sh 被改坏」的夹具指给门禁（--build-script <path>）
  set +e
  python3 "$HUD_FIXTURE/resource-pack/tools/verify_hud_layout.py" "$@" >/dev/null 2>&1
  echo $?
}

check "真实素材 + 唯一来源 + build.sh 常量 → 0" 0 "$(run_hud_py)"

python3 - "$HUD_FIXTURE/docs/reference/hud-layout.json" <<'PYJSON'
import json, sys
path = sys.argv[1]
layout = json.load(open(path, encoding='utf-8'))
layout["rail"]["width"] = 27
json.dump(layout, open(path, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
PYJSON
check "唯一来源轨道宽 27（素材仍 26）→ 1" 1 "$(run_hud_py)"

# 复原唯一来源，改一张图标的真实像素宽（PNG IHDR）
python3 - "$HUD_FIXTURE/docs/reference/hud-layout.json" "$ROOT/docs/reference/hud-layout.json" <<'PYJSON'
import shutil, sys
shutil.copyfile(sys.argv[2], sys.argv[1])
PYJSON
python3 - "$HUD_FIXTURE/resource-pack/assets/betterhud/textures/glyph_skill_heal.png" <<'PYJSON'
import struct, sys
data = bytearray(open(sys.argv[1], 'rb').read())
struct.pack_into('>I', data, 16, 17)
open(sys.argv[1], 'wb').write(bytes(data))
PYJSON
check "图标真实宽度 17（唯一来源 18）→ 1" 1 "$(run_hud_py)"

# build.sh 常量与唯一来源不一致 → Node 侧必须红
python3 - "$HUD_FIXTURE/resource-pack/build.sh" <<'PYJSON'
import pathlib, sys
path = pathlib.Path(sys.argv[1])
text = path.read_text(encoding='utf-8')
path.write_text(text.replace('HUD_PANEL_WIDTH=26', 'HUD_PANEL_WIDTH=27', 1), encoding='utf-8')
PYJSON
# 把夹具里的 build.sh 常量改坏，再让门禁指向它（唯一来源保持 26）
sed 's/^HUD_PANEL_WIDTH=26$/HUD_PANEL_WIDTH=27/' "$HUD_FIXTURE/resource-pack/build.sh" \
  > "$HUD_FIXTURE/resource-pack/build-broken.sh"
check "build.sh HUD_PANEL_WIDTH=27（唯一来源 26）→ 1" 1 \
  "$(run_hud_py --build-script "$HUD_FIXTURE/resource-pack/build-broken.sh")"
cd "$ROOT"

echo "== 6. G-206 接线自证：两条门禁必须被本脚本真实覆盖 =="
# 「写了门禁但 CI 里没跑」是比「门禁有 bug」更隐蔽的失败：脚本存在、文档写着，
# 但没有任何 CI stage 调用它。本段把「谁在跑」变成可 grep 的事实：
#   a) 本脚本自身必须调用 check-repo-artifacts.sh 与 install-datapacks.sh
#      （否则 §4c/§5 被删掉后上面仍然全绿，门禁回到「静默没人跑」状态）
#   b) 质量门 .cnb.yml 必须包含 `sh scripts/selftest-gates.sh`
#      —— 这就是 §4c/§5 进入 CI 的接线点，且本次改动无需修改 .cnb.yml
for wired in "scripts/check-repo-artifacts.sh" "scripts/install-datapacks.sh" \
        "resource-pack/tools/verify_hud_layout.py" "resource-pack/build.sh"; do
  set +e
  hits=$(grep -c -F "$wired" "$ROOT/scripts/selftest-gates.sh")
  set -e
  if [ "$hits" -ge 1 ]; then
    echo "  [OK  ] selftest-gates.sh 覆盖 $wired（$hits 处调用）"
    PASS=$((PASS + 1))
  else
    echo "  [FAIL] selftest-gates.sh 没有调用 $wired —— 该门禁不在 CI 里" >&2
    FAIL=$((FAIL + 1))
  fi
done

if [ -f "$ROOT/.cnb.yml" ] && grep -qF 'sh scripts/selftest-gates.sh' "$ROOT/.cnb.yml"; then
  echo "  [OK  ] .cnb.yml 的门禁自测 stage 执行 selftest-gates.sh（G-206 门禁接入 CI）"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] .cnb.yml 没有执行 selftest-gates.sh —— G-206 门禁不在 CI 里" >&2
  FAIL=$((FAIL + 1))
fi

# 负向对照：用一个**不存在的**门禁路径去查，必须查不到 ——
# 证明上一段的「查得到才算接线」真的会因为缺失而失败，而不是恒绿。
# 路径拼接而成，避免这行赋值自身被 grep 命中（否则负向对照永远「查得到」，
# 正是这种自匹配把负向对照变成恒绿 —— 实测第一版就踩了这个坑）。
phantom="scripts/definitely-not-a-real""-gate.sh"
# `grep -c` 零命中时退出码为 1；`set -e` 会让赋值直接终止脚本，
# 所以先关掉 errexit 取值、再打开 —— 不要用 `$(...; exit 0)`（子 shell 的 exit 拦不住外层的 set -e）。
set +e
phantom_hits=$(grep -c -F "$phantom" "$ROOT/scripts/selftest-gates.sh")
set -e
if [ "$phantom_hits" = "0" ]; then
  echo "  [OK  ] 不存在的门禁查不到（接线判据能变红）"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] 不存在的门禁竟被查到 $phantom_hits 次 —— 接线判据恒绿" >&2
  FAIL=$((FAIL + 1))
fi

echo "== 7. 素材门禁（W7-A1）八条判据都必须能真的变红（负向对照） =="
# 失败模式：素材门禁写在 scripts/check-resource-pack.sh 里，但它「看起来在跑」
# 和「真的会判红」是两件事 —— 本仓已经被这种假绿坑过多次（见本脚本开头）。
# 因此八条判据（清单一一对应 / 闭包 / 32x32 二值 alpha / HUD 十一码位 /
# 旅程八码位 / 中英语言键 / 罗盘 33 段方向分发 / pack.mcmeta 格式 88）在这里各做一次
# 「破坏 → 必须红 → 还原 → 必须绿」。
#
# ⚠️ 与 §4c 同样的纪律：负向对照**只在 $TMP 的夹具副本上做**。
# 早期版本直接改写仓库文件再恢复，一旦脚本被中断（CI 超时 / Ctrl-C），
# 工作区就会留下一个被改坏的素材 —— 那正是本自测要防的静默损坏。
RPG_FIXTURE="$TMP/resource-pack-ws"
mkdir -p "$RPG_FIXTURE/scripts"
# 夹具只需要 assets/originalrpg + 两个门禁脚本；整个 resource-pack/ 有 600+ 文件，
# 每次自测都拷贝会拖慢门禁，因此按判据实际读到的路径最小化拷贝。
mkdir -p "$RPG_FIXTURE/resource-pack/assets"
cp -R "$ROOT/resource-pack/assets/originalrpg" "$RPG_FIXTURE/resource-pack/assets/"
cp "$ROOT/resource-pack/runtime-item-models.txt" "$RPG_FIXTURE/resource-pack/"
# pack.mcmeta 也要进夹具：判据 8 读的就是它（漏掉会让判据 8 在夹具里恒红）
cp "$ROOT/resource-pack/pack.mcmeta" "$RPG_FIXTURE/resource-pack/"
cp "$ROOT/scripts/check-resource-pack.sh" "$ROOT/scripts/resource-pack-asset-check.py" \
  "$RPG_FIXTURE/scripts/"
# 还原用的**真实**仓库素材目录。所有 restore 命令都必须从这里取原始字节，
# 不能从夹具反拷（夹具里的文件已经被破坏过）；也不能用仓库自身当夹具。
RPG_REAL="$ROOT/resource-pack/assets/originalrpg"
RPG_REAL_TEX="$RPG_REAL/textures/item"
RPG_REAL_PACK="$ROOT/resource-pack"

run_rpg_gate() {
  # run_rpg_gate <夹具目录>；把退出码打到 stdout（调用方用命令替换取）
  set +e
  (cd "$1" && sh scripts/check-resource-pack.sh >/dev/null 2>&1)
  echo $?
}

check "素材门禁在干净夹具上必须绿（基准）" 0 \
  "$(run_rpg_gate "$RPG_FIXTURE")"

# 逐条负向：$1 = 判据名（与 --tests 过滤一致）, $2 = 描述, $3 = 破坏命令, $4 = 还原命令
rpg_negative() {
  name=$1
  desc=$2
  break_cmd=$3
  restore_cmd=$4
  (cd "$RPG_FIXTURE" && eval "$break_cmd") >/dev/null 2>&1
  # 用 --tests 只跑被破坏的那一条：既让「哪条判据红了」是确定的事实，
  # 也顺带证明每条判据可以独立运行（不是整包一起红才有意义）。
  set +e
  (cd "$RPG_FIXTURE" && sh scripts/check-resource-pack.sh --tests "$name" >/dev/null 2>&1)
  rc=$?
  set -e
  if [ "$rc" -ne 1 ]; then
    echo "  [FAIL] $desc —— 期望 exit=1 实际 $rc（判据 $name 不会红 = 假门禁）" >&2
    FAIL=$((FAIL + 1))
  else
    PASS=$((PASS + 1))
  fi
  (cd "$RPG_FIXTURE" && eval "$restore_cmd") >/dev/null 2>&1
  set +e
  (cd "$RPG_FIXTURE" && sh scripts/check-resource-pack.sh --tests "$name" >/dev/null 2>&1)
  rc2=$?
  set -e
  check "$desc（还原后必须绿）" 0 "$rc2"
}

# ① 清单一一对应：删一个 items 定义（清单里仍有该键）
rpg_negative manifest "① 删 items 定义 → manifest 判据红" \
  "mv resource-pack/assets/originalrpg/items/dungeon_core.json /tmp/.w7a1-items.json" \
  "mv /tmp/.w7a1-items.json resource-pack/assets/originalrpg/items/dungeon_core.json"

# ② 闭包：删一个模型 → item 定义闭包断裂
rpg_negative closure "② 删 model → closure 判据红（闭包断裂）" \
  "mv resource-pack/assets/originalrpg/models/item/hunt_crystal.json /tmp/.w7a1-model.json" \
  "mv /tmp/.w7a1-model.json resource-pack/assets/originalrpg/models/item/hunt_crystal.json"

# ②b 闭包：新增一张没人引用的野纹理 → 孤儿纹理
rpg_negative closure "②b 新增野纹理 → closure 判据红（孤儿纹理）" \
  "cp resource-pack/assets/originalrpg/textures/item/hunt_crystal.png resource-pack/assets/originalrpg/textures/item/orphan_probe.png" \
  "rm -f resource-pack/assets/originalrpg/textures/item/orphan_probe.png"

# ③ 纹理尺寸：把某张 PNG 换成 16x16（夹具图由 python 现场生成，不依赖仓库里的坏文件）
python3 - "$TMP" <<'PY'
import struct, sys, zlib


def png(width, height, rgba):
    def chunk(kind, body):
        payload = kind + body
        return struct.pack('>I', len(body)) + payload + struct.pack(
            '>I', zlib.crc32(payload) & 0xFFFFFFFF)

    raw = b''.join(b'\x00' + rgba[y * width * 4:(y + 1) * width * 4] for y in range(height))
    return (b'\x89PNG\r\n\x1a\n'
            + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw))
            + chunk(b'IEND', b''))


tmp = sys.argv[1]
# 16x16 全不透明红：同时会触发「尺寸」与「没有全透明像素」两条，都是判据3 的应有反应
open(tmp + '/rpg-16.png', 'wb').write(png(16, 16, bytes([255, 0, 0, 255]) * 256))
# 32x32 全半透明红：只触发「alpha 不是二值」
open(tmp + '/rpg-aa.png', 'wb').write(png(32, 32, bytes([255, 0, 0, 128]) * 1024))
PY

# 说明：破坏用「覆盖已存在的 hunt_crystal.png」而不是「新加一张图」——
# 新增野纹理同时会踩到判据 2 的孤儿纹理，红的原因会变得不唯一，
# 而这条对照要证明的是「判据 3 自己会红」。
rpg_negative textures "③ 某张 PNG 改成 16x16 → textures 判据红" \
  "cp $TMP/rpg-16.png resource-pack/assets/originalrpg/textures/item/hunt_crystal.png" \
  "cp $RPG_REAL_TEX/hunt_crystal.png resource-pack/assets/originalrpg/textures/item/hunt_crystal.png"

rpg_negative textures "③b 某张 PNG 用半透明 alpha → textures 判据红" \
  "cp $TMP/rpg-aa.png resource-pack/assets/originalrpg/textures/item/hunt_crystal.png" \
  "cp $RPG_REAL_TEX/hunt_crystal.png resource-pack/assets/originalrpg/textures/item/hunt_crystal.png"

# ④ HUD 图集裁掉一列（99x9 → 98x9）
python3 - "$RPG_FIXTURE" "$TMP" <<'PY'
import struct, sys, zlib

fixture, tmp = sys.argv[1], sys.argv[2]
src = fixture + '/resource-pack/assets/originalrpg/textures/font/hud_symbols.png'
data = open(src, 'rb').read()
pos, idat, width, height = 8, [], None, None
while pos + 8 <= len(data):
    (length,) = struct.unpack('>I', data[pos:pos + 4])
    kind = data[pos + 4:pos + 8]
    body = data[pos + 8:pos + 8 + length]
    pos += 12 + length
    if kind == b'IHDR':
        width, height = struct.unpack('>II', body[:8])
    elif kind == b'IDAT':
        idat.append(body)
    elif kind == b'IEND':
        break
stride = width * 4
raw = zlib.decompress(b''.join(idat))
rows = [raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)] for y in range(height)]


def chunk(kind, body):
    payload = kind + body
    return struct.pack('>I', len(body)) + payload + struct.pack('>I', zlib.crc32(payload) & 0xFFFFFFFF)


nw = width - 1
out = (b'\x89PNG\r\n\x1a\n'
       + chunk(b'IHDR', struct.pack('>IIBBBBB', nw, height, 8, 6, 0, 0, 0))
       + chunk(b'IDAT', zlib.compress(b''.join(b'\x00' + rows[y][:nw * 4] for y in range(height))))
       + chunk(b'IEND', b''))
open(tmp + '/rpg-hud-cropped.png', 'wb').write(out)
PY

rpg_negative hud "④ HUD 图集裁掉一列（99x9→98x9）→ hud 判据红" \
  "cp $TMP/rpg-hud-cropped.png resource-pack/assets/originalrpg/textures/font/hud_symbols.png" \
  "cp $RPG_REAL/textures/font/hud_symbols.png resource-pack/assets/originalrpg/textures/font/hud_symbols.png"

# ④b hud.json 把 U+E00A 改成 U+E00B
rpg_negative hud "④b hud.json 把 U+E00A 改成 U+E00B → hud 判据红" \
  "python3 -c \"import json;p='resource-pack/assets/originalrpg/font/hud.json';d=json.load(open(p));d['providers'][0]['chars']=[d['providers'][0]['chars'][0][:-1]+'\\\\ue00b'];json.dump(d,open(p,'w'),ensure_ascii=False,indent=2)\"" \
  "cp $RPG_REAL/font/hud.json resource-pack/assets/originalrpg/font/hud.json"

# ⑤ journey.json 把 U+E107 改成 U+E108
rpg_negative journey "⑤ journey.json 把 U+E107 改成 U+E108 → journey 判据红" \
  "python3 -c \"import json;p='resource-pack/assets/originalrpg/font/journey.json';d=json.load(open(p));d['providers'][7]['chars']=['\\\\ue108'];json.dump(d,open(p,'w'),ensure_ascii=False,indent=2)\"" \
  "cp $RPG_REAL/font/journey.json resource-pack/assets/originalrpg/font/journey.json"

# ⑤b journey.json 把某枚码位指向错误的旅程图（错位引用）
rpg_negative journey "⑤b journey.json 码位指向错误旅程图 → journey 判据红" \
  "python3 -c \"import json;p='resource-pack/assets/originalrpg/font/journey.json';d=json.load(open(p));d['providers'][0]['file']='originalrpg:item/journey_dungeon.png';json.dump(d,open(p,'w'),ensure_ascii=False,indent=2)\"" \
  "cp $RPG_REAL/font/journey.json resource-pack/assets/originalrpg/font/journey.json"

# ⑥ 改一个 lang 键
# ⚠️ 破坏/还原都写死同一个键名，**不能**用 sorted(d)[0] 取「第一个键」：
# 破坏后键名变成 `<key>x`，`sorted(d)[0]` 可能不再是原键（字典序）
# ⇒ 还原命令 KeyError ⇒ set -e 让整个自测脚本静默中断。
# 实测踩过：§7 在第 ⑥ 条之后整段消失，看起来像「自测通过」。
RPG_LANG_KEY=resourcePack.originalrpg.name
rpg_negative lang "⑥ zh_cn.json 改一个资源键 → lang 判据红" \
  "python3 -c \"import json;p='resource-pack/assets/originalrpg/lang/zh_cn.json';d=json.load(open(p));k='$RPG_LANG_KEY';d[k+'x']=d.pop(k);json.dump(d,open(p,'w'),ensure_ascii=False,indent=2)\"" \
  "python3 -c \"import json;p='resource-pack/assets/originalrpg/lang/zh_cn.json';d=json.load(open(p));k='$RPG_LANG_KEY';d[k]=d.pop(k+'x');json.dump(d,open(p,'w'),ensure_ascii=False,indent=2)\""

# ⑦ 罗盘：删掉一段方向分发（33 → 32）
# 还原统一「从真实仓库拷干净文件回来」，不做逆向手术 ——
# 逆向手术（把删掉的段重新接上）依赖破坏后的状态，写错一次就是静默中断（见 ⑥）。
rpg_negative compass "⑦ 罗盘 entries 删一段（33→32）→ compass 判据红" \
  "python3 -c \"import json;p='resource-pack/assets/originalrpg/items/traveler_compass.json';d=json.load(open(p));d['model']['entries'].pop();json.dump(d,open(p,'w'),ensure_ascii=False,indent=2)\"" \
  "cp $RPG_REAL/items/traveler_compass.json resource-pack/assets/originalrpg/items/traveler_compass.json"

# ⑦b 罗盘：删掉一帧模型
rpg_negative compass "⑦b 删一帧罗盘模型 → compass 判据红" \
  "mv resource-pack/assets/originalrpg/models/item/traveler_compass_07.json /tmp/.w7a1-cp07.json" \
  "mv /tmp/.w7a1-cp07.json resource-pack/assets/originalrpg/models/item/traveler_compass_07.json"

# ⑧ pack.mcmeta：把资源格式改成 87（26.2 是 88）
rpg_negative packmeta "⑧ pack.mcmeta 把 pack_format 改成 87 → packmeta 判据红" \
  "python3 -c \"import json;p='resource-pack/pack.mcmeta';d=json.load(open(p));d['pack']['pack_format']=87;json.dump(d,open(p,'w'),ensure_ascii=False,indent=2)\"" \
  "cp $RPG_REAL_PACK/pack.mcmeta resource-pack/pack.mcmeta"

# ⑧b pack.mcmeta：声明 overlays（包必须自包含单一版本）
rpg_negative packmeta "⑧b pack.mcmeta 声明 overlays → packmeta 判据红" \
  "python3 -c \"import json;p='resource-pack/pack.mcmeta';d=json.load(open(p));d['overlays']={'entries':[]};json.dump(d,open(p,'w'),ensure_ascii=False,indent=2)\"" \
  "cp $RPG_REAL_PACK/pack.mcmeta resource-pack/pack.mcmeta"

# 自证：负向对照没有污染真实仓库（这正是本节所有注释承诺的不变量）
set +e
(cd "$ROOT" && sh scripts/check-resource-pack.sh >/dev/null 2>&1)
rpg_after=$?
set -e
check "负向对照后仓库内素材仍为绿（工作区未被污染）" 0 "$rpg_after"

# 自证：--tests 收敛开关本身不能变成「一条都不跑」的假绿
set +e
(cd "$RPG_FIXTURE" && sh scripts/check-resource-pack.sh --tests definitely-not-a-criterion >/dev/null 2>&1)
rpg_bogus=$?
set -e
check "--tests 传入不存在的判据 → 非零退出（RUN=0 必须 RED）" 1 "$rpg_bogus"

cd "$ROOT"

echo "== 8. W7-A1 接线自证：素材门禁必须真的在 CI 里跑 =="
# 「脚本写好了但没有任何 CI stage 调用它」是比「脚本有 bug」更隐蔽的失败。
# 与 §6 同一套判据：把「谁在跑」变成可 grep 的事实。
#   注意：本脚本必须断言的是 .cnb.yml 的**全量质量门**里有素材门禁 stage。
#   只断言 check-resource-pack.sh 出现在某处是不够的 —— 它也可能只出现在注释里。
if [ -f "$ROOT/.cnb.yml" ] && grep -qF 'sh scripts/check-resource-pack.sh' "$ROOT/.cnb.yml"; then
  echo "  [OK  ] .cnb.yml 全量质量门执行 check-resource-pack.sh"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] .cnb.yml 没有执行 check-resource-pack.sh —— 素材门禁不在 CI 里" >&2
  FAIL=$((FAIL + 1))
fi

# 负向对照：用一个不存在的门禁路径去查必须查不到（证明上面那条判据能变红，
# 而不是「grep 恒命中」）。路径拼接而成，避免本行赋值自身被 grep 命中。
rpg_phantom="scripts/definitely-not-a-real""-asset-gate.sh"
set +e
rpg_phantom_hits=$(grep -c -F "$rpg_phantom" "$ROOT/.cnb.yml")
set -e
if [ "$rpg_phantom_hits" = "0" ]; then
  echo "  [OK  ] 不存在的素材门禁查不到（接线判据能变红）"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] 不存在的素材门禁竟被查到 $rpg_phantom_hits 次 —— 接线判据恒绿" >&2
  FAIL=$((FAIL + 1))
fi

cd "$ROOT"

echo "== 9. 素材逐像素基线门禁（W12-A1）四组负向对照 =="
# 失败模式：素材门禁判的是「素材自洽」（闭包 / 尺寸 / 二值 alpha / 码位 / 语言键），
# 判不了「这张图还是不是原来那张」—— 换一张同样 32x32、同样二值 alpha 的图，
# 八条判据全绿，116 张 betterhud 纹理可以被人悄悄换掉而 CI 毫无反应。
# 基线门禁（scripts/check-asset-baseline.py）就是补这个洞，本节证明它真的会红。
#
# 与 §7 同样的纪律：**只在 $TMP 的夹具副本上破坏**，不碰仓库工作区。
# 夹具整份拷 resource-pack/（约 3 MB），因为门禁扫的是 assets/**/*.png 全量。
BASE_FIXTURE="$TMP/asset-baseline-ws"
mkdir -p "$BASE_FIXTURE/scripts"
cp -R "$ROOT/resource-pack" "$BASE_FIXTURE/resource-pack"
cp "$ROOT/scripts/check-asset-baseline.py" "$ROOT/scripts/asset_fingerprint.py" \
  "$BASE_FIXTURE/scripts/"

run_baseline_gate() {
  # 把退出码打到 stdout（调用方用命令替换取，不走管道）
  set +e
  (cd "$BASE_FIXTURE" && python3 scripts/check-asset-baseline.py >/dev/null 2>&1)
  echo $?
}

check "基线门禁在干净夹具上必须绿（基准）" 0 "$(run_baseline_gate)"

# ① 改一张图的一个像素：必须 RED，且报出是哪张 + 期望 / 实际指纹
python3 - "$BASE_FIXTURE" <<'PY'
import struct, sys, zlib

fixture = sys.argv[1]
path = fixture + '/resource-pack/assets/originalrpg/textures/item/hunt_crystal.png'
data = open(path, 'rb').read()
pos, idat = 8, []
width = height = None
while pos + 8 <= len(data):
    (length,) = struct.unpack('>I', data[pos:pos + 4])
    kind = data[pos + 4:pos + 8]
    body = data[pos + 8:pos + 8 + length]
    pos += 12 + length
    if kind == b'IHDR':
        width, height, bit_depth, color_type, _c, _f, _i = struct.unpack('>IIBBBBB', body)
    elif kind == b'IDAT':
        idat.append(body)
    elif kind == b'IEND':
        break
raw = bytearray(zlib.decompress(b''.join(idat)))
raw[1] = (raw[1] + 1) & 0xFF  # 只动 (0,0) 像素的第一个通道


def chunk(kind, body):
    payload = kind + body
    return (struct.pack('>I', len(body)) + payload
            + struct.pack('>I', zlib.crc32(payload) & 0xFFFFFFFF))


out = (b'\x89PNG\r\n\x1a\n'
       + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, bit_depth, color_type, 0, 0, 0))
       + chunk(b'IDAT', zlib.compress(bytes(raw))) + chunk(b'IEND', b''))
open(path, 'wb').write(out)
PY
set +e
(cd "$BASE_FIXTURE" && python3 scripts/check-asset-baseline.py > "$TMP/base-1-pixel.txt" 2>&1)
base_pixel_rc=$?
set -e
check "① 改一个像素 → RED" 1 "$base_pixel_rc"
# 红的原因必须可归因：报出被改的那张图，并同时给出期望与实际指纹
if grep -q 'originalrpg/textures/item/hunt_crystal.png' "$TMP/base-1-pixel.txt" \
   && grep -q '期望 ' "$TMP/base-1-pixel.txt" && grep -q '实际 ' "$TMP/base-1-pixel.txt"; then
  echo "  [OK  ] ① 指出是哪张图并打印 期望/实际 指纹"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] ① RED 了但没说清是哪张图（门禁不可归因）" >&2
  FAIL=$((FAIL + 1))
fi
# 还原：从**真实仓库**取回原始字节（夹具里的已被改坏）
cp "$ROOT/resource-pack/assets/originalrpg/textures/item/hunt_crystal.png" \
  "$BASE_FIXTURE/resource-pack/assets/originalrpg/textures/item/hunt_crystal.png"
check "① 还原后必须绿" 0 "$(run_baseline_gate)"

# ② 删一张图：必须报「丢失」
mv "$BASE_FIXTURE/resource-pack/assets/originalrpg/textures/item/hunt_crystal.png" \
  "$TMP/base-deleted.png"
set +e
(cd "$BASE_FIXTURE" && python3 scripts/check-asset-baseline.py > "$TMP/base-missing.txt" 2>&1)
base_missing_rc=$?
set -e
check "② 删一张图 → RED" 1 "$base_missing_rc"
if grep -q '丢失' "$TMP/base-missing.txt" \
   && grep -q 'originalrpg/textures/item/hunt_crystal.png' "$TMP/base-missing.txt"; then
  echo "  [OK  ] ② 报出「丢失」并指出是哪张"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] ② 没有报出「丢失」" >&2
  FAIL=$((FAIL + 1))
fi
mv "$TMP/base-deleted.png" \
  "$BASE_FIXTURE/resource-pack/assets/originalrpg/textures/item/hunt_crystal.png"
check "② 还原后必须绿" 0 "$(run_baseline_gate)"

# ③ 加一张未登记的图：必须报「未登记」
cp "$BASE_FIXTURE/resource-pack/assets/originalrpg/textures/item/hunt_crystal.png" \
  "$BASE_FIXTURE/resource-pack/assets/originalrpg/textures/item/unregistered_probe.png"
set +e
(cd "$BASE_FIXTURE" && python3 scripts/check-asset-baseline.py > "$TMP/base-unregistered.txt" 2>&1)
base_unregistered_rc=$?
set -e
check "③ 加一张未登记图 → RED" 1 "$base_unregistered_rc"
if grep -q '未登记' "$TMP/base-unregistered.txt" \
   && grep -q 'unregistered_probe.png' "$TMP/base-unregistered.txt"; then
  echo "  [OK  ] ③ 报出「未登记」并指出是哪张"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] ③ 没有报出「未登记」" >&2
  FAIL=$((FAIL + 1))
fi
rm -f "$BASE_FIXTURE/resource-pack/assets/originalrpg/textures/item/unregistered_probe.png"
check "③ 还原后必须绿" 0 "$(run_baseline_gate)"

# ④ 清单文件缺失：必须 exit 2（不是 1，也不许当绿）
mv "$BASE_FIXTURE/resource-pack/assets-manifest.json" "$TMP/base-manifest.json"
check "④ 清单缺失 → exit 2" 2 "$(run_baseline_gate)"
mv "$TMP/base-manifest.json" "$BASE_FIXTURE/resource-pack/assets-manifest.json"
check "④ 还原后必须绿" 0 "$(run_baseline_gate)"

# 自证：负向对照没有污染真实仓库（§9 全程只动 $TMP 里的夹具）
set +e
(cd "$ROOT" && python3 scripts/check-asset-baseline.py >/dev/null 2>&1)
base_after=$?
set -e
check "负向对照后仓库内基线仍为绿（工作区未被污染）" 0 "$base_after"

# 自证：清单与磁盘素材「重跑 gen 后字节一致」——否则基线会在无关提交里漂移
set +e
(cd "$ROOT" && python3 scripts/gen-asset-manifest.py --check >/dev/null 2>&1)
gen_rc=$?
set -e
check "重跑 gen-asset-manifest.py 后清单字节一致（稳定输出）" 0 "$gen_rc"

echo "== 9b. W12-A1 接线自证：基线门禁必须真的在 CI 里跑 =="
# 与 §8 同一套判据：把「谁在跑」变成可 grep 的事实。
# 只断言脚本名出现在某处不够 —— 它也可能只出现在注释里，因此断言完整命令行。
if [ -f "$ROOT/.cnb.yml" ] && grep -qF 'python3 scripts/check-asset-baseline.py' "$ROOT/.cnb.yml"; then
  echo "  [OK  ] .cnb.yml 全量质量门执行 check-asset-baseline.py"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] .cnb.yml 没有执行 check-asset-baseline.py —— 基线门禁不在 CI 里" >&2
  FAIL=$((FAIL + 1))
fi

# 负向对照：拼出来的假路径必须查不到（证明上面那条判据能变红，不是恒命中）
base_phantom="scripts/definitely-not-a-real""-baseline-gate.py"
set +e
base_phantom_hits=$(grep -c -F "$base_phantom" "$ROOT/.cnb.yml")
set -e
if [ "$base_phantom_hits" = "0" ]; then
  echo "  [OK  ] 不存在的基线门禁查不到（接线判据能变红）"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] 不存在的基线门禁竟被查到 $base_phantom_hits 次 —— 接线判据恒绿" >&2
  FAIL=$((FAIL + 1))
fi

cd "$ROOT"

echo ""
echo "===== 门禁自测汇总：PASS=$PASS FAIL=$FAIL ====="
if [ "$FAIL" -ne 0 ]; then
  echo "GATE_SELFTEST=RED"
  exit 1
fi
echo "GATE_SELFTEST=GREEN"
