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
#   4b) scripts/check-source-contract.sh 在不可能满足的顺序契约上必须红、真实顺序必须绿；
#        W16b-A1 还证明规格目录（多份 *.json）会被真的执行、键冲突会点名两个文件、
#        SOURCE_CONTRACT_SPEC 单文件模式保持向后兼容
#   4d) W16-A1：scripts/check-source-string-tests.sh 的判据必须能真的变红（G-302-RESIDUAL 结项门禁）
#       W18-A1：补强判据从「同一方法体内直接出现」扩到间接形态（readString 藏 helper、
#       路径来自常量、indexOf 先后比较），并证明读 src/test/resources 的夹具不会误报
#       W18-A1 第二轮：再收紧两处过捕（lambda/比较里**提及**的常量不算读取路径；
#       读取结果做**过滤谓词**不算断言），新增夹具④豁免对照，并让「真实仓库 → 0」真的变绿
#       W19-A1：补夹具⑤⑥，锁住 W18 收紧过捕时引入的反向失效（assert 块包住读取调用 /
#       Files.readString(Path.of(常量)) 曾被漏判为 GREEN）
#   4c) scripts/install-datapacks.sh --check 在真实数据包上必须绿，函数调用未注册命令后必须红
#   5) scripts/check-repo-artifacts.sh 在真实文档上必须绿，且把数据包目录藏起来后必须红
#   5b) resource-pack 的 HUD 尺寸门禁（W7-A4）必须能真的变红，且 build.sh 常量与唯一来源一致
#   6) G-206 接线自证：§4c/§5 两段门禁必须被本脚本真实调用、且被 .cnb.yml 执行
#   7) scripts/check-resource-pack.sh（W7-A1 素材门禁）的八条判据都能真的变红
#   8) W7-A1 接线自证：素材门禁必须真的在 CI 里跑
#   9) W12-A1 素材逐像素基线门禁的四组负向对照（改 1 像素 / 删图 / 加未登记图 / 还原）
#  10) W13-A1 素材可复现性门禁的负向对照（改生成器常量 / 人工覆盖被盖回去 / 登记被改）
#  11) W21-A2 两条交付物级门禁：
#      a. SINGLE_SOURCE_OF_TRUTH_GATE —— 唯一真相源（state/world-state.json 必须存在、
#         plan/world-state.json 必须不存在；曾因派单白名单与协议声明不一致而被同一 NPC
#         在同一 commit 写成两份不同内容，见 G-DUP-WORLDSTATE）
#      b. VERDICT_MATRIX_CONSISTENCY_GATE —— 矩阵的机器可读结论行必须存在、且与
#         同版 verdict-vN.json 的 done/remaining 严格一致、basis 必须出现在矩阵头（见 G-VERDICT-MATRIX-SPLIT）
#  12) W28-A1 玩法规格↔代码 绑定门禁（scripts/check-mechanics-spec.sh）：
#      正夹具真实仓库必须 GREEN；负夹具证明三类载体（config/behavior/java-const）
#      改坏任一类都会 RED，还原后 GREEN —— 见 docs/GAME_DESIGN.md 的 3 次实测漂移
#      （G-106 血契转数 / G-202 槽上限公式 / G-209 预警窗口）
#      W28-A2 补：⑤ 改坏一条**后 8 节**新登记的 config 条目也必须 RED
#      （锁住「登记了但没进判据」）；⑥ 报告节与 spec 不一致时
#      gen-mechanics-spec-report.py --check 必须报 STALE（锁住「报告由脚本生成」）
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

# ---- W16b-A1：规格目录合并（多份 *.json）的负向对照 ----
# 动机：后续每个模块各加一份规格文件（rpg-items.json / rpg-dungeons.json / ...），
# 不再抢改同一个 all.json。这里证明「新加的规格文件是真的被执行」，不是只被读到：
#   a) 规格目录里放一个**注定失败**的夹具 ⇒ 该规格的违例必须真出现在输出里 ⇒ RED
#   b) 两个文件写同一个 spec 键 ⇒ RED，且错误信息点名两个文件（不许静默覆盖）
#   c) 无冲突的合并必须绿 ⇒ 上面那条冲突断言不是恒红（负向对照）
#   d) SOURCE_CONTRACT_SPEC 指单文件时只加载该文件（向后兼容，不被目录合并污染）
# 夹具改到哪个目录走 SOURCE_CONTRACT_SPECS_DIR（只给自测用，CI 不设置 ⇒ CI 永远
# 加载 scripts/source-contract-specs 全目录）。
SPECS_TMP="$TMP/specs-negative"
mkdir -p "$SPECS_TMP"

run_specs() {
  # $1 = 输出文件；返回退出码
  set +e
  SOURCE_CONTRACT_SPECS_DIR="$SPECS_TMP" sh scripts/check-source-contract.sh --all \
    >"$1" 2>&1
  echo $?
}

cat > "$SPECS_TMP/10-first.json" <<JSON
{
 "fake/First.java": {
  "files": ["$frag/Target.java"],
  "checks": [
   {"method": "selftest", "required": ["never-in-this-file"], "forbidden": [], "ordered": []}
  ]
 }
}
JSON

first_rc=$(run_specs "$TMP/specs-first.txt")
check "规格目录里新加的规格会被执行（夹具必然失败 → RED）" 1 "$first_rc"
# 判据不是「文件被读到」（读到一个空目录也会绿），而是「它声明的检查真的跑了」：
# 违例标签必须来自该规格的 method + 类名，且必须打出它那条 required 片段。
if grep -q 'First.java::selftest' "$TMP/specs-first.txt" \
  && grep -q 'never-in-this-file' "$TMP/specs-first.txt" \
  && grep -q 'SOURCE_CONTRACT_GATE=RED' "$TMP/specs-first.txt"; then
  echo "  [OK  ] 报出的违例来自新加的规格文件（证明它真的被执行，不只是被读到）"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] RED 了但没报出新规格的违例 —— 新文件可能根本没被加载" >&2
  sed 's/^/    /' "$TMP/specs-first.txt" >&2
  FAIL=$((FAIL + 1))
fi

# 冲突：第二个文件重复 10-first.json 的键，同时自己带一条会失败的检查。
# 若走「后者覆盖前者」的旧语义，输出里会只剩 20-second 的违例与 GREEN/RED 之外的
# 东西 —— 断言「两个文件名都出现在错误信息里」正是为了掐死这条路径。
cat > "$SPECS_TMP/20-second.json" <<JSON
{
 "fake/First.java": {
  "files": ["$frag/Target.java"],
  "checks": [
   {"method": "selftest", "required": ["never-in-this-file-either"], "forbidden": [], "ordered": []}
  ]
 },
 "fake/Second.java": {
  "files": [],
  "checks": [{"method": "selftest", "required": [], "forbidden": [], "ordered": []}]
 }
}
JSON

conflict_rc=$(run_specs "$TMP/specs-conflict.txt")
check "键冲突 → 报错（不是静默覆盖）" 2 "$conflict_rc"
if grep -q '规格键冲突' "$TMP/specs-conflict.txt" \
  && grep -q '10-first.json' "$TMP/specs-conflict.txt" \
  && grep -q '20-second.json' "$TMP/specs-conflict.txt"; then
  echo "  [OK  ] 冲突信息点名了两个文件（10-first.json 与 20-second.json）"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] 冲突信息没说清是哪两个文件" >&2
  FAIL=$((FAIL + 1))
fi
if grep -q 'SOURCE_CONTRACT_GATE=RED' "$TMP/specs-conflict.txt"; then
  echo "  [OK  ] 冲突时判据行是 SOURCE_CONTRACT_GATE=RED"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] 冲突时没有给出 SOURCE_CONTRACT_GATE=RED" >&2
  FAIL=$((FAIL + 1))
fi

# 负向对照：没有冲突时，同一个目录必须绿 —— 否则上面那条冲突断言可能只是恒红
rm -f "$SPECS_TMP/20-second.json"
cat > "$SPECS_TMP/10-first.json" <<JSON
{
 "fake/First.java": {
  "files": ["$frag/Target.java"],
  "checks": [
   {"method": "selftest", "required": ["void a()"], "forbidden": [], "ordered": []}
  ]
 }
}
JSON
cat > "$SPECS_TMP/20-second.json" <<JSON
{
 "fake/Second.java": {
  "files": ["$frag/Target.java"],
  "checks": [
   {"method": "selftest", "required": ["void b()"], "forbidden": [], "ordered": []}
  ]
 }
}
JSON

merged_rc=$(run_specs "$TMP/specs-merged.txt")
check "两份不冲突的规格合并后 → 0" 0 "$merged_rc"
if grep -q 'SOURCE_CONTRACT_CHECKS=2 SPECS=2' "$TMP/specs-merged.txt"; then
  echo "  [OK  ] 两份规格都被执行（CHECKS=2 SPECS=2）"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] 合并后的计数不对，应有 2 条检查 / 2 个规格" >&2
  sed 's/^/    /' "$TMP/specs-merged.txt" >&2
  FAIL=$((FAIL + 1))
fi

# 向后兼容：SOURCE_CONTRACT_SPEC 指单文件时只加载该文件（selftest 夹具与
# *ContractTest 的单键用法都靠它）
cat > "$TMP/specs-single.json" <<JSON
{
 "fake/First.java": {
  "files": ["$frag/Target.java"],
  "checks": [
   {"method": "selftest", "required": ["void a()"], "forbidden": [], "ordered": []}
  ]
 }
}
JSON
set +e
SOURCE_CONTRACT_SPEC="$TMP/specs-single.json" \
  SOURCE_CONTRACT_SPECS_DIR="$SPECS_TMP" \
  sh scripts/check-source-contract.sh --all >"$TMP/specs-single.txt" 2>&1
single_rc=$?
set -e
check "SOURCE_CONTRACT_SPEC 指定单文件仍然生效 → 0" 0 "$single_rc"
if grep -q 'SOURCE_CONTRACT_CHECKS=1 SPECS=1' "$TMP/specs-single.txt"; then
  echo "  [OK  ] 单文件模式下只加载该文件（SPECS=1，没有合并目录里那两份）"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] 单文件模式被目录合并污染了" >&2
  sed 's/^/    /' "$TMP/specs-single.txt" >&2
  FAIL=$((FAIL + 1))
fi
rm -rf "$SPECS_TMP"

# 自证：负向对照没有污染真实规格目录（全程只动 $TMP 里的夹具）
set +e
sh scripts/check-source-contract.sh --all >"$TMP/specs-real.txt" 2>&1
real_rc=$?
set -e
check "负向对照后真实规格目录仍为绿（工作区未被污染）" 0 "$real_rc"
# 判据不是「检查数等于某个常数」—— 每个模块都会往规格里加条目（W16b-A1 的目录合并
# 就是为这件事服务的），写死常数只会让后来者被迫改这个数字，反而稀释了这条自证的意义。
# 这里断言的是「真实规格目录真的被加载、且规模没有缩水」：条数与文件数都必须 ≥ 已知基线，
# 且必须出现 GREEN 判据行。缩水（有人把规格删了）会立刻变红。
real_checks=$(sed -n 's/.*SOURCE_CONTRACT_CHECKS=\([0-9]\+\) SPECS=\([0-9]\+\).*/\1 \2/p' \
  "$TMP/specs-real.txt")
