# 全量机制矩阵 v6（gap-matrix-v6）

> 生成者：CNB CodeBuddy NPC，任务 `W11-A1-recheck-and-final-verdict`（**复核 + 收尾判定轮**）。
> 本轮只做两件事：(1) 用可复现步骤复核 verdict-v5 的**两条 remaining**；(2) 复查 W9 新增物有无零消费者。
> 未改 `plugins/**/src/main` 任何源码（`git status --short` 为空）。
> 状态取值：`已接线` / `声明未接线` / `存疑` / `已废弃` / `真实缺口` / `假缺口（撤掉）`。

## 0. 本轮结论（先给判定，再给证据）

| 项 | verdict-v5 判定 | v6 判定 | 依据 |
|---|---|---|---|
| **G-303-SCSTALE**（声称 `check-source-contract.sh` 的 `exec python3 - <<'PY'` 从继承 stdin 读代码 ⇒ 27 个 `*ContractTest` 一起假绿） | 真实缺口 | **假缺口（撤掉）** | §1：5 种 stdin 形态 + 2 种投毒形态**全部 RED / exit=1**；27 个包装器 45 次调用的 SPEC_KEY 与 method **全部命中规格**（0 处空跑）。见 V-30..V-36。 |
| **G-ENV-PENDING**（声称三次质量门都卡在 `stage-1 start,0`，拿不到判据行） | 真实缺口 / 无法取证 | **假缺口（时序假象）** | §2：`GET build/status` 实查。**当前 main HEAD `6d12e03` 的 push 门 `sn=cnb-kb4-1k3uk7cro` = success，八门判据行齐全且非零耗时**。`start,0` 是**在跑中**阶段的正常显示（V-37..V-39）。 |

⇒ 差集矩阵「声明未接线」= 0；「可派工的实现缺口」= 0；八门在当前 main 上全绿（有 sn 与判据行）。
⇒ `plan/verdict-v6.json`：`done=true`、`remaining=0`。

## 1. G-303-SCSTALE 复核（判定：假缺口）

### 1.1 被质疑的代码

`scripts/check-source-contract.sh:34`（原文）：

```sh
exec python3 - "$SPEC" "$KEY" "$METHOD" <<'PY'
```

verdict-v5 的推理是：`exec` 之后 python 从**继承来的 stdin** 读程序体，于是门禁可能执行了调用方喂进来的代码，而不是 here-doc 里的检查逻辑。

### 1.2 反证实验（命令原文 + 实际输出）

**实验一：制造变异体（把规格里第一条 required 字面量替换掉）**

```
$ F=plugins/rpg-classes/src/main/java/cn/originalrpg/classes/RpgClassesPlugin.java
$ grep -n "Bukkit.getPlayer(player.getUniqueId()) == player" "$F"
743:        return player != null && player.isOnline() && Bukkit.getPlayer(player.getUniqueId()) == player

$ python3 - "$F" <<'PY'   # 只替换 1 处，写盘
... t.replace("Bukkit.getPlayer(player.getUniqueId()) == player","/*MUTATED*/") ...
replaced occurrences: 1

$ grep -n "MUTATED" "$F"        # 证明变异真的落盘
743:        return player != null && player.isOnline() && /*MUTATED*/
$ grep -c "Bukkit.getPlayer(player.getUniqueId()) == player" "$F"
0
```

**实验二：5 种 stdin 形态下的门禁退出码（不用管道取退出码）**

