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
#
# 用法：sh scripts/selftest-gates.sh     （退出码 0 = 全部通过）
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

# 豁免分支：构造器的 --remove-table 把 rpg_progression_anomalies 从 DDL 里剥掉。
# 它同时命中反向豁免，门禁应报 [反向豁免 PENDING] 且第 7 节仍 GREEN（豁免可审计）。
frame_rc=$(reinit --remove-table rpg_progression_anomalies)
if [ "$frame_rc" != "0" ]; then
  echo "  [FAIL] 夹具 --remove-table rpg_progression_anomalies 失败 frame_rc=$frame_rc（夹具没造出来）" >&2
  FAIL=$((FAIL + 1))
fi
run_gate "$TMP/pending.java" "DDL_REGISTRATION_GATE=GREEN" "缺表但在反向豁免清单里 → 第 7 节 GREEN"
run_gate "$TMP/pending.java" "[反向豁免 PENDING] rpg_progression_anomalies" "反向豁免必须打印可审计分类行"

# 同一张造出来的缺表，把引用换成非豁免表，第 7 节必须变红：
# 证明上一格的绿是豁免给的，不是门禁漏判。
run_gate "$TMP/ghost.java" "DDL_REGISTRATION_GATE=RED" "同一夹具换成非豁免缺表 → 第 7 节 RED（绿是豁免给的）"

echo ""
echo "===== 门禁自测汇总：PASS=$PASS FAIL=$FAIL ====="
if [ "$FAIL" -ne 0 ]; then
  echo "GATE_SELFTEST=RED"
  exit 1
fi
echo "GATE_SELFTEST=GREEN"
