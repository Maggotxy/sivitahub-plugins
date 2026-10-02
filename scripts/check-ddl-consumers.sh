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

# 各节红灯不立刻退出，只置位；末尾统一决定退出码（见第 8 节）
SKIPPED_GATE=0
export SKIPPED_GATE

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
EXEMPT_EOF
)
# 已接线、应删除的旧豁免（保留一行注释说明为什么删，便于审计追溯）：
#   rpg_outbox                  -> JdbcOutboxRegistry / OutboxDispatcher   (W2-D5)
#   rpg_session_leases          -> JdbcSessionLease                        (W2-B6/W2-D5)
#   rpg_community_approvals     -> JdbcCommunityService                    (W2-D4, G-103)
#   rpg_season_settlements      -> JdbcSeasonSettlementService             (W2-D1, G-101)
#   rpg_support_credit_accounts -> JdbcSupportCreditService                (W6-A1, C-012 独立账本余额)
#   rpg_support_credit_ledger   -> JdbcSupportCreditService                (W6-A1, 幂等流水)
#   rpg_market_buy_orders       -> JdbcMarketBuyOrderService               (W6-A1, Phase 4 买单簿, C-009 单一托管)
#   rpg_progression_anomalies   -> JdbcProgressionAnomalyService           (W6-A1, Phase 1 异常写入口, [H] 复核出口)
#
# 现在 EXEMPT 为空：DDL 表数 79 全部有 src/main 真实消费者。新增豁免必须在本脚本
# 登记分类 + 理由 + 文档锚点，并在 docs/planning/deferred-tables.md 开一节写明判定证据。

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
  # 显式跳过（而不是 exit）：本脚本同时是「机械门禁」与「被自测调用的判据载体」。
  # 直接 exit 会让第 6/7 节的判据根本没机会执行，自测里的夹具（只有探针源码，
  # 第 1 节必然红）就永远看不到 DDL_REGISTRATION_GATE 那一行 —— 门禁在 CI 里
  # 该不该红由各节自己把错误码累加到 SKIPPED_GATE，最后统一体现。
  SKIPPED_GATE=1
fi

# ---- 6. Slash 指令引用门禁 ----
#
# 失败模式：语言包（lang/*.yml）里的 tooltip 承诺「执行 /<cmd> 做某事」，
# 但那个指令从来没有被任何 plugin.yml 注册过 —— 玩家点下去只会得到
# 「Unknown command」。这与 DDL 门禁是同一类病：声明了，但没有消费者。
#
# 判据：语言包里每一次 `/foo` 引用，必须命中某个 plugin.yml 的 commands 键，
# 或命中显式豁免（原版指令 / 第三方插件拥有的指令 / 已知待接线）。
#
# 真实失效样本（W3-A2 实测）：`/claimslist` 与 `/claim` 由 GriefPrevention 拥有，
# 从未出现在本仓任何 plugin.yml 里，但 `guide.help.claims.*tooltip` 承诺了它们，
# 并在同一测试里被判为「Guide 暴露了普通玩家不可用的指令」。
# 落成文件而不是变量：多行 here-doc 存进 shell 变量后再 `grep "^x|"` 在 dash 下
# 实测恒不命中（第一行之外的行都被拆散）⇒ 门禁会恒绿，等于没有门禁。
CMD_EXEMPT_FILE=$(mktemp)
cat > "$CMD_EXEMPT_FILE" <<'CMD_EXEMPT_EOF'
help|原版 /help，由 Paper 内建提供，不在任何 plugin.yml 里
claim|GriefPrevention 拥有（本仓不含该插件，见 docs/PLUGIN_INTEGRATION.md）
claimslist|GriefPrevention 拥有（同上）
trust|GriefPrevention 拥有
containertrust|GriefPrevention 拥有
accesstrust|GriefPrevention 拥有
trustlist|GriefPrevention 拥有
untrust|GriefPrevention 拥有
abandonclaim|GriefPrevention 拥有
CMD_EXEMPT_EOF

REGISTERED_CMDS=''
for y in $(find plugins -path '*/src/main/resources/plugin.yml' -type f 2>/dev/null); do
  REGISTERED_CMDS="$REGISTERED_CMDS$(sed -n '/^commands:/,$p' "$y" \
    | grep -oE '^  [A-Za-z0-9_-]+:' | sed 's/^  //; s/:$//')