real_spec_checks=${real_checks%% *}
real_spec_files=${real_checks##* }
if [ -n "$real_checks" ] \
  && [ "$real_spec_checks" -ge 466 ] && [ "$real_spec_files" -ge 2 ] \
  && grep -q 'SOURCE_CONTRACT_GATE=GREEN' "$TMP/specs-real.txt"; then
  echo "  [OK  ] 真实规格目录已加载且未缩水（CHECKS=$real_spec_checks≥466 SPECS=$real_spec_files≥2 GREEN）"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] 真实规格目录缩水或加载失败（CHECKS=$real_spec_checks SPECS=$real_spec_files）" >&2
  sed 's/^/    /' "$TMP/specs-real.txt" >&2
  FAIL=$((FAIL + 1))
fi

cd "$ROOT"

echo "== 4d. G-302-RESIDUAL 结项门禁：本仓生产源码里不得再有源码字符串断言 =="
# 判据：sh scripts/check-source-string-tests.sh
#   GREEN = 没有任何 *Test 方法体同时「读 plugins|tools 下的生产文件」+「contains/indexOf 断言」。
#   它最危险的失效是「永远绿」：判据一旦被写错（例如路径前缀过滤失效），
#   这批伪测试会静默回来。这里用最小夹具证明它真的会红。
SST_FIXTURE="$TMP/source-string-fixture"
mkdir -p "$SST_FIXTURE/plugins/fake/src/test/java" "$SST_FIXTURE/plugins/fake/src/main/java" \
    "$SST_FIXTURE/scripts"
# 脚本从自身位置解析 ROOT 并 cd 过去，因此夹具根必须有 settings.gradle.kts 才不会被
# 解析到真实仓库（否则负向对照会「看起来通过」，实际跑的是真实仓）。
: > "$SST_FIXTURE/settings.gradle.kts"
cat > "$SST_FIXTURE/plugins/fake/src/main/java/Target.java" <<'JAVA'
class Target { void a() {} }
JAVA
cat > "$SST_FIXTURE/plugins/fake/src/test/java/FakeTest.java" <<'JAVA'
class FakeTest {
    @Test
    void fake() throws Exception {
        String s = java.nio.file.Files.readString(java.nio.file.Path.of("plugins/fake/src/main/java/Target.java"));
        org.junit.jupiter.api.Assertions.assertTrue(s.contains("void a()"));
    }
}
JAVA
cp scripts/check-source-string-tests.sh "$SST_FIXTURE/scripts/"
set +e
( cd "$SST_FIXTURE" && sh scripts/check-source-string-tests.sh >/dev/null 2>&1 )
fixture_rc=$?
set -e
check "夹具①内联：存在「读生产源码 + contains」的测试 → 1" 1 "$fixture_rc"

# ---- W18-A1：间接形态必须同样被抓到（否则同类还会再漏）----
# 形态②：readString 藏在 helper（read(...)）里、路径来自类常量、断言用 indexOf 做先后比较。
# 这正是 W17 实测漏掉 DungeonTimeoutLifecycleContractTest 的形状。
cat > "$SST_FIXTURE/plugins/fake/src/test/java/IndirectTest.java" <<'JAVA'
class IndirectTest {
    private static final String PRODUCTION =
            "plugins/fake/src/main/java/Target.java";

    @Test
    void ordering() throws Exception {
        String body = read(PRODUCTION);
        org.junit.jupiter.api.Assertions.assertTrue(
                body.indexOf("void a()") >= 0 && body.indexOf("void b()") < body.indexOf("void a()"));
    }

    private static String read(String relative) throws Exception {
        return java.nio.file.Files.readString(java.nio.file.Path.of(relative));
    }
}
JAVA
set +e
( cd "$SST_FIXTURE" && sh scripts/check-source-string-tests.sh >/dev/null 2>&1 )
indirect_rc=$?
set -e
check "夹具②间接：readString 藏 helper + 常量路径 + indexOf 先后比较 → 1" 1 "$indirect_rc"

# 形态③豁免对照：读 src/test/resources 夹具不算缺口（否则门禁会误报，没人再信它）。
rm -f "$SST_FIXTURE/plugins/fake/src/test/java/FakeTest.java" \
      "$SST_FIXTURE/plugins/fake/src/test/java/IndirectTest.java"
mkdir -p "$SST_FIXTURE/plugins/fake/src/test/resources"
printf 'expected\n' > "$SST_FIXTURE/plugins/fake/src/test/resources/fixture.txt"
cat > "$SST_FIXTURE/plugins/fake/src/test/java/FixtureTest.java" <<'JAVA'
class FixtureTest {
    @Test
    void fixture() throws Exception {
        String data = java.nio.file.Files.readString(java.nio.file.Path.of(
                "plugins/fake/src/test/resources/fixture.txt"));
        org.junit.jupiter.api.Assertions.assertTrue(data.contains("expected"));
    }
}
JAVA
set +e
( cd "$SST_FIXTURE" && sh scripts/check-source-string-tests.sh >/dev/null 2>&1 )
fixture_ok_rc=$?
set -e
check "夹具③豁免：读 src/test/resources 夹具 → 0（证明不误报）" 0 "$fixture_ok_rc"

# ---- W19-A1：遍历式源码扫描（walk src/main + readString + contains 聚合成文件列表）→ 必须 RED ----
# 这正是 rpg-items/EquipmentExitPathReachabilityTest 与
# rpg-core/SeasonAttunementSettlementWiringTest 的 productionReferences(identifier) 原形态：
#   Files.walk(<根指向 plugins/.../src/main>) → 逐个 readString → .contains(identifier)
#   → 把「哪些文件提到了某标识符」收集成列表 → 对列表 anyMatch 断言。
# 断言对象看着是文件列表，但每个元素的真伪都取决于**生产源码正文里含不含那个词**
# （改注释就会变红），因此它就是源码字符串断言。W18 第二轮曾以「解析后的集合」为由
# 整体豁免它（注释里点名了两个类），导致「把类改回原形态必须变红」这条负向对照失效。
# W19 撤掉该豁免：此夹具必须 → 1。
rm -f "$SST_FIXTURE/plugins/fake/src/test/java/FixtureTest.java"
cat > "$SST_FIXTURE/plugins/fake/src/test/java/PredicateTest.java" <<'JAVA'
class PredicateTest {
    @Test
    void listed() throws Exception {
        java.util.List<String> hits = new java.util.ArrayList<>();
        try (var files = java.nio.file.Files.walk(
                java.nio.file.Path.of("plugins/fake/src/main/java"))) {
            for (java.nio.file.Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (java.nio.file.Files.readString(file).contains("void a()")) {
                    hits.add(file.getFileName().toString());
                }
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(hits.stream().anyMatch(p -> p.startsWith("Target")));
    }
}
JAVA
set +e
( cd "$SST_FIXTURE" && sh scripts/check-source-string-tests.sh >/dev/null 2>&1 )
predicate_rc=$?
set -e
check "夹具④遍历式源码扫描（walk src/main + readString + contains 聚合成列表）→ 1" 1 "$predicate_rc"
rm -f "$SST_FIXTURE/plugins/fake/src/test/java/PredicateTest.java"

# 同族的**真豁免**对照（避免上述收紧把正当用法误报）：遍历目录后只**解析**文件
# （SnakeYAML，不 readString、不对正文 contains）⇒ 0。
# 对应真实仓 LanguageBundleContractTest#projectYamlFilesParseWithoutDuplicateKeys。
cat > "$SST_FIXTURE/plugins/fake/src/test/java/YamlParseTest.java" <<'JAVA'
class YamlParseTest {
    @Test
    void strict() throws Exception {
        try (var files = java.nio.file.Files.walk(java.nio.file.Path.of("plugins/fake"))) {
            for (java.nio.file.Path file : files.filter(p -> p.toString().endsWith(".yml")).toList()) {
                try (var in = java.nio.file.Files.newInputStream(file)) {
                    new org.yaml.snakeyaml.Yaml().load(in);
                }
            }
        }
    }
}
JAVA
set +e
( cd "$SST_FIXTURE" && sh scripts/check-source-string-tests.sh >/dev/null 2>&1 )
yaml_rc=$?
set -e
check "夹具④b豁免：遍历目录 + 真解析 YAML（不 readString/不 contains）→ 0" 0 "$yaml_rc"
rm -f "$SST_FIXTURE/plugins/fake/src/test/java/YamlParseTest.java"

# ---- W19-A1：收紧过捕时**不得把真实断言一起放过**（过捕收紧的反向失效）----
# W18 第二轮为了不误报过滤谓词，把一行式判据收成「assert 文本紧邻读取调用」。
# 该口径过窄：`assertTrue(Files.readString(...).contains(x))` 里 assert 与读取之间
# 隔着实参，于是**最直白的那种源码字符串断言**反被判 GREEN（实测漏判）。
# 这两组夹具把这个反向失效锁住：断言块包住读取调用 → 必须 RED。
cat > "$SST_FIXTURE/plugins/fake/src/test/java/WrappedAssertTest.java" <<'JAVA'
class WrappedAssertTest {
    @Test
    void wrapped() throws Exception {
        org.junit.jupiter.api.Assertions.assertTrue(java.nio.file.Files.readString(
                java.nio.file.Path.of("plugins/fake/src/main/java/Target.java")).contains("void a()"));
    }
}
JAVA
set +e
( cd "$SST_FIXTURE" && sh scripts/check-source-string-tests.sh >/dev/null 2>&1 )
wrapped_rc=$?
set -e
check "夹具⑤：assert 块包住读取调用（assertTrue(readString(...).contains(x))）→ 1" 1 "$wrapped_rc"

# 同族的第二形态：`Files.readString(Path.of(常量))` —— 路径来自常量且被 Path.of 包了一层。
# W18 只认「实参以常量名开头」，`Path.of(TARGET)` 因此解析不出生产路径 → 漏判。
cat > "$SST_FIXTURE/plugins/fake/src/test/java/ConstPathTest.java" <<'JAVA'
class ConstPathTest {
    private static final String TARGET = "plugins/fake/src/main/java/Target.java";

    @Test
    void constantPath() throws Exception {
        String body = java.nio.file.Files.readString(java.nio.file.Path.of(TARGET));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("void a()"));
    }
}
JAVA
rm -f "$SST_FIXTURE/plugins/fake/src/test/java/WrappedAssertTest.java"
set +e
( cd "$SST_FIXTURE" && sh scripts/check-source-string-tests.sh >/dev/null 2>&1 )
constpath_rc=$?
set -e
check "夹具⑥：Files.readString(Path.of(常量)) + contains → 1" 1 "$constpath_rc"
rm -f "$SST_FIXTURE/plugins/fake/src/test/java/ConstPathTest.java"

set +e
( cd "$ROOT" && sh scripts/check-source-string-tests.sh >/dev/null 2>&1 )
real_rc=$?
set -e
check "真实仓库已无此类断言 → 0" 0 "$real_rc"

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

echo "== 10. 素材可复现性门禁（W13-A1）三组负向对照 =="
# 失败模式：基线门禁判「磁盘像素 == 清单像素」，挡不住「注册表和生成器一起漂走」——
# 手改 PNG + 重跑 gen-asset-manifest.py，两道门禁全绿，纹理却再也造不出来。
# 本节证明可复现性门禁真的会红：改生成器常量 → RED；把 human-override 换成
# 生成器输出 → RED（反向断言）；human-override 的登记被改掉 → RED；还原 → GREEN。
#
# 与 §7/§9 同样的纪律：**只在 $TMP 的夹具副本上破坏**，不碰仓库工作区。
# 夹具必须整份拷 resource-pack/（生成器就在 tools/ 里），并且要能自取 JDK ——
# 本机没有 JDK 时把下载做一次到 $TMP，之后各次对照复用。
REPRO_FIXTURE="$TMP/asset-repro-ws"
mkdir -p "$REPRO_FIXTURE/scripts"
cp -R "$ROOT/resource-pack" "$REPRO_FIXTURE/resource-pack"
cp "$ROOT/scripts/check-asset-reproducible.sh" "$ROOT/scripts/check-asset-reproducible.py" \
  "$ROOT/scripts/asset_fingerprint.py" "$REPRO_FIXTURE/scripts/"

# 自取一次 JDK 给全部对照复用（装不上就明确报红，不静默跳过）
REPRO_JDK_HOME="$TMP/asset-repro-jdk"
mkdir -p "$REPRO_JDK_HOME"
repro_jdk=""
if [ -n "${ASSET_REPRO_JDK:-}" ] && [ -x "${ASSET_REPRO_JDK}/bin/javac" ]; then
  repro_jdk="$ASSET_REPRO_JDK"
elif [ -x /opt/jdk25/bin/javac ]; then
  repro_jdk="/opt/jdk25"
elif command -v javac >/dev/null 2>&1; then
  repro_jdk=$(dirname "$(dirname "$(command -v javac)")")
elif command -v curl >/dev/null 2>&1; then
  repro_archive=OpenJDK25U-jdk_x64_linux_hotspot_25.0.4.1_1.tar.gz
  repro_mirror=https://mirrors.tuna.tsinghua.edu.cn/Adoptium/25/jdk/x64/linux
  if curl -sSL -o "$TMP/jdk.tar.gz" "$repro_mirror/$repro_archive" 2>/dev/null; then
    tar xzf "$TMP/jdk.tar.gz" -C "$REPRO_JDK_HOME" --strip-components=1 2>/dev/null || true
    [ -x "$REPRO_JDK_HOME/bin/javac" ] && repro_jdk="$REPRO_JDK_HOME"
  fi
fi

run_repro_gate() {
  # 退出码落到调用方给的文件里（不走管道取码：`| tail; echo $?` 拿到的是 tail 的 0）
  set +e
  (cd "$REPRO_FIXTURE" && ASSET_REPRO_JDK="$repro_jdk" \
    sh scripts/check-asset-reproducible.sh > "$1" 2>&1)
  echo $?
}

if [ -z "$repro_jdk" ]; then
  echo "  [FAIL] 本机既无 JDK 也取不到 Temurin 25 —— 可复现性门禁的负向对照无法执行" >&2
  echo "         （CI 侧由 eclipse-temurin:25-jdk 提供，见 .cnb.yml 的 W13-A1 stage）" >&2
  FAIL=$((FAIL + 1))
else
  echo "  [INFO] 负向对照用 JDK: $repro_jdk"
  check "可复现性门禁在干净夹具上必须绿（基准）" 0 "$(run_repro_gate "$TMP/repro-base.txt")"

