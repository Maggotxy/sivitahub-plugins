#!/bin/sh
# G-301 DDL 消费者门禁
#
# 失败模式：表 / 枚举 / 状态机 / 注册点存在，但没有消费者。
# 判据：Database.java 里每条 CREATE TABLE IF NOT EXISTS <t> 的表名，
#       必须在 plugins/*/src/main 或 tools/*/src/main 下存在「真实消费者」。
#
# 表名来源（为什么选它）：
#   plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java
#   是本项目唯一的运行时建表真相源（全部 75 条 DDL 都在此文件内联）。
#   tools/db-migration 的 SchemaManifest 只是迁移拷贝清单，会跟随 Database.java
#   变化，不是真相源，因此不拿来抽表名（否则两处漂移无法定位）。
#
# 什么算「消费者」：
#   文件里出现 SQL 语句引用该表（SELECT/INSERT/UPDATE/DELETE/MERGE ... <t>）。
#   仅把表名写进常量清单（SchemaManifest）或 DDL 定义（Database.java）不算——
#   这正是本门禁要掐死的形态。
#
# 豁免清单见下方 EXEMPT，写在脚本里便于审计。

set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

# 允许用 DDL_GATE_DDL_FILE 覆盖（仅用于本门禁的负向自测，CI 不设置）
DDL_FILE="${DDL_GATE_DDL_FILE:-plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java}"

if [ ! -f "$DDL_FILE" ]; then
  echo "DDL_CONSUMER_GATE=RED 找不到 DDL 真相源: $DDL_FILE" >&2
  exit 2
fi

# 可审计豁免清单：分类|表名|理由|文档锚点（字段以 | 分隔，行首空格忽略）
#
# 分类取值（显式，门禁输出里可区分）：
#   PENDING   待接线：能力仍然有效，消费者晚于 schema 落地（计划内）
#   ABANDONED 已废弃：能力已被取代/被锁死，DDL 保留但不再接线（计划外）
# 两类都计入「未接线」计数并触发 RED；豁免只是分类可审计，不是放行。
# 禁止拿豁免掩盖本该接线的漏接；禁止静默删除豁免条目。
# 逐条判定与 grep 证据见 docs/planning/deferred-tables.md。
EXEMPT=$(cat <<'EXEMPT_EOF'
PENDING|rpg_support_credit_accounts|Phase 5 独立 support_credit 账本余额表，落库先行、验签订单服务未接线（宪法 C-012 物理隔离）|docs/planning/deferred-tables.md#21
PENDING|rpg_support_credit_ledger|Phase 5 support_credit 幂等流水表，schema 契约含 idempotency_key 唯一键，写入方未实现|docs/planning/deferred-tables.md#22
PENDING|rpg_market_buy_orders|Phase 4 市场 V2 买单簿，API 模型 MarketOrder 已存在，撮合事务未实现（C-009 单一托管）|docs/planning/deferred-tables.md#23
PENDING|rpg_progression_anomalies|Phase 1 旧档案无法唯一映射时的人工复核清单，迁移写入口未实现，需 [H] 门禁|docs/planning/deferred-tables.md#24
EXEMPT_EOF
)
# W2 已完成接线、应删除的旧豁免（保留一行注释说明为什么删，便于审计追溯）：
#   rpg_outbox              -> JdbcOutboxRegistry / OutboxDispatcher   (W2-D5)
#   rpg_session_leases      -> JdbcSessionLease                        (W2-B6/W2-D5)
#   rpg_community_approvals -> JdbcCommunityService                    (W2-D4, G-103)
#   rpg_season_settlements  -> JdbcSeasonSettlementService             (W2-D1, G-101)

# ---- 1. 从 DDL 抽取表名 ----
TABLES=$(grep -oE 'CREATE TABLE IF NOT EXISTS[[:space:]]+rpg_[a-z_]+' "$DDL_FILE" \
  | awk '{print $NF}' | sort -u)

if [ -z "$TABLES" ]; then
  echo "DDL_CONSUMER_GATE=RED 未从 $DDL_FILE 解析出任何表名（DDL 语法可能变了）" >&2
  exit 2
fi

TABLE_COUNT=$(printf '%s\n' "$TABLES" | wc -l | tr -d ' ')

