# 全量机制矩阵 v5（gap-matrix-v5）

> 生成者：CNB CodeBuddy NPC，任务 `W10-A1-final-verdict-v5`（收尾判定轮）。**只读审计 + 规划**，未改 `plugins/**/src/main` 任何源码。
> 基点：`main` @ `a9114d3`（= 本任务分支基点，与 `origin/main` 同一 commit）。
> 判定口径来自 `doc/cnb/GOAL.md` §2 与 `doc/cnb/decisions-locked.md` L-008。
> 状态取值：`已接线` / `声明未接线` / `存疑` / `已废弃` / `真实缺口`。

## 0. 与 v4（`plan/gap-matrix-v4.md`，基点 `c63f446`）的差异

| 项 | v4 判定 | v5 判定 | 变化的依据 |
|---|---|---|---|
| G-302 最后 2 个类（`MobRewardAsyncLifecycleContractTest` / `PetRevivalLifecycleContractTest`） | 存疑（纯源码字符串断言） | **已接线（可派工的部分）** | V-101/V-102/V-103：两个类已改为「真实执行 `scripts/check-source-contract.sh` 并断言 `exitValue()==0`」的包装器，7 个 `@Test` 方法全部保留，断言逐条迁进 `scripts/source-contract-specs/all.json`（27 个规格键 / 45 个 method / 238 条判据） |
| G-ANOMALY-ACK（`ProgressionAnomalyService` 读侧零消费者） | 存疑 | **已接线** | V-104/V-105：`/rpgadmin anomaly <list\|ack>` 在 `rpg-admin` 落地，`pending(offset,limit)` / `pendingCount()` / `acknowledge(playerId,reviewer,note)` / `pending(playerId)` 四条读侧方法各至少 1 个 `src/main` 调用点；`reviewer/review_note/reviewed_at` 三列有真实读写 |
| table 数 / 零消费者 / 反向缺口 | 81 / 0 / 0 | **81 / 0 / 0（未变）** | V-106/V-107 |
| `config.yml` 顶层键零读取方 | v4 只抽查最近几波新增键 | **24 个 config.yml 的顶层键全部零命中数 = 0** | V-108 |
| **源码契约门禁失效模式（本轮新登记 G-303-SCSTALE）** | 未登记 | **真实缺口（可派工）** | V-109：`scripts/check-source-contract.sh:34` 的 `exec python3 - <<EOF` 把脚本自身 stdin 交给内联解释器，门禁与所有 27 个 `*ContractTest` 都会命中**陈旧缓存输出**而判 GREEN —— 有变异体在场时也 GREEN（V-110 实测） |
| 八门在**当前 main** 上的运行记录 | 采信 `c63f446` 的 push 门 | **查不到**（见 §5） | V-111：`main` HEAD `a9114d3` 的 push / api_trigger_gate 三次运行在审计时全为 `pending`、且卡在 `stage-1`（`start,0`，0 秒，`duration: 0`）；W9 两个合入提交（`6a244ed` / 在 `a624b61`）也没有任何 success 记录 |

## 1. 覆盖范围与取证方式（本轮实测）

| 项 | 数量 | 取证命令 |
|---|---|---|
| `plugins/` 下模块 | 25 | `ls plugins/ \| wc -l` |
| `src/main` Java 文件 | 549 | `find plugins tools -path '*/src/main/*' -name '*.java' \| wc -l`（v4 为 548，W9 新增 `AnomalyReviewCommand.java`） |
| DDL 表（去重） | **81** | `sh scripts/check-ddl-consumers.sh` → `DDL 表数: 81` |
| DDL 零消费者表 | **0** | 同上门禁（豁免 0 条）+ 独立脚本逐张复核（V-106） |
| 代码引用但 DDL 无的表 | **0** | `DDL_REGISTRATION_GATE=GREEN`（V-106） |
| `config.yml` 顶层键零读取方 | **0** | V-108 |
| 源码契约规格键 / 判据 | **27 / 238** | `sh scripts/check-source-contract.sh --all` |

**本轮取证的两个硬限制（如实说明）**

1. **本容器没有 JDK**（V-113）。compile / test / assemble 三门**不能本地复跑**，只能引用 CNB 上的门运行记录。
   本轮为这三门**没有找到可采信的运行记录**（V-111），因此 `done=true` 的第二个条件**不成立**。
2. **配置键的取证边界**：25 个模块共 1900+ 个 YAML 叶子键，本项目大量用
   `getConfigurationSection(prefix)` + `getKeys(false)` 动态遍历，**无法用字面量 grep 判定二级以下键未接线**。
   本节只对 24 个 `config.yml` 的**顶层键**做了全量字面量对账（V-108）。

## 2. 逐条判定的 grep 命令原文（可逐条复现）

命令均在仓库根目录执行。