"
done
REGISTERED_CMDS=$(printf '%s\n' "$REGISTERED_CMDS" | sed '/^$/d' | sort -u)

CMD_UNKNOWN=''
for lang in plugins/*/src/main/resources/lang/*.yml; do
  [ -f "$lang" ] || continue
  # 只认「命令行首」的引用：`/foo` 前面必须是行首、空白、引号或标点（含中文标点），
  # 后面是行尾/空白/标点。这样 `left/right-click`、`safe/lucky`、`current/next-level`
  # 这类「选项对」不会被误当成指令。匹配后统一用 sed 归一化成裸指令名。
  #
  # 注意：不要用 `grep -oE '...)::'` 想靠 `::` 多输出一次分隔符 —— GNU grep 的 -oE
  # 把 `::` 当普通字符，整个 -oE 会静默匹配不到任何东西，循环体一次都不执行，
  # 门禁就变成恒绿的假门禁（实测踩过，负向对照抓出来的）。
  referenced=$(sed -n 's|^\(.*\)$|\1|p' "$lang" \
    | grep -oE '(^|[[:space:]]|["（(、。；：;,:])/[a-z][a-z0-9_-]+' \
    | grep -oE '/[a-z][a-z0-9_-]+' | sed 's|^/||' | sort -u)
  for cmd in $referenced; do
    printf '%s\n' "$REGISTERED_CMDS" | grep -qx "$cmd" && continue
    grep -q "^$cmd|" "$CMD_EXEMPT_FILE" && continue
    CMD_UNKNOWN="$CMD_UNKNOWN$lang -> /$cmd
"
  done
done

echo "已注册指令数: $(printf '%s\n' "$REGISTERED_CMDS" | sed '/^$/d' | wc -l | tr -d ' ')"
echo "指令豁免清单: $(grep -c '|' "$CMD_EXEMPT_FILE") 条（脚本内 CMD_EXEMPT，可审计）"

if [ -n "$CMD_UNKNOWN" ]; then
  echo ""
  echo "--- 语言包引用了未注册/未豁免的指令 ---"
  printf '%s\n' "$CMD_UNKNOWN" | sed '/^$/d'
  n=$(printf '%s\n' "$CMD_UNKNOWN" | sed '/^$/d' | wc -l | tr -d ' ')
  echo ""
  echo "提示：把 tooltip 指向已注册的指令，或在脚本 CMD_EXEMPT 中登记持有人（需评审）。"
  echo "CMD_REFERENCE_GATE=RED UNKNOWN=$n"
  # 同第 1 节：不立刻退出，置位后继续跑后面几节（末尾统一决定退出码）
  SKIPPED_GATE=1
fi
echo "CMD_REFERENCE_GATE=GREEN"

# ---- 7. DDL 注册门禁（G-302 反向检查）----
#
# 失败模式（W4-A2 实测）：代码里写了 `INSERT INTO rpg_skill_point_grants (...)`，
# 但 DDL 里没有这张表、迁移清单里也没有登记 —— 真机启动后任何授予经验都会以
# 「Table not found」回滚，而编译、单测（测试自带 H2 夹具）与人工评审全都看不到。
# 第 1-5 节只查「DDL 里的表有没有消费者」，方向相反；两者合起来才是双向闭合。
#
# 判据：plugins/*/src/main 与 tools/*/src/main 里出现的每一个 rpg_* 表名字符串，
#       必须能在 DDL 真相源里找到 CREATE TABLE IF NOT EXISTS。
#
# 表名怎么认（避免把索引名 / 列名 / 常量名当成表名）：
#   SQL 写法一（代码里实际使用的）：`FROM <t>` / `INTO <t>` / `UPDATE <t>` /
#     `JOIN <t>` / `DELETE FROM <t>` / `ON <t>`（把 `FROM rpg_x` 命中成
#     `from_local_level` 这类列名的假阳性就此排除）。
#   Java 写法二：整个字符串字面量正好是一个 rpg_* 标识符 —— 但 `idx_rpg_*` /
#     `uq_rpg_*` 是索引名，先按前缀排除掉，否则一条 CREATE INDEX 就会让门禁报
#     「代码引用了不存在的表」。
#
# 关于前缀歧义（最容易写歪的一条规则）：
#   取到的是一个**完整标识符**（词边界由正则的 [A-Za-z0-9_]* 保证），所以
#   `FROM rpg_season_settlement_transitions` 拿到的是全长名字，不会截成
#   `rpg_season_settlement`。也就是说「已知表名的裸前缀」这种写法在数据库方言里
#   虽然合法，但在本仓的源码里并不出现 —— 第一次实现时我按「最长已知表名前缀」
#   消歧，结果 W4-A2 那个 typo（`rpg_skill_point_grants_typo` 恰好以
#   `rpg_skill_point_grants` 开头）被这条消歧规则静默吞掉，门禁恒绿（实测）。
#   现在的判定是**精确匹配 + 索引名前缀白名单**：任何不在 DDL 里的 rpg_* 名字
#   （包括前缀式 typo）一律报红。

# 索引名白名单从 DDL 真相源里现抽，不写死。
#
# 豁免清单见下方 DDL_TABLE_EXEMPT（分类可审计，与第 1 节 EXEMPT 同样的纪律：
# 豁免不是放行，只是把「不接线」变成有理由、有文档锚点的显式状态）。

# 可审计豁免清单：分类|表名|理由|文档锚点
#   PENDING   待接线：表已按设计落库，写入方/消费者在其后的 Phase
#   INDEXONLY DDL 自带消费：表只服务 DDL 内部的 CREATE INDEX 语句
#
# W6-A1（d738e71「接线最后 4 张 PENDING 表并清空门禁豁免」）已把下列表全部接上
# 真实 src/main 消费者，反向豁免随之作废（保留一行注释便于审计追溯）：
#   rpg_support_credit_accounts -> JdbcSupportCreditService            (W6-A1, C-012)
#   rpg_support_credit_ledger   -> JdbcSupportCreditService            (W6-A1, 幂等流水)
#   rpg_market_buy_orders       -> JdbcMarketBuyOrderService           (W6-A1, Phase 4)
#   rpg_progression_anomalies   -> JdbcProgressionAnomalyService       (W6-A1, Phase 1)
#   rpg_chapter_history         -> JdbcChapterService (INSERT INTO)    (既有)
#
# 现在清单为空：反向扫描到的每个 rpg_* 表名都必须能在 DDL 里精确命中。
# 与第 1 节的正向 EXEMPT 同一口径 —— 豁免只把「不接线」变成可审计状态，不是放行。
DDL_TABLE_EXEMPT_FILE=$(mktemp)
cat > "$DDL_TABLE_EXEMPT_FILE" <<'DDL_TABLE_EXEMPT_EOF'
DDL_TABLE_EXEMPT_EOF

# 代码扫描范围：全部 src/main（含测试源码？不含 —— 门禁要拦的是真机会执行的代码；
# 测试自带 H2 夹具，夹具漏建表不会让真机炸）。反过来，夹具里凭空引用不存在的表
# 也不是本门禁的失败模式，故只扫 src/main。
UNIVERSE_FILES_LIST=$(mktemp)
find plugins tools -path '*/src/main/*' -type f \( -name '*.java' -o -name '*.sql' \) 2>/dev/null > "$UNIVERSE_FILES_LIST"
if [ ! -s "$UNIVERSE_FILES_LIST" ]; then
  echo "DDL_CONSUMER_GATE=RED 未找到任何 src/main 源码（扫描根目录可能变了，反向检查会恒绿）" >&2
  exit 2
fi
UNIVERSE_FILE_COUNT=$(wc -l < "$UNIVERSE_FILES_LIST" | tr -d ' ')

# DDL 里出现的索引名（idx_rpg_* / uq_rpg_* / CREATE INDEX 上任意名字）。
# 代码里写索引名是合法的，不能被当成「缺表」。
INDEX_NAMES=$(grep -oE '(CREATE (UNIQUE )?INDEX IF NOT EXISTS[[:space:]]+[A-Za-z_][A-Za-z0-9_]*)' "$DDL_FILE" \
  | awk '{print $NF}' | grep '^rpg_' | sort -u || true)

# SQL 关键词后面所跟的标识符：FROM / INTO / UPDATE / JOIN / DELETE FROM / ON / TABLE。
# ON 只在 `ON <ident>` 命中，且 `ON CONFLICT` / `ON DUPLICATE KEY` 拿到的不是 rpg_*
# 名字，天然被下面的 `grep '^rpg_'` 过滤掉。
SQL_TOKENS=$(grep -hoE '(^|[^A-Za-z_])(FROM|INTO|UPDATE|JOIN|DELETE[[:space:]]+FROM|ON|TABLE)[[:space:]]+[A-Za-z_][A-Za-z0-9_]*' \
  $(cat "$UNIVERSE_FILES_LIST") 2>/dev/null \
  | awk '{print $NF}' | grep '^rpg_' | sort -u || true)

# Java 里整串字符串字面量就是一个 rpg_* 标识符（常量表名 / 夹具表名）
STRING_LITERALS=$(grep -hoE '"rpg_[a-z_]+"' $(cat "$UNIVERSE_FILES_LIST") 2>/dev/null \
  | tr -d '"' | sort -u || true)

# 去掉索引名：判定的是「表」，不是「索引」
REFERENCED=$(printf '%s\n%s\n' "$SQL_TOKENS" "$STRING_LITERALS" \
  | sed '/^$/d' | grep -v -x -F -f /dev/null | sort -u)
if [ -n "$INDEX_NAMES" ]; then
  REFERENCED=$(printf '%s\n' "$REFERENCED" | grep -v -x -F -e "$(printf '%s' "$INDEX_NAMES" | tr '\n' '|' | sed 's/|$//')" || true)
fi
REFERENCED=$(printf '%s\n' "$REFERENCED" | sed '/^$/d' | sort -u)

UNREGISTERED=''
REF_COUNT=0
for table in $REFERENCED; do
  REF_COUNT=$((REF_COUNT + 1))
  # 精确匹配，不做前缀消歧：前缀式 typo（rpg_skill_point_grants_typo）必须报红
  if printf '%s\n' "$TABLES" | grep -qx "$table"; then continue; fi
  line=$(grep -E "^(PENDING|INDEXONLY)\|$table\|" "$DDL_TABLE_EXEMPT_FILE" || true)
  if [ -n "$line" ]; then
    cls=$(printf '%s' "$line" | cut -d'|' -f1)
    reason=$(printf '%s' "$line" | cut -d'|' -f3)
    anchor=$(printf '%s' "$line" | cut -d'|' -f4)
    echo "[反向豁免 $cls] $table  <- $reason（见 $anchor）"
    continue
  fi
  UNREGISTERED="$UNREGISTERED$table
"
done

echo "反向检查扫描文件数: $UNIVERSE_FILE_COUNT"
echo "代码引用的 rpg_* 表名: $REF_COUNT 个（去重后）"
echo "反向豁免清单: $(grep -c '|' "$DDL_TABLE_EXEMPT_FILE") 条（脚本内 DDL_TABLE_EXEMPT，可审计）"

if [ -n "$UNREGISTERED" ]; then
  echo ""
  echo "--- 代码引用了 DDL 里不存在的表（真机会 'Table not found' 回滚）---"
  printf '%s\n' "$UNREGISTERED" | sed '/^$/d' | sed 's/^/  /'
  n=$(printf '%s\n' "$UNREGISTERED" | sed '/^$/d' | wc -l | tr -d ' ')
  echo ""
  echo "提示：在 Database.java 补 CREATE TABLE IF NOT EXISTS，并同步 SchemaManifest.tables()；"
  echo "      若确实是有意落库先行，请在 DDL_TABLE_EXEMPT 登记理由 + 文档锚点（需评审）。"
  echo "DDL_REGISTRATION_GATE=RED UNREGISTERED=$n"
  exit 1
fi
echo "DDL_REGISTRATION_GATE=GREEN"

# ---- 8. 汇总 ----
# 各节在红灯处只置 SKIPPED_GATE，不立刻 exit：这样任何一节红，其它节的判据
# 仍然会被打印出来（一次 CI 跑就能看全，而不是修一个红再等下一轮）；同时
# 末尾的退出码仍然严格由「有没有任何一节红」决定，判据没有放松。
if [ "${SKIPPED_GATE:-0}" -ne 0 ]; then
  echo "DDL_CONSUMER_GATE=RED（有节判红，详见上方各节的 =RED 行）"
  exit 1
fi
echo "DDL_CONSUMER_GATE=GREEN"
exit 0