```
### FORM 1: 继承 stdin（= 包装器 ProcessBuilder 未 redirectInput 的形态）
$ sh scripts/check-source-contract.sh --all
SOURCE_CONTRACT_CHECKS=238 SPECS=27
SOURCE_CONTRACT_GATE=RED ClassAsyncLifecycleContractTest.java::asynchronousClassUiRejectsOldConnectionsAndPluginShutdown: 必须出现但缺失 -> 'Bukkit.getPlayer(player.getUniqueId()) == player'
EXIT1=1

### FORM 2: 单 spec key + method（= 包装器 assertContractHolds(method) 的确切调用形态）
$ sh scripts/check-source-contract.sh plugins/rpg-classes/src/test/java/cn/originalrpg/classes/ClassAsyncLifecycleContractTest.java asynchronousClassUiRejectsOldConnectionsAndPluginShutdown
SOURCE_CONTRACT_CHECKS=18 SPECS=1
SOURCE_CONTRACT_GATE=RED ClassAsyncLifecycleContractTest.java::asynchronousClassUiRejectsOldConnectionsAndPluginShutdown: 必须出现但缺失 -> 'Bukkit.getPlayer(player.getUniqueId()) == player'
EXIT2=1

### FORM 3: stdin 是 /dev/null
$ sh scripts/check-source-contract.sh --all < /dev/null
... SOURCE_CONTRACT_GATE=RED ...
EXIT3=1

### FORM 4: stdin 是空管道
$ true | sh scripts/check-source-contract.sh --all
... SOURCE_CONTRACT_GATE=RED ...
EXIT4=1

### FORM 5: stdin 有别的数据
$ echo "print('HIJACKED')" | sh scripts/check-source-contract.sh --all
... SOURCE_CONTRACT_GATE=RED ...   ← 打印的是门禁自己的判据行，不是 HIJACKED
EXIT5=1
```

**实验三：投毒（专测「stdin 是否能 hijack 掉 here-doc」）**

```
$ printf "import json,sys\nprint('SOURCE_CONTRACT_GATE=GREEN');sys.exit(0)\n" | sh scripts/check-source-contract.sh --all
SOURCE_CONTRACT_CHECKS=238 SPECS=27
... SOURCE_CONTRACT_GATE=RED ...
HIJACK_VALID_PY_EXIT=1

$ yes "print('GREEN')" | head -100000 | sh scripts/check-source-contract.sh --all
HIJACK_BIG_STDIN_EXIT=1
... SOURCE_CONTRACT_GATE=RED ...
```

⇒ 即使喂进一段**自称 GREEN 并 sys.exit(0) 的合法 python 程序**，门禁仍执行 here-doc 里的检查逻辑并 RED。`exec python3 - <<'PY'` 的 here-doc 会以 `dup2` 装上临时文件作为 stdin，**完整覆盖**继承来的 stdin。

### 1.3 27 个包装器是否真有「空跑」（vacuous）风险？

`*ContractTest` 的 `assertContractHolds(method)` 走 `new ProcessBuilder("sh", gate, SPEC_KEY, method).directory(root).redirectErrorStream(true).start()`，**没有** `redirectInput` ⇒ 等价 FORM 1；且它在末尾 `assertEquals(0, process.exitValue(), ...)` ⇒ 门禁 RED 即测试 RED。

逐调用核对（脚本化，命令见 V-35）：

```
wrapper files using gate: 27
assertContractHolds calls: 45
mismatches: 0
```

⇒ 27 个类 / 45 次调用，SPEC_KEY 与 method **全部命中规格**（`spec[key]['checks']` 里存在该 method）。**没有任何一次是跳过全部 check 的空跑**。

### 1.4 门禁自身有负向对照

```
$ sh scripts/selftest-gates.sh
== 4b. source-contract 门禁必须能真的变红（负向对照） ==
  [OK  ] 不可能满足的顺序契约 → 1 (exit=1)
  [OK  ] 真实顺序契约 → 0 (exit=0)
  [OK  ] 禁止片段命中 → 1 (exit=1)
  [OK  ] 禁止片段未命中 → 0 (exit=0)
===== 门禁自测汇总：PASS=67 FAIL=0 =====
GATE_SELFTEST=GREEN
```

### 1.5 撤销变异 + 复原验证

```
$ cp /tmp/RpgClassesPlugin.java.bak plugins/rpg-classes/src/main/java/cn/originalrpg/classes/RpgClassesPlugin.java
$ git status --short          # 空
$ grep -c "Bukkit.getPlayer(player.getUniqueId()) == player" "$F"
1
$ grep -c "MUTATED" "$F"
0
$ sh scripts/check-source-contract.sh --all
SOURCE_CONTRACT_CHECKS=238 SPECS=27
SOURCE_CONTRACT_GATE=GREEN
RESTORED_EXIT=0
```

### 1.6 上一轮为什么会看到 GREEN / exit=0（机制解释）

本轮实测证明门禁**不会**假绿。上一轮的 GREEN 观测到三处最可能的来源，逐条已排除/定位：

