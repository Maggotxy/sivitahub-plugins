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

# 可审计豁免清单：表名|理由（tab 前的空格会被忽略）
# 只允许「已规划的未来预留」，禁止拿豁免掩盖本该接线的漏接。
EXEMPT=$(cat <<'EXEMPT_EOF'
rpg_outbox|Phase 8 Transactional Outbox，多服/Session Lease 落地前预留（docs/audits/PHASE7_8_EVIDENCE.md）
rpg_session_leases|Phase 8 跨服 player->node 租约，依赖 Velocity 多服，尚未接入（docs/audits/PHASE7_8_EVIDENCE.md）
rpg_support_credit_accounts|Phase 5 support_credit 独立账本，落库先行、账本服务未接线（docs/audits/PHASE2_3_5_EVIDENCE.md）
rpg_support_credit_ledger|Phase 5 support_credit 独立账本流水，同上，尚无读写消费者（docs/audits/PHASE2_3_5_EVIDENCE.md）
rpg_market_buy_orders|市场 V2 买单簿，Phase 4 退出机制的一部分，撮合未实现
rpg_community_approvals|Phase 6 社区治理双批/继任审批流，治理服务未实现
rpg_season_settlements|Phase 5 软赛季结算事务表，仅状态机注释引用（SeasonSettlementState.java），无持久化消费者
rpg_progression_anomalies|成长异常审计表，Phase 1 dry-run 冻结期预留，写入口未接线
EXEMPT_EOF
)

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
if [ "${DDL_GATE_IGNORE_EXEMPT:-0}" = "1" ]; then
  EXEMPT=""
fi
EXCUSED=''
REAL_UNWIRED=''
if [ -n "$UNWIRED" ]; then
  printf '%s' "$UNWIRED" | while IFS= read -r t; do
    [ -z "$t" ] && continue
    if printf '%s\n' "$EXEMPT" | grep -q "^$t|"; then
      reason=$(printf '%s\n' "$EXEMPT" | grep "^$t|" | sed "s/^$t|//")
      echo "[豁免] $t  <- $reason"
    else
      echo "[未接线] $t"
    fi
  done > /tmp/ddl-gate-report.txt
else
  : > /tmp/ddl-gate-report.txt
fi

EXCUSED=$(grep '^\[豁免\]' /tmp/ddl-gate-report.txt || true)
UNWIRED_LINES=$(grep '^\[未接线\]' /tmp/ddl-gate-report.txt || true)

# ---- 5. 报告 ----
echo "DDL 真相源: $DDL_FILE"
echo "DDL 表数: $TABLE_COUNT"
echo "豁免清单: $(printf '%s\n' "$EXEMPT" | wc -l | tr -d ' ') 条（脚本内 EXEMPT，可审计）"
echo ""

if [ -n "$EXCUSED" ]; then
  echo "--- 已豁免（未来预留）---"
  printf '%s\n' "$EXCUSED"
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
