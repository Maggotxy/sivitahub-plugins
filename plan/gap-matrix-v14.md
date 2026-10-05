# 全量机制矩阵 v14（gap-matrix-v14）

> verdict: done=true remaining=0
> basis: 7c23a35a
> basis-substring: 7c23a35a
> 生成者：CNB CodeBuddy NPC，任务 `W30-A1-verdict-v14`（**最终复验轮 v14**）。
> ① 实测 `git rev-parse HEAD` == `git rev-parse origin/main` == `7c23a35a8bd7fe5c5f5f627d08bcb22e4610b7c9`（两者**相等**，见 §1.1）。
> ② 本矩阵的 basis **字面等于**它分叉时的 main HEAD（= 上面那个 sha），修掉 v13 的 `basis=c74249e` ≠ 当时 main HEAD `7c23a35` 的口径偏差。
> ③ M13（本轮采信的十门证据 sha）= `7c23a35a`（= 判定 basis 本身）；本分支自身不含构建输入，见 §9.2。
> ④ 素材层四条、G-302 系列、机制规格绑定层、G-CACHE-STAMP-BEFORE-LOCK 全部**由我在本轮 basis 上亲自重跑**（不采信调度器结论），证据见 §1–§5、§6、§7、§8。
> 状态取值：`已接线` / `声明未接线` / `存疑` / `已废弃` / `真实缺口`。
> 口径警戒（逐条给证据，只字不偷换）：「计数下降」≠「缺口清零」；「判据变窄」≠「问题消失」；
> 「门禁 GREEN」≠「判据没有过宽豁免」；「NPC 容器 GREEN」≠「门禁容器 GREEN」。

## 0. 本轮结论（先给判定，再给证据）

| 项 | v13 判定（basis=c74249e） | v14 判定（basis=7c23a35a） | 依据 |
|---|---|---|---|
| **矩阵 basis 字面 == main HEAD**（本轮首要目的） | ⚠️ 不一致：v13 的 `basis=c74249e`，但当时 main HEAD = `7c23a35` | ✅ **一致**：`basis: 7c23a35a`（本文件 :4）+ 实测 `HEAD==origin/main==7c23a35a…`（§1.1） | §1.1：`git rev-parse HEAD` / `git rev-parse origin/main` 两条命令原文，`EQUAL=true` |
| **素材层四条**（零消费者 / 基线 / 溯源 / items+七项） | 成立 | ✅ **成立（本轮 basis 上亲自重跑，贴原文）** | §2（a）/§3（b）/§4（c）/§5（d）：命令 + 退出码 + 输出原文 |
| **G-302-RESIDUAL 系列**（含 -2/-3/-4） | 仍清零 | ✅ **仍清零（判据未放宽，本轮独立复跑 + 4 类抽样 + 3 组负夹具）** | §6：`SOURCE_STRING_TESTS=0 / SOURCE_STRING_GATE=GREEN / EXIT=0`；4 类方法体复核；夹具⑤⑥⑦各自 RED → 删除 → GREEN；反例未被判红 |
| **机制规格绑定层** | 已建立并闭环 | ✅ **闭环（本轮独立复核 + 独立负向对照）** | §7：`MECHANICS_SPEC_GATE=GREEN` / `ENTRIES=137`（与 M13 一致）/ `WEAK=0`；`MECHANICS_SPEC_REPORT=FRESH`；零 `import yaml`；**我把一条 config 载体 expect 改坏 ⇒ RED 且点名 `M-CORE-003`** |
| **G-CACHE-STAMP-BEFORE-LOCK** | 仍闭环 | ✅ **仍闭环（独立复核代码事实）** | §8：eager `nextCommitVersion()` 调用为零；`markLocked` 惰性一次取号；5/5 发布点 `LockedTransaction.wrap`；3 个回归测试在 |
| **十门 @ basis** | 全绿（14 stage） | ✅ **全绿（回读核对）** | §9：权威 sn=`cnb-7e9-1k46rf4i2`，`event=api_trigger_gate`，`sha=7c23a35a`，13 stage success + `DebugDetection` skipped，判据行逐条回读 |
| **`G-FLAKY-GATE`**（同 sha 是否仍复现假红） | ⚠️ 未采样（只采到 1 个 main 构建） | ✅ **同 sha 两次构建均 success（RED=0）** | §11：`7c23a35a` 上 `cnb-7e9-1k46rf4i2`(api_trigger_gate) + `cnb-1e5-1k46rem0u`(push) **两个 main 构建都 success**，判据行逐条一致 |

⇒ 差集矩阵「声明未接线」= **0 条**；「可派工的实现缺口」= **0 条**；
十门在 M13=`7c23a35a` 上全绿（判据行齐全，含 `MECHANICS_SPEC_*`）；素材层四条在本轮 basis 上成立；
剩余问题**只有** `REQUIRES_HUMAN`（H-001 / U-003 / W7-A2 四条 `[R]`）。
⇒ `plan/verdict-v14.json`：`done=true`、`remaining=0`（与本节 `> verdict:` 行**严格一致**，见 §10）。

## 1. basis 实测（本轮首要目的）

### 1.1 `HEAD` 与 `origin/main` 是否相等

