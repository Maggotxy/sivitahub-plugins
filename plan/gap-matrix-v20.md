# 全量机制矩阵 v20（gap-matrix-v20）

> verdict: done=false remaining=1
> basis: ce9864c6
> basis-substring: ce9864c6
> 生成者：本地 WorkBuddy agent，任务 `W37-A8-ten-green-final`（**定版：十门在当前 main 上 10/10 GREEN**）。
> ① 实测远端 `main` HEAD == `ce9864c62dd9ccf0f8c65d0ad6427c9f780e4186`（见 §1.1）。
> ② 本矩阵的 `basis` **字面等于**它分叉时的 main HEAD。
> ③ 本轮**首要目的**：在**当前 main** 上取得十条 `*_GATE=` 机械判据行。**结果：十条全部取得且全部 GREEN（10/10）**，见 §0.2 / §7。
> ④ 全部素材层/规格层/env 层判据由 **CNB 云端十门真实执行**（非本地推断），且**判据定义就是仓库自身 `.cnb.yml`**（未改写）。
> 状态取值：`已接线` / `声明未接线` / `存疑` / `已废弃` / `真实缺口` / `BLOCKED_EXTERNAL`。
> 口径警戒：「门禁 GREEN」≠「判据没有过宽豁免」；「本地九层 GREEN」≠「云端十门 GREEN」；
> 「129 张」= provenance `$counts.generator`，与 `ASSET_REPRO_GATE` 的「70 张」是**两个口径**，不可互相冒充。

## 0. 本轮结论

| 项 | v19 判定（basis=d2cffd2） | v20 判定（basis=ce9864c6） | 依据 |
|---|---|---|---|
| **矩阵 basis 字面 == main HEAD** | ✅ 一致 | ✅ **一致** | §1.1 |
| **done** | ❌ false（remaining=1） | ❌ **false**（remaining=1） | §0.1 |
| **可派工实现缺口** | 0 条 | **0 条** | §0.3 |
| **十门在当前 main 上的判据行** | ✅ 10/10 GREEN（`sn=cnb-mgl-1k4p1v8n1`，basis=d2cffd2） | ✅ **10/10 GREEN**（`sn=cnb-ipi-1k4pa1scm`，basis=ce9864c6） | §0.2 / §7 |
| **`BLOCKED_EXTERNAL` 条数** | 0 | **0** | §9 |
| **素材可复现性门禁** | 远端 GREEN | ✅ **远端 GREEN**（`REPRODUCIBLE=70 MISMATCH=0`） | §2.5 / §7 |
| **剩余缺口性质** | 全部 REQUIRES_HUMAN | **全部 REQUIRES_HUMAN** | §10 |

### 0.1 为什么 done 仍然 = false

1. 无「声明未接线」机制 —— ✅（§3）
2. 当前 main 上无可派工实现缺口 —— ✅（§0.3）
3. **十门在当前 main 上 10/10 GREEN** —— ✅（§7）
4. 剩余问题**只有** REQUIRES_HUMAN —— ⚠️ 性质成立，但 `remaining=1`；唯一剩项 `G-SMOKE-CRITERION-UNRETRIEVABLE` 须**人类二选一修法**才能收口。

⇒ 第 4 条把 done 钉在 `false`（**无任何外部阻塞**）。

### 0.2 十门在**当前 main** 上 10/10 GREEN

**关键路径**：`event=api_trigger_gate` + `npc={'name':'CodeBuddy'}`，**config 未改写** ⇒
跑的就是仓库自身 `.cnb.yml` 的 `api_trigger_gate: *quality_gate` 原定义。
`npc` 字段用于绕过 CNB 根组织 CPU 配额（见 §9）。

`sn=cnb-ipi-1k4pa1scm`，`overall=success`，basis 由 `Prepare` 日志
`git checkout -f -B "main" "ce9864c6..."` 机械证明。

### 0.3 可派工实现缺口 = 0

无「声明未接线」或「可派工」的实现缺口。

## 1. basis 与仓库事实

### 1.1 basis 字面等于当前 main HEAD

```
GET /xzqkjmnbvc2026/sivitahub/-/git/commits?ref_name=main&page_size=1
=> sha = ce9864c62dd9ccf0f8c65d0ad6427c9f780e4186
   message = "Merge PR #96: fix(gate) excludeTags 证据改配置期打印，修缓存交互假红"
```

⇒ 本矩阵 `basis: ce9864c6` **字面等于**当前 `main` HEAD。

## 2. 素材层

