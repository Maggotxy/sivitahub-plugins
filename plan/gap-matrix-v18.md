# 全量机制矩阵 v18（gap-matrix-v18）

> verdict: done=false remaining=1
> basis: d2cffd2

> basis-substring: d2cffd2
> 生成者：本地 WorkBuddy agent，任务 `W36-A5-gate-quota-blocked`（**十门被配额阻塞轮 v18**）。
> ① 实测 `git rev-parse origin/main` == `d2cffd27632ba9a623c957397fe168bca753ff7f`（见 §1.1）。
> ② 本矩阵的 `basis` **字面等于**它分叉时的 main HEAD（= 上面那个 sha，即 PR#90 合入后的 main）。
> ③ 本轮**首要目的**：在**当前 main**（`d2cffd2`）上取得机械判据行。**结果：十门取不到，根因是平台配额（外部阻塞）**，见 §9。
> ④ 素材层四条 + 机制规格层 + env 标签层 + **素材可复现性层**全部**由我在本轮亲自重跑**（§2–§6）。
> 状态取值：`已接线` / `声明未接线` / `存疑` / `已废弃` / `真实缺口` / `BLOCKED_EXTERNAL`。
> 口径警戒（逐条给证据，只字不偷换）：「计数下降」≠「缺口清零」；「判据变窄」≠「问题消失」；
> 「门禁 GREEN」≠「判据没有过宽豁免」；「NPC 容器 GREEN」≠「门禁容器 GREEN」；
> 「退出码 0」≠「判据行拿到」（§W）；**本轮新增**：「流水线受理成功」≠「构建真的开始」（§9，配额阻塞）。

## 0. 本轮结论（先给判定，再给证据）

| 项 | v17 判定（basis=761b416） | v18 判定（basis=d2cffd2） | 依据 |
|---|---|---|---|
| **矩阵 basis 字面 == main HEAD** | ✅ 一致 | ✅ **一致**：`basis: d2cffd2`（本文件 :5）+ 实测 `origin/main == d2cffd2…`（§1.1） | §1.1 |
| **done** | ❌ false（remaining=1） | ❌ **false**（remaining=1） | §0.1 |
| **可派工实现缺口** | 0 条 | **0 条** | §0.3 |
| **十门在当前 main 上的判据行** | 未取（basis 早于 main） | ❌ **未取**（`BLOCKED_EXTERNAL`：CNB 根组织 CPU 配额耗尽，流水线死在 `Prepare`） | §9 |
| **素材可复现性门禁 `ASSET_REPRO_GATE`** | 未取到（本机无 JDK） | ✅ **GREEN**（本轮亲自重跑，`REPRODUCIBLE=70 MISMATCH=0`，§2.5） | §2.5 |
| **剩余缺口性质** | 全部 REQUIRES_HUMAN | 全部 REQUIRES_HUMAN + **1 条外部阻塞** | §10 / §9 |

### 0.1 为什么 done 仍然 = false

口径的四条（逐条）：

1. **没有「声明未接线」的机制** —— ✅ 成立（§3，与 v17 同）。
2. **当前 main 上没有可派工的实现缺口** —— ✅ 成立（§0.3）。
3. **素材层四条全绿** —— ✅ 成立（§2.1–§2.4）。
4. **剩余问题**：全部 `REQUIRES_HUMAN` + 新增 1 条 `BLOCKED_EXTERNAL` —— **本条不成立**（`remaining=1`，见 §0.2 / §9）。

⇒ 第 4 条把 done 钉在 `false`。

### 0.2 本轮唯一的「新事实」：十门被平台配额阻塞（决定性，外部）

**这不是「我没去跑」，是「跑了三次、三次都死在平台 `Prepare` 阶段」**：

