#!/bin/sh
# scripts/list-env-tests.sh —— 列出被 @Tag(env) 显式分类的测试方法名（每行一个，已排序去重）
#
# 用途：测试门在「排除清单」段落里逐条打印这些名字，并断言它们都没出现在测试日志里。
# 为什么独立成脚本而不是写在 .cnb.yml 里：
#   1) 它可以被 scripts/selftest-gates.sh 自测（门禁自身的逻辑也要有测试）
#   2) 抽取逻辑有两个真实的坑，写在 YAML 里没人会发现（见下）
#
# 两个坑（都实际踩过、且都会「静默产出空清单」，让门禁看起来正常但从不生效）：
#   A) `grep -o -A1 <pat>` 会在匹配之间插入 `--` 分隔行，下游 `grep -oE 'void ...'`
#      因此一行都匹配不到 —— 清单恒为空。改用 awk 读文件、getline 取下一行。
#   B) 标签常量写法在历史上换过（DeploymentEnvironmentFixtures.TAG / 直接字面量 "env"），
#      所以匹配放在一个变量里，新增写法时只改一处。
#
# 退出码：0 = 正常（清单可能为空，由调用方判断空是否可接受）；1 = 参数错误

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

# 标签常量：DeploymentEnvironmentFixtures.TAG 求值后是 "env"
TAG_ANCHOR='DeploymentEnvironmentFixtures\.TAG'

if [ "$#" -gt 0 ]; then
  echo "用法: scripts/list-env-tests.sh" >&2
  exit 1
fi

# 只扫测试源码；文件清单由 grep -l 给出，再用 awk 在文件内取「锚点行的下一行」
FILES=$(grep -rl "$TAG_ANCHOR" plugins/*/src/test tools/*/src/test 2>/dev/null || true)
if [ -z "$FILES" ]; then
  exit 0
fi

printf '%s\n' "$FILES" | while IFS= read -r f; do
  [ -z "$f" ] && continue
  [ -f "$f" ] || continue
  awk -v anchor="$TAG_ANCHOR" '
    $0 ~ anchor {
      if ((getline nxt) > 0) {
        # 取 @Tag 行后面第一条 void 方法名（允许中间夹着别的注解/空行）
        while (nxt !~ /void [A-Za-z0-9_]+/ && (getline nxt) > 0) { }
        if (match(nxt, /void [A-Za-z0-9_]+/)) {
          print substr(nxt, RSTART + 5, RLENGTH - 5)
        }
      }
    }
  ' "$f"
done | sed '/^$/d' | sort -u
exit 0