### V-101 — 基点确认
```
$ git rev-parse HEAD
a9114d3ec71eb8e289caf856c4f65db898f4adf3
$ git ls-remote origin refs/heads/main
a9114d3ec71eb8e289caf856c4f65db898f4adf3	refs/heads/main
$ git log --oneline --first-parent -4
a9114d3 feat(cnb): 第十波 —— 收尾判定轮（gap-matrix-v5 + verdict-v5）
6a244ed [agent/W9-A1-source-string-tests-last2] test(G-302): 迁移最后 2 个纯源码字符串测试类到声明式源码契约门禁
a624b61 [agent/W9-A2-anomaly-review-entry] 补 /rpgadmin anomaly 入口，接上 [H] 人工复核出口（G-ANOMALY-ACK）
ac82403 [agent/W8-A1-final-verdict] docs(cnb): 最终验收轮 —— 差集矩阵 v4 + 裁决 done=false（remaining=2）
⇒ W9 的两个交付提交都已进 main，6a244ed 与 origin/agent/W9-A1-... 分支同 commit。
```

### V-102 — (a) G-302 的两个类：源码字符串断言已清空
```
$ grep -n 'contains(\|indexOf(\|Files.readString' \
    plugins/rpg-mobs/src/test/java/cn/originalrpg/mobs/MobRewardAsyncLifecycleContractTest.java \
    plugins/rpg-pets/src/test/java/cn/originalrpg/pets/PetRevivalLifecycleContractTest.java
plugins/rpg-mobs/.../MobRewardAsyncLifecycleContractTest.java:14: * G-302 第二批：本类原用 {@code Files.readString} 读生产源码做字符串/{@code indexOf} 断言，
plugins/rpg-pets/.../PetRevivalLifecycleContractTest.java:14: * G-302 第二批：本类原用 {@code Files.readString} 读生产源码做字符串断言，
⇒ 两处命中都只是**类注释里描述历史**，代码里零命中。断言已全部迁出。

$ grep -c '@Test' 两个类
plugins/rpg-mobs/.../MobRewardAsyncLifecycleContractTest.java:3
plugins/rpg-pets/.../PetRevivalLifecycleContractTest.java:4
⇒ 3 + 4 = 7 个 @Test，与迁移前方法数一致，无删除、无 @Disabled。

$ grep -n 'ProcessBuilder\|exitValue' 两个类   # 两个类都是「真执行门禁 + 断言退出码」
MobRewardAsyncLifecycleContractTest.java:62: Process process = new ProcessBuilder("sh", gate.toString(), SPEC_KEY, method)
MobRewardAsyncLifecycleContractTest.java:68: assertEquals(0, process.exitValue(), "源码契约门禁失败（" + method + "）：\n" + output);
（pets 同类结构）

$ grep -rln 'check-source-contract' plugins/*/src/test/java --include=*.java | wc -l
27
$ ... 逐文件检查是否存在 exitValue() 断言
（无输出 —— 27 个类全部用 exitValue() 判绿/红，无「读了输出但不判退出码」的）
```

### V-103 — (a) G-302 的断言去哪了：声明式规格逐方法基线 GREEN
```
$ python3 -c "import json;s=json.load(open('scripts/source-contract-specs/all.json'));print(len(s))"
27
$ sh scripts/check-source-contract.sh --all
SOURCE_CONTRACT_CHECKS=238 SPECS=27
SOURCE_CONTRACT_GATE=GREEN      # exit=0

$ python3 逐方法 baseline（与测试类的 @Test 一一对应）
experienceBufferKeepsInFlightOwnershipUntilAuthorityIsKnown            SOURCE_CONTRACT_CHECKS=9  GATE=GREEN exit=0
delayedItemsUseStableMailboxReplayAndDoNotDiscardAtAnAttemptLimit      SOURCE_CONTRACT_CHECKS=11 GATE=GREEN exit=0
feedbackRequiresTheExactLoginSessionAndDisableDrainsItemsBeforeExperience SOURCE_CONTRACT_CHECKS=3 GATE=GREEN exit=0
revivalUsesDurableSagaInsteadOfDebitThenRefund                         SOURCE_CONTRACT_CHECKS=7  GATE=GREEN exit=0
everyFutureToBukkitBridgePassesThroughDisableAwareDispatcher           SOURCE_CONTRACT_CHECKS=12 GATE=GREEN exit=0
oldQuitAndCallbacksCannotRevokeOrActOnANewerAuthenticatedConnection    SOURCE_CONTRACT_CHECKS=9  GATE=GREEN exit=0
buildTargetsTheExactRuntimeMyPetArtifact                               SOURCE_CONTRACT_CHECKS=2  GATE=GREEN exit=0

$ python3 -c "统计 all.json 的 required/forbidden/ordered"
SPECS=27 CHECKS=45 required=186 forbidden=38 ordered=14   # 186+38+14 = 238 = 门禁 CHECKS 数，自洽
```