  # ① 改一个生成器常量（STEEL 的蓝通道 +1）：用到 STEEL 的纹理全部失配
  repro_src="$REPRO_FIXTURE/resource-pack/tools/GenerateTextures.java"
  cp "$repro_src" "$TMP/repro-GT.java"
  sed -i 's/private static final int STEEL = 0xffd8e4e8;/private static final int STEEL = 0xffd8e4e9;/' "$repro_src"
  check "① 改生成器常量（STEEL +1）→ RED" 1 "$(run_repro_gate "$TMP/repro-a.txt")"
  # 红的必须可归因：判据行是 RED，且报出具体是哪张不一致
  if grep -q 'ASSET_REPRO_GATE=RED' "$TMP/repro-a.txt" \
     && grep -q '生成器输出与仓库不一致' "$TMP/repro-a.txt" \
     && grep -q 'MISMATCH=' "$TMP/repro-a.txt"; then
    echo "  [OK  ] ① 判据行 RED、指出「生成器输出与仓库不一致」并给出 MISMATCH 计数"
    PASS=$((PASS + 1))
  else
    echo "  [FAIL] ① RED 了但不可归因（没有指向具体文件 / 没有判据行）" >&2
    FAIL=$((FAIL + 1))
  fi
  cp "$TMP/repro-GT.java" "$repro_src"
  check "① 还原后必须绿" 0 "$(run_repro_gate "$TMP/repro-a2.txt")"

  # ② 反向断言：把 ancient_echo_skill_book 换成生成器输出 —— 必须 RED
  # 前提是夹具里的生成器真的能编译并跑出那张图（否则对照是空转）
  repro_out="$TMP/repro-generated"
  mkdir -p "$repro_out"
  set +e
  "$repro_jdk/bin/javac" -d "$TMP/repro-classes" "$repro_src" > "$TMP/repro-javac.txt" 2>&1
  javac_rc=$?
  "$repro_jdk/bin/java" -cp "$TMP/repro-classes" GenerateTextures "$repro_out" --force \
    > "$TMP/repro-gen.txt" 2>&1
  gen_rc=$?
  set -e
  check "② 夹具的生成器可编译（反向断言的前提）" 0 "$javac_rc"
  check "② 夹具的生成器可运行" 0 "$gen_rc"
  if [ -f "$repro_out/ancient_echo_skill_book.png" ]; then
    echo "  [OK  ] ② 生成器确实产出了 ancient_echo_skill_book.png（反向断言有对照物）"
    PASS=$((PASS + 1))
  else
    echo "  [FAIL] ② 生成器没产出 ancient_echo_skill_book.png —— 反向断言会空转" >&2
    FAIL=$((FAIL + 1))
  fi
  repro_override="$REPRO_FIXTURE/resource-pack/assets/originalrpg/textures/item/ancient_echo_skill_book.png"
  cp "$repro_override" "$TMP/repro-aesb.png"
  cp "$repro_out/ancient_echo_skill_book.png" "$repro_override"
  check "② 把 ancient_echo_skill_book 换成生成器输出 → RED（反向断言）" 1 \
    "$(run_repro_gate "$TMP/repro-b.txt")"
  if grep -q '人工覆盖竟与生成器输出一致' "$TMP/repro-b.txt"; then
    echo "  [OK  ] ② 报出「人工覆盖竟与生成器输出一致」——反向断言确实在跑"
    PASS=$((PASS + 1))
  else
    echo "  [FAIL] ② RED 了但没报出反向断言的那句话" >&2
    FAIL=$((FAIL + 1))
  fi
  cp "$TMP/repro-aesb.png" "$repro_override"
  check "② 还原后必须绿" 0 "$(run_repro_gate "$TMP/repro-b2.txt")"

  # ③ human-override 的登记自证：把 category 改掉必须 RED
  # （否则「我这张不用管」会变成一个可以随便贴的豁免标签）
  repro_prov="$REPRO_FIXTURE/resource-pack/asset-provenance.json"
  cp "$repro_prov" "$TMP/repro-prov.json"
  python3 - "$repro_prov" <<'PYREPROV'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
data = json.loads(path.read_text(encoding='utf-8'))
key = 'assets/originalrpg/textures/item/ancient_echo_skill_book.png'
data['assets'][key]['category'] = 'generator'
path.write_text(json.dumps(data, ensure_ascii=False, indent=1) + '\n', encoding='utf-8')
PYREPROV
  check "③ human-override 被改登记为 generator → RED" 1 "$(run_repro_gate "$TMP/repro-c.txt")"
  if grep -q '应为 human-override' "$TMP/repro-c.txt"; then
    echo "  [OK  ] ③ 报出 category 与 human-override 不符"
    PASS=$((PASS + 1))
  else
    echo "  [FAIL] ③ RED 了但没报出 category 不符" >&2
    FAIL=$((FAIL + 1))
  fi
  cp "$TMP/repro-prov.json" "$repro_prov"
  check "③ 还原后必须绿" 0 "$(run_repro_gate "$TMP/repro-c2.txt")"

  # 自证：负向对照没有污染真实仓库（§10 全程只动 $TMP 里的夹具）
  set +e
  (cd "$ROOT" && ASSET_REPRO_JDK="$repro_jdk" sh scripts/check-asset-reproducible.sh \
    >/dev/null 2>&1)
  repro_after=$?
  set -e
  check "负向对照后仓库内可复现性仍为绿（工作区未被污染）" 0 "$repro_after"
fi

echo "== 10b. W13-A1 接线自证：可复现性门禁必须真的在 CI 里跑 =="
# 与 §8/§9b 同一套判据：把「谁在跑」变成可 grep 的事实，且断言完整命令行
# （只断言脚本名会命中注释）。
if [ -f "$ROOT/.cnb.yml" ] && grep -qF 'sh scripts/check-asset-reproducible.sh' "$ROOT/.cnb.yml"; then
  echo "  [OK  ] .cnb.yml 全量质量门执行 check-asset-reproducible.sh"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] .cnb.yml 没有执行 check-asset-reproducible.sh —— 可复现性门禁不在 CI 里" >&2
  FAIL=$((FAIL + 1))
fi

# 负向对照：拼出来的假路径必须查不到（证明上面那条判据能变红，不是恒命中）
repro_phantom="scripts/definitely-not-a-real""-repro-gate.sh"
set +e
repro_phantom_hits=$(grep -c -F "$repro_phantom" "$ROOT/.cnb.yml")
set -e
if [ "$repro_phantom_hits" = "0" ]; then
  echo "  [OK  ] 不存在的可复现性门禁查不到（接线判据能变红）"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] 不存在的可复现性门禁竟被查到 $repro_phantom_hits 次 —— 接线判据恒绿" >&2
  FAIL=$((FAIL + 1))
fi

cd "$ROOT"