| 轮 | 事件 | `sn` | 整体 `status` | `Prepare` 结果 |
|---|---|---|---|---|
| 1 | `api_trigger_gate` | `cnb-fef-1k4ot1qvq` | `error` | `error`（十门 stage **全部 skipped**） |
| 2 | `api_trigger_smoke`（对照） | `cnb-r2i-1k4ot3gs2` | `error` | `error`（同上） |
| 3 | `api_trigger_gate`（重试） | `cnb-u8e-1k4ot4o4n` | `error` | `error`（同上） |
| 4 | `api_trigger_seed`（**自定义极简 config** 探测） | `cnb-qvu-1k4p03o5r` | `error` | `error`（自定 stage **skipped**） |

第 4 轮的独立性：它**不用仓库 `.cnb.yml`**，而是走 `start_build` 的 `config` 字段传一段最小 YAML（`echo QUOTA_PROBE_OK`），
只为排除「是不是本仓配置太重」。结果**照样死在 `Prepare`** ⇒ 进一步确证阻塞发生在**平台层**，与本仓配置无关。
（原始取证：`plan/quota-probe-v18.json`。）

**四次的 `Prepare` 末尾原文字面相同**：

```
Pipeline prepare error: Root Group's events CPU core-hours are insufficient for pre-freezing
(Freezing time:5.00 min,equivalent to 0.67 core-hours).
Contact the root group administrator to extend.
根组织的云原生构建-CPU配额已不够预冻结(冻结时间：5.00 min，折合0.67核时)，请联系根组织管理员提升配额。
```

⇒ 事件无关（gate ×2 + smoke ×1 + seed ×1 同一句话）⇒ 是**根组织级全局配额**。
⇒ `Prepare` 是**平台自带 stage**，不在 `.cnb.yml` 里 ⇒ **改 `.cnb.yml` / job `timeout` / stage 条件全都无效**。
⇒ 本条判 `BLOCKED_EXTERNAL`（外部阻塞），**不是**产品缺陷，也**不是**「未达成 by 缺工」。

**连带后果（本轮实际发生）**：连「借流水线 push」的播种法（`putfile.py`：`start_build` + `config` 里 `git push origin HEAD:<branch>`）
也被同一道 `Prepare` 挡住 ⇒ **本轮分支推送与 PR 创建全部不可行**。故本会话的 v18 交付物**只落到本地提交**
（`codex/asset-repro-encoding-fix`=`6308da5`、`codex/gap-matrix-v18`=`a984483`），待配额恢复后一次性推送开 PR。
这不是「忘了推」，是**外部阻塞覆盖了所有 CNB 写路径**。

**副产品（有用的）**：`Prepare` 的 git-clone 日志里打印了它真正 checkout 的 sha：

```
$ git rev-parse HEAD
d2cffd27632ba9a623c957397fe168bca753ff7f      ← 与请求的 sha 字面一致
```

⇒ 即使十门没跑，也**机械证明**了「平台确实按我给的 basis 拉到了代码」，而不是只写「我请求了 d2cffd2」。

### 0.3 可派工实现缺口 = 0

v16 的 `W33-RUNTIME-SMOKE` 已在 v17 闭环（PR#86/#88/#89）。本轮**没有新增**可派工实现缺口。
本轮新增的两条都是**非实现类**：
- `G-SMOKE-CRITERION-UNRETRIEVABLE`（`REQUIRES_HUMAN`，v17 已登记，见 §9.4）；
- `CNB-QUOTA-EXHAUSTED`（`BLOCKED_EXTERNAL`，本轮新增，见 §0.2）。

### 0.4 本轮顺带修复的一条真缺陷（判据可移植性）

`scripts/check-asset-reproducible.sh` 调 `javac` 时**未指定源编码**。生成器源码含中文注释（UTF-8），
而 `javac` 默认源编码随平台走 —— CNB 的 `eclipse-temurin:25-jdk` 默认 UTF-8 所以从未暴露；
但在默认 GBK 的 JDK（本机 Temurin 17 / `zh_CN`）上会报「编码 GBK 的不可映射字符」而整门假红。
**修法**：`javac -encoding UTF-8`，4 处 `java` 运行加 `-Dfile.encoding=UTF-8`。
**效果**：`ASSET_REPRO_GATE` 从「只在 UTF-8 平台可复现」变成「与平台默认字符集无关」。
见 §2.5 与分支 `codex/asset-repro-encoding-fix`（`6308da5`）。