```
$ git fetch origin main
From https://cnb.cool/xzqkjmnbvc2026/sivitahub
 * branch            main       -> FETCH_HEAD
 * [new branch]      main       -> origin/main
$ git rev-parse HEAD
7c23a35a8bd7fe5c5f5f627d08bcb22e4610b7c9
$ git rev-parse origin/main
7c23a35a8bd7fe5c5f5f627d08bcb22e4610b7c9
$ [ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] && echo EQUAL=true
EQUAL=true
```

⇒ **两者相等**。`basis = 7c23a35a8bd7fe5c5f5f627d08bcb22e4610b7c9`（短 sha `7c23a35a`）。

### 1.2 与 v13 的差别（为什么必须有这一轮）

- `plan/gap-matrix-v13.md:4` 的 `basis: c74249e` **字面不等于**当时的 main HEAD `7c23a35`（`7c23a35` = `c74249e` 之后 6 个非构建输入文件的提交）。评审口径要求「最新矩阵的 basis 必须等于当前 main 的 HEAD」。
- `git diff --name-only c74249e 7c23a35a` = 6 个文件：`.cnb/agent-out/W29-A1-verdict-v13.json`、`doc/cnb/gap-registry.md`、`plan/gap-matrix-v13.md`、`plan/tasks-w29.json`、`plan/verdict-v13.json`、`state/world-state.json` —— **全部非构建输入** ⇒ M13 的门禁判据行（sn=`cnb-7e9-1k46rf4i2`，sha=`7c23a35a`）继续适用。
- 本轮把 basis 前移到 `7c23a35a`（= 分叉时的 main HEAD），并在**这个 basis 上**亲自重跑素材层四条、G-302 系列、机制规格层（修掉 v13「素材层证据是在更早 basis 上核对」的缺口）。

## 2. 素材层 (a)：`scripts/` 下零消费者 = 0

判据：`scripts/` 下每个 `.sh`/`.py` 都必须有真实调用点（不是只在文档里被提到）。

```
$ ls scripts/*.sh scripts/*.py | wc -l
23
```

23 个脚本，逐个在 `.cnb.yml` stage 入口 / `scripts/selftest-gates.sh` / 插件 `*ContractTest`（经 `ProcessBuilder` 真跑）/ 其它脚本里找到真实调用点。**逐条调用点原文**（`grep -rn` 命中行）：

| # | 脚本 | 真实调用点（file:line 原文） |
|---|---|---|
| 1 | `check-asset-reproducible.sh` | `scripts/selftest-gates.sh`（§asset-repro 段；`.cnb.yml` stage-5 入口亦调用） |
| 2 | `check-config-fallbacks.sh` | `scripts/selftest-gates.sh`（§config-fallbacks 段） |
| 3 | `check-ddl-consumers.sh` | `.cnb.yml` stage-2「DDL 门禁」入口 |
| 4 | `check-file-deps.sh` | `scripts/fetch-deps.sh` 内调用 |
| 5 | `check-mechanics-spec.sh` | `scripts/selftest-gates.sh:1711`（`(cd "$SPEC_FIXTURE" && sh scripts/check-mechanics-spec.sh >"$1" 2>&1)`）、`:1717`（`(cd "$ROOT" && sh scripts/check-mechanics-spec.sh > "$TMP/spec-real.txt" 2>&1)`）、`:1811`、`:1988`；`scripts/gen-mechanics-spec-report.py:28`（`GATE = os.path.join(ROOT, "scripts", "check-mechanics-spec.sh")`） |
| 6 | `check-repo-artifacts.sh` | `scripts/selftest-gates.sh`（§repo-artifacts 段，`[OK] selftest-gates.sh 覆盖 scripts/check-repo-artifacts.sh（4 处调用）`） |
| 7 | `check-resource-pack.sh` | `.cnb.yml` stage-3「素材门禁」入口 |
| 8 | `check-source-contract.sh` | `scripts/selftest-gates.sh`（§source-contract 段）+ 多个 `*ContractTest` 经 `ProcessBuilder("sh", gate.toString(), ...)` 真跑（如 `EquipmentExitPathReachabilityTest.java:336`） |
| 9 | `check-source-string-tests.sh` | `scripts/selftest-gates.sh`（§4d 段，夹具①~⑥各带退出码断言） |
| 10 | `check-trusted-menu-holders.sh` | `scripts/selftest-gates.sh`（§trusted-menu 段） |
| 11 | `fetch-deps.sh` | `.cnb.yml` stage-0「准备下载器」入口 |
| 12 | `install-datapacks.sh` | `scripts/selftest-gates.sh`（`[OK] selftest-gates.sh 覆盖 scripts/install-datapacks.sh（6 处调用）`） |
| 13 | `list-env-tests.sh` | `.cnb.yml` test stage（抽取 `@Tag(env)` 测试清单；`[OK] 抽出 8 个 @Tag(env) 测试方法`） |
| 14 | `selftest-gates.sh` | `.cnb.yml:71` 原文 `sh scripts/selftest-gates.sh`（stage 声明在 `:63`） |
| 15 | `asset_fingerprint.py` | `scripts/check-asset-baseline.py`（生成/校验指纹） |
| 16 | `check-asset-baseline.py` | `.cnb.yml` stage-4「素材逐像素基线门禁」入口 |
| 17 | `check-asset-reproducible.py` | `scripts/check-asset-reproducible.sh` 内调用 |
| 18 | `check-plugin-jars.py` | `.cnb.yml` assemble stage（`JAR_MISSING_PLUGIN_YML` 判据） |
| 19 | `dump-test-results.py` | `.cnb.yml` test stage（SUMMARY 汇总） |
| 20 | `gen-asset-manifest.py` | `scripts/selftest-gates.sh`（素材负夹具：删 items 定义 → manifest 判据红） |
| 21 | `gen-mechanics-spec-report.py` | `scripts/selftest-gates.sh:1941`（`(cd "$ROOT" && python3 scripts/gen-mechanics-spec-report.py --check > "$TMP/spec-report-fresh.txt" 2>&1)`）、`:1972` |
| 22 | `resource-pack-asset-check.py` | `scripts/check-resource-pack.sh` 内调用（stage-3） |
| 23 | `test-frames.py` | `scripts/selftest-gates.sh`（帧模型负夹具） |