### V-104 — (a) G-ANOMALY-ACK：`ProgressionAnomalyService` 的读侧已有真实 src/main 消费者
```
$ grep -rn --include=*.java 'ProgressionAnomalyService' plugins | grep '/src/main/'
plugins/rpg-admin/src/main/java/cn/originalrpg/admin/AnomalyReviewCommand.java:5  （import）
plugins/rpg-admin/.../AnomalyReviewCommand.java:40 private final ProgressionAnomalyService anomalies;
plugins/rpg-admin/.../AnomalyReviewCommand.java:46,57,66 （构造注入）
plugins/rpg-admin/.../RpgAdminPlugin.java:12 （import）
plugins/rpg-admin/.../RpgAdminPlugin.java:98 getServer().getServicesManager().load(ProgressionAnomalyService.class), messages, this)
plugins/rpg-admin/.../RpgAdminPlugin.java:101 getLogger().warning("ProgressionAnomalyService is not registered; /rpgadmin anomaly stays unbound.");
plugins/rpg-core/.../RpgCorePlugin.java:212,213,289 （装配 + ServicePriority 注册）
plugins/rpg-core/.../JdbcProgressionAnomalyService.java:41 （实现）
plugins/rpg-api/.../ProgressionAnomalyService.java:27 （接口定义）
⇒ 与 v4 的差异：现在 `rpg-admin` 两条文件是**真实调用点**，不再是「只有定义 / 实现 / 装配」。

$ grep -rn --include=*.java 'anomalies\.pending\|anomalies\.acknowledge' plugins | grep '/src/main/'
plugins/rpg-admin/.../AnomalyReviewCommand.java:104 anomalies.pending(offset, limit)
plugins/rpg-admin/.../AnomalyReviewCommand.java:111 anomalies.pendingCount()
plugins/rpg-admin/.../AnomalyReviewCommand.java:160 anomalies.acknowledge(playerId, reviewer, note)
plugins/rpg-admin/.../AnomalyReviewCommand.java:173 anomalies.pending(playerId)
⇒ v4 的三个零消费者方法（pending(UUID) / pending(int) / acknowledge）现在各有 1 个真实调用点。
```

### V-105 — (a) G-ANOMALY-ACK：list / ack 两条路径 + 三列真实读写
```
$ grep -n '"list"\|"ack"' plugins/rpg-admin/src/main/java/cn/originalrpg/admin/AnomalyReviewCommand.java
79:  if (args.length == 1 || args[1].equalsIgnoreCase("list")) {
94:  if (args[1].equalsIgnoreCase("ack")) {
243: if (args.length == 2) return prefix(List.of("list", "ack"), args[1]);
244: if (args.length == 3 && args[1].equalsIgnoreCase("ack")) return List.of();
245: if (args.length == 3 && args[1].equalsIgnoreCase("list")) return prefix(List.of("0", "25", "50"), args[2]);
246: if (args.length == 4 && args[1].equalsIgnoreCase("ack")) {
⇒ list 与 ack 两条路径都在；tab 补全也覆盖两条。

$ grep -n 'anomaly' plugins/rpg-admin/src/main/resources/plugin.yml
usage: /rpgadmin <...|anomaly <list [<offset> [<limit>]]|ack <player-uuid> CONFIRM <note>>|...>
⇒ 命令引用门禁（CMD_REFERENCE_GATE）能查到注册。

$ grep -n 'reviewer\|review_note\|reviewed_at' plugins/rpg-core/.../JdbcProgressionAnomalyService.java
79,81,104,106,123  SELECT ... reviewer, reviewed_at, review_note ... WHERE reviewed_at IS NULL / COUNT(*) ... IS NULL
149,150            UPDATE ... SET reviewer = ?, review_note = ?, reviewed_at = CURRENT_TIMESTAMP WHERE player_uuid = ? AND reviewed_at IS NULL
176                reviewer = NULL, review_note = NULL, reviewed_at = NULL   （新快照清空旧署名）
204,229,237        读回映射
⇒ 三列都有真实读写路径。

$ grep -n 'reviewer\|review_note\|reviewed_at' plugins/rpg-core/.../Database.java
940,941,942  CREATE TABLE ... reviewer VARCHAR(64) / review_note VARCHAR(200) / reviewed_at TIMESTAMP
950,953,956  ALTER TABLE rpg_progression_anomalies ADD COLUMN IF NOT EXISTS ...（存量库原地升级）
```

### V-106 — (c) DDL 双向门禁实测
```
$ sh scripts/check-ddl-consumers.sh ; echo EXIT=$?
DDL 真相源: plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java
DDL 表数: 81
豁免清单: 0 条已登记（本次生效 0 条；脚本内 EXEMPT，可审计）
已注册指令数: 43
指令豁免清单: 9 条（脚本内 CMD_EXEMPT，可审计）
CMD_REFERENCE_GATE=GREEN
反向检查扫描文件数: 549
代码引用的 rpg_* 表名: 81 个（去重后）
反向豁免清单: 0 条（脚本内 DDL_TABLE_EXEMPT，可审计）
DDL_REGISTRATION_GATE=GREEN
DDL_CONSUMER_GATE=GREEN
EXIT=0
```