echo ""

echo "== 11. W21-A2 交付物级门禁：账本唯一真相源 + verdict↔矩阵一致 =="
# 两条判据都在本段内实现（不新增脚本文件）：这样「判据存在」与「判据被跑」是同一条事实。
#
# 判据 1（G-DUP-WORLDSTATE）：同一 commit 6500a21 里 state/world-state.json 与 plan/world-state.json
#   被同一个 NPC 写成不同内容（7 个字段不同：assets/gates/status/next_action/
#   open_questions_for_human/wave_plan/evidence；40076B vs 35064B），两个 agent 分别读两份
#   会得出不同结论 ⇒ 交接协议失效。根因是派单 allowed_paths 与协议声明的账本路径不一致
#   （.cnb/agent-out/W10-A1-final-verdict-v5.json 的 next_steps 第 5 条原文）。
# 判据 2（G-VERDICT-MATRIX-SPLIT）：gap-matrix-v8.md §0 写「清零/done=true/remaining=0」，
#   同轮 verdict-v8.json 写 done=false/remaining=1 ⇒ 交付物自相矛盾。

sst_gate() {
  # $1 = 仓库根。只用 POSIX sh 与 grep/sed，不用数组、不用进程替换。
  root=$1
  red=0
  total=0
  if [ -f "$root/state/world-state.json" ]; then
    total=$((total + 1))
  else
    echo "  缺少 state/world-state.json（唯一真相源不存在）" >&2
    red=1
  fi
  if [ -f "$root/plan/world-state.json" ]; then
    total=$((total + 1))
    b_state=$(wc -c < "$root/state/world-state.json" | tr -d ' ')
    b_plan=$(wc -c < "$root/plan/world-state.json" | tr -d ' ')
    if cmp -s "$root/state/world-state.json" "$root/plan/world-state.json"; then
      echo "  plan/world-state.json 仍存在（与 state 那份恰好一致，但已废弃，仍判 RED）：state=${b_state}B plan=${b_plan}B" >&2
      red=1
    else
      echo "  两份账本内容不一致：state=${b_state}B plan=${b_plan}B" >&2
      red=1
    fi
  fi
  if [ "$red" -eq 0 ]; then
    echo "SINGLE_SOURCE_OF_TRUTH_GATE=GREEN"
    echo "WORLD_STATE_FILES=$total"
    return 0
  fi
  echo "SINGLE_SOURCE_OF_TRUTH_GATE=RED"
  echo "WORLD_STATE_FILES=$total"
  return 1
}

vmc_gate() {
  # $1 = 仓库根（plan/ 与 verdict-vN.json 都在其下）
  root=$1
  # 取 N 最大的一对 verdict-vN.json + gap-matrix-vN.md（N 用文件名正则取整数）
  vbest=""
  vn=-1
  for f in "$root"/plan/verdict-v*.json; do
    [ -f "$f" ] || continue
    base=$(basename "$f")
    n=$(printf '%s' "$base" | sed -n 's/^verdict-v\([0-9][0-9]*\)\.json$/\1/p')
    [ -n "$n" ] || continue
    if [ "$n" -gt "$vn" ]; then vn=$n; vbest=$f; fi
  done
  if [ -z "$vbest" ]; then
    echo "  找不到 plan/verdict-vN.json（N 最大的一版）" >&2
    echo "VERDICT_MATRIX_CONSISTENCY_GATE=RED"
    return 1
  fi
  mat="$root/plan/gap-matrix-v$vn.md"
  if [ ! -f "$mat" ]; then
    echo "  找不到与其配对的 plan/gap-matrix-v$vn.md" >&2
    echo "VERDICT_MATRIX_CONSISTENCY_GATE=RED"
    return 1
  fi
  head20="$root/plan/.vmc-head20.$$"
  sed -n '1,20p' "$mat" > "$head20"
  # verdict 行
  vline=$(grep -m1 -E '^> verdict: done=(true|false) remaining=([0-9]+)$' "$head20" || true)
  if [ -z "$vline" ]; then
    echo "  矩阵缺少机器可读结论行（前 20 行内应有 '> verdict: done=<bool> remaining=<int>'）" >&2
    rm -f "$head20"
    echo "VERDICT_MATRIX_CONSISTENCY_GATE=RED"
    return 1
  fi
  mdone=$(printf '%s' "$vline" | sed -n 's/^> verdict: done=\(true\|false\) remaining=.*$/\1/p')
  mrem=$(printf '%s' "$vline" | sed -n 's/^> verdict: done=.* remaining=\([0-9][0-9]*\)$/\1/p')
  # json 的 done / remaining / basis：用 python3 抽取（与其它门禁同一依赖）
  jout=$(python3 - "$vbest" <<'PYEOF'
import json, sys
d = json.load(open(sys.argv[1], encoding='utf-8'))
print('%s %s %s' % (str(d.get('done')).lower(), d.get('remaining'), d.get('basis', '')))
PYEOF
) || jout=""
  if [ -z "$jout" ]; then
    echo "  无法解析 $vbest 的 done/remaining/basis" >&2
    rm -f "$head20"
    echo "VERDICT_MATRIX_CONSISTENCY_GATE=RED"
    return 1
  fi
  jdone=$(printf '%s' "$jout" | cut -d' ' -f1)
  jrem=$(printf '%s' "$jout" | cut -d' ' -f2)
  jbasis=$(printf '%s' "$jout" | cut -d' ' -f3)
  red=0
  if [ "$mdone" != "$jdone" ]; then
    echo "  done 不一致：矩阵=$mdone JSON=$jdone（$vbest）" >&2
    red=1
  fi
  if [ "$mrem" != "$jrem" ]; then
    echo "  remaining 不一致：矩阵=$mrem JSON=$jrem（$vbest）" >&2
    red=1
  fi
  if [ -n "$jbasis" ] && ! grep -q -F "$jbasis" "$head20"; then
    echo "  basis 未出现在矩阵前 20 行内：basis=$jbasis" >&2
    red=1
  fi
  rm -f "$head20"
  if [ "$red" -eq 0 ]; then
    echo "VERDICT_MATRIX_CONSISTENCY_GATE=GREEN"
    echo "N=$vn done=$mdone remaining=$mrem basis=$jbasis"
    return 0
  fi
  echo "VERDICT_MATRIX_CONSISTENCY_GATE=RED"
  return 1
}

# ---- 判据 1：真实仓库必须 GREEN ----
set +e
sst_gate "$ROOT" > "$TMP/ssot-real.txt" 2>&1
ssot_real=$?
set -e
check "真实仓库：账本唯一真相源（state 存在 / plan 不存在）→ 0" 0 "$ssot_real"
sed 's/^/    /' "$TMP/ssot-real.txt"

# ---- 判据 1 负向夹具：造一份内容不同的假 plan/world-state.json → 必须 RED；删除 → GREEN ----
# 夹具纪律：全程只动 $TMP 的临时目录（mktemp -d），跑完 git status 必须干净。
SSOT_FIX="$TMP/ssot-fixture"
mkdir -p "$SSOT_FIX/state" "$SSOT_FIX/plan"
cp "$ROOT/state/world-state.json" "$SSOT_FIX/state/world-state.json"
echo '{"round":24,"updated_by":"fake-plan-ledger"}' > "$SSOT_FIX/plan/world-state.json"
set +e
sst_gate "$SSOT_FIX" > "$TMP/ssot-neg.txt" 2>&1
ssot_neg=$?
set -e
check "夹具（负向）：plan/world-state.json 存在且与 state 不一致 → 1" 1 "$ssot_neg"
sed 's/^/    /' "$TMP/ssot-neg.txt"
grep -q 'SINGLE_SOURCE_OF_TRUTH_GATE=RED' "$TMP/ssot-neg.txt" \
  && { echo "  [OK  ] 负向夹具打出了 SINGLE_SOURCE_OF_TRUTH_GATE=RED"; PASS=$((PASS+1)); } \
  || { echo "  [FAIL] 负向夹具没有打出 RED 判据行" >&2; FAIL=$((FAIL+1)); }
rm -f "$SSOT_FIX/plan/world-state.json"
set +e
sst_gate "$SSOT_FIX" > "$TMP/ssot-restore.txt" 2>&1
ssot_restore=$?
set -e
check "夹具（还原）：删除假 plan/world-state.json → 0（判据不是恒红）" 0 "$ssot_restore"
grep -q 'WORLD_STATE_FILES=1' "$TMP/ssot-restore.txt" \
  && { echo "  [OK  ] 还原后 WORLD_STATE_FILES=1"; PASS=$((PASS+1)); } \
  || { echo "  [FAIL] 还原后 WORLD_STATE_FILES 不是 1" >&2; FAIL=$((FAIL+1)); }
# 另造一份「state 缺失」的形态，证明第一条判据也不是恒绿
SSOT_FIX2="$TMP/ssot-fixture2"
mkdir -p "$SSOT_FIX2/plan"
set +e
sst_gate "$SSOT_FIX2" > "$TMP/ssot-neg2.txt" 2>&1
ssot_neg2=$?
set -e
check "夹具（负向2）：state/world-state.json 缺失 → 1" 1 "$ssot_neg2"

# ---- 判据 2：真实仓库必须 GREEN ----
set +e
vmc_gate "$ROOT" > "$TMP/vmc-real.txt" 2>&1
vmc_real=$?
set -e
check "真实仓库：verdict↔矩阵一致（最高版 v8）→ 0" 0 "$vmc_real"
sed 's/^/    /' "$TMP/vmc-real.txt"

# ---- 判据 2 负向夹具（三态：缺结论行 RED / 数值不一致 RED / 一致 GREEN）----
VMC_FIX="$TMP/vmc-fixture"
mkdir -p "$VMC_FIX/plan"
cat > "$VMC_FIX/plan/verdict-v99.json" <<'JSONEOF'
{"done": false, "remaining": 1, "basis": "deadbeef"}
JSONEOF
# (i) 矩阵缺结论行 → 必须 RED，且报「矩阵缺少机器可读结论行」
cat > "$VMC_FIX/plan/gap-matrix-v99.md" <<'MDEOF'
# 夹具矩阵 v99

> basis: deadbeef

## 0. 结论
没有机器可读结论行。
MDEOF
set +e
vmc_gate "$VMC_FIX" > "$TMP/vmc-neg-i.txt" 2>&1
vmc_i=$?
set -e
check "夹具2(i)：矩阵缺结论行 → 1" 1 "$vmc_i"
sed 's/^/    /' "$TMP/vmc-neg-i.txt"
grep -q '矩阵缺少机器可读结论行' "$TMP/vmc-neg-i.txt" \
  && { echo "  [OK  ] 报了「矩阵缺少机器可读结论行」"; PASS=$((PASS+1)); } \
  || { echo "  [FAIL] 没报缺结论行" >&2; FAIL=$((FAIL+1)); }