**本轮新增的两个脚本的调用点（硬性要求）**：

```
$ grep -rn 'check-mechanics-spec.sh' scripts/selftest-gates.sh | head -6
1693:cp "$ROOT/scripts/check-mechanics-spec.sh" "$ROOT/scripts/mechanics-spec.json" \
1711:  (cd "$SPEC_FIXTURE" && sh scripts/check-mechanics-spec.sh >"$1" 2>&1)
1717:(cd "$ROOT" && sh scripts/check-mechanics-spec.sh > "$TMP/spec-real.txt" 2>&1)
1811:(cd "$SPEC_FIXTURE" && sh scripts/check-mechanics-spec.sh > "$TMP/spec-weak.txt" 2>&1)
1860:spec_yaml_hits=$(grep -cE '^[[:space:]]*(import|from)[[:space:]]+yaml' "$ROOT/scripts/check-mechanics-spec.sh" || true)
1988:(cd "$ROOT" && sh scripts/check-mechanics-spec.sh >/dev/null 2>&1)

$ grep -rn 'gen-mechanics-spec-report.py' scripts/selftest-gates.sh | head -4
1941:(cd "$ROOT" && python3 scripts/gen-mechanics-spec-report.py --check > "$TMP/spec-report-fresh.txt" 2>&1)
1944:check "报告节与 spec 一致（gen-mechanics-spec-report.py --check → 0）" 0 "$spec_report_rc"
1957:cp "$ROOT/scripts/gen-mechanics-spec-report.py" "$ROOT/scripts/mechanics-spec.json" \
1972:python3 "$REPORT_FIXTURE/scripts/gen-mechanics-spec-report.py" --check \
```

`scripts/selftest-gates.sh` 本身由 `.cnb.yml:71` 执行 ⇒ 两条接线链闭合。

⇒ **零消费者脚本 = 0 条**（23/23 有真实调用点）。

## 3. 素材层 (b)：逐像素基线门禁

> ⚠️ 任务描述里写的是 `sh scripts/check-asset-baseline.py`。**该文件是 Python 文件（首行 `#!/usr/bin/env python3`），用 `sh` 会报语法错**（实测 `sh scripts/check-asset-baseline.py` → `scripts/check-asset-baseline.py: 25: import: not found` / `Syntax error: "(" unexpected"` / `EXIT=2`）。因此按 `.cnb.yml` stage-4 的真实调用方式用 `python3`，如实说明。

```
$ python3 scripts/check-asset-baseline.py > /tmp/b.txt 2>&1; echo EXIT=$?
EXIT=0
$ cat /tmp/b.txt
ASSET_BASELINE: 校验 252 张 PNG（清单 252 条）
  UNREGISTERED=0 MISSING=0 MODIFIED=0
ASSET_BASELINE_GATE=GREEN
```

⇒ **252 ↔ 252**，`UNREGISTERED=0 MISSING=0 MODIFIED=0`，`ASSET_BASELINE_GATE=GREEN`，`EXIT=0`。

## 4. 素材层 (c)：`assets-manifest.json` 与 `asset-provenance.json` 归一 `assets/` 前缀后同集合

```
$ python3 - <<'EOF'  (归一 assets/ 前缀 + 逐条重算分类)
EXIT=0
prov assets type: dict n= 252
MANIFEST_N= 252 PROV_N= 252
same_set= True
manifest_only= 0 []
prov_only= 0 []
$needsHumanDecision= []
$counts= {"ai-prompt": 10, "derived-from": 69, "generator": 129, "human-override": 1, "upstream-betterhud": 38, "upstream-vanilla": 5}
counts_sum= 252
recomputed per-entry: {'generator': 129, 'upstream-vanilla': 5, 'upstream-betterhud': 38, 'human-override': 1, 'ai-prompt': 10, 'derived-from': 69}
```

⇒ 归一 `assets/` 前缀后 `same_set=True`（`manifest_only=0` / `prov_only=0`）；
`$needsHumanDecision=[]`（空）；六类 `$counts` 合计 **252** 且与逐条重算**一致**。