| 候选原因 | 本轮验证 | 结论 |
|---|---|---|
| `sh script \| tail -5; echo $?` 取到的是 **tail** 的退出码（恒 0） | V-36：`sh ... \| tail -2; echo $?` 打印 `TAIL_EXIT=0`，而同一命令写文件后 `REAL_EXIT=1` | **最可能的坑**：`tail` 恒 0 ⇒ 把 RED 误读成 GREEN/exit=0 |
| 变异没有真正落盘 | 实验一：替换后 `grep -c MUTATED = 1`、原字面量 `= 0` | 变异确实落盘；若上一轮用 `git stash`/在别的 shell 里、或改了另一份 worktree，则变异不在被跑的那棵树上 |
| 跑的是别的 spec key | FORM 2（单 key）与 FORM 1（`--all`）都 RED | 只要 method 命中规格，任何 key 口径都能变红 |
| 门禁有「缺 method 就 0 check 仍 GREEN」的表面 | V-38：传一个**不存在的 method** → `SOURCE_CONTRACT_CHECKS=0 SOURCE_CONTRACT_GATE=GREEN exit=0` | 这是**门禁真实存在的表面**，但 27 个包装器传的 method **全部存在**（V-35），所以不构成假绿；作为 W11 的观察项记录在 §3 |

**判定：G-303-SCSTALE 不成立，撤掉。**

## 2. G-ENV-PENDING 复核（判定：时序假象，撤掉）

### 2.1 查询方式

```
$ cnb build get-build-logs --repo xzqkjmnbvc2026/sivitahub --targetRef main --page-size 6
```

### 2.2 当前 main HEAD 的门（`sn=cnb-kb4-1k3uk7cro`）

```
sha: 6d12e0301120e6a6c21dd8b03599d0c009ad497c   （= origin/main HEAD，同一 commit）
event: push
status: success
duration: 890001
```

`cnb build get-build-status --repo xzqkjmnbvc2026/sivitahub --sn cnb-kb4-1k3uk7cro`：

```
status: success
pipelinesStatus:
  "cnb-kb4-1k3uk7cro-001":
    name: 全量质量门（编译 → 测试 → 打包）
    status: success
    stages[11]{status,duration,name,id}:
      success,3960,Prepare,prepare
      success,27671,准备下载器（必须是第一个 stage）,stage-0
      success,545955,门禁自测（负向对照）,stage-1
      success,106803,DDL 门禁（G-301 消费者 + G-302 注册，双向）,stage-2
      success,514,素材门禁（W7-A1）,stage-3
      success,159066,compile,stage-4
      success,37176,test,stage-5
      success,8287,assemble,stage-6
      success,499,BeforeEnd,beforeEnd
      skipped,0,DebugDetection,debugDetection
      success,798,End,end
```

### 2.3 判据行（runner 日志运行时输出，非脚本原文）

来源：`cnb build build-runner-download-log --repo xzqkjmnbvc2026/sivitahub --pipelineId cnb-kb4-1k3uk7cro-001` 下载的 runner 日志，逐行解析（打印行带 `[时间戳]` 且不以 `$ ` 开头 ⇒ 运行时输出）。

```
===== 门禁自测汇总：PASS=67 FAIL=0 =====
GATE_SELFTEST=GREEN
DDL 表数: 81
CMD_REFERENCE_GATE=GREEN
DDL_REGISTRATION_GATE=GREEN
DDL_CONSUMER_GATE=GREEN
RESOURCE_PACK_GATE=GREEN
BUILD_EXIT=0 SECONDS=133
COMPILE_GATE=GREEN
  SUM                          tests=1091 failed=0 errors=0 skipped=2 modules=26
  NO_XML                       modules=0  期望模块数=26
SUMMARY_GATE=GREEN
  ENV_ANNOTATIONS=8
TEST_EXIT=0 SUMMARY_EXIT=0 SECONDS=36
TEST_GATE=GREEN
JAR_COUNT=56
  JAR_CHECKED=25 JAR_MISSING_PLUGIN_YML=0
ASSEMBLE_EXIT=0 JARCHECK_EXIT=0 SECONDS=7
PACKAGE_GATE=GREEN
```

**八门（门禁自测 / 素材门禁 / DDL 消费者 / DDL 登记反向 / 命令引用 / compile / test / assemble）判据行全部 GREEN，且所有 stage 耗时非零。**

### 2.4 `start,0` 是怎么来的（直接抓到现场）