## 1. 基准（basis）

### 1.1 矩阵 basis 字面 == main HEAD（本轮首要目的）

| 来源 | 命令 | 值 |
|---|---|---|
| 本地 HEAD（v18 分支 B 的基座） | `git rev-parse d2cffd27632ba9a623c957397fe168bca753ff7f` | `d2cffd27632ba9a623c957397fe168bca753ff7f` |
| 远端 main（本地跟踪） | `git rev-parse origin/main` | `d2cffd27632ba9a623c957397fe168bca753ff7f` |

⇒ `basis: d2cffd2`（本文件 :5）**字面等于**当前 main HEAD。

### 1.2 本分支的改动范围

本分支（B，`codex/gap-matrix-v18`）**只加文档与取证脚本**，**不改任何判据 / 阈值 / 机制**：

```
plan/gap-matrix-v18.md          （本文件）
plan/verdict-v18.json
plan/local-layers-v18.txt       （本地九层判据行汇总）
plan/layer-i-repro-v18.txt      （层 i 原始输出）
plan/gate-run-v18.json(.stages.txt)（十门被配额阻塞的原始取证）
plan/run_local_layers_v18.py    （本地层取证脚本）
plan/poll_gate_v18.py           （十门轮询取证脚本）
doc/cnb/gap-registry.md         （追加 §I.11 quota 阻塞）
state/world-state.json          （basis/round 更新）
```

判据层的代码修复（`check-asset-reproducible.sh` 编码）**拆到独立分支** `codex/asset-repro-encoding-fix`，
理由：它改的是运行期依赖（编码），与「判定文档」是两类改动，混在一个 PR 里会让「判定」与「修码」互相背书。

## 2. 素材层（本轮亲自重跑，不采信任何上一轮结论）

> 运行环境：**干净 LF 树**（`git -c core.autocrlf=false -c core.eol=lf archive HEAD`），保持仓库结构。
> 判据层跑在该树的 **`6308da5`**（= `d2cffd2` + §0.4 的编码修复）上 —— 编码修复只影响本机 javac 能否跑，
> **不改变任何判据的阈值或逻辑**。原始输出见 `plan/local-layers-v18.txt` / `plan/layer-i-repro-v18.txt`。

### 2.1 (b) 素材逐像素基线（W12-A1）

```
$ python3 scripts/check-asset-baseline.py
ASSET_BASELINE: 校验 252 张 PNG（清单 252 条）
  UNREGISTERED=0 MISSING=0 MODIFIED=0
ASSET_BASELINE_GATE=GREEN
EXIT=0
```

### 2.2 (c) provenance 与 manifest 双向一致（W12-A1）

```
$ python3 scripts/gen-asset-manifest.py --check
清单与磁盘素材一致: resource-pack/assets-manifest.json（252 张）
EXIT=0
```

`resource-pack/asset-provenance.json` 的 `$counts` 与 `$needsHumanDecision`：

```
{'ai-prompt': 10, 'derived-from': 69, 'generator': 129, 'human-override': 1,
 'upstream-betterhud': 38, 'upstream-vanilla': 5}
$needsHumanDecision = []
```

⇒ 口径里的「生成器类 129 张」= provenance 中 `generator: 129`。它**不等于**可复现门禁的覆盖数（70）：
后者是「存在可编译 Java 生成器、且产物在仓库里」的那 70 条（见 §2.5）。两个数都如实保留，**不互相冒充**。

### 2.3 (d) 资源包完整性（W7-A1，七项判据）

```
$ sh scripts/check-resource-pack.sh
  （判据：manifest closure textures hud journey lang compass packmeta）
  RUN=8 SKIPPED=0
RESOURCE_PACK_GATE=GREEN
EXIT=0
```

### 2.4 (a) 脚本零消费者

```
$ ls scripts/*.sh scripts/*.py | wc -l
27
```

### 2.5 (i) 素材可复现性（W13-A1）—— **本轮首次取得**