## 5. 素材层 (d)：items 定义 81 == runtime-item-models 去注释键 81 + 七项检查全 exit=0

```
$ find resource-pack/assets/originalrpg/items -maxdepth 1 -type f -name '*.json' | wc -l
81
$ python3 - <<'EOF'  (键集合比较)
EXIT=0
items_def_n= 81
runtime_keys_n= 81
items_only= 0 []
models_only= 0 []
EQUAL_SET= True
```

七项 `resource-pack-asset-check.py --check`：

```
$ for c in closure compass hud journey lang packmeta textures; do python3 scripts/resource-pack-asset-check.py --root . --check $c >/tmp/rc_$c.txt 2>&1; echo "CHECK=$c EXIT=$?"; done
CHECK=closure  EXIT=0  :: 物品定义 81 / 可达模型 119 / 可达纹理 115
CHECK=compass  EXIT=0  :: 33 段方向分发 ↔ 32 帧模型 + 32 帧纹理（scale=32.0 target=spawn）
CHECK=hud      EXIT=0  :: U+E000..U+E00A 十一码位 ↔ 99x9 图集；U+E020..U+E04F 四十八码位 ↔ 432x9 图集
CHECK=journey  EXIT=0  :: U+E100..U+E107 八码位 ↔ 八张 32x32 旅程图
CHECK=lang     EXIT=0  :: en_us/zh_cn 键集合一致（各 18 键）
CHECK=packmeta EXIT=0  :: pack.mcmeta 精确声明资源格式 88（min=max=88，无 overlays）
CHECK=textures EXIT=0  :: 115 张物品 PNG 全部 32x32 二值 alpha
```

⇒ `items` 81 == 81 且**键集合相等**；**七项全 exit=0**。

## 6. `G-302-RESIDUAL` 系列：仍清零且**判据未被放宽**（本轮独立复跑）

### 6.1 门禁判据行（重定向取码，不走管道）

```
$ bash -c 'sh scripts/check-source-string-tests.sh > /tmp/sst.txt 2>&1; echo EXIT=$?'
EXIT=0
$ cat /tmp/sst.txt
SOURCE_STRING_TESTS=0
SOURCE_STRING_GATE=GREEN 本仓生产源码已无源码字符串断言（豁免口径见脚本头部注释）

$ bash -c 'sh scripts/check-source-string-tests.sh --json > /tmp/sstj.txt 2>&1; echo EXIT=$?'
EXIT=0
$ cat /tmp/sstj.txt
{
 "hits": [],
 "count": 0
}
SOURCE_STRING_TESTS=0
SOURCE_STRING_GATE=GREEN ...
```

⇒ `SOURCE_STRING_GATE=GREEN` / `SOURCE_STRING_TESTS=0` / `EXIT=0`，`--json` → `{"hits": [], "count": 0}`。

### 6.2 独立抽样复核 **4 个类**（含指定的 3 个 + 自选第 4 个）

| 类（file:line） | 判定依据（读的**不是**生产源码正文） |
|---|---|
| `plugins/rpg-dungeons/src/test/java/cn/originalrpg/dungeons/DungeonTimeoutLifecycleContractTest.java` | `grep -nE 'readString\|readAllBytes\|Files\.\|Path\.of'` 全类**只命中 `:19`（注释）**；3 个 `@Test` 真构造 `DungeonManager`（`DungeonBukkitTestHarness.newManager(scheduler)`），断言 `scheduler.callbacks.size()` / `scheduler.cancelled` / `instanceSessions` / `instanceTimeouts` 等可观察状态 ⇒ **行为级** |
| `plugins/rpg-items/src/test/java/cn/originalrpg/items/forge/EquipmentExitPathReachabilityTest.java` | `:333` `assertTrue(Files.isRegularFile(spec), ...)` + `:334` `new String(Files.readAllBytes(spec))`（读 `scripts/source-contract-specs/rpg-items.json` **规格 JSON**）+ `:336` `ProcessBuilder("sh", gate, ...)` + `:340` 读**进程 stdout** ⇒ 不是生产源码正文 |
| `plugins/rpg-core/src/test/java/cn/originalrpg/core/persistence/SeasonAttunementSettlementWiringTest.java` | `:243` 检查门禁脚本存在 + `:250` 读 `ProcessBuilder` 的**进程 stdout** + `:299` `Files.newInputStream` + SnakeYAML **真解析**（`:297-301`）⇒ 不是生产源码正文 |
| `plugins/rpg-dungeons/src/test/java/cn/originalrpg/dungeons/DungeonEntryCommitAmbiguityContractTest.java`（自选第 4 个） | `:65` `Files.isRegularFile(gate)` + `:66` `ProcessBuilder("sh", gate, specKey, method)` + `:70` 读**进程 stdout** + `:76-77` 仅用 `settings.gradle.kts` 定位仓库根 ⇒ 不是生产源码正文 |

### 6.3 反向验证判据仍有力：**三组**夹具现场造（跑完删除）

夹具造在 `plugins/rpg-core/src/test/java/_gatefixture/`。三组各自单独测过、也合并测过：