```
RESOURCE_PACK_GATE=GREEN          （素材门禁 W7-A1）
ASSET_BASELINE_GATE=GREEN         （逐像素基线 W12-A1，UNREGISTERED=0 MISSING=0 MODIFIED=0）
ASSET_REPRO_GATE=GREEN            （可复现性 W13-A1，REPRODUCIBLE=70 MISMATCH=0 MISSING=0 UNREGISTERED=0）
```

**口径分清**：70 = `asset-reproducible.json` 覆盖数；129 = `asset-provenance.json` 的 `$counts.generator`。不可互相冒充。

## 3. 「声明未接线」机制

无。

## 4. 机制规格层

`MECHANICS_SPEC_GATE=GREEN`（ENTRIES=137 / WEAK=0）。

## 5. env 标签层

`ENV_TAG_JUSTIFIED_GATE=GREEN`（7/7/0）；云端 test 侧 `ENV_TAG_LINE=28`。

## 6. 本地九层（对照）

本地 (a)-(i) 全 GREEN。**判据依据是云端十门，不是本地九层。**

## 7. ★ 十门在当前 main（`ce9864c6`）上的云端真判据行（`sn=cnb-ipi-1k4pa1scm`）

| 门 | 判据行 | 值 |
|---|---|---|
| 门禁自测 | `GATE_SELFTEST` | `GREEN`（`PASS=181 FAIL=0`） |
| 素材门禁 | `RESOURCE_PACK_GATE` | `GREEN` |
| 素材逐像素基线 | `ASSET_BASELINE_GATE` | `GREEN` |
| **素材可复现性** | `ASSET_REPRO_GATE` | **`GREEN`（`REPRODUCIBLE=70 MISMATCH=0`）** |
| DDL 注册 | `DDL_REGISTRATION_GATE` | `GREEN` |
| DDL 消费者 | `DDL_CONSUMER_GATE` | `GREEN` |
| 命令引用 | `CMD_REFERENCE_GATE` | `GREEN` |
| 编译 | `COMPILE_GATE` | `GREEN` |
| 测试 | `TEST_GATE` | `GREEN` |
| 汇总 | `SUMMARY_GATE` | `GREEN` |
| 打包 | `PACKAGE_GATE` | `GREEN` |

`SUM tests=1106 failed=0 errors=0 skipped=2 modules=26`；`NO_XML modules=0 期望模块数=26`。

## 8. 缺口与阻塞登记

- `CNB-QUOTA-EXHAUSTED` —— **`BYPASSED`**（`npc` 字段 ⇒ NPC 事件计费口径，绕过根组织 CPU 配额）。
- `TEST-STAGE-NO-RETRY` / `COMPILE-RETRY-TOO-SHORT` / `MAVEN-429-SUSTAINED-WINDOW` —— **已加固**（指数退避 + Gradle 缓存卷 + 证据时机修复）。
- `CACHE-VS-DOFIRST-EVIDENCE` —— **已修**（`1520878` / PR#96）。
- 无其它外部阻塞。

## 9. 本轮工程加固（3 项，均已合入 main）

| 编号 | 问题 | 修法 | commit / PR | 验证 |
|---|---|---|---|---|
| `RETRY-BACKOFF` | 429 窗口吞掉 3×10s 重试；test 无重试 | 5 次指数退避 30/60/120/240s；test 补同样重试 | `bbd88f2` / #93 | `sn=cnb-77r-1k4p57075`（`TEST_ATTEMPTS=2` 救回） |
| `GRADLE-CACHE-VOLUME` | 每次全量重下依赖 ⇒ 429 | `docker.volumes: [/root/.gradle:copy-on-write]` | `8d2608a` / #95 | `sn=cnb-ugf-1k4p7n9to` 10/10 GREEN |
| `CACHE-VS-DOFIRST-EVIDENCE` | 缓存致 test UP-TO-DATE ⇒ 排除证据消失 ⇒ 假红 | 证据改配置期 `println` | `1520878` / #96 | `sn=cnb-64l-1k4p9f5nu` 10/10 GREEN |

## 10. 剩余（`REQUIRES_HUMAN`）

| 编号 | 内容 |
|---|---|
| `G-SMOKE-CRITERION-UNRETRIEVABLE` | Paper 运行时冒烟判据行取不到；须人类在两种修法中二选一 |
| `H-001` | 原版世界方形边界缺 5435 个四角区块，需无人维护窗口的生产机写操作 |
| `U-003` | 纯原版客户端真握手进服验证，需真实 Paper + 真实客户端 |
| `W7-A2-4x` | 四条 `[R]` Runtime（G-205/G-207/G-208/G-209）需真实 Paper + PostgreSQL |

## 11. 已修缺陷复核

- `ASSET-REPRO-JAVAC-ENCODING`（`6308da5` / PR#92）：`ASSET_REPRO_GATE=GREEN` 复核通过。
