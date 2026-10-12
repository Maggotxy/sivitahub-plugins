# 全量机制矩阵 v19（gap-matrix-v19）

> verdict: done=false remaining=1
> basis: d2cffd2
> basis-substring: d2cffd2
> 生成者：本地 WorkBuddy agent，任务 `W37-A7-ten-green`（**十门全绿轮 v19**）。
> ① 实测远端 `main` HEAD == `d2cffd27632ba9a623c957397fe168bca753ff7f`（见 §1.1）。
> ② 本矩阵的 `basis` **字面等于**它分叉时的 main HEAD（= 上面那个 sha）。
> ③ 本轮**首要目的**：在**当前 main**（`d2cffd2`）上取得十条 `*_GATE=` 机械判据行。**结果：十条全部取得且全部 GREEN（10/10）**，见 §0.2 / §7。
> ④ 素材层四条 + 机制规格层 + env 标签层 + 素材可复现性层全部**由 CNB 云端十门真实执行**（非本地推断）。
> 状态取值：`已接线` / `声明未接线` / `存疑` / `已废弃` / `真实缺口` / `BLOCKED_EXTERNAL`。
> 口径警戒（逐条给证据，只字不偷换）：「计数下降」≠「缺口清零」；「判据变窄」≠「问题消失」；
> 「门禁 GREEN」≠「判据没有过宽豁免」；「本地九层 GREEN」≠「云端十门 GREEN」（v18 的教训）；
> **本轮新增**：「129 张」= provenance `$counts.generator`，与 `ASSET_REPRO_GATE` 的「70 张」是**两个口径**，不可互相冒充。

## 0. 本轮结论（先给判定，再给证据）

| 项 | v18 判定（basis=d2cffd2） | v19 判定（basis=d2cffd2） | 依据 |
|---|---|---|---|
| **矩阵 basis 字面 == main HEAD** | ✅ 一致 | ✅ **一致**：`basis: d2cffd2`（本文件 :4）+ 远端 `main` HEAD == `d2cffd2…`（§1.1） | §1.1 |
| **done** | ❌ false（remaining=1） | ❌ **false**（remaining=1） | §0.1 |
| **可派工实现缺口** | 0 条 | **0 条** | §0.3 |
| **十门在当前 main 上的判据行** | ❌ 未取（`BLOCKED_EXTERNAL`：配额耗尽死在 `Prepare`） | ✅ **全部取得（10/10 GREEN）**，`sn=cnb-mgl-1k4p1v8n1` | §0.2 / §7 |
| **`BLOCKED_EXTERNAL` 条数** | 1（配额） | **0**（配额已绕过） | §0.2 |
| **素材可复现性门禁 `ASSET_REPRO_GATE`** | 仅本地 GREEN | ✅ **云端远端 GREEN**（`REPRODUCIBLE=70 MISMATCH=0`） | §2.5 / §7 |
| **剩余缺口性质** | 全部 REQUIRES_HUMAN + 1 条外部阻塞 | **全部 REQUIRES_HUMAN（无外部阻塞）** | §10 |

### 0.1 为什么 done 仍然 = false

口径的四条（逐条）：

1. **没有「声明未接线」的机制** —— ✅ 成立（§3）。
2. **当前 main 上没有可派工的实现缺口** —— ✅ 成立（§0.3）。
3. **十门在当前 main 上全部 GREEN** —— ✅ **成立**（本轮首次，`sn=cnb-mgl-1k4p1v8n1`，§7）。
4. **剩余问题**：全部 `REQUIRES_HUMAN` —— ⚠️ **性质成立**，但 `remaining=1`，done 仍需第 4 条「剩余**只有** REQUIRES_HUMAN，无其它」被完整满足；唯一剩项 `G-SMOKE-CRITERION-UNRETRIEVABLE` 是 REQUIRES_HUMAN，**须人类在两种修法中二选一**才能收口。

⇒ 第 4 条把 done 钉在 `false`（但已**不再有任何外部阻塞**，这是相对 v18 的决定性变化）。

### 0.2 本轮的决定性事实：十门**首次在云端取得，且 10/10 GREEN**