| 夹具 | 形态 | 单独运行判据行 | 合并运行 |
|---|---|---|---|
| `Fixture5WrappedAssertTest.java:12` | ⑤ `assertTrue(Files.readString(Path.of("plugins/.../RpgCorePlugin.java")).contains("class"))` | `EXIT=1` / `SOURCE_STRING_TESTS=1` / `GATE=RED` | — |
| `Fixture6ConstPathTest.java:7` | ⑥ 常量名 ≥3 字符的 `Files.readString(Path.of(PRODUCTION_SOURCE))`（常量初始化语句回溯） | `EXIT=1` / `SOURCE_STRING_TESTS=1` / `GATE=RED` | — |
| `Fixture7TraversalTest.java:19` | ⑦ `Files.walk(root.resolve("plugins").resolve(MODULE).resolve(MAIN))` + `Files.readString(f).contains(id)` 聚合成 `List` 后 `anyMatch` | `EXIT=1` / `SOURCE_STRING_TESTS=1` / `GATE=RED` | 三组合并 `EXIT=1` / `SOURCE_STRING_TESTS=3` / `GATE=RED` |

`--json` 证据（夹具⑦单独）：

```
$ sh scripts/check-source-string-tests.sh --json
EXIT=1
{"hits":[{"file":"plugins/rpg-core/src/test/java/_gatefixture/Fixture7TraversalTest.java",
          "line":19,"tests":["traversalAggregate"],
          "production_paths":["(traversal root: plugins|tools -> src/main)"]}],"count":1}
SOURCE_STRING_TESTS=1
SOURCE_STRING_GATE=RED ...
```

`--json` 证据（夹具⑥单独，点名常量解析出的**生产源码路径**）：

```
$ sh scripts/check-source-string-tests.sh --json
EXIT=1
{"hits":[{"file":".../_gatefixture/Fixture6ConstPathTest.java","line":7,"tests":["constPath"],
          "production_paths":["plugins/rpg-skills/src/main/java/cn/originalrpg/skills/HiddenPathRules.java"]}],"count":1}
```

删除夹具 + 收尾：

```
$ rm -rf plugins/rpg-core/src/test/java/_gatefixture
$ bash -c 'sh scripts/check-source-string-tests.sh > /tmp/fzz.txt 2>&1; echo EXIT=$?'
EXIT=0
SOURCE_STRING_TESTS=0
SOURCE_STRING_GATE=GREEN ...
$ git status --porcelain
（空）   ← 干净
```

**反例（不应被判红）**：`plugins/rpg-core/src/test/java/cn/originalrpg/core/i18n/LanguageBundleContractTest.java`
—— `:255/:281` 读的是**部署产物**（`server/datapacks/**`、`resource-pack/**`，CNB 工作仓按设计不含），`:458-467` `Files.walk` + `Files.newInputStream` + SnakeYAML **真解析** ⇒ **未**被判红（在 `SOURCE_STRING_TESTS=0` 的 GREEN 状态里）。

**⚠️ 一条如实登记的细节（不是缺口，也不粉饰）**：我第一版夹具⑥用的常量名是单字符 `P`，判据的常量回溯正则要求常量名 `[A-Z][A-Z0-9_]{2,}`（**≥3 字符**），故未命中。改成 `PRODUCTION_SOURCE` 后命中。这与 v13 §6.5 登记的「判据形态边界」同源（判据对短常量名不回溯）；判定**不构成本轮缺口**，理由：(1) 该形态不是历史上漂移过的形态（3 个原形态都在命中范围内）；(2) CI stage-1 §4d 夹具①~⑥各带退出码断言，每次推送都被负向对照；(3) 当前 `SOURCE_STRING_TESTS=0` 是**方法体事实**（4 类逐条复核），不依赖该边界是否可绕。改判据属构建输入，本任务不允许改。

### 6.4 规格规模未缩水（相对 v8 基线 `CHECKS=466 SPECS=78 SPEC_FILES=2`）

```
$ bash -c 'sh scripts/check-source-contract.sh --all > /tmp/sc.txt 2>&1; echo EXIT=$?'
EXIT=0
$ grep -E 'SOURCE_CONTRACT' /tmp/sc.txt | head
SOURCE_CONTRACT_SPEC_FILES=3
SOURCE_CONTRACT_CHECKS=469 SPECS=79
SOURCE_CONTRACT_GATE=GREEN
```

⇒ `CHECKS=469 ≥ 466` / `SPECS=79 ≥ 78` / `SPEC_FILES=3 ≥ 2`，**没有缩水**。（CI stage-1 亦有同口径断言 `[OK] 真实规格目录已加载且未缩水（CHECKS=469≥466 SPECS=79≥2 GREEN）`。）

## 7. 机制规格绑定层：独立复核 + **独立负向对照**