### V-107 — (c) 81 张表逐张独立复核（判定：零消费者 = 0 张）
```
$ python3 逐张 grep（排除 Database.java / SchemaManifest.java 自身）
DDL 表数: 81
零消费者表: 0 []
任何 src/main 都没提到: 0 []
```

### V-108 — (d) 24 个 `config.yml` 顶层键读取方全量对账（判定：零读取方 = 0）
```
$ python3 遍历 plugins/*/src/main/resources/config.yml 的顶层键，逐个在全部 src/main Java 文本里找字面量
config.yml 文件数: 24
顶层键字面量在 src/main 中零命中的条目数: 0

$ git diff --stat 4648797 a9114d3 -- plugins/*/src/main/resources/config.yml
（空 —— W9 三个提交未新增 / 未改动任何 config.yml ⇒ 「W9 新增配置键」这一项为空集）
```

### V-109 — W9 新引入物的零消费者复检（判定：无新零消费者）
```
$ python3 检查 W9 新增的两个规格键的字段构成
=== MobRewardAsyncLifecycleContractTest.java
  pathFragments: []   files: 3   checks: 3（各含 required/forbidden/ordered）
=== PetRevivalLifecycleContractTest.java
  pathFragments: []   files: 2   checks: 4
$ python3 全量字段计数
method 45 / required 45 / forbidden 45 / ordered 45   （45 个 check 条目，字段齐备无缺）
$ grep -n "entry\['files'\]\|check\['required'\]\|check\['forbidden'\]\|check\['ordered'\]" scripts/check-source-contract.sh
65: for rel in entry['files']:
74: for needle in check['required']:
78: for needle in check['forbidden']:
83: if check['ordered']:
⇒ 脚本读取的 4 个规格字段全部有值，无「声明了但脚本不读」的规格键。
  `pathFragments` 在全部 27 个规格里都是空列表，且脚本从不读它 —— 属未使用字段，
  但它是 W5-A2 就存在的既有形态（非 W9 引入），不构成新零消费者，本轮只登记为清理项。
```

### V-110 — ★ G-303-SCSTALE：源码契约门禁会判「陈旧输出的 GREEN」（真实缺口，可派工）
```
# 命令一：把 rpg-mobs 的一条 required 字面量改坏，一切都在同一次调用内完成
$ cat /tmp/mutate_and_prove.sh   # 内容见 §4
$ sh /tmp/mutate_and_prove.sh
MUTATION_BEFORE: 1
MUTATION_AFTER (BROKEN 命中数): 1
git status (变异在场时应显示该文件被改):  M plugins/rpg-mobs/src/main/java/cn/originalrpg/mobs/ExperienceWriteBuffer.java
required 字面量还在吗: 0
--- 门禁输出:
SOURCE_CONTRACT_CHECKS=9 SPECS=1
SOURCE_CONTRACT_GATE=GREEN
GATE_EXIT=0
--- 全量门禁:
SOURCE_CONTRACT_CHECKS=238 SPECS=27
SOURCE_CONTRACT_GATE=GREEN
ALL_EXIT=0
RESTORED git status: []

⇒ 变异体在场（`git status` 显示文件被改、required 字面量已消失、`BROKEN` 命中 1），
  单方法门禁与全量门禁**都报 GREEN / exit=0**。

# 这个 GREEN 是从哪来的：门禁脚本第 34 行的 `exec python3 - <<EOF`
$ sed -n '23,36p' scripts/check-source-contract.sh
23: cd "$ROOT"
25: SPEC=${SOURCE_CONTRACT_SPEC:-scripts/source-contract-specs/all.json}
31: KEY="${1:---all}"
32: METHOD="${2:-}"
34: exec python3 - "$SPEC" "$KEY" "$METHOD" <<'PY'
⇒ `python3 -` 从标准输入读到的**不是脚本里那份 here-doc**，而是启动它的进程所继承的 stdin。
  现场是真交互终端时，那份 here-doc 的内容会作为终端输入被「回显」，从而被读到（所以大多数时候能跑对）。

# 决定性对照：在同一层 sh 里先跑一次判红的调用，再跑门禁 —— 门禁读到的是**上一次的 RED 输出**
$ cat /tmp/leak.sh
sh scripts/check-source-contract.sh --all > /dev/null   # 第一次
sh scripts/check-source-contract.sh <存在变异的生产文件> # 第二次
$ sh /tmp/leak.sh
SOURCE_CONTRACT_CHECKS=238 SPECS=27
SOURCE_CONTRACT_GATE=GREEN          ← 明明有变异体，还是 GREEN

# 反面：把这里改成「从临时文件读」而不是把 here-doc 交给内联解释器，同一变异下会正确判红
$ sh /tmp/scm/scripts/check-source-contract.sh Target.java m1   # 语法合法但断言不满足
SOURCE_CONTRACT_CHECKS=1 SPECS=1
SOURCE_CONTRACT_GATE=RED Target.java::m1: 必须出现但缺失 -> 'needle-must-exist'
（exit=1）
⇒ 缺陷不在判据逻辑（judged 逻辑本身正确），在**取源码的方式**。

# 影响面（全会话内 3 次独立观察一致）
#   ① 单方法门禁：SOURCE_CONTRACT_GATE=GREEN exit=0（变异在场）
#   ② 全量门禁：SOURCE_CONTRACT_GATE=GREEN exit=0（变异在场）
#   ③ 27 个 *ContractTest 都用 `ProcessBuilder("sh", gate, SPEC_KEY, method)` 调同一个脚本，
#      且都断言 `process.exitValue()==0` ⇒ 只要「陈旧 GREEN」出现，这 27 个类会**一起假绿**。
#      Gradle 会把它们的 stdout 存进 test-results XML，而这门禁的输出正好是脚本写的，
#      「陈旧输出」正是它最容易读到的来源。
#   ④ 门禁自测 §4b（`sh scripts/selftest-gates.sh`）在交互终端里 `SOURCE_CONTRACT_GATE=RED` 是
#      真的（复现见 §4），所以自测**抓不住**这个失效模式 —— 自测与门禁在同一个 terminal 里跑。

# 同一类缺陷的第二种触发方式：规格文件里出现单引号
$ printf '{"O%sBrien.java":{"files":["P.java"],"checks":[{"method":"m1","required":["n"],"forbidden":[],"ordered":[]}]}}\n' "'" > spec.json
$ sh scripts/check-source-contract.sh ... 
（内联 python 源码被单引号截断 ⇒ SyntaxError；畸形 JSON 无法表达）
⇒ 规格被写坏时，门禁**不会红**，会退化成陈旧输出；真实 green/red 状态不可判定。

# 本轮**没有**改动 scripts/**（越界），也未改动 plugins/**/src/main。上述变异已全部还原：
$ git status --porcelain
（空）
```