# (ii) 有结论行但 remaining 与 JSON 不一致 → 必须 RED
cat > "$VMC_FIX/plan/gap-matrix-v99.md" <<'MDEOF'
# 夹具矩阵 v99

> basis: deadbeef
> verdict: done=false remaining=0

## 0. 结论
MDEOF
set +e
vmc_gate "$VMC_FIX" > "$TMP/vmc-neg-ii.txt" 2>&1
vmc_ii=$?
set -e
check "夹具2(ii)：remaining 与 JSON 不一致（矩阵 0 / JSON 1）→ 1" 1 "$vmc_ii"
sed 's/^/    /' "$TMP/vmc-neg-ii.txt"
grep -q 'remaining 不一致：矩阵=0 JSON=1' "$TMP/vmc-neg-ii.txt" \
  && { echo "  [OK  ] 打出了两边取值"; PASS=$((PASS+1)); } \
  || { echo "  [FAIL] 没打印 remaining 的取值" >&2; FAIL=$((FAIL+1)); }
# (ii-b) basis 不在矩阵前 20 行 → 必须 RED（第三个子形态，锁住 basis 判据不是恒绿）
cat > "$VMC_FIX/plan/gap-matrix-v99.md" <<'MDEOF'
# 夹具矩阵 v99

> verdict: done=false remaining=1

## 0. 结论
MDEOF
set +e
vmc_gate "$VMC_FIX" > "$TMP/vmc-neg-iib.txt" 2>&1
vmc_iib=$?
set -e
check "夹具2(ii-b)：basis 未出现在矩阵前 20 行 → 1" 1 "$vmc_iib"
# (iii) 改成一致 → 恢复 GREEN
cat > "$VMC_FIX/plan/gap-matrix-v99.md" <<'MDEOF'
# 夹具矩阵 v99

> basis: deadbeef
> verdict: done=false remaining=1

## 0. 结论
MDEOF
set +e
vmc_gate "$VMC_FIX" > "$TMP/vmc-neg-iii.txt" 2>&1
vmc_iii=$?
set -e
check "夹具2(iii)：结论行与 JSON 一致 + basis 在头部 → 0（判据不是恒红）" 0 "$vmc_iii"
sed 's/^/    /' "$TMP/vmc-neg-iii.txt"
# 跑完删掉这对夹具（夹具本来就在 $TMP，会被 trap 清掉；仍显式删一次并断言工作区干净）
rm -f "$VMC_FIX/plan/verdict-v99.json" "$VMC_FIX/plan/gap-matrix-v99.md" "$SSOT_FIX/plan/world-state.json"
# 自证：两组夹具没有污染真实仓库。
# 注意：本任务本身就是要改 plan/ 与 state/ 的（删 plan/world-state.json、合并 state/world-state.json、
# 补 gap-matrix-v8 机器行、新增 plan/README.md）⇒ 不能用「git status 非空」当判据，
# 否则任何合法改动都会把这条自证判红（实测已踩一次）。改为断言「夹具路径在真实仓库里不存在」。
set +e
fixture_left=$(ls -d "$ROOT/plan/world-state.json" "$ROOT/plan/verdict-v99.json" "$ROOT/plan/gap-matrix-v99.md" 2>/dev/null | wc -l | tr -d ' ')
set -e
if [ "$fixture_left" = "0" ]; then
  echo "  [OK  ] 负向夹具未在真实仓库留下残留（§11 全程只动 \$TMP，跑完显式删除）"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] 负向夹具在真实仓库留下 $fixture_left 个残留：" >&2
  ls -d "$ROOT/plan/world-state.json" "$ROOT/plan/verdict-v99.json" "$ROOT/plan/gap-matrix-v99.md" 2>/dev/null >&2 || true
  FAIL=$((FAIL + 1))
fi

cd "$ROOT"

echo "== 12. W28-A1 玩法规格↔代码 绑定门禁（scripts/check-mechanics-spec.sh）必须能真的变红 =="
# 失败模式：这条门禁要拦的是「玩法规格 docs/GAME_DESIGN.md 与代码载体漂移」——
# 实测已发生 3 次（G-106 血契二转/三转、G-202 槽上限固定区间、G-209 预警 0.4-0.8s）。
# 最危险的失效是「声明了 spec 但每条都比不出红」：spec 里的 expect 被静默放宽成代码现值，
# 门禁就变成给漂移盖章的仪仗队。本节证明它真的会红。
#
# 与 §7/§9/§10 同样的纪律：**只在 $TMP 的夹具副本上破坏**，不碰真实仓库工作区。
# 夹具必须带齐门禁实际读到的路径：scripts/（spec+脚本）、plugins/*/config.yml、
# plugins/*/src/test/java（behavior 载体）、plugins/*/src/main/java（java-const 载体）。
SPEC_FIXTURE="$TMP/mechanics-spec-ws"
mkdir -p "$SPEC_FIXTURE/scripts"
cp "$ROOT/scripts/check-mechanics-spec.sh" "$ROOT/scripts/mechanics-spec.json" \
  "$SPEC_FIXTURE/scripts/"