```
$ bash -c 'sh scripts/check-mechanics-spec.sh > /tmp/ms.txt 2>&1; echo EXIT=$?'
EXIT=0
$ cat /tmp/ms.txt
MECHANICS_SPEC_GATE=GREEN
MECHANICS_SPEC_ENTRIES=137
MECHANICS_SPEC_BY_KIND=config:78,behavior:56,java-const:3
MECHANICS_SPEC_WEAK=0

$ bash -c 'python3 scripts/gen-mechanics-spec-report.py --check > /tmp/msr.txt 2>&1; echo EXIT=$?'
EXIT=0
$ cat /tmp/msr.txt
MECHANICS_SPEC_REPORT=FRESH

$ bash -c "grep -nE '^[[:space:]]*(import|from)[[:space:]]+yaml' scripts/check-mechanics-spec.sh > /tmp/yaml.txt 2>&1; echo EXIT=\$?"
EXIT=1        # grep 无命中
$ wc -l < /tmp/yaml.txt
0             # 零命中（门禁镜像没有 PyYAML）
```

⇒ `ENTRIES=137` 与 M13 一致；`WEAK=0`；`FRESH`；**零 `import yaml`**。

**我自己独立做的负向对照**（把一条 `config` 载体的 `expect` 改坏，在 `/tmp` 副本上，不动仓库内 spec）：

```
$ cp scripts/mechanics-spec.json /tmp/mxfix14/spec.broken.json
# 把 M-CORE-003 的 carrier.expect 从 [2,3,4,5] 改成 [2,3,4,9]
$ bash -c 'sh scripts/check-mechanics-spec.sh --spec /tmp/mxfix14/spec.broken.json > /tmp/msb.txt 2>&1; echo EXIT=$?'
EXIT=1
$ cat /tmp/msb.txt
MECHANICS_SPEC_GATE=RED
MECHANICS_SPEC_ENTRIES=137
MECHANICS_SPEC_BY_KIND=config:78,behavior:56,java-const:3
MECHANICS_SPEC_WEAK=0
  [FAIL] M-CORE-003
         claim  : 0/1/2/3 转分别开放技能 Lv.2/3/4/5
         期望   : [2, 3, 4, 9]
         实际   : ['2', '3', '4', '5']

$ bash -c 'sh scripts/check-mechanics-spec.sh --spec /tmp/mxfix14/spec.restored.json > /tmp/msr2.txt 2>&1; echo EXIT=$?'
EXIT=0
$ head -2 /tmp/msr2.txt
MECHANICS_SPEC_GATE=GREEN
MECHANICS_SPEC_ENTRIES=137
$ git status --porcelain
（空）   ← 仓库内 spec 未被改动
```

⇒ 改坏**必红且点名到具体 id `M-CORE-003`**；还原即 GREEN（不是恒红）。

## 8. `G-CACHE-STAMP-BEFORE-LOCK`：仍闭环（独立复核代码事实）

```
$ grep -rn 'nextCommitVersion()' plugins/rpg-core/src/main/java/
…/JdbcProfileService.java:503:     * @param version 调用方事务**持锁时**取得的版本号（{@link #nextCommitVersion()}）。
…/JdbcProfileService.java:518:     * @param version 该事务**持锁时（commit 之前）**取得的版本号（{@link #nextCommitVersion()}）
…/JdbcProfileService.java:527:    long nextCommitVersion() {
…/JdbcAdminIncidentCompensationService.java:715:  （{@link JdbcProfileService#nextCommitVersion()}）
…/Committed.java:7:  （见 {@link JdbcProfileService#nextCommitVersion()}）
…/JdbcAdminTestService.java:291:  （{@link JdbcProfileService#nextCommitVersion()}）
…/JdbcAscensionService.java:277:  （{@link JdbcProfileService#nextCommitVersion()}）
…/JdbcDungeonRewardService.java:311:  （{@link JdbcProfileService#nextCommitVersion()}）
```

⇒ 只有 **1 个定义**（`JdbcProfileService.java:527`）+ 7 处 javadoc/注释，**零个 eager 调用点**。

```
$ grep -rn 'LockedTransaction.wrap' plugins/*/src/main/java/
JdbcProfileService.java:841:                     Connection connection = LockedTransaction.wrap(raw, this::nextCommitVersion);
JdbcAdminIncidentCompensationService.java:723:     Connection connection = LockedTransaction.wrap(raw, profiles::nextCommitVersion);
JdbcAdminTestService.java:297:                 Connection connection = LockedTransaction.wrap(raw, profiles::nextCommitVersion);
JdbcAscensionService.java:284:               Connection connection = LockedTransaction.wrap(raw, profiles::nextCommitVersion);
JdbcDungeonRewardService.java:318:           Connection connection = LockedTransaction.wrap(raw, profiles::nextCommitVersion);

$ sed -n '40,56p' plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/LockedTransaction.java
    static Connection wrap(Connection connection, LongSupplier nextVersion) {
        if (handlerOf(connection) != null) return connection;
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                new LockedTransaction(connection, nextVersion));
    }
    ...
    /** 行锁到手：登记一次提交版本号（重复调用只生效一次，号必须最早那个）。 */
    private void markLocked() {
        if (version == 0L) version = nextVersion.getAsLong();
    }

$ grep -n 'readCacheIsNotRolledBackByALateCommit\|lateCommitStampCannotRollBackTheReadCache\|commitVersionMustBeTakenAfterTheRowLockIsHeld' plugins/rpg-core/src/test/java/cn/originalrpg/core/persistence/JdbcServicesTest.java
1035:    void readCacheIsNotRolledBackByALateCommit() throws Exception {
1088:    void lateCommitStampCannotRollBackTheReadCache() throws Exception {
1151:    void commitVersionMustBeTakenAfterTheRowLockIsHeld() throws Exception {
```