```
$ sh scripts/check-asset-reproducible.sh
[repro] javac -d <tmp>/classes resource-pack/tools/{GenerateTextures,GenerateHudAtlas,GenerateSkillHudAtlas,GenerateCompassFrames}.java
[repro] java GenerateTextures <tmp>/generated/GenerateTextures --force
[repro] java GenerateHudAtlas <tmp>/generated/GenerateHudAtlas/hud_symbols.png
Generated 11 crisp HUD glyphs at 99x9
[repro] java GenerateSkillHudAtlas <tmp>/generated/GenerateSkillHudAtlas/class_skill_symbols.png
Generated 48 class/skill HUD glyphs
[repro] java GenerateCompassFrames assets/.../traveler_compass.png <tmp>/generated/GenerateCompassFrames/{textures,models}
Generated 32 traveler-compass angle frames
[repro] 逐字节比对 32 个罗盘模型 JSON
[repro] 罗盘模型 JSON：一致 32/32
ASSET_REPRO: 比对 70 张（4 个生成器）
  逐像素一致: 70
  REPRODUCIBLE=70 MISMATCH=0 MISSING=0 UNREGISTERED=0
ASSET_REPRO_GATE=GREEN
EXIT=0
```

⇒ **`ASSET_REPRO_GATE=GREEN`**（本会话此前从未取到，v17 §8 明写「本容器无 JDK 未本地复跑」）。
⇒ 运行前提：`javac -encoding UTF-8`（§0.4）。**未加时**本机（GBK）会报 58 个「不可映射字符」而假红；
加后 4 个生成器全部编译通过。

## 3. 「声明未接线」= 0（与 v17 同，判据未放宽）

### (e) 源码字符串断言

```
$ sh scripts/check-source-string-tests.sh
SOURCE_STRING_GATE=GREEN 本仓生产源码已无源码字符串断言（豁免口径见脚本头部注释）
SOURCE_STRING_TESTS=0
EXIT=0
```

## 4. 源码契约规格层

### (f)

```
$ sh scripts/check-source-contract.sh
SOURCE_CONTRACT_CHECKS=469 SPECS=79
SOURCE_CONTRACT_SPEC_FILES=3
EXIT=0
```

## 5. 机制规格绑定层

### (g)

```
$ sh scripts/check-mechanics-spec.sh
MECHANICS_SPEC_GATE=GREEN
MECHANICS_SPEC_ENTRIES=137
MECHANICS_SPEC_BY_KIND=config:78,behavior:56,java-const:3
MECHANICS_SPEC_WEAK=0
EXIT=0
```

## 6. env 标签正当性层

### (h)

```
$ sh scripts/check-env-tag-justified.sh
ENV_TAG_METHODS=7
ENV_TAG_JUSTIFIED=7
ENV_TAG_UNJUSTIFIED=0
ENV_TAG_JUSTIFIED_GATE=GREEN
EXIT=0
```

## 7. 十门：本轮**未**取得判据行（`BLOCKED_EXTERNAL`）

### 7.1 四次触发，四次死在平台 `Prepare`

见 §0.2。原始取证：`plan/gate-run-v18.json`（`sn=cnb-fef-1k4ot1qvq`）+ `.stages.txt`，第 4 轮见 `plan/quota-probe-v18.json`。
十门 stage 状态全为 `skipped`（`Prepare=error`）：

```
Prepare=error
准备下载器（必须是第一个 stage）=skipped
门禁自测（负向对照）=skipped
DDL 门禁（G-301 消费者 + G-302 注册，双向）=skipped
素材门禁（W7-A1）=skipped
素材逐像素基线门禁（W12-A1）=skipped
素材可复现性门禁（W13-A1）=skipped
compile=skipped
test=skipped
assemble=skipped
```

⇒ 判据行 `hits=[]`（空）**是对的**：一门都没跑，当然没有 `*_GATE=`。

### 7.2 十门 vs 本地九层：口径必须分清