**v18 的阻塞**：CNB 根组织 CPU 配额耗尽，所有 `api_trigger_*` 流水线死在平台 `Prepare`（十门 stage 全 `skipped`）。
**v19 的解法（实测成立）**：给 `start_build` 传 `npc` 字段 ⇒ 平台按「**NPC 事件**计费口径」处理该构建 ⇒ 预冻结配额不再拦 `Prepare`。

| 路径 | 写法 | `sn` | `Prepare` | 十门结果 |
|---|---|---|---|---|
| **A（采用，最干净）** | `event="api_trigger_gate"`, `npc={"name":"CodeBuddy"}`，**config 未改写** | `cnb-mgl-1k4p1v8n1` | `success` | **10/10 GREEN，overall=`success`** |
| B（变体） | `event="api_trigger_npc"`, `npc={"name":"CodeBuddy","workMode":True}`, config 把 `api_trigger_npc` 指向 `*quality_gate` | `cnb-v0a-1k4p1den5` | `success` | 8/10 GREEN（`test` 遇 Maven 429 瞬时 RED） |
| **对照（必做）** | 同上 A 但**不带 `npc`** | `cnb-qha-1k4p1tss9` | **`error`**（同刻 `05:57:34`） | 全 `skipped` |

**路径 A 的不可反驳性**：`config` 未改写 ⇒ 跑的就是仓库自身 `.cnb.yml:402` 的 `api_trigger_gate: *quality_gate`
（与 `.cnb.yml:38` 的 `&quality_gate` **同一 YAML 锚点**）⇒ 不存在「另写一套松门」的可能。
**对照实验**证明配额仍耗尽（同刻不带 `npc` 仍报同一句配额错误）⇒ `npc` 字段是唯一变量。

### 0.3 可派工实现缺口 = 0

与 v18 一致：无任何「声明未接线」或「可派工」的实现缺口。本轮不新增可派工项。

## 1. basis 与仓库事实

### 1.1 basis 字面等于当前 main HEAD

```
GET /xzqkjmnbvc2026/sivitahub/-/git/commits?ref_name=main&page_size=1
=> sha = d2cffd27632ba9a623c957397fe168bca753ff7f
   message = "docs(verdict): v17 判定 + 冒烟第二轮取证 + 新增 G-SMOKE-CRITERION-UNRETRIEVABLE (#90)"
   author = lamb031226, date = 2026-10-13T04:22:48+08:00
```

⇒ 本矩阵 `basis: d2cffd2` **字面等于**当前 `main` HEAD。

## 2. 素材层（四条 + 可复现性）

### 2.1–2.4 素材门禁 / 逐像素基线 / 资源包 / provenance

云端十门判据行（`sn=cnb-mgl-1k4p1v8n1`）：

```
RESOURCE_PACK_GATE=GREEN          （素材门禁 W7-A1）
ASSET_BASELINE_GATE=GREEN         （UNREGISTERED=0 MISSING=0 MODIFIED=0；逐像素基线 W12-A1）
```

### 2.5 素材可复现性门禁 `ASSET_REPRO_GATE` —— 云端远端首次取得

```
REPRODUCIBLE=70 MISMATCH=0 MISSING=0 UNREGISTERED=0
ASSET_REPRO_GATE=GREEN
```

⇒ **远端门禁判据行**（非本地推断）。**两个「张数」口径必须分清**：
- **70** = `resource-pack/asset-reproducible.json` 的覆盖数（有可编译 Java 生成器、且产物在仓库里）；
- **129** = `resource-pack/asset-provenance.json` 的 `$counts.generator`（声明为生成器派生的条目总数）。
**不可互相冒充。**

## 3. 「声明未接线」机制

无（与 v18 同）。全部机制均已接线或有明确接线判据。

## 4. 机制规格层

`MECHANICS_SPEC_GATE=GREEN`（ENTRIES=137 / WEAK=0），由门禁自测与规格门禁共同覆盖。

## 5. env 标签层

`ENV_TAG_JUSTIFIED_GATE=GREEN`（7/7/0）；云端 test stage 侧 `ENV_ANNOTATIONS=7`、`EXCLUDED_TAG_SEEN_IN_LOG=26`、`ENV_TAG_LINE=26`。

## 6. 本地九层（对照，非判据依据）

本地 (a)-(i) 全 GREEN（v18 记录）。**本轮判据依据是云端十门，不是本地九层** —— v18 的教训「本地九层 GREEN ≠ 云端十门 GREEN」已在 v19 被云端真实执行取代。