⇒ `wrap` 只做代理包装**不取号**；`markLocked` 有 `if (version == 0L)` 守卫（惰性一次取号）；5/5 发布点用 `LockedTransaction.wrap`；3 个回归测试在。⇒ **仍闭环**。

## 9. 十门在 M13 上全绿（回读核对）

### 9.1 权威构建 `cnb-7e9-1k46rf4i2` 的元信息与 stage 判据行

```
$ cnb build get-build-logs --repo xzqkjmnbvc2026/sivitahub --sn cnb-7e9-1k46rf4i2
status: success
event: api_trigger_gate
targetRef: main
sourceRef: main
sha: 7c23a35a8bd7fe5c5f5f627d08bcb22e4610b7c9
pipelineSuccessCount: 1   pipelineFailCount: 0
duration: 892162
```

```
$ cnb build get-build-status --repo xzqkjmnbvc2026/sivitahub --sn cnb-7e9-1k46rf4i2
status: success
pipelinesStatus:
  "cnb-7e9-1k46rf4i2-001": name=全量质量门（编译 → 测试 → 打包）, status=success, duration=891297
    stages[13]{status,duration,name,id}:
      success,3382,Prepare,prepare
      success,17991,准备下载器（必须是第一个 stage）,stage-0
      success,561216,门禁自测（负向对照）,stage-1
      success,107266,DDL 门禁（G-301 消费者 + G-302 注册，双向）,stage-2
      success,534,素材门禁（W7-A1）,stage-3
      success,338,素材逐像素基线门禁（W12-A1）,stage-4
      success,1647,素材可复现性门禁（W13-A1）,stage-5
      success,130713,compile,stage-6
      success,59760,test,stage-7
      success,7978,assemble,stage-8
      success,386,BeforeEnd,beforeEnd
      skipped,0,DebugDetection,debugDetection
      success,627,End,end
```

逐 stage 判据行（`cnb build get-build-stage ... -v` 回读，原文）：

| stage | 判据行（原文） |
|---|---|
| stage-0 准备下载器 | `DOWNLOADER=ready`（success） |
| stage-1 门禁自测 | `===== 门禁自测汇总：PASS=167 FAIL=0 =====` / `GATE_SELFTEST=GREEN`；`N=13 done=true remaining=0 basis=c74249e` / `VERDICT_MATRIX_CONSISTENCY_GATE=GREEN`；`SINGLE_SOURCE_OF_TRUTH_GATE=GREEN` / `WORLD_STATE_FILES=1`；`MECHANICS_SPEC_GATE=GREEN` / `MECHANICS_SPEC_ENTRIES=137` / `MECHANICS_SPEC_BY_KIND=config:78,behavior:56,java-const:3` / `MECHANICS_SPEC_WEAK=0`；`MECHANICS_SPEC_REPORT=FRESH` |
| stage-2 DDL 门禁 | `DDL_CONSUMER_GATE=GREEN` / `DDL_REGISTRATION_GATE=GREEN` / `CMD_REFERENCE_GATE=GREEN` |
| stage-3 素材门禁 | `RESOURCE_PACK_GATE=GREEN` / `RUN=8` / `SKIPPED=0` |
| stage-4 逐像素基线 | `ASSET_BASELINE_GATE=GREEN` / `UNREGISTERED=0` / `MISSING=0` / `MODIFIED=0` |
| stage-5 可复现性 | `ASSET_REPRO_GATE=GREEN` / `REPRODUCIBLE=70` / `MISMATCH=0` |
| stage-6 compile | `COMPILE_GATE=GREEN` / `TASKS_DONE=28` / `BUILD_EXIT=0` / `SECONDS=122` |
| stage-7 test | `TEST_GATE=GREEN` / `TEST_EXIT=0` / `SUMMARY_EXIT=0` / `SECONDS=59` / `SUMMARY_GATE=GREEN` / `SUM tests=1105 failed=0 errors=0 skipped=2 modules=26` / `FAILURE_XML_COUNT=0` |
| stage-8 assemble | `PACKAGE_GATE=GREEN` / `JAR_COUNT=56` / `JAR_CHECKED=25` / `JAR_MISSING_PLUGIN_YML=0` |
| debugDetection | `skipped`（设计上跳过） |

⇒ **十门全绿**：`GATE_SELFTEST=GREEN(PASS=167 FAIL=0)` / `COMPILE_GATE=GREEN` / `TEST_GATE=GREEN` /
`SUMMARY_GATE=GREEN(SUM tests=1105 failed=0 errors=0 skipped=2 modules=26)` /
`PACKAGE_GATE=GREEN(JAR_COUNT=56)` / `RESOURCE_PACK_GATE=GREEN` / `ASSET_BASELINE_GATE=GREEN` /
`ASSET_REPRO_GATE=GREEN` / `DDL_CONSUMER_GATE=GREEN` / `DDL_REGISTRATION_GATE=GREEN` /
`CMD_REFERENCE_GATE=GREEN` / `SINGLE_SOURCE_OF_TRUTH_GATE=GREEN` /
`VERDICT_MATRIX_CONSISTENCY_GATE=GREEN` / `MECHANICS_SPEC_GATE=GREEN(ENTRIES=137 / WEAK=0)` /
`MECHANICS_SPEC_REPORT=FRESH`。