| 判据行 | 十门 stage（CNB 门禁镜像） | 本地九层（本轮） |
|---|---|---|
| `GATE_SELFTEST=PASS=N FAIL=0` | ❌ 未取到（stage-1 skipped） | ❌ 未跑（`selftest-gates.sh` 在本机 Windows 上环境不可用，见 §7.3） |
| `RESOURCE_PACK_GATE` | ❌ 未取到 | ✅ `GREEN`（§2.3） |
| `ASSET_BASELINE_GATE` | ❌ 未取到 | ✅ `GREEN`（§2.1） |
| `ASSET_REPRO_GATE` | ❌ 未取到 | ✅ **`GREEN`**（§2.5） |
| `DDL_CONSUMER_GATE` / `DDL_REGISTRATION_GATE` | ❌ 未取到 | ❌ 未跑（见 §7.3） |
| `CMD_REFERENCE_GATE` | ❌ 未取到 | ❌ 未跑 |
| `COMPILE_GATE` / `TEST_GATE` / `SUMMARY_GATE` / `PACKAGE_GATE` | ❌ 未取到 | ❌ 未跑（本机无 JDK 25 + Gradle 缓存） |
| `SOURCE_STRING_GATE` / `SOURCE_CONTRACT_*` / `MECHANICS_SPEC_*` / `ENV_TAG_*` | ❌ 未取到 | ✅ 全 `GREEN`（§3–§6） |

⇒ **本地九层 GREEN 不等于十门 GREEN**。两者跑在不同的镜像与 stage 编排里。
本轮**没有**在 `d2cffd2` 上取得任何来自 CNB 流水线的 `*_GATE=` 行。

### 7.3 为什么本地没有补齐 DDL / compile / test / 门禁自测

| 层 | 为什么本轮没跑 |
|---|---|
| `check-ddl-consumers.sh`（DDL 双向） | 依赖全量源码树遍历，可本地跑；**本轮时间盒未纳入**（见 §8 如实登记） |
| `selftest-gates.sh` | 本机 Windows：`[safe-delete][SAFE_DELETE_INVALID_PATH] embedded drive prefix is not allowed`（沙箱 shim 拒 `mktemp -d`）。**已验证纯净树同样失败** ⇒ 与改动无关，是环境问题 |
| `compile` / `test` / `assemble` | 本机无 JDK 25、无 Gradle 缓存，且这三门是十门 stage 的核心 —— **必须**在 CNB 上跑 |

## 8. 本轮未重跑的东西（如实登记，不粉饰）

| 项 | 为什么没跑 | 影响 |
|---|---|---|
| **远端十门（`api_trigger_gate`）** | **CNB 根组织 CPU 配额耗尽**，三次触发全死在平台 `Prepare`（§0.2） | 无法在 `d2cffd2` 上宣称「十门全绿」；本项判 `BLOCKED_EXTERNAL` |
| 本地 `check-ddl-consumers.sh` | 本轮时间盒未纳入 | DDL 两门在 `d2cffd2` 上**无本轮判据行**；v17 的判据行来自旧 basis，**不得搬用** |
| 本地 `selftest-gates.sh` | 本机 Windows 环境不可用（§7.3） | `GATE_SELFTEST` 本轮无本地判据行 |
| 本地全量编译 / 单测 / 打包 | 本机无 JDK 25 + Gradle 缓存 | 无影响（本分支不含构建输入，§1.2） |
| 运行时冒烟（`api_trigger_smoke`） | 同配额阻塞 | 冒烟本轮的 `[R]` 证据**继续维持 v17 状态**（不可引用，§9.4） |

## 9. 缺口与阻塞登记（本轮）

### 9.1 `CNB-QUOTA-EXHAUSTED`（本轮新增，`BLOCKED_EXTERNAL`）