# W28-A2 起 config 载体也指向 datapacks/（G 快捷操作八动作的 tag 成员），必须一起带进夹具，
# 否则干净夹具会因为「文件不存在」而恒红，下面所有负向对照就变成假红。
mkdir -p "$SPEC_FIXTURE/datapacks"
cp -R "$ROOT/datapacks/." "$SPEC_FIXTURE/datapacks/"
mkdir -p "$SPEC_FIXTURE/plugins"
for plugin_dir in "$ROOT"/plugins/*/; do
  plugin_name=$(basename "$plugin_dir")
  mkdir -p "$SPEC_FIXTURE/plugins/$plugin_name"
  # 三类载体都要带：config.yml（config）、src/test（behavior）、src/main（java-const）。
  # `cp -R` 失败不视为错误：部分插件只有 main 没有 test。
  cp -R "$plugin_dir/src" "$SPEC_FIXTURE/plugins/$plugin_name/" 2>/dev/null || true
done

run_spec_gate() {
  # 退出码打到 stdout（调用方用命令替换取，不走管道）
  set +e
  (cd "$SPEC_FIXTURE" && sh scripts/check-mechanics-spec.sh >"$1" 2>&1)
  echo $?
}

# 正夹具：真实仓库（不是夹具）⇒ 必须 GREEN 且判据行完整
set +e
(cd "$ROOT" && sh scripts/check-mechanics-spec.sh > "$TMP/spec-real.txt" 2>&1)
spec_real=$?
set -e
check "真实仓库：玩法规格↔代码绑定 → 0（GREEN）" 0 "$spec_real"
if grep -q '^MECHANICS_SPEC_GATE=GREEN$' "$TMP/spec-real.txt" \
   && grep -q '^MECHANICS_SPEC_ENTRIES=' "$TMP/spec-real.txt" \
   && grep -q '^MECHANICS_SPEC_BY_KIND=' "$TMP/spec-real.txt" \
   && grep -q '^MECHANICS_SPEC_WEAK=' "$TMP/spec-real.txt"; then
  echo "  [OK  ] 判据行完整：GATE/ENTRIES/BY_KIND/WEAK 四行俱在"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] 判据行不完整（ENTRIES/BY_KIND/WEAK 缺一即失效）" >&2
  sed 's/^/    /' "$TMP/spec-real.txt" >&2
  FAIL=$((FAIL + 1))
fi
sed 's/^/    /' "$TMP/spec-real.txt"

# 正夹具（夹具自身）：干净副本必须也 GREEN —— 否则下面的负向对照可能是「夹具恒红」的假红
check "干净夹具副本 → 0（基准，证明负向不是假红）" 0 "$(run_spec_gate "$TMP/spec-base.txt")"

# ---- 负夹具①：把一条 config 条目的值改坏 ----
# 破坏对象就用 M-EQP-002（forge.base-success-rate 0.65）。改的是**夹具副本**的 config.yml。
sed -i 's/^  base-success-rate: 0.65$/  base-success-rate: 0.55/' \
  "$SPEC_FIXTURE/plugins/rpg-items/src/main/resources/config.yml"
spec_cfg_rc=$(run_spec_gate "$TMP/spec-neg-config.txt")
check "负夹具①：config 值改坏（forge.base-success-rate 0.65→0.55）→ 1（RED）" 1 "$spec_cfg_rc"
# 红的必须可归因：判据行 RED、打出 M-EQP-002 的 id/期望/实际
if grep -q '^MECHANICS_SPEC_GATE=RED$' "$TMP/spec-neg-config.txt" \
   && grep -q 'M-EQP-002' "$TMP/spec-neg-config.txt" \
   && grep -q '0.65' "$TMP/spec-neg-config.txt" \
   && grep -q '0.55' "$TMP/spec-neg-config.txt"; then
  echo "  [OK  ] ① 判据行 RED 且打印 id/期望/实际（可归因）"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] ① RED 了但不可归因（缺 id 或期望/实际）" >&2
  sed 's/^/    /' "$TMP/spec-neg-config.txt" >&2
  FAIL=$((FAIL + 1))
fi
# 还原：从真实仓库取回原始文件（夹具里的已被改坏）
cp "$ROOT/plugins/rpg-items/src/main/resources/config.yml" \
  "$SPEC_FIXTURE/plugins/rpg-items/src/main/resources/config.yml"
check "负夹具①：还原后必须 GREEN" 0 "$(run_spec_gate "$TMP/spec-neg-config-restore.txt")"

# ---- 负夹具②：把一条 behavior 条目的测试名指向不存在的测试 ----
python3 - "$SPEC_FIXTURE/scripts/mechanics-spec.json" <<'PYSPEC'
import json, sys
path = sys.argv[1]
spec = json.load(open(path, encoding='utf-8'))
for entry in spec['entries']:
    if entry['id'] == 'M-EQP-007':
        entry['carrier']['test'] = (
            'plugins/rpg-items/src/test/java/cn/originalrpg/items/forge/'
            'ForgeRulesTest.java#definitelyNotARealTestMethod')
json.dump(spec, open(path, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
PYSPEC
spec_beh_rc=$(run_spec_gate "$TMP/spec-neg-behavior.txt")
check "负夹具②：behavior 指向不存在的测试（M-EQP-007）→ 1（RED）" 1 "$spec_beh_rc"
if grep -q '^MECHANICS_SPEC_GATE=RED$' "$TMP/spec-neg-behavior.txt" \
   && grep -q 'M-EQP-007' "$TMP/spec-neg-behavior.txt" \
   && grep -q 'definitelyNotARealTestMethod' "$TMP/spec-neg-behavior.txt"; then
  echo "  [OK  ] ② 判据行 RED 且点名不存在的测试方法"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] ② RED 了但没点名不存在的测试方法" >&2
  sed 's/^/    /' "$TMP/spec-neg-behavior.txt" >&2
  FAIL=$((FAIL + 1))
fi
# 还原：从真实仓库取回 spec
cp "$ROOT/scripts/mechanics-spec.json" "$SPEC_FIXTURE/scripts/mechanics-spec.json"
check "负夹具②：还原后必须 GREEN" 0 "$(run_spec_gate "$TMP/spec-neg-behavior-restore.txt")"

# ---- 负夹具③：把 java-const 的值改坏（第三条 kind，锁住三类载体都会红） ----
sed -i 's/^    public static final int LOCAL_LEVEL_CAP = 30;$/    public static final int LOCAL_LEVEL_CAP = 25;/' \
  "$SPEC_FIXTURE/plugins/rpg-api/src/main/java/cn/originalrpg/api/rules/ProgressionRules.java"
spec_const_rc=$(run_spec_gate "$TMP/spec-neg-const.txt")
check "负夹具③：java-const 值改坏（LOCAL_LEVEL_CAP 30→25）→ 1（RED）" 1 "$spec_const_rc"
cp "$ROOT/plugins/rpg-api/src/main/java/cn/originalrpg/api/rules/ProgressionRules.java" \
  "$SPEC_FIXTURE/plugins/rpg-api/src/main/java/cn/originalrpg/api/rules/ProgressionRules.java"
check "负夹具③：还原后必须 GREEN" 0 "$(run_spec_gate "$TMP/spec-neg-const-restore.txt")"

# ---- 负夹具④：java-const 条目去掉 behavior_ref ⇒ WEAK 计数必须真的变（不是恒 0） ----
# 这条锁住的是「WEAK 判据本身能变红」：若门禁读错了 behavior_ref 的位置（条目级 vs carrier 级），
# WEAK 会恒为 0，「没有 behavior_ref 的 java-const 单列」就变成一句空话。
python3 - "$SPEC_FIXTURE/scripts/mechanics-spec.json" <<'PYWEAK'
import json, sys
path = sys.argv[1]
spec = json.load(open(path, encoding='utf-8'))
for entry in spec['entries']:
    if entry['id'] == 'M-CORE-001':
        entry.pop('behavior_ref', None)
        entry['carrier'].pop('behavior_ref', None)
json.dump(spec, open(path, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
PYWEAK
set +e
(cd "$SPEC_FIXTURE" && sh scripts/check-mechanics-spec.sh > "$TMP/spec-weak.txt" 2>&1)
spec_weak_rc=$?
set -e
check "负夹具④：java-const 去掉 behavior_ref → 仍 0（仅登记为 weak，不判红）" 0 "$spec_weak_rc"
if grep -q '^MECHANICS_SPEC_WEAK=1$' "$TMP/spec-weak.txt"; then
  echo "  [OK  ] ④ WEAK 真的变 1（判据不是恒 0）"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] ④ WEAK 没有随缺失的 behavior_ref 变化" >&2
  sed 's/^/    /' "$TMP/spec-weak.txt" >&2
  FAIL=$((FAIL + 1))
fi
cp "$ROOT/scripts/mechanics-spec.json" "$SPEC_FIXTURE/scripts/mechanics-spec.json"
check "负夹具④：还原后必须 GREEN/WEAK 回 0" 0 "$(run_spec_gate "$TMP/spec-weak-restore.txt")"

# ---- 负夹具⑤（W28-A2）：改坏一条**后 8 节**的 config 条目 ----
# 动机：W28-A2 一口气登记了 71 条后 8 节条目（强化与精炼 / 市场与交易限制 / 黑心商人 /
# 菜单帮助引导 / 任务与剧情 / 主城地图 NPC / 商城会员累计奖励）。它们最危险的失效形态
# 是「登记了但没进判据」—— 报告里的条数很好看，门禁其实没读它们。
# 这里直接改坏一条**只属于后 8 节**的条目（M-REF-001 精炼成功率 0.65，config 载体），
# 证明这些新登记的条目真的在被检查。
sed -i 's/^  success-rate: 0.65$/  success-rate: 0.55/' \
  "$SPEC_FIXTURE/plugins/rpg-enhancement/src/main/resources/config.yml"
spec_rest_cfg_rc=$(run_spec_gate "$TMP/spec-neg-rest-config.txt")
check "负夹具⑤：后 8 节 config 值改坏（refinement.success-rate 0.65→0.55）→ 1（RED）" 1 "$spec_rest_cfg_rc"
if grep -q '^MECHANICS_SPEC_GATE=RED$' "$TMP/spec-neg-rest-config.txt" \
   && grep -q 'M-REF-001' "$TMP/spec-neg-rest-config.txt" \
   && grep -q '0.65' "$TMP/spec-neg-rest-config.txt" \
   && grep -q '0.55' "$TMP/spec-neg-rest-config.txt"; then
  echo "  [OK  ] ⑤ 判据行 RED 且点名 M-REF-001（新条目不是只登记不检查）"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] ⑤ RED 了但不可归因（缺 id 或期望/实际）" >&2
  sed 's/^/    /' "$TMP/spec-neg-rest-config.txt" >&2
  FAIL=$((FAIL + 1))
fi
# 还原：从真实仓库取回原始文件（夹具里的已被改坏）
cp "$ROOT/plugins/rpg-enhancement/src/main/resources/config.yml" \
  "$SPEC_FIXTURE/plugins/rpg-enhancement/src/main/resources/config.yml"
check "负夹具⑤：还原后必须 GREEN" 0 "$(run_spec_gate "$TMP/spec-neg-rest-config-restore.txt")"

# ---- 判据 12d（W28-A3）：门禁必须 stdlib-only，不得再引入 PyYAML 依赖 ----
# 失效模式：门禁镜像（eclipse-temurin:25-jdk + apt 装 curl/python3）里既没有 PyYAML
# 也没有 pip。脚本一旦 `import yaml`，容器里会直接打 RED「python3 缺少 PyYAML」，
# 而且**没有 pip 可装** —— 门禁从「拦漂移」变成「恒红」。
#
# 这条夹具的判据是**静态**的：脚本本体不得出现 `^\s*(import|from)\s+yaml`。
# 静态判据的理由：门禁镜像的动态条件（有多少 label / 是否 api_trigger）在自测里复现不了，
# 而「依赖声明」这件事在源码里就是可 grep 的事实。
spec_yaml_hits=$(grep -cE '^[[:space:]]*(import|from)[[:space:]]+yaml' "$ROOT/scripts/check-mechanics-spec.sh" || true)
check "判据 12d：check-mechanics-spec.sh 不含 import yaml（静态零命中）" 0 "$spec_yaml_hits"
if [ "$spec_yaml_hits" = "0" ]; then
  echo "  [OK  ] 门禁是 stdlib-only（不含 PyYAML 依赖声明）"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] 门禁又引入了 PyYAML 依赖（$spec_yaml_hits 处）—— 门禁镜像没有 pip，会变成恒红" >&2
  grep -nE '^[[:space:]]*(import|from)[[:space:]]+yaml' "$ROOT/scripts/check-mechanics-spec.sh" >&2 || true
  FAIL=$((FAIL + 1))
fi

# 负向对照：在**夹具副本**上插入 `import yaml` ⇒ 同一判据必须变红。
# 不做这一步，「静态判据」就只是一句没被验证过的断言（假绿风险：grep 写错成恒 0）。
cat > "$SPEC_FIXTURE/scripts/check-mechanics-spec.sh" <<'PYDEOF'
import yaml
PYDEOF
cat "$ROOT/scripts/check-mechanics-spec.sh" >> "$SPEC_FIXTURE/scripts/check-mechanics-spec.sh"
fixture_yaml_hits=$(grep -cE '^[[:space:]]*(import|from)[[:space:]]+yaml' "$SPEC_FIXTURE/scripts/check-mechanics-spec.sh" || true)
check "负夹具⑦：夹具脚本插入 import yaml（1 处）→ 静态判据必须报 1" 1 "$fixture_yaml_hits"
# 还原：从真实仓库取回脚本，再确认判据回 0（证明判据不是恒红）
cp "$ROOT/scripts/check-mechanics-spec.sh" "$SPEC_FIXTURE/scripts/check-mechanics-spec.sh"
restored_yaml_hits=$(grep -cE '^[[:space:]]*(import|from)[[:space:]]+yaml' "$SPEC_FIXTURE/scripts/check-mechanics-spec.sh" || true)
check "负夹具⑦：还原后静态判据回 0（不是恒红）" 0 "$restored_yaml_hits"
# 还原后门禁本身也必须仍然可跑（不是「把脚本写坏来换绿」）
check "负夹具⑦：还原后夹具门禁仍 GREEN" 0 "$(run_spec_gate "$TMP/spec-yaml-restore.txt")"

# ---- 判据 12e（W28-A3）：无 PyYAML 解释器下门禁必须照样 GREEN ----
# 12d 只证明「脚本没声明依赖」；这一步证明「解析真的不靠 PyYAML」——把系统里的 PyYAML
# 从 sys.path 里移走（隔离垫片），门禁仍必须打出 4 行判据且与正常解释器**逐字一致**。
# 垫片只在 $TMP 里，两个实现都先自证「垫片下 import yaml 真的失败」，否则这条对照是空跑。
YAML_SHIM="$TMP/noyaml-shim"
mkdir -p "$YAML_SHIM"
cat > "$YAML_SHIM/sitecustomize.py" <<'PYSEOF'
# 隔离垫片：把 dist-packages 从 sys.path 移走，让系统里的 PyYAML 不可见。
import sys
sys.path[:] = [p for p in sys.path if "dist-packages" not in p]
PYSEOF
set +e
PYTHONPATH="$YAML_SHIM" python3 -c 'import yaml' > "$TMP/noyaml-probe.txt" 2>&1
noyaml_probe_rc=$?
set -e
check "判据 12e：隔离垫片下 import yaml 必须失败（否则下面是对照空跑）" 1 "$noyaml_probe_rc"
if grep -q "ModuleNotFoundError: No module named 'yaml'" "$TMP/noyaml-probe.txt"; then
  echo "  [OK  ] 垫片下 PyYAML 确实不可导入（原文：ModuleNotFoundError: No module named 'yaml'）"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] 垫片没真的屏蔽 PyYAML —— 无 PyYAML 对照是空跑" >&2
  sed 's/^/    /' "$TMP/noyaml-probe.txt" >&2
  FAIL=$((FAIL + 1))
fi
set +e
(cd "$ROOT" && PYTHONPATH="$YAML_SHIM" sh scripts/check-mechanics-spec.sh > "$TMP/spec-noyaml.txt" 2>&1)
spec_noyaml_rc=$?
set -e
check "判据 12e：无 PyYAML 解释器下门禁 → 0（GREEN）" 0 "$spec_noyaml_rc"
if grep -q '^MECHANICS_SPEC_GATE=GREEN$' "$TMP/spec-noyaml.txt" \
   && grep -q '^MECHANICS_SPEC_ENTRIES=' "$TMP/spec-noyaml.txt" \
   && grep -q '^MECHANICS_SPEC_BY_KIND=' "$TMP/spec-noyaml.txt" \
   && grep -q '^MECHANICS_SPEC_WEAK=' "$TMP/spec-noyaml.txt"; then
  echo "  [OK  ] 无 PyYAML 下判据行完整（GATE/ENTRIES/BY_KIND/WEAK 四行俱在）"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] 无 PyYAML 下判据行不完整" >&2
  sed 's/^/    /' "$TMP/spec-noyaml.txt" >&2
  FAIL=$((FAIL + 1))
fi
# 逐字一致：无 PyYAML 与有 PyYAML 的判据行必须一模一样（条数不能被 stdout 顺序掩盖）
if diff -q "$TMP/spec-real.txt" "$TMP/spec-noyaml.txt" >/dev/null 2>&1; then
  echo "  [OK  ] 无 PyYAML 判据行与正常解释器逐字一致（$(grep -c . "$TMP/spec-noyaml.txt") 行）"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] 无 PyYAML 判据行与正常解释器不一致" >&2
  diff "$TMP/spec-real.txt" "$TMP/spec-noyaml.txt" >&2 || true
  FAIL=$((FAIL + 1))
fi

# ---- 判据 12c（W28-A2）：报告节必须可重生成且与 spec 一致 ----
# 「报告里的数字必须由脚本生成」这句承诺本身也要有判据：如果报告节与当前 spec/门禁不符，
# --check 必须退出 1。这里做两件事：① 真实仓库必须 FRESH；② 打乱报告节的一个数字（夹具副本，
# 不动仓库）后必须 STALE —— 证明 --check 不是恒绿。
set +e
(cd "$ROOT" && python3 scripts/gen-mechanics-spec-report.py --check > "$TMP/spec-report-fresh.txt" 2>&1)
spec_report_rc=$?
set -e
check "报告节与 spec 一致（gen-mechanics-spec-report.py --check → 0）" 0 "$spec_report_rc"
if grep -q '^MECHANICS_SPEC_REPORT=FRESH$' "$TMP/spec-report-fresh.txt"; then
  echo "  [OK  ] 判据行 MECHANICS_SPEC_REPORT=FRESH"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] 报告节不是 FRESH（重跑 --write）" >&2
  sed 's/^/    /' "$TMP/spec-report-fresh.txt" >&2
  FAIL=$((FAIL + 1))
fi

# 负向：把**夹具副本**里的报告数字改掉 -> --check 必须 STALE（exit 1）
REPORT_FIXTURE="$TMP/mechanics-report-ws"
mkdir -p "$REPORT_FIXTURE/scripts" "$REPORT_FIXTURE/docs"
cp "$ROOT/scripts/gen-mechanics-spec-report.py" "$ROOT/scripts/mechanics-spec.json" \
  "$REPORT_FIXTURE/scripts/"
cp "$ROOT/scripts/check-mechanics-spec.sh" "$REPORT_FIXTURE/scripts/"
cp "$ROOT/docs/GAME_DESIGN.md" "$REPORT_FIXTURE/docs/"
# 生成器会真实跑门禁拿判据行，所以夹具必须带齐门禁读到的载体（plugins/ + datapacks/）。
# 不带就会因为「文件不存在」而拿到 RED 判据行，负向对照变成在测别的东西。
cp -R "$ROOT/datapacks/." "$REPORT_FIXTURE/datapacks/" 2>/dev/null || true
mkdir -p "$REPORT_FIXTURE/plugins"
for plugin_dir in "$ROOT"/plugins/*/; do
  plugin_name=$(basename "$plugin_dir")
  mkdir -p "$REPORT_FIXTURE/plugins/$plugin_name"
  cp -R "$plugin_dir/src" "$REPORT_FIXTURE/plugins/$plugin_name/" 2>/dev/null || true