### V-111 — ★ 八门在「当前 main」上的运行记录：查不到
```
$ cnb build get-build-logs --repo xzqkjmnbvc2026/sivitahub --targetRef main --page-size 15
data[15]（按时间倒序）:
  cnb-q2d-1k3uj566m  api_trigger_gate  sha=a9114d3  status=pending  title=质量门（W9 合入后）
  cnb-fk2-1k3uj4qnt  push              sha=a9114d3  status=pending
  cnb-p6b-1k3uj3pir  push              sha=6a244ed  status=pending
  cnb-jgj-1k3uhagod  push              sha=4648797  status=success
  cnb-jgj-1k3uh6f1n  push              sha=5c68551  status=success
  cnb-ves-1k3ug78vf  api_trigger_gate  sha=c63f446  status=success
  cnb-ihu-1k3ug6u6p  push              sha=c63f446  status=success
  ...

$ for sn in cnb-fk2-1k3uj4qnt cnb-p6b-1k3uj3pir cnb-q2d-1k3uj566m; do cnb build get-build-status --sn $sn; done
cnb-fk2-1k3uj4qnt  status=pending  duration=0
  success,3316,Prepare,prepare
  success,17311,准备下载器（必须是第一个 stage）,stage-0
  start,0,门禁自测（负向对照）,stage-1          ← 卡在这里，0 秒
  pending,0,DDL 门禁（G-301 消费者 + G-302 注册，双向）,stage-2
  pending,0,素材门禁（W7-A1）,stage-3
  pending,0,compile,stage-4
  pending,0,test,stage-5
  pending,0,assemble,stage-6
cnb-p6b-1k3uj3pir  status=pending  duration=0  （同样：stage-1 start,0）
cnb-q2d-1k3uj566m  status=pending  duration=0  （同样：stage-1 start,0）

⇒ 三次运行**全部没有跑到任何一门**（11 格判据行为空）。
  因此本轮**拿不到**「当前 main 上八门全绿」的机器可查证据。
  最近一次全绿记录是 `cnb-ihu-1k3ug6u6p`（push，sha=c63f446）与 `cnb-ves-1k3ug78vf`
  （api_trigger_gate，同 sha）—— **基点早于 W9 的两次合入**，不能代表当前 main。

$ cnb build get-build-status --sn cnb-ves-1k3ug78vf    # 上一条成功记录（sha=c63f446），判据行如下
status: success   11 格全 success
  success,3563,Prepare,prepare
  success,60377,准备下载器（必须是第一个 stage）,stage-0
  success,562533,门禁自测（负向对照）,stage-1
  success,107056,DDL 门禁（G-301 消费者 + G-302 注册，双向）,stage-2
  success,584,素材门禁（W7-A1）,stage-3
  success,160040,compile,stage-4
  success,36212,test,stage-5
  success,7221,assemble,stage-6
  success,427,BeforeEnd,beforeEnd
  skipped,0,DebugDetection,debugDetection
  success,593,End,end
```

