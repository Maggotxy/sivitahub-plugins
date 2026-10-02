#!/bin/sh
# scripts/selftest-gates.sh —— 质量门「自身逻辑」的自测（负向对照）
#
# 背景：门禁最危险的失效不是「红了」，而是「永远绿」或「永远红」——
#   永远绿 = 漏判（假门禁），永远红 = 假红（没人再信门禁）。
# 本脚本用最小夹具逐个验证三件事：
#   1) scripts/dump-test-results.py 的四个判据分支都会给出预期退出码
#   2) scripts/check-plugin-jars.py 的四个判据分支都会给出预期退出码
#   3) scripts/list-env-tests.sh 在真实源码上能抽出非空清单
#   4) scripts/check-repo-artifacts.sh 在真实文档上必须绿，且把数据包目录藏起来后必须红\n#   4b) scripts/install-datapacks.sh --check 在真实数据包上必须绿，函数调用未注册命令后必须红
#
# 用法：sh scripts/selftest-gates.sh     （退出码 0 = 全部通过）
#
# G-206 接线说明（要求④「把这类问题在 CI 拦下来」）：
#   §4b / §5 这两段就是「仓库内数据包 + docs 产物引用」的 CI 拦截点。
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

echo "== 4b. install-datapacks 门禁（G-206）必须能真的变红（负向对照） =="
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

echo "== 6. G-206 接线自证：两条门禁必须被本脚本真实覆盖 =="
# 「写了门禁但 CI 里没跑」是比「门禁有 bug」更隐蔽的失败：脚本存在、文档写着，
# 但没有任何 CI stage 调用它。本段把「谁在跑」变成可 grep 的事实：
#   a) 本脚本自身必须调用 check-repo-artifacts.sh 与 install-datapacks.sh
#      （否则 §4b/§5 被删掉后上面仍然全绿，门禁回到「静默没人跑」状态）
#   b) 质量门 .cnb.yml 必须包含 `sh scripts/selftest-gates.sh`
#      —— 这就是 §4b/§5 进入 CI 的接线点，且本次改动无需修改 .cnb.yml
for wired in "scripts/check-repo-artifacts.sh" "scripts/install-datapacks.sh"; do
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

echo ""
echo "===== 门禁自测汇总：PASS=$PASS FAIL=$FAIL ====="
if [ "$FAIL" -ne 0 ]; then
  echo "GATE_SELFTEST=RED"
  exit 1
fi
echo "GATE_SELFTEST=GREEN"