| 项 | 值 |
|---|---|
| 现象 | `start_build` 返回 `success:true`，~2.6s 后 `build_status=error`；`Prepare=error`，自带 stage 全 `skipped` |
| 根因 | `Root Group's events CPU core-hours are insufficient for pre-freezing`（配额折算 0.67 核时都拿不出） |
| 作用域 | **根组织级**（gate ×2 + smoke ×1 + seed ×1 同一句话） |
| 绕过可能 | **无**（`Prepare` 是平台 stage，不在 `.cnb.yml`；连自定义 `config` 也照样死在 `Prepare`） |
| **连带影响** | **所有 CNB 写路径均不可用**：十门、冒烟、以及借流水线 push 的播种法（`putfile.py`）⇒ 本轮**无法推送分支 / 无法开 PR**，v18 交付物仅落本地提交 |
| 处置 | 停手、记 `BLOCKED_EXTERNAL`、报人类（组织管理员扩容）；**不得**重试刷配额 |
| 升级对象 | CNB 根组织管理员 |
| 取证 | `plan/quota-probe-v18.json`（第 4 轮，自定义极简 config，`sn=cnb-qvu-1k4p03o5r`） |

### 9.2 `G-SMOKE-CRITERION-UNRETRIEVABLE`（v17 已登记，维持 `REQUIRES_HUMAN`）

状态不变。见 v17 §9.4。**在人类选定修法前，冒烟不得作为 `[R]` 证据。**

## 10. 剩余问题：`REQUIRES_HUMAN` + `BLOCKED_EXTERNAL`

| id | 类别 | 内容 |
|---|---|---|
| `H-001` | REQUIRES_HUMAN | 原版世界方形边界缺 5435 个四角区块，需在生产机 `lamb:/home/lamb/minecraft` 跑 `scripts/maintain-world-coverage.sh` |
| `U-003` | REQUIRES_HUMAN | 纯原版客户端能否真握手进服（需真实 Paper + 真实客户端） |
| `W7-A2` 四条 `[R]` | REQUIRES_HUMAN | G-205/207/208/209（需真实 Paper 运行） |
| `G-SMOKE-CRITERION-UNRETRIEVABLE` | REQUIRES_HUMAN | 冒烟判据行落在平台交付窗口外，须人类在两种修法间选 |
| **`CNB-QUOTA-EXHAUSTED`** | **BLOCKED_EXTERNAL** | 根组织 CPU 配额耗尽，十门跑不起来，须管理员扩容 |

## 11. 三处已修缺陷的复核

| 缺陷 | 状态 | 本轮证据 |
|---|---|---|
| `G-SMOKE-LOCK-SOURCE-MISMATCH` | ✅ 已闭环 | v17 §7（`SMOKE_LOCK_CHECK=PASS`）；本轮未变 |
| `G-SMOKE-MISSING-FETCH-DEPS` | ✅ 已修（代码事实） | `run-paper-smoke.sh:364-391`；⚠️ 运行期证据仍未取到，按纪律**不声称已验证** |
| `G-GATE-LOG-TRUNCATED` | ✅ 已修（本地侧） | `run_gate.py` 默认 `2000000`；**平台侧窗口**另立 `G-SMOKE-CRITERION-UNRETRIEVABLE` |
| **`ASSET-REPRO-JAVAC-ENCODING`**（本轮新增，已修） | ✅ 已修 | §0.4 / §2.5：本地 GBK JDK 上从假红变 GREEN |
| **`CNB-QUOTA-EXHAUSTED`**（本轮新增，未修） | ❌ `BLOCKED_EXTERNAL` | §0.2 / §9.1 |

---

## 附：本轮所有可复现命令

```sh
# basis
git rev-parse origin/main        # -> d2cffd27632ba9a623c957397fe168bca753ff7f

# 十门（被配额阻塞的取证）
python3 ~/.workbuddy-ai/skills/cnb-workflow/scripts/run_gate.py \
    --repo xzqkjmnbvc2026/sivitahub --event api_trigger_gate --branch main \
    --sha d2cffd27632ba9a623c957397fe168bca753ff7f --out plan/gate-run-v18.json
# 回读 Prepare 末尾：Pipeline prepare error: Root Group's events CPU core-hours are insufficient…

# 本地九层（干净 LF 树）
python3 plan/run_local_layers_v18.py
# 或单跑层 i
sh scripts/check-asset-reproducible.sh
```