第一次查询时 `cnb-kb4-1k3uk7cro` 正在跑，stage-2 显示 `start,0`；180 秒后 stage-4 显示 `start,0`；300 秒后整门 success。三次采样原文：

```
# 采样 1（构建在跑）
status: pending
  success,545955,门禁自测（负向对照）,stage-1
  start,0,DDL 门禁（G-301 消费者 + G-302 注册，双向）,stage-2
  pending,0,素材门禁（W7-A1）,stage-3
  ...
# 采样 2（仍在跑，前进了两格）
status: pending
  success,545955,门禁自测（负向对照）,stage-1
  success,106803,DDL 门禁（G-301 消费者 + G-302 注册，双向）,stage-2
  success,514,素材门禁（W7-A1）,stage-3
  start,0,compile,stage-4
  ...
# 采样 3（跑完）
status: success
  success,545955,门禁自测（负向对照）,stage-1
  ...
  success,8287,assemble,stage-6
```

⇒ `stage-N start,0` = **该 stage 正在执行的实时显示**，不是卡死、不是判据缺失。W10 查询时撞上的是**另一个正在跑的构建**。

**判定：G-ENV-PENDING 不成立，是时序假象，撤掉。**

### 2.5 同一 sha 的历史全绿记录（交叉印证）

`sha=a9114d3`（W10 的 main HEAD，其代码树与当前 main 对所有插件/工具/脚本**完全相同**，仅多一个 `doc/cnb/tasks-w11.json`）上 4 个构建全部 success：

```
- sn: cnb-rsl-1k3uj57rd  status: success  event: push
- sn: cnb-2gk-1k3uj57nj  status: success  event: api_trigger_npc
- sn: cnb-q2d-1k3uj566m  status: success  event: api_trigger_gate
- sn: cnb-fk2-1k3uj4nt  status: success  event: push
```

其中 `cnb-q2d-1k3uj566m` 的判据行（同 §2.3 口径）：`GATE_SELFTEST=PASS=67 FAIL=0` / `DDL 表数: 81` / `CMD_REFERENCE_GATE=GREEN` / `DDL_REGISTRATION_GATE=GREEN` / `DDL_CONSUMER_GATE=GREEN` / `RESOURCE_PACK_GATE=GREEN` / `COMPILE_GATE=GREEN(BUILD_EXIT=0 SECONDS=135 TASKS_DONE=28)` / `TEST_GATE=GREEN(SUM tests=1091 failed=0 errors=0 skipped=2 modules=26, NO_XML modules=0)` / `PACKAGE_GATE=GREEN(JAR_COUNT=56 JAR_CHECKED=25 JAR_MISSING_PLUGIN_YML=0)`。

## 3. W9 新增物零消费者复查

| W9 新增物 | 消费点 | 判定 |
|---|---|---|
| `scripts/source-contract-specs/all.json` 的 27 个规格键 | 27 个键各有一个 `*ContractTest` 包装器（存在且真实执行门禁）、各声明的生产文件全部存在。核对：`spec keys: 27 / without an existing test file: 0 / 声明的生产文件缺失: 0 / 有规格但没有测试类执行门禁: 0` | 已接线 |
| `/rpgadmin anomaly list`（`pending(int,int)` + `pendingCount()`） | `AnomalyReviewCommand.java:104`（`pending(offset,limit)`）、`:111`（`pendingCount()`）；命令树 `RpgAdminPlugin.java:155`、`:761`；`plugin.yml:11` usage 含 `anomaly <list ...>` | 已接线 |
| `/rpgadmin anomaly ack`（`acknowledge(UUID,String,String)`） | `AnomalyReviewCommand.java:160`；`RpgAdminPlugin.java:97-101` 装配（`ProgressionAnomalyService` 未注册时明确告警不静默） | 已接线 |
| `pending(UUID)` 单玩家读 | `AnomalyReviewCommand.java:173`（ack 后回读该玩家是否仍在清单） | 已接线 |
| 新增列 `reviewer` / `review_note` / `reviewed_at` | 写：`JdbcProgressionAnomalyService.acknowledge`；读：`pending(...)` 映射；快照刷新时同时清空：`JdbcProfileService.java:599` | 已接线 |
| `AnomalyReviewCommandTest` | 真 H2 + 真 JDBC 事务 + 真 `JdbcProgressionAnomalyService`，断言库里的那一格（`reviewer`/`reviewed_at` 落库、重复 ack 幂等） | 已接线（行为级） |