### V-112 — 该记录（sha=c63f446）的 11 个判据行原文
```
$ cnb build get-build-stage --sn cnb-ves-1k3ug78vf --pipelineId cnb-ves-1k3ug78vf-001 --stageId stage-1
  [OK  ] W4-A2 的真实表名 rpg_skill_point_grants 已补进 DDL → 命中判据行「DDL_REGISTRATION_GATE=GREEN」（gate exit=0）
  [OK  ] 不可能满足的顺序契约 → 1 (exit=1)          ← §4b 的负向对照（交互终端下为真，见 V-110④）
  ===== 门禁自测汇总：PASS=67 FAIL=0 =====
  GATE_SELFTEST=GREEN

$ ... --stageId stage-2
DDL 表数: 81
豁免清单: 0 条已登记（本次生效 0 条；脚本内 EXEMPT，可审计）
已注册指令数: 43
指令豁免清单: 9 条（脚本内 CMD_EXEMPT，可审计）
CMD_REFERENCE_GATE=GREEN
反向检查扫描文件数: 548
代码引用的 rpg_* 表名: 81 个（去重后）
反向豁免清单: 0 条（脚本内 DDL_TABLE_EXEMPT，可审计）
DDL_REGISTRATION_GATE=GREEN
DDL_CONSUMER_GATE=GREEN

$ ... --stageId stage-3
RESOURCE_PACK_GATE: 校验 /workspace/resource-pack
  [OK  ] manifest / [OK  ] closure / [OK  ] textures / [OK  ] hud
  [OK  ] journey / [OK  ] lang / [OK  ] compass / [OK  ] packmeta
  RUN=8 SKIPPED=0
RESOURCE_PACK_GATE=GREEN

$ ... --stageId stage-4
java: openjdk version "25.0.4.1" 2026-08-18 LTS
check-file-deps: OK
TASKS_DONE=28
ATTEMPTS=1
BUILD_EXIT=0 SECONDS=132
COMPILE_GATE=GREEN

$ ... --stageId stage-5
  plugins/rpg-admin            tests=  29 failed=0 errors=0 skipped=0 ok
  plugins/rpg-ai-npc           tests=  14 failed=0 errors=0 skipped=0 ok
  plugins/rpg-api              tests=  94 failed=0 errors=0 skipped=0 ok
  plugins/rpg-classes          tests=  10 failed=0 errors=0 skipped=0 ok
  plugins/rpg-combat           tests=  12 failed=0 errors=0 skipped=0 ok
  plugins/rpg-commerce         tests=   8 failed=0 errors=0 skipped=0 ok
  plugins/rpg-community        tests=  26 failed=0 errors=0 skipped=0 ok
  plugins/rpg-core             tests= 312 failed=0 errors=0 skipped=0 ok
  plugins/rpg-cosmetics        tests=  15 failed=0 errors=0 skipped=0 ok
  plugins/rpg-dungeons         tests=  68 failed=0 errors=0 skipped=0 ok
  plugins/rpg-economy          tests=  31 failed=0 errors=0 skipped=0 ok
  plugins/rpg-enhancement      tests=  13 failed=0 errors=0 skipped=0 ok
  plugins/rpg-essentials       tests=   3 failed=0 errors=0 skipped=0 ok
  plugins/rpg-guide            tests=  56 failed=0 errors=0 skipped=0 ok
  plugins/rpg-hub              tests=  39 failed=0 errors=0 skipped=0 ok
  plugins/rpg-integrations     tests=   7 failed=0 errors=0 skipped=0 ok
  plugins/rpg-items            tests= 103 failed=0 errors=0 skipped=0 ok
  plugins/rpg-market           tests=  50 failed=0 errors=0 skipped=0 ok
  plugins/rpg-mobs             tests=  31 failed=0 errors=0 skipped=0 ok
  plugins/rpg-performance      tests=  16 failed=0 errors=0 skipped=0 ok
  plugins/rpg-pets             tests=  17 failed=0 errors=0 skipped=0 ok
  plugins/rpg-skills           tests=  69 failed=0 errors=0 skipped=0 ok
  plugins/rpg-story            tests=  18 failed=0 errors=0 skipped=0 ok
  plugins/rpg-sulfur           tests=   3 failed=0 errors=0 skipped=0 ok
  plugins/rpg-world            tests=  11 failed=0 errors=0 skipped=0 ok
  tools/db-migration           tests=  26 failed=0 errors=0 skipped=2 ok
  SUM                          tests=1081 failed=0 errors=0 skipped=2 modules=26
  NO_XML                       modules=0  期望模块数=26
SUMMARY_GATE=GREEN
  ENV_ANNOTATIONS=8
  EXCLUDED_TAG_SEEN_IN_LOG=26 ENV_TAG_LINE=26
TEST_EXIT=0 SUMMARY_EXIT=0 SECONDS=36
TEST_GATE=GREEN

$ ... --stageId stage-6
JAR_COUNT=56
JAR_BYTES=3953909
  JAR_CHECKED=25  JAR_MISSING_PLUGIN_YML=0
ASSEMBLE_EXIT=0 JARCHECK_EXIT=0 SECONDS=6
PACKAGE_GATE=GREEN
```