done
sed -i 's/^MECHANICS_SPEC_ENTRIES=137$/MECHANICS_SPEC_ENTRIES=1/' "$REPORT_FIXTURE/docs/GAME_DESIGN.md"
set +e
python3 "$REPORT_FIXTURE/scripts/gen-mechanics-spec-report.py" --check \
  > "$TMP/spec-report-stale.txt" 2>&1
spec_report_stale_rc=$?
set -e
check "负夹具⑥：把报告节里的 entries 数改掉 → --check 报 STALE（1）" 1 "$spec_report_stale_rc"
if grep -q '^MECHANICS_SPEC_REPORT=STALE$' "$TMP/spec-report-stale.txt"; then
  echo "  [OK  ] ⑥ 报告陈旧能被判出来（--check 不是恒绿）"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] ⑥ --check 没判出被改坏的报告节" >&2
  sed 's/^/    /' "$TMP/spec-report-stale.txt" >&2
  FAIL=$((FAIL + 1))
fi

# 自证：负向对照没有污染真实仓库（§12 全程只动 $TMP 里的夹具）
set +e
(cd "$ROOT" && sh scripts/check-mechanics-spec.sh >/dev/null 2>&1)
spec_after=$?
set -e
check "负向对照后仓库内玩法规格绑定仍为绿（工作区未被污染）" 0 "$spec_after"

echo "== 12b. W28-A1 接线自证：玩法规格门禁必须真的在 CI 里跑 =="
# 「脚本写好了但没有任何 CI stage 调用它」是比「脚本有 bug」更隐蔽的失败（见 §6/§8/§9b/§10b）。
# 本任务的改动白名单**不含 .cnb.yml**，因此接线方式不能是「往 .cnb.yml 加一个 stage」，
# 而必须是：**本脚本（selftest-gates.sh）自己调用新门禁** —— .cnb.yml 早已执行
# `sh scripts/selftest-gates.sh`（§6 已验证该事实），于是 §12 的正/负夹具每次推送都会跑。
# 判据因此断言「本脚本调用 check-mechanics-spec.sh」这一条可 grep 的事实（完整命令行）。
for wired in "scripts/check-mechanics-spec.sh" "scripts/mechanics-spec.json"; do
  set +e
  hits=$(grep -c -F "$wired" "$ROOT/scripts/selftest-gates.sh")
  set -e
  if [ "$hits" -ge 1 ]; then
    echo "  [OK  ] selftest-gates.sh 覆盖 $wired（$hits 处）"
    PASS=$((PASS + 1))
  else
    echo "  [FAIL] selftest-gates.sh 没有调用 $wired —— 该门禁不在 CI 里" >&2
    FAIL=$((FAIL + 1))
  fi
done

# 负向对照：拼出来的假路径必须查不到（证明上面那条判据能变红，不是恒命中）
spec_phantom="scripts/definitely-not-a-real""-mechanics-gate.sh"
set +e
spec_phantom_hits=$(grep -c -F "$spec_phantom" "$ROOT/scripts/selftest-gates.sh")
set -e
if [ "$spec_phantom_hits" = "0" ]; then
  echo "  [OK  ] 不存在的玩法规格门禁查不到（接线判据能变红）"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] 不存在的玩法规格门禁竟被查到 $spec_phantom_hits 次 —— 接线判据恒绿" >&2
  FAIL=$((FAIL + 1))
fi

cd "$ROOT"

echo "== 12c. W32-A2 env 标签正当性门禁（scripts/check-env-tag-justified.sh）必须能真的变红 =="
# 背景（W32-A1）：8 个 @Tag(env) 用例里 1 个的唯一依赖就在本仓 —— 它从来没跑过，
# 而过宽的排除没有任何机制会提醒你。本段锁住三件事：
#   ① 真实仓库必须 GREEN（ENV_TAG_METHODS=7 / ENV_TAG_UNJUSTIFIED=0）；
#   ② 把那个标签**加回去**（临时整树副本）必须 RED 且点名该方法，去掉后回到 GREEN；
#   ③ 方法体里抽不到任何路径字面量 ⇒ UNJUSTIFIED_UNKNOWN 且 RED（不许静默放过）。
# 负夹具复制的是**整棵树**：判据问的是「引用的路径在本仓存在吗」，
# 只复制 plugins/ 会让 resource-pack/ 一起消失，被检查的文件反而变成「不存在」，
# 夹具就假绿了（W32-A2 实测踩过这个坑）。
cd "$ROOT"
set +e
sh scripts/check-env-tag-justified.sh > "$TMP/envtag-green.txt" 2>&1
envtag_green_rc=$?
set -e
check "真实仓库的 env 标签正当性判据 → 0" 0 "$envtag_green_rc"
envtag_status=$(sed -n 's/^ENV_TAG_JUSTIFIED_GATE=\(.*\)$/\1/p' "$TMP/envtag-green.txt" | tail -1)
envtag_methods=$(sed -n 's/^ENV_TAG_METHODS=\(.*\)$/\1/p' "$TMP/envtag-green.txt" | tail -1)
envtag_unjustified=$(sed -n 's/^ENV_TAG_UNJUSTIFIED=\(.*\)$/\1/p' "$TMP/envtag-green.txt" | tail -1)
if [ "$envtag_status" = "GREEN" ] && [ "$envtag_methods" = "7" ] && [ "$envtag_unjustified" = "0" ]; then
  echo "  [OK  ] 真实仓库 ENV_TAG_JUSTIFIED_GATE=GREEN / ENV_TAG_METHODS=7 / ENV_TAG_UNJUSTIFIED=0"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] 真实仓库判据行不符：GATE=$envtag_status METHODS=$envtag_methods UNJUSTIFIED=$envtag_unjustified" >&2
  sed 's/^/    /' "$TMP/envtag-green.txt" >&2
  FAIL=$((FAIL + 1))
fi

# 整树副本工具：夹具要包含 resource-pack/ 等被引用的路径，否则判据问不到真东西
envtag_tree() {
  # envtag_tree <目标目录>
  # ⚠️ 门禁镜像（eclipse-temurin:25-jdk + apt curl/coreutils/python3）**没有 git**。
  # 实测（sn=cnb-aq4-1k4da30vh）：这里原来用 `git -C "$ROOT" archive HEAD` 取副本，
  # 在门禁里报 `git: not found` ⇒ 管道下游收到空流 ⇒ `tarfile.ReadError: empty file`
  # ⇒ 整个「门禁自测」stage 假红（NPC 容器有 git，所以本地跑不出来）。改用 python 复制工作树。
  rm -rf "$1"
  python3 -c "
import shutil, sys
shutil.copytree(sys.argv[1], sys.argv[2], ignore=shutil.ignore_patterns('.git'))
" "$ROOT" "$1"
}

# 残留判据用「plugins/ 的 sha256 清单」而不是 `git status`（门禁镜像没有 git，见上）。
envtag_manifest() {
  find plugins -type f -exec sha256sum {} + 2>/dev/null | sort
}
ENVTAG_BEFORE=$(envtag_manifest)

ENVTAG_FIXTURE="$TMP/envtag-tree"
envtag_tree "$ENVTAG_FIXTURE"
python3 - "$ENVTAG_FIXTURE" add <<'PYEOF'
import os
import sys