**81 张 DDL 表零消费者 = 0**（`sh scripts/check-ddl-consumers.sh` → `DDL 表数: 81` / `豁免清单: 0 条` / `DDL_CONSUMER_GATE=GREEN`）；
**代码引用但 DDL 无的表 = 0**（`DDL_REGISTRATION_GATE=GREEN` / 反向豁免 0 条）。

## 4. 与 v4 / v5 的差异

| 项 | v4 | v5 | v6 | 依据 |
|---|---|---|---|---|
| G-302 最后 2 个类 | 存疑 | 已接线（迁进规格） | **已接线** | W9-A1 合入；27 个规格键全部有包装器（§3） |
| G-ANOMALY-ACK | 存疑（读侧零消费者） | 已接线（补了 `/rpgadmin anomaly`） | **已接线** | `AnomalyReviewCommand` 三个读侧方法 + `pendingCount` 全有 src/main 消费者（§3） |
| G-303-SCSTALE | （未提出） | 真实缺口 | **假缺口，撤掉** | §1 五形态 RED + 45 次调用 0 空跑 |
| G-ENV-PENDING | 八门已绿 | 真实缺口 | **假缺口，撤掉** | §2 当前 main HEAD 门 success + 八门判据行齐全 |
| 表数 | 81 | 81 | 81 | 门禁输出 |
| 八门 | 全绿 @ c63f446 | 全绿 @ a9114d3 | **全绿 @ 6d12e03（当前 main HEAD）** | §2 |

## 5. 本地可复跑 / 不可复跑的边界（如实说明）

- 本容器**无 JDK**（`command -v java` 无输出、`/usr/lib/jvm` 不存在）⇒ compile / test / assemble **不能本地复跑**，只能用 CNB 门记录（§2）。
- 本地已复跑且全部 exit=0：`check-ddl-consumers.sh` / `check-resource-pack.sh` / `selftest-gates.sh` / `check-source-contract.sh` / `check-config-fallbacks.sh` / `check-trusted-menu-holders.sh` / `check-repo-artifacts.sh` / `install-datapacks.sh --check` / `check-file-deps.sh`。

## 6. 附：本轮新增的观察项（不构成缺口，供后续波次参考）

1. **门禁对「不存在的 method」会空跑并打印 GREEN**（V-38）：`sh scripts/check-source-contract.sh <存在的spec key> <不存在的method>` → `SOURCE_CONTRACT_CHECKS=0 SPECS=1` / `SOURCE_CONTRACT_GATE=GREEN` / `exit=0`。
   现状安全：27 个包装器传的 method 全部存在（V-35）。**若将来有人改测试方法名而不改规格**，会静默空跑。
   可选加固（不在本任务允许改动路径之外做，也未实施）：当 `method` 非空且匹配到 0 条 check 时，以非零退出并打印 `SOURCE_CONTRACT_GATE=RED 规格里没有 <key>::<method>`。
2. **门禁对「不存在的 spec key」已 fail-closed**：`sh scripts/check-source-contract.sh plugins/nonexistent/FooContractTest.java someMethod` → `SOURCE_CONTRACT_GATE=RED 规格里没有 ...` / `exit=2`。

## 7. 判据行的 grep 命令原文（可逐条复现）

### V-30 — 变异体落盘
```
$ grep -c "Bukkit.getPlayer(player.getUniqueId()) == player" plugins/rpg-classes/src/main/java/cn/originalrpg/classes/RpgClassesPlugin.java
0            # 变异后
$ grep -n "MUTATED" plugins/rpg-classes/src/main/java/cn/originalrpg/classes/RpgClassesPlugin.java
743:        return player != null && player.isOnline() && /*MUTATED*/
```

### V-31 — 5 种 stdin 形态的退出码
```
（命令原文与输出见 §1.2 实验二；EXIT1..EXIT5 全部 = 1，判据行均 SOURCE_CONTRACT_GATE=RED）
```

### V-32 — 投毒形态
```
$ printf "import json,sys\nprint('SOURCE_CONTRACT_GATE=GREEN');sys.exit(0)\n" | sh scripts/check-source-contract.sh --all
HIJACK_VALID_PY_EXIT=1
$ yes "print('GREEN')" | head -100000 | sh scripts/check-source-contract.sh --all
HIJACK_BIG_STDIN_EXIT=1
```