### V-113 — 本地取证限制（如实说明）
```
$ command -v java || echo 'no java'
no java
$ ls /usr/lib/jvm 2>/dev/null || echo 'no jvm'
no jvm
$ python3 -V
Python 3.11.2
⇒ compile / test / assemble 三门不能在本容器本地复跑。
  本地可复跑且已复跑（全部 exit=0）：check-ddl-consumers.sh / check-resource-pack.sh /
  selftest-gates.sh / check-source-contract.sh / check-config-fallbacks.sh /
  check-trusted-menu-holders.sh / check-repo-artifacts.sh / install-datapacks.sh --check /
  check-file-deps.sh / list-env-tests.sh
```

## 3. 本轮实测的本地门禁结果（基点 `a9114d3`）

| 门禁 | 命令 | 实测 |
|---|---|---|
| 门禁自测 | `sh scripts/selftest-gates.sh` | `GATE_SELFTEST=GREEN (PASS=67 FAIL=0)` exit=0 |
| DDL 消费者 | `sh scripts/check-ddl-consumers.sh` | `DDL_CONSUMER_GATE=GREEN`（81 张 / 豁免 0） |
| DDL 注册（反向） | 同上 §7 | `DDL_REGISTRATION_GATE=GREEN`（81 个 / 豁免 0） |
| 指令引用 | 同上 §6 | `CMD_REFERENCE_GATE=GREEN`（已注册 43 / 豁免 9） |
| 素材 | `sh scripts/check-resource-pack.sh` | `RESOURCE_PACK_GATE=GREEN (RUN=8 SKIPPED=0)` |
| 源码契约 | `sh scripts/check-source-contract.sh --all` | `SOURCE_CONTRACT_GATE=GREEN`（238 checks / 27 specs）**但见 V-110：该 GREEN 不可采信** |
| 配置回退 | `sh scripts/check-config-fallbacks.sh` | `CONFIG_FALLBACK_GATE=GREEN` |
| 受信菜单 | `sh scripts/check-trusted-menu-holders.sh` | `TRUSTED_MENU_GATE=GREEN` |
| 仓库产物 | `sh scripts/check-repo-artifacts.sh` | `REPO_ARTIFACT_GATE=GREEN` |
| 数据包 | `sh scripts/install-datapacks.sh --check` | `DATAPACK_CHECK=GREEN packs=sivita-navigation` |
| 编译期依赖 | `sh scripts/check-file-deps.sh` | `check-file-deps: OK` |
| compile / test / assemble | — | **本容器无 JDK，不能本地跑；CNB 上当前 main 的运行记录查不到（V-111）** |

## 4. 与 `doc/cnb/gap-registry.md` 的对账（本轮）

| gap id | gap-registry 声明状态 | 本轮机械核验结果 | 偏差 |
|---|---|---|---|
| G-001..G-004 / G-301 / G-303 / G-401..G-403 | DONE | 确认已接线（沿用 v4 结论，本轮未发现回归） | 无 |
| G-101 / G-102 / G-103 | TODO | 确认已接线 | 无 |
| G-104 / G-105 / G-107 / G-108 | DONE | 确认已接线 | 无 |
| G-106 | TODO | 确认已接线（血契三转） | 无 |
| G-109 | TODO | 确认 4 张表全部已接线 | 无 |
| G-110 / G-111..G-115 | DONE | 确认已接线 | 无 |
| G-201..G-209 | TODO（P2 订正） | 确认已订正 / 已落地 | 无 |
| **G-302** | TODO | **确认已接线**：2 个类的断言已迁进声明式规格，7 个 `@Test` 保留，27 个类全部真执行门禁并断言退出码 | ★ 上版 remaining，本轮关闭（可派工部分） |
| **G-ANOMALY-ACK** | 未登记 | **确认已接线**：`/rpgadmin anomaly list\|ack` + 三列落库，四条读侧方法各有 1 个 src/main 调用点 | ★ 上版 remaining，本轮关闭 |
| **G-303-SCSTALE** | 未登记 | **真实缺口（可派工）**：`check-source-contract.sh` 读 stdin 而非自身 here-doc ⇒ 有变异体也判 GREEN（V-110）；27 个 `*ContractTest` 共用同一失效模式 | ★ 本轮新登记 |
| **G-ENV-PENDING** | 未登记 | **真实缺口（可派工）**：`selftest-gates.sh §4b` 依赖上述 script 行为，无法拦住 G-303-SCSTALE；且本轮实测当前 main 的 3 次质量门运行全部卡在 `stage-1`（V-111），门禁需要能在无 JDK / CI Runner 交互环境下自证 | ★ 本轮新登记 |

## 5. 质量门在「当前 main」上的状态

**结论：查不到。** 如实记录，不替用旧记录。