# ---- 2. 收集待扫描源码文件（排除 DDL / manifest 声明点）----
FILES=$(find plugins tools -path '*/src/main/*' -name '*.java' -type f 2>/dev/null \
  | grep -v '/Database.java$' | grep -v '/SchemaManifest.java$')

# ---- 3. 判定每张表是否被真实消费 ----
UNWIRED=''
for t in $TABLES; do
  hit=0
  for f in $FILES; do
    # SQL 语句里引用该表即算消费者；\b 用 [^a-z_] 模拟词边界，避免前缀误匹配
    if grep -qEi "(select|insert[[:space:]]+into|update|delete[[:space:]]+from|merge[[:space:]]+into)[^;]{0,240}(^|[^a-z_])$t([^a-z_]|\$)" "$f" 2>/dev/null; then
      hit=1
      break
    fi
  done
  if [ "$hit" -eq 0 ]; then
    UNWIRED="$UNWIRED$t
"
  fi
done

# ---- 4. 应用豁免清单 ----
# DDL_GATE_IGNORE_EXEMPT=1 时不套用豁免，用于验证门禁确实会拦下漏接（CI 不设置）
EXEMPT_DECLARED=$(printf '%s\n' "$EXEMPT" | grep -cE '^(PENDING|ABANDONED)\|' || true)
if [ "${DDL_GATE_IGNORE_EXEMPT:-0}" = "1" ]; then
  EXEMPT=""
fi
EXCUSED=''
REAL_UNWIRED=''
if [ -n "$UNWIRED" ]; then
  printf '%s' "$UNWIRED" | while IFS= read -r t; do
    [ -z "$t" ] && continue
    # 豁免条目形如 分类|表名|理由|文档锚点，按表名精确匹配（$t| 或 |$t|）
    line=$(printf '%s\n' "$EXEMPT" | grep -E "^(PENDING|ABANDONED)\|$t\|" || true)
    if [ -n "$line" ]; then
      cls=$(printf '%s' "$line" | cut -d'|' -f1)
      reason=$(printf '%s' "$line" | cut -d'|' -f3)
      anchor=$(printf '%s' "$line" | cut -d'|' -f4)
      if [ "$cls" = "ABANDONED" ]; then
        echo "[已废弃] $t  <- $reason（见 $anchor）"
      else
        echo "[待接线] $t  <- $reason（见 $anchor）"
      fi
    else
      echo "[未接线] $t"
    fi
  done > /tmp/ddl-gate-report.txt
else
  : > /tmp/ddl-gate-report.txt
fi

PENDING_LINES=$(grep '^\[待接线\]' /tmp/ddl-gate-report.txt || true)
ABANDONED_LINES=$(grep '^\[已废弃\]' /tmp/ddl-gate-report.txt || true)
EXCUSED=$(cat /tmp/ddl-gate-report.txt | grep -E '^\[(待接线|已废弃)\]' || true)
UNWIRED_LINES=$(grep '^\[未接线\]' /tmp/ddl-gate-report.txt || true)

# ---- 5. 报告 ----
echo "DDL 真相源: $DDL_FILE"
echo "DDL 表数: $TABLE_COUNT"
echo "豁免清单: $EXEMPT_DECLARED 条已登记（本次生效 $(printf '%s\n' "$EXEMPT" | grep -cE '^(PENDING|ABANDONED)\|' || true) 条；脚本内 EXEMPT，可审计）"
echo ""

if [ -n "$PENDING_LINES" ]; then
  echo "--- 待接线 PENDING（能力有效，消费者晚于 schema 落地）---"
  printf '%s\n' "$PENDING_LINES"
  echo ""
fi

if [ -n "$ABANDONED_LINES" ]; then
  echo "--- 已废弃 ABANDONED（能力被取代/锁死，保留 DDL 不再接线）---"
  printf '%s\n' "$ABANDONED_LINES"
  echo ""
fi

if [ -n "$UNWIRED_LINES" ]; then
  echo "--- 未接线表（声明了但无消费者）---"
  printf '%s\n' "$UNWIRED_LINES"
  n=$(printf '%s\n' "$UNWIRED_LINES" | wc -l | tr -d ' ')
  echo ""
  echo "提示：为上述表接入真实读写消费者，或在脚本 EXEMPT 中登记理由（需评审）。"
  echo "DDL_CONSUMER_GATE=RED UNWIRED=$n"
  exit 1
fi

echo "DDL_CONSUMER_GATE=GREEN"
exit 0
