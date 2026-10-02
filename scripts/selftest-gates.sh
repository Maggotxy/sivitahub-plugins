#!/bin/sh
# scripts/selftest-gates.sh —— 质量门「自身逻辑」的自测（负向对照）
#
# 背景：门禁最危险的失效不是「红了」，而是「永远绿」或「永远红」——
#   永远绿 = 漏判（假门禁），永远红 = 假红（没人再信门禁）。
# 本脚本用最小夹具逐个验证三件事：
#   1) scripts/dump-test-results.py 的四个判据分支都会给出预期退出码
#   2) scripts/check-plugin-jars.py 的四个判据分支都会给出预期退出码
#   3) scripts/list-env-tests.sh 在真实源码上能抽出非空清单
#   4) scripts/check-repo-artifacts.sh 在真实文档上必须绿，且把数据包目录藏起来后必须红
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

echo "== 4. source-contract 门禁必须能真的变红（负向对照） =="
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

echo ""
echo "===== 门禁自测汇总：PASS=$PASS FAIL=$FAIL ====="
if [ "$FAIL" -ne 0 ]; then
  echo "GATE_SELFTEST=RED"
  exit 1
fi
echo "GATE_SELFTEST=GREEN"