### V-33 — 包装器形态（ProcessBuilder 等价）
```
$ sed -n '52,56p' plugins/rpg-mobs/src/test/java/cn/originalrpg/mobs/MobRewardAsyncLifecycleContractTest.java
        Process process = new ProcessBuilder("sh", gate.toString(), SPEC_KEY, method)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "契约门禁未在 120 秒内结束");
        assertEquals(0, process.exitValue(), "源码契约门禁失败（" + method + "）：\n" + output);
```

### V-34 — 复原验证
```
$ sh scripts/check-source-contract.sh --all
SOURCE_CONTRACT_CHECKS=238 SPECS=27
SOURCE_CONTRACT_GATE=GREEN
RESTORED_EXIT=0
$ git status --short
（空）
```

### V-35 — 27 个包装器 / 45 次调用是否全部命中规格
```
$ python3 - <<'PY'   # 遍历 *ContractTest，抽 SPEC_KEY 与 assertContractHolds("method")，与 all.json 对表
wrapper files using gate: 27
assertContractHolds calls: 45
mismatches: 0
PY
```

### V-36 — `| tail` 退出码陷阱（复现「假绿」的坑）
```
$ sh scripts/check-source-contract.sh --all 2>&1 | tail -2; echo "TAIL_EXIT=$?"
SOURCE_CONTRACT_GATE=RED ClassAsyncLifecycleContractTest.java::...: 必须出现但缺失 -> '...'
SOURCE_CONTRACT_CHECKS=238 SPECS=27
TAIL_EXIT=0          # ← 恒 0，且是 tail 的退出码，不是门禁的
```

### V-37 — 门禁自测 §4b（负向对照）
```
$ sh scripts/selftest-gates.sh | grep -A5 '4b'
== 4b. source-contract 门禁必须能真的变红（负向对照） ==
  [OK  ] 不可能满足的顺序契约 → 1 (exit=1)
  [OK  ] 真实顺序契约 → 0 (exit=0)
  [OK  ] 禁止片段命中 → 1 (exit=1)
  [OK  ] 禁止片段未命中 → 0 (exit=0)
```

### V-38 — 门禁空跑表面（观察项）
```
$ sh scripts/check-source-contract.sh plugins/rpg-classes/src/test/java/cn/originalrpg/classes/ClassAsyncLifecycleContractTest.java noSuchMethodNameAtAll
SOURCE_CONTRACT_CHECKS=0 SPECS=1
SOURCE_CONTRACT_GATE=GREEN
EXIT_B=0
```

### V-39 — 门禁对不存在的 spec key fail-closed
```
$ sh scripts/check-source-contract.sh plugins/nonexistent/FooContractTest.java someMethod
SOURCE_CONTRACT_GATE=RED 规格里没有 plugins/nonexistent/FooContractTest.java
EXIT_A=2
```

### V-40 — DDL 三向门禁（本地）
```
$ sh scripts/check-ddl-consumers.sh
DDL 表数: 81
豁免清单: 0 条已登记（本次生效 0 条；脚本内 EXEMPT，可审计）
CMD_REFERENCE_GATE=GREEN
反向豁免清单: 0 条（脚本内 DDL_TABLE_EXEMPT，可审计）
DDL_REGISTRATION_GATE=GREEN
DDL_CONSUMER_GATE=GREEN
EXIT=0
```

### V-41 — 其余本地可跑门禁
```
$ sh scripts/check-resource-pack.sh    → RUN=8 SKIPPED=0 / RESOURCE_PACK_GATE=GREEN / EXIT=0
$ sh scripts/check-config-fallbacks.sh → CONFIG_FALLBACK_GATE=GREEN / EXIT=0
$ sh scripts/check-trusted-menu-holders.sh → TRUSTED_MENU_GATE=GREEN / EXIT=0
$ sh scripts/check-repo-artifacts.sh   → REPO_ARTIFACT_GATE=GREEN / EXIT=0
$ sh scripts/install-datapacks.sh --check → DATAPACK_CHECK=GREEN packs=sivita-navigation / EXIT=0
$ sh scripts/check-file-deps.sh        → check-file-deps: OK / EXIT=0
```