root, action = sys.argv[1], sys.argv[2]
path = os.path.join(root, 'plugins/rpg-guide/src/test/java/cn/originalrpg/guide/HelpTopicTest.java')
with open(path, encoding='utf-8') as handle:
    source = handle.read()
anchor = '    void everyCustomHelpModelIsDeclaredByTheRuntimeResourcePack() throws Exception {'
assert anchor in source, '夹具锚点丢失：HelpTopicTest 里找不到目标方法'
if action == 'add':
    patched = source.replace(anchor, '    @Tag(DeploymentEnvironmentFixtures.TAG)\n' + anchor)
    assert patched != source, '加回标签失败'
else:
    patched = source.replace(
        '    @Tag(DeploymentEnvironmentFixtures.TAG)\n' + anchor, anchor)
    assert patched != source, '去掉标签失败'
with open(path, 'w', encoding='utf-8') as handle:
    handle.write(patched)
PYEOF
set +e
sh scripts/check-env-tag-justified.sh "$ENVTAG_FIXTURE" > "$TMP/envtag-red.txt" 2>&1
envtag_red_rc=$?
set -e
check "负夹具（把 A1 去掉的标签加回去）→ 1" 1 "$envtag_red_rc"
if grep -q '^ENV_TAG_JUSTIFIED_GATE=RED$' "$TMP/envtag-red.txt"; then
  echo "  [OK  ] 过宽的 env 标签被判 RED"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] 标签加回去后没有变红（判据对本缺陷恒绿）" >&2
  sed 's/^/    /' "$TMP/envtag-red.txt" >&2
  FAIL=$((FAIL + 1))
fi
# 必须**点名**到那个方法，否则「红了」等于没红（人不知道该改哪）
if grep -q '^UNJUSTIFIED .*HelpTopicTest.java#everyCustomHelpModelIsDeclaredByTheRuntimeResourcePack' "$TMP/envtag-red.txt"; then
  echo "  [OK  ] RED 输出点名 HelpTopicTest#everyCustomHelpModelIsDeclaredByTheRuntimeResourcePack"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] RED 输出没有点名到出问题的方法" >&2
  sed 's/^/    /' "$TMP/envtag-red.txt" >&2
  FAIL=$((FAIL + 1))
fi

# 恢复：夹具里把标签再去掉 ⇒ 回到 GREEN（证明 RED 由该标签引起，不是别的东西）
python3 - "$ENVTAG_FIXTURE" remove <<'PYEOF'
import os
import sys

root, action = sys.argv[1], sys.argv[2]
path = os.path.join(root, 'plugins/rpg-guide/src/test/java/cn/originalrpg/guide/HelpTopicTest.java')
with open(path, encoding='utf-8') as handle:
    source = handle.read()
anchor = '    void everyCustomHelpModelIsDeclaredByTheRuntimeResourcePack() throws Exception {'
patched = source.replace('    @Tag(DeploymentEnvironmentFixtures.TAG)\n' + anchor, anchor)
assert patched != source, '去掉标签失败'
with open(path, 'w', encoding='utf-8') as handle:
    handle.write(patched)
PYEOF
set +e
sh scripts/check-env-tag-justified.sh "$ENVTAG_FIXTURE" > "$TMP/envtag-restored.txt" 2>&1
envtag_restored_rc=$?
set -e
check "夹具恢复（标签去掉）→ 0" 0 "$envtag_restored_rc"
if grep -q '^ENV_TAG_JUSTIFIED_GATE=GREEN$' "$TMP/envtag-restored.txt"; then
  echo "  [OK  ] 恢复后回到 GREEN（RED 确由该标签引起）"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] 恢复后没有回到 GREEN" >&2
  sed 's/^/    /' "$TMP/envtag-restored.txt" >&2
  FAIL=$((FAIL + 1))
fi

# 告警路径：方法体里抽不到路径字面量 ⇒ UNJUSTIFIED_UNKNOWN 且 RED
ENVTAG_UNKNOWN="$TMP/envtag-unknown"
envtag_tree "$ENVTAG_UNKNOWN"
python3 - "$ENVTAG_UNKNOWN" <<'PYEOF'
import os
import sys

root = sys.argv[1]
path = os.path.join(root, 'plugins/rpg-hub/src/test/java/cn/originalrpg/hub/WorldTravelConfigurationContractTest.java')
with open(path, encoding='utf-8') as handle:
    source = handle.read()
old = '''        String maintenance = Files.readString(root.resolve(
                "server/bin/maintain-world-coverage.sh"), java.nio.charset.StandardCharsets.UTF_8);'''
new = '''        String maintenance = Files.readString(root.resolve(opsFile()),
                java.nio.charset.StandardCharsets.UTF_8);'''
assert old in source, '夹具锚点丢失：maintain-world-coverage.sh 读取点找不到'
source = source.replace(old, new)
source = source.replace(
    '    private static Map<?, ?> yaml(Path path) throws IOException {',
    '    private static String opsFile() {\n        return "maintain" + "-world-coverage.sh";\n    }\n\n'
    '    private static Map<?, ?> yaml(Path path) throws IOException {')
with open(path, 'w', encoding='utf-8') as handle:
    handle.write(source)
PYEOF
set +e
sh scripts/check-env-tag-justified.sh "$ENVTAG_UNKNOWN" > "$TMP/envtag-unknown.txt" 2>&1
envtag_unknown_rc=$?
set -e
check "负夹具（路径字面量抽不到）→ 1" 1 "$envtag_unknown_rc"
if grep -q '^UNJUSTIFIED_UNKNOWN .*WorldTravelConfigurationContractTest.java#controlledGenerationCoversTheCompleteBorderAndItsChunkMargin' "$TMP/envtag-unknown.txt"; then
  echo "  [OK  ] 抽不到路径字面量被计为 UNJUSTIFIED_UNKNOWN 并判 RED"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] 抽不到路径字面量被静默放过（判据失效的形态）" >&2
  sed 's/^/    /' "$TMP/envtag-unknown.txt" >&2
  FAIL=$((FAIL + 1))
fi

# 收尾断言：全程只动 $TMP，真实仓库里的被测载体不得有残留。
# 只看 plugins/ —— 本脚本自身与新增的门禁脚本本来就处于「已改动」状态（工作区常态），
# 把它们算进来会让这条断言恒红，从而被无视。
# ⚠️ 门禁镜像没有 git，所以这里**不能**用 `git status --porcelain`（原实现如此，实测假红）；
# 改用 plugins/ 的 sha256 清单前后比对：只要有一个字节变了就报红。
cd "$ROOT"
set +e
ENVTAG_AFTER=$(envtag_manifest)
set -e
if [ "$ENVTAG_BEFORE" = "$ENVTAG_AFTER" ]; then
  echo "  [OK  ] env 标签判据的夹具未在真实仓库留下残留（plugins/ 的 sha256 清单前后一致）"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] 夹具污染了真实仓库（plugins/ 的 sha256 清单发生变化）：" >&2
  printf '%s\n' "$ENVTAG_BEFORE" > "$TMP/envtag-manifest-before.txt"
  printf '%s\n' "$ENVTAG_AFTER" > "$TMP/envtag-manifest-after.txt"
  diff "$TMP/envtag-manifest-before.txt" "$TMP/envtag-manifest-after.txt" | sed 's/^/    /' >&2 || true
  FAIL=$((FAIL + 1))
fi

# 12f. 回归判据：门禁镜像没有 git ⇒ scripts/ 层不得调用它。
# 这条判据是上面那次假红的「把缺陷变成一条门」：只要有人再在脚本里用 git，这里就红。
# 允许出现在**注释**里（# 开头的行不算）。
set +e
GIT_CALLS=$(grep -nE '(^|[^a-zA-Z_/])git[[:space:]]' "$ROOT"/scripts/*.sh "$ROOT"/scripts/*.py 2>/dev/null \
  | grep -vE ':[0-9]+: *#' || true)
set -e
GIT_CALL_N=$(printf '%s' "$GIT_CALLS" | grep -c . || true)
if [ "$GIT_CALL_N" -eq 0 ]; then
  echo "  [OK  ] 判据 12f：scripts/ 层没有对「git」的调用（门禁镜像无 git） (exit=0)"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] scripts/ 层调用了 git，但门禁镜像没有 git：" >&2
  printf '%s\n' "$GIT_CALLS" | sed 's/^/    /' >&2
  FAIL=$((FAIL + 1))
fi

cd "$ROOT"


echo "== 12d. W32-A2 接线自证：env 标签判据必须真的在 CI 里跑 =="
# 「脚本写好了但没有任何 CI stage 调用它」比「脚本有 bug」更隐蔽（同 §6/§8/§9b/§10b/§12b）。
# 本任务的白名单不含 .cnb.yml，接线方式是：本脚本调用新门禁，
# 而 .cnb.yml 早已执行 `sh scripts/selftest-gates.sh`。
set +e
envtag_wired=$(grep -c -F "sh scripts/check-env-tag-justified.sh" "$ROOT/scripts/selftest-gates.sh")
set -e
if [ "$envtag_wired" -ge 1 ]; then
  echo "  [OK  ] selftest-gates.sh 调用 check-env-tag-justified.sh（$envtag_wired 处）"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] selftest-gates.sh 没有调用 check-env-tag-justified.sh —— 该门禁不在 CI 里" >&2
  FAIL=$((FAIL + 1))
fi

if [ -f "$ROOT/.cnb.yml" ] && grep -qF 'sh scripts/selftest-gates.sh' "$ROOT/.cnb.yml"; then
  echo "  [OK  ] .cnb.yml 执行 sh scripts/selftest-gates.sh（配合上一条即接线成立）"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] .cnb.yml 没有执行 selftest-gates.sh —— 新增判据进不了 CI" >&2
  FAIL=$((FAIL + 1))
fi

# 负向对照：拼出来的假路径必须查不到（证明上面那条判据能变红，不是恒命中）
envtag_phantom="scripts/definitely-not-a-real""-env-tag-gate.sh"
set +e
envtag_phantom_hits=$(grep -c -F "$envtag_phantom" "$ROOT/scripts/selftest-gates.sh")
set -e
if [ "$envtag_phantom_hits" = "0" ]; then
  echo "  [OK  ] 不存在的 env 标签门禁查不到（接线判据能变红）"
  PASS=$((PASS + 1))
else
  echo "  [FAIL] 不存在的 env 标签门禁竟被查到 $envtag_phantom_hits 次 —— 接线判据恒绿" >&2
  FAIL=$((FAIL + 1))
fi

cd "$ROOT"

echo "===== 门禁自测汇总：PASS=$PASS FAIL=$FAIL ====="
if [ "$FAIL" -ne 0 ]; then
  echo "GATE_SELFTEST=RED"
  exit 1
fi
echo "GATE_SELFTEST=GREEN"