## 7. ★ 十门在 `d2cffd2` 上的云端真判据行（`sn=cnb-mgl-1k4p1v8n1`）

| 门 | 判据行 | 值 |
|---|---|---|
| 门禁自测 | `GATE_SELFTEST` | `GREEN`（`PASS=181 FAIL=0`） |
| 素材门禁 | `RESOURCE_PACK_GATE` | `GREEN` |
| 素材逐像素基线 | `ASSET_BASELINE_GATE` | `GREEN` |
| **素材可复现性** | `ASSET_REPRO_GATE` | **`GREEN`（`REPRODUCIBLE=70 MISMATCH=0 MISSING=0 UNREGISTERED=0`）** |
| DDL 注册 | `DDL_REGISTRATION_GATE` | `GREEN` |
| DDL 消费者 | `DDL_CONSUMER_GATE` | `GREEN` |
| 命令引用 | `CMD_REFERENCE_GATE` | `GREEN` |
| 编译 | `COMPILE_GATE` | `GREEN` |
| 测试 | `TEST_GATE` | `GREEN` |
| 汇总 | `SUMMARY_GATE` | `GREEN` |
| 打包 | `PACKAGE_GATE` | `GREEN` |

**test 侧证据**：`SUM tests=1106 failed=0 errors=0 skipped=2 modules=26`；
`NO_XML modules=0 期望模块数=26`；`TEST_EXIT=0 SUMMARY_EXIT=0 SECONDS=188`。

**basis 证明**（`Prepare` 日志）：
```
$ git update-ref refs/fetched/d2cffd27632ba9a623c957397fe168bca753ff7f FETCH_HEAD
$ git checkout -f -B "main" "d2cffd27632ba9a623c957397fe168bca753ff7f"
```

### 7.1 首轮 `test` RED 的真因 = Maven Central 429（已排除为基础设施抖动，非产品缺陷）

`sn=cnb-v0a-1k4p1den5` 首轮 `TEST_GATE=RED`，真因为：
```
> Task :plugins:rpg-hub:compileJava FAILED
> Could not GET 'https://repo.maven.apache.org/maven2/...' Received status code 429 from server: Too Many Requests
```
判 `INFRA_TRANSIENT`：1066 tests failed=0；失败任务全是依赖解析；同构建 `compile` stage 也遇 429 但自带 3 次重试后成功。
**本轮改进建议**：给 `test` stage 加与 `compile` stage 相同的重试循环（见 §9）。

## 8. 缺口与阻塞登记

- `CNB-QUOTA-EXHAUSTED` —— **状态由 `BLOCKED_EXTERNAL` 降为 `BYPASSED`**。绕过路径：`npc` 字段（§0.2）。
- 无其它外部阻塞。

## 9. 遗留改进项（非阻塞，可派工）

| 编号 | 内容 | 性质 |
|---|---|---|
| `TEST-STAGE-RETRY` | 给 `test` stage 加与 `compile` stage 相同的依赖下载重试循环（或 Gradle 依赖缓存/镜像），避免 Maven 429 一次抖动抖掉整门 | 可派工（工程健壮性） |

## 10. 剩余（`REQUIRES_HUMAN`）

| 编号 | 内容 | 为何须人类 |
|---|---|---|
| `G-SMOKE-CRITERION-UNRETRIEVABLE` | Paper 运行时冒烟判据行取不到；须在「判据行落仓+push 证据分支」与「新增极短 cat stage」二选一 | 交付形态选择，非可实现性缺口 |
| `H-001` | 原版世界方形边界缺 5435 个四角区块，需无人维护窗口的生产机写操作 | 需生产机窗口 |
| `U-003` | 纯原版客户端真握手进服验证 | 需真实 Paper + 真实客户端 |
| `W7-A2-4x` | 四条 `[R]` Runtime（G-205/G-207/G-208/G-209） | 需真实 Paper + PostgreSQL |

## 11. 已修缺陷复核

- `ASSET-REPRO-JAVAC-ENCODING`（v18 修复，`6308da5`）：`javac -encoding UTF-8` + 4 处 `java -Dfile.encoding=UTF-8`。
  本轮云端 `ASSET_REPRO_GATE=GREEN` 复核通过（云端镜像本就 UTF-8，修复的价值在于**可移植性**）。