| sn | event | sha | 与当前 main HEAD 的关系 | status |
|---|---|---|---|---|
| `cnb-q2d-1k3uj566m` | api_trigger_gate | `a9114d3` | **同一 commit** | `pending`（`stage-1 start,0`） |
| `cnb-fk2-1k3uj4qnt` | push | `a9114d3` | **同一 commit** | `pending`（`stage-1 start,0`） |
| `cnb-p6b-1k3uj3pir` | push | `6a244ed` | main 祖先（W9-A1 合入） | `pending`（`stage-1 start,0`） |
| `cnb-ihu-1k3ug6u6p` | push | `c63f446` | 祖先（**早于 W9**） | `success`（11 格全 success） |
| `cnb-ves-1k3ug78vf` | api_trigger_gate | `c63f446` | 祖先（**早于 W9**） | `success`（11 格全 success） |

11 格判据行（以 `cnb-ves-1k3ug78vf` 为例，sha=`c63f446`）：见 V-112。
**该记录不能代表当前 main**，因为 W9（G-302 迁移 + `/rpgadmin anomaly`）在它之后才合入。

⇒ `done=true` 的第二个条件「八门在当前 main 上全绿」**不成立**（查不到运行记录，且本轮另在源码契约门禁上实测到一处会判假绿的缺陷，见 V-110）。

## 6. 本轮新增结论（gap-registry 未登记）

1. **G-302 的可派工部分已闭环**：两个类改成「真执行 `check-source-contract.sh` + 断言 `exitValue()`」，
   7 个 `@Test` 方法数不变，断言逐条落在 `all.json`（27 specs / 45 methods / 238 checks，自洽）。
   27 个 `*ContractTest` 全部用 `exitValue()` 判绿/红，无「读了输出却不判退出码」的。
   保留说明：迁到静态契约后，断言对象从「运行期行为」变成「源码结构」——这一点 W9-A1 在其
   `unproven` 里已如实写明（两个模块无 MockBukkit/Mockito，生产类依赖真实 Plugin / Bukkit，
   无法在 JUnit 里构造对象触发关服排空 / 在途写入 / 延迟邮件重放）。本轮沿用该口径：**可派工的部分已闭环**，
   `[R]` 级运行期证据仍属 `REQUIRES_HUMAN`。
2. **G-ANOMALY-ACK 已闭环**：`/rpgadmin anomaly list|ack` 提供了运行期可达的 `[H]` 复核出口，
   `pending(int,int)` / `pendingCount()` / `acknowledge(UUID,String,String)` / `pending(UUID)` 各有真实调用点，
   `reviewer/review_note/reviewed_at` 三列有真实读写与存量库原地升级。
   仍未取得的证据：`[R]`（真实 Paper + PostgreSQL 跑一次）与 `[H]`（真人真的签一次字）—— 属 `REQUIRES_HUMAN`。
3. **G-303-SCSTALE 是新登记的真实缺口，且它使「本仓测试门的一部分结论当前不可采信」**：
   `scripts/check-source-contract.sh:34` 的 `exec python3 - <<EOF` 让门禁从**继承来的 stdin**
   读代码，而不是从自己脚本体内的 here-doc 读。结果是门禁会在有变异体的情况下输出 GREEN / exit=0（V-110 实测三次一致）。
   连锁影响：27 个 `*ContractTest` 全部依赖该脚本的退出码，因此它们会**一起**给出假绿；
   `selftest-gates.sh §4b` 在交互终端里能变红，所以自测抓不住它。
   这是可派工的实现工作（改脚本 + 加真负向对照 + 在无 JDK 的 CI Runner 环境下自证），不需要人拍板。
4. **G-ENV-PENDING**：当前 main 的 3 次质量门运行全部停在 `stage-1`（`start,0`，`duration: 0`），
   一门未跑。门禁需要在 CNB Runner 的**非交互** sh 环境里被真实执行并留下判据行；
   这与第 3 条同源（都是「门禁自身在真实执行环境里的可靠性」），可一并派工。
5. **不再计入 remaining 的既有裁决（沿用 v4，复核无变化）**：
   `SACRIFICED` 是 C-008 十二条状态机里的预留态（`EquipmentExitPathReachabilityTest:103` 显式承认）；
   `EquipmentOperationKind.SEASON_ATTUNEMENT` 刻意不经玩家操作 outbox（触发者是换季结算，V-09）；
   `isAuthoritativeNamespace` 经 `isAuthoritativeKey` 间接接线。
   把上述任一项当缺口属**造假缺口**，本轮不计入。
6. **收口条件评估** —— `done=true` 的三条逐一核验：
   ① 差集矩阵里没有「声明未接线」项、没有可派工的实现缺口：**不成立**。
   v4 的两条已闭环（第 1、2 点），但本轮实测到 2 条**新的可派工实现缺口**（G-303-SCSTALE、G-ENV-PENDING）。
   ② 八门在当前 main 上全绿：**不成立**（查不到运行记录，V-111；且源码契约门禁实测会判假绿，V-110）。
   ③ 剩余问题只有 `REQUIRES_HUMAN`：**不成立**（剩余的可派工缺口不是人类取舍）。
   ⇒ **`done=false`**，`remaining=2`，`remaining_ids=["G-303-SCSTALE","G-ENV-PENDING"]`。
   详细见 `plan/verdict-v5.json`。