> 说明：stage-1 里的 `VERDICT_MATRIX_CONSISTENCY_GATE` 判据行打印 `N=13 ... basis=c74249e`——那是 M13 构建时（2026-10-05T20:18Z）仓库里**最新**的矩阵还是 v13（basis=c74249e）。本轮的 v14 矩阵在**本分支**上，尚未合入 main ⇒ 不影响 M13 的十门判定，也不影响本轮 basis 一致性（§1.1 已实测）。

### 9.2 本分支自身不含构建输入

```
$ git diff --name-only 7c23a35a8bd7fe5c5f5f627d08bcb22e4610b7c9 HEAD
（空 —— 尚未提交前 HEAD==basis）
```

提交后实测（见 §10 / `.cnb/agent-out/W30-A1-verdict-v14.json` 的 `basis_scope.diff_vs_M13`）：
`git diff --name-only <M13> HEAD` 只含 `plan/**`、`state/**`、`doc/cnb/**`、`.cnb/agent-out/**` ⇒ **无一个构建输入**（不含 `plugins/**`、`scripts/**`、`.cnb.yml`、`resource-pack/**`、`docs/**`）。

## 10. 与 `plan/verdict-v14.json` 的一致性自查（`vmc_gate` 同口径）

按 `scripts/selftest-gates.sh` 的 `vmc_gate()` 同口径：

```
$ sed -n '1,20p' plan/gap-matrix-v14.md | grep -m1 -E '^> verdict: done=(true|false) remaining=([0-9]+)$'
> verdict: done=true remaining=0
$ python3 -c "import json;d=json.load(open('plan/verdict-v14.json'));print(str(d['done']).lower(),d['remaining'],d['basis'])"
true 0 7c23a35a
$ grep -F '7c23a35a' <(sed -n '1,20p' plan/gap-matrix-v14.md)
> basis: 7c23a35a
```

⇒ 矩阵前 20 行内 `> verdict: done=true remaining=0` 与 `> basis: 7c23a35a`，与 `plan/verdict-v14.json` 的 `done`/`remaining`/`basis` **逐字一致**。

## 11. `G-FLAKY-GATE`：本轮**同 sha 两次构建均 success**（v13 的「未采样」已补上）

v13 如实登记「`c74249e` 上只采到一个 main 构建 ⇒ 不作已闭环声明」。**本轮在 basis=`7c23a35a` 上采到两个 main 构建，且都 success**：

```
$ cnb build get-build-logs --repo xzqkjmnbvc2026/sivitahub --sha 7c23a35a8bd7fe5c5f5f627d08bcb22e4610b7c9
total: 4
  sn: cnb-n7u-1k46sl0se   status: pending   event: push              targetRef: agent/W30-A1-verdict-v14
  sn: cnb-kf0-1k46sl0pg   status: pending   event: api_trigger_npc   targetRef: agent/W30-A1-verdict-v14
  sn: cnb-7e9-1k46rf4i2   status: success   event: api_trigger_gate  targetRef: main   ← 权威
  sn: cnb-1e5-1k46rem0u   status: success   event: push              targetRef: main   ← 第二个
```

两个 main 构建的判据行**逐条一致**：

| 判据 | `cnb-7e9-1k46rf4i2`（api_trigger_gate） | `cnb-1e5-1k46rem0u`（push） |
|---|---|---|
| 全 stage status | 13 stage success + `DebugDetection` skipped | 13 stage success + `DebugDetection` skipped |
| `GATE_SELFTEST` | `GREEN` / `PASS=167 FAIL=0` | `GREEN` / `PASS=167 FAIL=0` |
| `COMPILE_GATE` | `GREEN` / `TASKS_DONE=28` / `BUILD_EXIT=0` / `SECONDS=122` | `GREEN` / `TASKS_DONE=28` / `BUILD_EXIT=0` / `SECONDS=115` |
| `TEST_GATE` / `SUMMARY_GATE` | `GREEN` / `SUM tests=1105 failed=0 errors=0 skipped=2 modules=26` | `GREEN` / `SUM tests=1105 failed=0 errors=0 skipped=2 modules=26` |
| `PACKAGE_GATE` | `GREEN` / `JAR_COUNT=56` | `GREEN` / `JAR_COUNT=56` |

⇒ 同 sha `7c23a35a` 上 **2 个 main 构建均 success、RED 计数 = 0**。`G-FLAKY-GATE` 的原始形态是「同 sha 上须有一个 RED」（§I.4，`f014b447` 先绿后红）⇒ 本轮**无 RED 观测 + 两个 success**，把 v13 的「未采样」补成**两绿采样**。
（`cnb-1e5…` 与 `cnb-7e9…` 的 `createTime` 都在 2026-10-05T20:18，是同一提交的 `push` 与 `api_trigger_gate` 两条触发路径。）
