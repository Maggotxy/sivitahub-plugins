# 全量机制矩阵 v15（gap-matrix-v15）

> verdict: done=true remaining=0
> basis: c241979

> basis-substring: c241979
> 生成者：CNB CodeBuddy NPC，任务 `W31-A1-verdict-v15`（**最终复验轮 v15**）。
> ① 实测 `git rev-parse HEAD` == `git rev-parse origin/main` == `c2419799d93c751c5c5e486db6cb12dbe5f721dc`（两者**相等**，见 §1.1）。
> ② 本矩阵的 basis **字面等于**它分叉时的 main HEAD（= 上面那个 sha）。
> ③ M14（本轮采信的十门证据 sha）= `c241979`（= 判定 basis 本身，见 §9）；本分支自身不含构建输入，见 §9.2。
> ④ 素材层四条、G-302 系列、机制规格绑定层、G-CACHE-STAMP-BEFORE-LOCK 全部**由我在本轮 basis 上亲自重跑**（不采信调度器结论），证据见 §2–§5、§6、§7、§8。
> 状态取值：`已接线` / `声明未接线` / `存疑` / `已废弃` / `真实缺口`。
> 口径警戒（逐条给证据，只字不偷换）：「计数下降」≠「缺口清零」；「判据变窄」≠「问题消失」；
> 「门禁 GREEN」≠「判据没有过宽豁免」；「NPC 容器 GREEN」≠「门禁容器 GREEN」。

## 0. 本轮结论（先给判定，再给证据）

| 项 | v14 判定（basis=7c23a35a） | v15 判定（basis=c241979） | 依据 |
|---|---|---|---|
| **矩阵 basis 字面 == main HEAD**（本轮首要目的） | ✅ 一致 | ✅ **一致**：`basis: c241979`（本文件 :4）+ 实测 `HEAD==origin/main==c241979…`（§1.1） | §1.1：`git rev-parse HEAD` / `git rev-parse origin/main` 两条命令原文，`EQUAL=true` |
| **素材层四条**（零消费者 / 基线 / 溯源 / items+七项） | 成立 | ✅ **成立（本轮 basis 上亲自重跑，贴原文）** | §2（a）/§3（b）/§4（c）/§5（d）：命令 + 退出码 + 输出原文 |
| **G-302-RESIDUAL 系列**（含 -2/-3/-4） | 仍清零 | ✅ **仍清零（判据未放宽，本轮独立复跑 + 4 类抽样 + 3 组负夹具）** | §6：`SOURCE_STRING_TESTS=0 / SOURCE_STRING_GATE=GREEN / EXIT=0`；4 类方法体复核；夹具⑤⑥⑦各自 RED → 删除 → GREEN；反例未被判红 |
| **机制规格绑定层** | 已建立并闭环 | ✅ **闭环（本轮独立复核 + 独立负向对照）** | §7：`MECHANICS_SPEC_GATE=GREEN` / `ENTRIES=137`（与 M14 一致）/ `WEAK=0`；`MECHANICS_SPEC_REPORT=FRESH`；零 `import yaml`；**我把一条 config 载体 expect 改坏 ⇒ RED 且点名 `M-CORE-003`** |
| **G-CACHE-STAMP-BEFORE-LOCK** | 仍闭环 | ✅ **仍闭环（独立复核代码事实）** | §8：eager `nextCommitVersion()` 调用为零；`markLocked` 惰性一次取号；5/5 发布点 `LockedTransaction.wrap`；3 个回归测试在 |
| **十门 @ basis** | 全绿 | ✅ **全绿（回读核对）** | §9：权威 sn=`cnb-jpu-1k46t3ft2`，`event=api_trigger_gate`，`sha=c241979`，13 stage success + `DebugDetection` skipped，判据行逐条回读 |
| **`G-FLAKY-GATE`**（同 sha 是否仍复现假红） | 同 sha 两次构建均 success | ✅ **本轮未采到 RED（同上补证，见 §11）** | §11：`c241979` 上权威构建 `cnb-jpu-1k46t3ft2`（api_trigger_gate）success；RED 计数 = 0 |

⇒ 差集矩阵「声明未接线」= **0 条**；「可派工的实现缺口」= **0 条**；
十门在 M14=`c241979` 上全绿（判据行齐全，含 `MECHANICS_SPEC_*`）；素材层四条在本轮 basis 上成立；
剩余问题**只有** `REQUIRES_HUMAN`（H-001 / U-003 / W7-A2 四条 `[R]`）。
⇒ `plan/verdict-v15.json`：`done=true`、`remaining=0`（与本节 `> verdict:` 行**严格一致**，见 §10）。
> 本轮 PR：https://cnb.cool/xzqkjmnbvc2026/sivitahub/-/pulls/83（分支 `agent/W31-A1-verdict-v15`）。

## 1. basis 实测（本轮首要目的）

### 1.1 `HEAD` 与 `origin/main` 是否相等

命令与输出原文：

```
$ git rev-parse HEAD
c2419799d93c751c5c5e486db6cb12dbe5f721dc

$ git rev-parse origin/main
c2419799d93c751c5c5e486db6cb12dbe5f721dc

$ # 判定
EQUAL=true   # 两者相等

$ git rev-parse --short=7 HEAD
c241979

$ git status --porcelain
(空 —— 干净)
```

⇒ `HEAD == origin/main == c2419799d93c751c5c5e486db6cb12dbe5f721dc`，两者**相等**（`EQUAL=true`）。
`basis` 取实测短 sha **`c241979`**（7 位）。

**口径澄清（防误读）**：上表的 `EQUAL=true` 是**分叉那一刻**的实测（本分支的**起点**）。
本分支提交交付物后，分支 `HEAD` 自然前移（`fba63b1` → `129629a`），因此**事后**再跑
`git rev-parse HEAD` 会得到分支自己的 HEAD（≠ `origin/main`），这是**预期**的、不是矛盾。
可机械核验分叉点：

```
$ git merge-base HEAD origin/main            # 本分支分叉点
c2419799d93c751c5c5e486db6cb12dbe5f721dc
$ git rev-parse fba63b1^                     # 本分支第一个提交的父
c2419799d93c751c5c5e486db6cb12dbe5f721dc
$ git rev-parse origin/main                  # 交付后 origin/main 未变
c2419799d93c751c5c5e486db6cb12dbe5f721dc
```
⇒ 三者一致 ⇒ `basis: c241979` **字面等于本矩阵分叉时的 main HEAD**。

### 1.2 与 v14 的差别（为什么必须有这一轮）

- v14 的 basis = `7c23a35a`；本轮 basis = `c241979`（= 分叉时的 main HEAD）。
- 自 v14 以来 main 没有新的**构建输入**改动：`c241979` 相对 `7c23a35a` 的 diff 仅含
  `plan/**`、`state/**`、`doc/cnb/**`、`.cnb/agent-out/**`（见 §9.2）。
- M14 的门禁判据行继续适用（§9 回读核对），且 **M14 的 sha 就是本轮 basis 本身** ⇒
  十门证据的构建输入与本轮判定 basis 同集合。

## 2. 素材层 (a)：`scripts/` 下零消费者 = 0

**判据**：`scripts/` 下每个 `.sh` / `.py` 都要有**真实调用点**（不是「文件存在」）。

`ls scripts/*.sh scripts/*.py | wc -l` → **23**。

逐个调用点原文（`.cnb.yml` 直接入口 + `scripts/selftest-gates.sh` 内部调用 +
插件 `*ContractTest` 经 `ProcessBuilder` 真实执行，三类合计）：

| # | 脚本 | 真实调用点（原文） |
|---|---|---|
| 1 | `selftest-gates.sh` | `.cnb.yml:71` `sh scripts/selftest-gates.sh` |
| 2 | `check-ddl-consumers.sh` | `.cnb.yml:89` `sh scripts/check-ddl-consumers.sh`；`selftest-gates.sh:200` `(cd "$GW" && sh scripts/check-ddl-consumers.sh >"$GW/out.txt" 2>&1)` |
| 3 | `check-resource-pack.sh` | `.cnb.yml:117` `sh scripts/check-resource-pack.sh`；`selftest-gates.sh:883` `(cd "$1" && sh scripts/check-resource-pack.sh >/dev/null 2>&1)` |
| 4 | `check-asset-baseline.py` | `.cnb.yml:143` `python3 scripts/check-asset-baseline.py`；`selftest-gates.sh:1118` `(cd "$BASE_FIXTURE" && python3 scripts/check-asset-baseline.py >/dev/null 2>&1)` |
| 5 | `check-asset-reproducible.sh` | `.cnb.yml:171` `sh scripts/check-asset-reproducible.sh`；`selftest-gates.sh:1389` |
| 6 | `check-asset-reproducible.py` | `scripts/check-asset-reproducible.sh:128` `exec python3 scripts/check-asset-reproducible.py --root "$ROOT" --generated "$GEN"`（链：`.cnb.yml:171` → `.sh:128` → `.py`） |
| 7 | `asset_fingerprint.py` | `scripts/check-asset-reproducible.py:32` `from asset_fingerprint import fingerprint_png  # noqa: E402`（被上一个真实执行链 import） |
| 8 | `fetch-deps.sh` | `.cnb.yml:179` / `.cnb.yml:420` `sh scripts/fetch-deps.sh` |
| 9 | `check-file-deps.sh` | `.cnb.yml:181` / `.cnb.yml:421` `sh scripts/check-file-deps.sh` |
| 10 | `dump-test-results.py` | `.cnb.yml:293` `python3 scripts/dump-test-results.py --min-modules 26`；`selftest-gates.sh:91` |
| 11 | `list-env-tests.sh` | `.cnb.yml:303` `sh scripts/list-env-tests.sh > /tmp/env-methods.txt`；`selftest-gates.sh:159` `ENV_LIST=$(sh scripts/list-env-tests.sh)` |
| 12 | `check-plugin-jars.py` | `.cnb.yml:387` `python3 scripts/check-plugin-jars.py /tmp/jars.txt`；`selftest-gates.sh:132` |
| 13 | `check-repo-artifacts.sh` | `selftest-gates.sh:726` `(cd "$ROOT" && sh scripts/check-repo-artifacts.sh >"$TMP/artifact-green.txt" 2>&1)`；`selftest-gates.sh:738`（红夹具） |
| 14 | `install-datapacks.sh` | `selftest-gates.sh:690` `(cd "$DATAPACK_FIXTURE" && sh scripts/install-datapacks.sh --check >"$TMP/datapack-green.txt" 2>&1)`；`selftest-gates.sh:716`（真实仓库） |
| 15 | `gen-asset-manifest.py` | `selftest-gates.sh:1232` `(cd "$ROOT" && python3 scripts/gen-asset-manifest.py --check >/dev/null 2>&1)` |
| 16 | `check-source-contract.sh` | `selftest-gates.sh:456` `sh scripts/check-source-contract.sh --all >"$TMP/specs-real.txt" 2>&1`；**另** 40 个插件测试类 `root.resolve("scripts/check-source-contract.sh")` 经 `ProcessBuilder` 真跑 |
| 17 | `check-source-string-tests.sh` | `selftest-gates.sh:506` `( cd "$SST_FIXTURE" && sh scripts/check-source-string-tests.sh >/dev/null 2>&1 )` |
| 18 | `check-mechanics-spec.sh` | `selftest-gates.sh:1717` `(cd "$ROOT" && sh scripts/check-mechanics-spec.sh > "$TMP/spec-real.txt" 2>&1)`；：1711（绿夹具）/：1811（弱夹具） |
| 19 | `gen-mechanics-spec-report.py` | `selftest-gates.sh:1941` `(cd "$ROOT" && python3 scripts/gen-mechanics-spec-report.py --check > "$TMP/spec-report-fresh.txt" 2>&1)`；：1972（陈旧夹具） |
| 20 | `resource-pack-asset-check.py` | `scripts/check-resource-pack.sh:131` `python3 "$ROOT/scripts/resource-pack-asset-check.py" --root "$ROOT" --check closure`（链：`.cnb.yml:117` → `check-resource-pack.sh:131..161` 七项） |
| 21 | `test-frames.py` | `selftest-gates.sh:188` `python3 scripts/test-frames.py build ddl-consumer-root "$GW" "$@" >/dev/null 2>&1` |
| 22 | `check-config-fallbacks.sh` | `plugins/rpg-enhancement/src/test/java/cn/originalrpg/enhancement/EnhancementFallbackContractTest.java:35` `Path gate = root.resolve("scripts/check-config-fallbacks.sh");` + `:38` `new ProcessBuilder("sh", gate.toString())`（真实执行） |
| 23 | `check-trusted-menu-holders.sh` | `plugins/rpg-market/src/test/java/cn/originalrpg/market/TrustedMenuBoundaryContractTest.java:62` `java.nio.file.Path gate = root.resolve("scripts/check-trusted-menu-holders.sh");` + `:64` `new ProcessBuilder("sh", gate.toString())`（真实执行） |

**两条指定的行号**（任务要求逐条给出）：

```
$ grep -n 'check-mechanics-spec.sh' scripts/selftest-gates.sh
1711:  (cd "$SPEC_FIXTURE" && sh scripts/check-mechanics-spec.sh >"$1" 2>&1)
1717:(cd "$ROOT" && sh scripts/check-mechanics-spec.sh > "$TMP/spec-real.txt" 2>&1)
1811:(cd "$SPEC_FIXTURE" && sh scripts/check-mechanics-spec.sh > "$TMP/spec-weak.txt" 2>&1)
1861:check "判据 12d：check-mechanics-spec.sh 不含 import yaml（静态零命中）" 0 "$spec_yaml_hits"
1999:for wired in "scripts/check-mechanics-spec.sh" "scripts/mechanics-spec.json"; do
```

```
$ grep -n 'gen-mechanics-spec-report.py' scripts/selftest-gates.sh
1941:(cd "$ROOT" && python3 scripts/gen-mechanics-spec-report.py --check > "$TMP/spec-report-fresh.txt" 2>&1)
1957:cp "$ROOT/scripts/gen-mechanics-spec-report.py" "$ROOT/scripts/mechanics-spec.json" \
1972:python3 "$REPORT_FIXTURE/scripts/gen-mechanics-spec-report.py" --check \
```

**接线闭环**：`selftest-gates.sh` 自身被 `.cnb.yml:71` 执行 ⇒ 上表 13 个「只由 selftest-gates 调用」
的脚本每次推送都真跑。`selftest-gates.sh:813-828` 有机械自证段（`for wired in ... ; grep -c -F`），
且 `selftest-gates.sh:1999` 断言本脚本调用 `check-mechanics-spec.sh`。
⇒ **零消费者 = 0 条**。

## 3. 素材层 (b)：逐像素基线门禁

```
$ python3 scripts/check-asset-baseline.py ; echo EXIT=$?
ASSET_BASELINE: 校验 252 张 PNG（清单 252 条）
  UNREGISTERED=0 MISSING=0 MODIFIED=0
ASSET_BASELINE_GATE=GREEN
EXIT=0
```

⇒ **252 ↔ 252**，`UNREGISTERED=0 MISSING=0 MODIFIED=0`，`ASSET_BASELINE_GATE=GREEN`，`EXIT=0`。
⚠️ 该文件是 **Python** 文件，任务描述里的 `sh scripts/check-asset-baseline.py` 会报语法错；
我按 `.cnb.yml:143` 的真实调用方式用 `python3`（如实说明）。CI 同一判据行见 §9（stage-4 `ASSET_BASELINE_GATE=GREEN`）。

## 4. 素材层 (c)：`assets-manifest.json` 与 `asset-provenance.json` 归一 `assets/` 前缀后同集合

两个文件的结构不同：`asset-provenance.json` 的 `assets` 是**字典**（键带 `assets/` 前缀），
`assets-manifest.json` 是**顶层扁平字典**（键不带前缀）。归一后逐条比对：

```
$ python3 - <<'PY'
prov keys: 252 (uniq 252)   # 键形如 assets/originalrpg/textures/item/skill_heal.png
mani keys: 252 (uniq 252)   # 键形如 originalrpg/textures/item/skill_heal.png
manifest-only: []
prov-only:     []
same_set:      True
$needsHumanDecision: []
$counts: {"ai-prompt":10,"derived-from":69,"generator":129,"human-override":1,"upstream-betterhud":38,"upstream-vanilla":5}
counts sum: 252
PY
```

**逐条重算**六类与声明 `$counts` 比对：`recomputed categories == declared $counts` → `match: True`。

⇒ 归一 `assets/` 前缀后**同集合**（`manifest-only=0` / `prov-only=0`）、`$needsHumanDecision` 为**空**、
六类 `` 合计 **252** 且与逐条重算一致。

## 5. 素材层 (d)：items 定义 81 == runtime-item-models 去注释键 81 + 七项检查全 exit=0

```
$ ls resource-pack/assets/originalrpg/items/*.json | wc -l
81
$ grep -v '^#' resource-pack/runtime-item-models.txt | grep -v '^$' | wc -l
81
$ # 集合比对
items definitions: 81
runtime keys (no comments): 81
items-only:   []
models-only:  []
equal_sets:   True
```

七项 `--check`（`python3 scripts/resource-pack-asset-check.py --root . --check <name>`）：

```
--- --check closure  : EXIT=0 ---   物品定义 81 / 可达模型 119 / 可达纹理 115
--- --check compass  : EXIT=0 ---   33 段方向分发 ↔ 32 帧模型 + 32 帧纹理（scale=32.0 target=spawn）
--- --check hud      : EXIT=0 ---   U+E000..U+E00A 十一码位 ↔ 99x9 图集；U+E020..U+E04F 四十八码位 ↔ 432x9 图集
--- --check journey  : EXIT=0 ---   U+E100..U+E107 八码位 ↔ 八张 32x32 旅程图
--- --check lang     : EXIT=0 ---   en_us/zh_cn 键集合一致（各 18 键）
--- --check packmeta : EXIT=0 ---   pack.mcmeta 精确声明资源格式 88（min=max=88，无 overlays）
--- --check textures : EXIT=0 ---   115 张物品 PNG 全部 32x32 二值 alpha
```

⇒ **七项全 `exit=0`**。

## 6. `G-302-RESIDUAL` 系列：仍清零且**判据未被放宽**（本轮独立复跑）

### 6.1 门禁判据行（**重定向取码，不走管道**）

```
$ sh scripts/check-source-string-tests.sh > out.txt 2>&1 ; echo EXIT=$?   # 重定向取码
EXIT=0
SOURCE_STRING_TESTS=0
SOURCE_STRING_GATE=GREEN 本仓生产源码已无源码字符串断言（豁免口径见脚本头部注释）

$ sh scripts/check-source-string-tests.sh --json ; echo EXIT=$?
{"hits": [], "count": 0}
EXIT=0
```

⇒ `SOURCE_STRING_TESTS=0` / `SOURCE_STRING_GATE=GREEN` / `EXIT=0`。（⚠️ 取退出码一律重定向到文件再取 `$?`。）

### 6.2 独立抽样复核 **4 个类**（含指定的 3 个 + 自选第 4 个）

| 类 | `文件:行号` | 判定依据（读的是**什么**） |
|---|---|---|
| `DungeonTimeoutLifecycleContractTest` | `plugins/rpg-dungeons/src/test/java/cn/originalrpg/dungeons/DungeonTimeoutLifecycleContractTest.java`：**全类唯一 `Files.` 命中 = `:19` 注释**（`:19` `* G-302-RESIDUAL-2：本类原用 {@code Files.readString} 读 DungeonManager.java 生产源码，`） | 3 个 `@Test`（`:47 assignmentCancelsTheOldTimeoutBeforeBindingANewSession` / `:75 recyclingAnInstanceCancelsItsActiveTimeoutTask` / `:97 aLateScheduledCallbackMustStillOwnTheCurrentSession`）用 `DungeonBukkitTestHarness.newManager(scheduler)` **真构造** `DungeonManager`，断言 `scheduler.callbacks/cancelled`、`instanceSessions` 代际、`instanceTimeouts.containsKey` 等**可观察状态**。**不读生产源码正文** |
| `EquipmentExitPathReachabilityTest` | `plugins/rpg-items/src/test/java/cn/originalrpg/items/forge/EquipmentExitPathReachabilityTest.java`：`:330 root.resolve("scripts/check-source-contract.sh")` / `:331 root.resolve("scripts/source-contract-specs/rpg-items.json")` / `:334 Files.readAllBytes(spec)` / `:340 new String(process.getInputStream().readAllBytes())` | `:332` `Files.isRegularFile(gate)` 判脚本存在；`:334` 读的是**规格 JSON**（`rpg-items.json`，非生产源码）；`:336-344` `ProcessBuilder("sh", gate, SPEC_CLASS+"#"+specKey, method)` **真跑门禁**并断言 `exitValue()==0`。**读的是规格 JSON + 进程 stdout** |
| `SeasonAttunementSettlementWiringTest` | `plugins/rpg-core/src/test/java/cn/originalrpg/core/persistence/SeasonAttunementSettlementWiringTest.java`：`:214-215` 原实现注释 / `:224 yaml(repositoryRoot().resolve(".../config.yml"))` / `:242-243 root.resolve("scripts/check-source-contract.sh")` / `:250 new String(process.getInputStream().readAllBytes())` / `:299 Files.newInputStream(path)` | `:224` 用 SnakeYAML **真解析** `config.yml` 并断言 `season-attunement.enabled==FALSE`；`:245-258` `ProcessBuilder("sh", gate, <本类名>#fragment, method)` **真跑契约门禁**；`:299 newInputStream` 是**部署产物/夹具**读取。**读的是配置解析 + 进程 stdout** |
| `DungeonEntryCommitAmbiguityContractTest`（自选第 4 个） | `plugins/rpg-dungeons/src/test/java/cn/originalrpg/dungeons/DungeonEntryCommitAmbiguityContractTest.java`：`:37/:44/:51/:56` 四个 `@Test` / `:62 assertContractHolds(...)` / `:65 Files.isRegularFile(gate)` / `:70 new String(process.getInputStream().readAllBytes())` / `:77 repositoryRoot` | 四个 `@Test` 全走 `assertContractHolds(specKey, method)`：`:65` 判脚本存在 → `ProcessBuilder` **真跑** `check-source-contract.sh` → `:70` 读**进程 stdout** → `assertEquals(0, exit)`。**不读生产源码正文** |

⇒ 4 个类读的都是**测试夹具 / 源规格 JSON / 进程 stdout / 真解析出的配置**，**不是生产源码正文**。

### 6.3 反向验证判据仍有力：**三组**夹具现场造（跑完删除）

夹具造在 `plugins/rpg-core/src/test/java/_gatefixture/` 下（插件测试目录，非生产源码）：

| 夹具 | 形态 | 文件:行号 | 结果 |
|---|---|---|---|
| ⑤ `Fixture5WrappedAssertTest` | 内联 `assertTrue(Files.readString(Path.of("plugins/.../X.java")).contains("x"))` | `Fixture5WrappedAssertTest.java:8` 命中 | `EXIT=1` / `SOURCE_STRING_TESTS=1` / `SOURCE_STRING_GATE=RED`，点名 `:8 -> ['plugins/rpg-core/src/main/java/.../LockedTransaction.java']` |
| ⑥ `Fixture6ConstPathTest` | `Files.readString(Path.of(<常量>))`（常量 `PRODUCTION_SOURCE`） | `Fixture6ConstPathTest.java:14` 命中 | `EXIT=1` / `SOURCE_STRING_TESTS=1` / `GATE=RED`，点名 `:14 -> ['plugins/rpg-core/src/main/java/.../LockedTransaction.java']` |
| ⑦ `Fixture7TraversalTest` | `Files.walk(root.resolve("plugins").resolve(MODULE).resolve(MAIN))` + `readString(f).contains(id)` 聚合成 `List` 后 `anyMatch` | `Fixture7TraversalTest.java:19` 命中 | `EXIT=1` / `SOURCE_STRING_TESTS=1` / `GATE=RED`，点名 `:19 -> ['(traversal root: plugins\|tools -> src/main)']` |

三组**同时**存在时合并计数：

```
$ sh scripts/check-source-string-tests.sh > out.txt 2>&1 ; echo EXIT=$?
EXIT_ALL_THREE=1
SOURCE_STRING_TESTS=3
SOURCE_STRING_GATE=RED 仍有测试在「读本仓生产源码 + 源码字符串断言」
```

**删除后**：

```
$ rm -rf plugins/rpg-core/src/test/java/_gatefixture
$ sh scripts/check-source-string-tests.sh > out.txt 2>&1 ; echo EXIT=$?
EXIT_AFTER_ALL_DELETED=0
SOURCE_STRING_TESTS=0
SOURCE_STRING_GATE=GREEN
$ git status --porcelain
(空 —— 干净)
```

**反例**：`LanguageBundleContractTest`（`plugins/rpg-core/src/test/java/cn/originalrpg/core/i18n/LanguageBundleContractTest.java`，
`Files.newInputStream` + SnakeYAML **真解析**部署产物）**未**被判红
（`grep -c 'LanguageBundleContractTest' out.txt` → **0**）。

⇒ 三组各自变红、删除后恢复 GREEN、`git status --porcelain` 干净、反例未被误判。

**已知形态边界（如实登记，与 v13 §6.5 / v14 §6.3 同源，不构成本轮缺口）**：
形态 ⑦ 的命中要求 `Files.walk` 实参里**同时**出现 `plugins|tools` 与 `src/main` 两个**独立字面量**
（或类里有两个独立字面量）。我本轮直接按两段独立字面量
（`root.resolve("plugins").resolve(MODULE).resolve(MAIN)`）写夹具 ⇒ 一次命中（`EXIT=1`）。
判定不构成缺口的三条理由：(1) 三个原历史形态都在命中范围内；(2) `selftest-gates.sh` §4d 的夹具
①②④各带退出码断言，判据每次推送都被负向对照；(3) `SOURCE_STRING_TESTS=0` 是**方法体事实**（§6.2 逐条复核），
不依赖该边界是否可绕。改判据属构建输入，本任务不允许改。

### 6.4 规格规模未缩水（相对 v8 基线 `CHECKS=466 SPECS=78 SPEC_FILES=2`）

```
$ sh scripts/check-source-contract.sh --all ; echo EXIT=$?
SOURCE_CONTRACT_SPEC_FILES=3
SOURCE_CONTRACT_CHECKS=469 SPECS=79
SOURCE_CONTRACT_GATE=GREEN
EXIT=0
```

⇒ `CHECKS=469 ≥ 466` / `SPECS=79 ≥ 78` / `SPEC_FILES=3 ≥ 2`，**没有缩水**（逐项均不低于 v8 基线）。

## 7. 机制规格绑定层：独立复核 + **独立负向对照**

### 7.1 正向

```
$ sh scripts/check-mechanics-spec.sh ; echo EXIT=$?
MECHANICS_SPEC_GATE=GREEN
MECHANICS_SPEC_ENTRIES=137
MECHANICS_SPEC_BY_KIND=config:78,behavior:56,java-const:3
MECHANICS_SPEC_WEAK=0
EXIT=0

$ python3 scripts/gen-mechanics-spec-report.py --check ; echo EXIT=$?
MECHANICS_SPEC_REPORT=FRESH
EXIT=0
```

⇒ `GREEN` / `ENTRIES=137`（与 M14 一致）/ `WEAK=0` / `REPORT=FRESH`。

### 7.2 零 `import yaml`（门禁镜像无 PyYAML/pip）

```
$ grep -nE '^[[:space:]]*(import|from)[[:space:]]+yaml' scripts/check-mechanics-spec.sh ; echo EXIT=$?
EXIT=1     # grep 无命中 → 退出码 1
$ grep -nE '^[[:space:]]*(import|from)[[:space:]]+yaml' scripts/check-mechanics-spec.sh | wc -l
0
```

⇒ **零命中**（0 行）。门禁为纯 stdlib。

### 7.3 **我自己独立做的**负向对照（不采信 NPC）

把一条 `config` 载体的 `expect` 改坏：`M-CORE-003`
（`carrier.kind=config`，`path=plugins/rpg-skills/src/main/resources/config.yml`，
`key=skill-leveling.maximum-level-by-ascension`，原 `expect=[2,3,4,5]`）→ 改成 `[2,3,4,9]`；
**在 `/tmp` 副本上改，仓库内 spec 未被改动**。

```
$ sh scripts/check-mechanics-spec.sh --spec /tmp/mxfix15/spec.broken.json ; echo EXIT=$?
MECHANICS_SPEC_GATE=RED
  [FAIL] M-CORE-003
         期望   : [2, 3, 4, 9]
         实际   : ['2', '3', '4', '5']
EXIT=1

$ sh scripts/check-mechanics-spec.sh --spec /tmp/mxfix15/spec.restored.json ; echo EXIT=$?
MECHANICS_SPEC_GATE=GREEN
EXIT=0
```

⇒ 改坏 ⇒ **RED 且点名到具体 id `M-CORE-003`**；还原 ⇒ **GREEN**（不是恒红）。

## 8. `G-CACHE-STAMP-BEFORE-LOCK`：仍闭环（独立复核代码事实）

### 8.1 `LockedTransaction.java` 存在且 `markLocked` 只在第一次调用时取号

```
$ ls -la plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/LockedTransaction.java
-rw-r--r-- 1 root root 3431 ... LockedTransaction.java

$ sed -n '40,56p' .../LockedTransaction.java
    static Connection wrap(Connection connection, LongSupplier nextVersion) {
        if (handlerOf(connection) != null) return connection;
        return (Connection) Proxy.newProxyInstance(...                 # :43-45 只做代理包装，不取号
                new LockedTransaction(connection, nextVersion));
    }
    ...
    /** 行锁到手：登记一次提交版本号（重复调用只生效一次，号必须最早那个）。 */
    private void markLocked() {                                        # :54
        if (version == 0L) version = nextVersion.getAsLong();          # :55 惰性一次取号
    }
```

⇒ `wrap`（`:40-46`）只包装**不取号**；`markLocked`（`:54-55`）`if (version == 0L)` **只在第一次调用时取号**。

### 8.2 5 个服务的取号触发点都在 `FOR UPDATE` **之后**

| 服务 | 发布点（`LockedTransaction.wrap`） | 取号触发点（`FOR UPDATE` 之后） |
|---|---|---|
| `JdbcProfileService` | `:841 Connection connection = LockedTransaction.wrap(raw, this::nextCommitVersion);` | `:484` `"SELECT player_uuid FROM rpg_profiles WHERE player_uuid = ? FOR UPDATE"` → `:492 markLocked(connection, true);`；`:667 SELECT … FOR UPDATE` → `:633 markLocked(connection, lock);`；新建行分支 `:649 markLocked(...)`（`:648` 注释「第二次 select 已经持有行锁，取号点在其之后」） |
| `JdbcAscensionService` | `:284 Connection connection = LockedTransaction.wrap(raw, profiles::nextCommitVersion);` | `:81 PlayerProfile current = profiles.loadForUpdate(connection, playerId);`（→ `JdbcProfileService.java:471` 经 `:667 FOR UPDATE` → `markLocked`） |
| `JdbcDungeonRewardService` | `:318 Connection connection = LockedTransaction.wrap(raw, profiles::nextCommitVersion);` | `:116 PlayerProfile current = profiles.loadForUpdate(connection, request.playerId());` |
| `JdbcAdminIncidentCompensationService` | `:723 Connection connection = LockedTransaction.wrap(raw, profiles::nextCommitVersion);` | `:127 PlayerProfile before = profiles.loadForUpdate(connection, value.playerId());` |
| `JdbcAdminTestService` | `:297 Connection connection = LockedTransaction.wrap(raw, profiles::nextCommitVersion);` | `:53/:73/:103 profiles.loadForUpdate(connection, playerId);`；`:124 profiles.loadSnapshot(connection, playerId);`（`:226 ... FOR UPDATE`） |

**eager 取号已彻底消失**（v11 的缺陷形态）：

```
$ grep -rn 'nextCommitVersion()' plugins/rpg-core/src/main/java/
JdbcProfileService.java:503:    * @param version 调用方事务**持锁时**取得的版本号（{@link #nextCommitVersion()}）。
JdbcProfileService.java:518:    * @param version 该事务**持锁时（commit 之前）**取得的版本号（{@link #nextCommitVersion()}）
JdbcProfileService.java:527:    long nextCommitVersion() {          # ← 唯一「定义」
JdbcAdminIncidentCompensationService.java:715:  *（{@link JdbcProfileService#nextCommitVersion()}）…（javadoc）
JdbcAdminTestService.java:291:  *（javadoc）
JdbcAscensionService.java:277:  *（javadoc）
JdbcDungeonRewardService.java:311:  *（javadoc）
Committed.java:7:  *（javadoc）
```

⇒ **零个 eager 调用**（只剩 1 个定义 `:527` + 7 处 javadoc 引用）。

### 8.3 三个缓存顺序回归测试都在且断言未放宽

```
$ grep -rn 'readCacheIsNotRolledBackByALateCommit\|lateCommitStampCannotRollBackTheReadCache\|commitVersionMustBeTakenAfterTheRowLockIsHeld' plugins/ --include=*.java
plugins/rpg-core/src/test/java/cn/originalrpg/core/persistence/JdbcServicesTest.java:1035:    void readCacheIsNotRolledBackByALateCommit() throws Exception {
plugins/rpg-core/src/test/java/cn/originalrpg/core/persistence/JdbcServicesTest.java:1088:    void lateCommitStampCannotRollBackTheReadCache() throws Exception {
plugins/rpg-core/src/test/java/cn/originalrpg/core/persistence/JdbcServicesTest.java:1151:    void commitVersionMustBeTakenAfterTheRowLockIsHeld() throws Exception {
```

断言**未放宽**（`JdbcServicesTest.java`）：

```
:1180  for (int index = 0; index < 20; index++) { grants.add(profiles.grantExperience(playerId, 1)…); }
:1182  CompletableFuture.allOf(grants.toArray(CompletableFuture[]::new)).join();      # 仍是 20 路并发
:1198  assertEquals(20, fromDb, "数据库里 20 次授予必须全部落账");
:1199  assertEquals(fromDb, profiles.get(playerId)…experience(), "取号必须在持有行锁之后…");
```

⇒ 仍是「缓存 == 数据库」「数据库 == 20」，**没有**放宽期望值、**没有**把并发改串行。
PASS 证据见 §9（CI stage-7 `SUMMARY_GATE=GREEN` / `SUM tests=1105 failed=0 errors=0 skipped=2 modules=26`）。

## 9. 十门在 M14 上全绿（回读核对）

### 9.1 权威构建 `cnb-jpu-1k46t3ft2` 的元信息与 stage 判据行

**元信息**（`cnb build get-build-logs --repo xzqkjmnbvc2026/sivitahub --sn cnb-jpu-1k46t3ft2 -v`）：

```
.sn                = cnb-jpu-1k46t3ft2
.status            = success
.event             = api_trigger_gate
.targetRef         = main
.sourceRef         = main
.sha               = c2419799d93c751c5c5e486db6cb12dbe5f721dc      # == 本轮 basis
.pipelineSuccessCount = 1
.pipelineFailCount    = 0
```

**stage 状态**（`cnb build get-build-status --repo … --sn cnb-jpu-1k46t3ft2 -v`）：
13 个 stage `success` + `DebugDetection` `skipped`（设计上跳过），每个 success stage 的 duration 均非零：

```
success,4118,Prepare
success,17705,准备下载器（必须是第一个 stage）        → stage-0
success,562790,门禁自测（负向对照）                  → stage-1
success,108947,DDL 门禁（G-301 消费者 + G-302 注册，双向）→ stage-2
success,549,素材门禁（W7-A1）                        → stage-3
success,363,素材逐像素基线门禁（W12-A1）              → stage-4
success,1812,素材可复现性门禁（W13-A1）               → stage-5
success,131552,compile                              → stage-6
success,58444,test                                  → stage-7
success,7086,assemble                               → stage-8
success,447,BeforeEnd
skipped,0,DebugDetection
success,633,End
```

**每个 stage 的 status 与判据行**（逐条回读）：

| stage | status | 判据行（回读原文） |
|---|---|---|
| stage-0 准备下载器 | success | `DOWNLOADER=ready` |
| stage-1 门禁自测 | success | `===== 门禁自测汇总：PASS=167 FAIL=0 =====` / `GATE_SELFTEST=GREEN`；`SINGLE_SOURCE_OF_TRUTH_GATE=GREEN`（负夹具打出 `=RED`）；`VERDICT_MATRIX_CONSISTENCY_GATE=GREEN`（打印 `N=14 done=true remaining=0 basis=7c23a35a` —— 那是 M14 构建时仓库最新矩阵仍为 v14；本轮 v15 矩阵的一致性按 §10 自查）；`MECHANICS_SPEC_GATE=GREEN` / `MECHANICS_SPEC_ENTRIES=137` / `MECHANICS_SPEC_BY_KIND=config:78,behavior:56,java-const:3` / `MECHANICS_SPEC_WEAK=0` / `[OK  ] 判据行 MECHANICS_SPEC_REPORT=FRESH` |
| stage-2 DDL 门禁 | success | `CMD_REFERENCE_GATE=GREEN` / `DDL_REGISTRATION_GATE=GREEN` / `DDL_CONSUMER_GATE=GREEN` |
| stage-3 素材门禁 | success | `RESOURCE_PACK_GATE=GREEN`（`RESOURCE_PACK_GATE: 校验 /workspace/resource-pack`） |
| stage-4 素材逐像素基线 | success | `ASSET_BASELINE_GATE=GREEN` |
| stage-5 素材可复现性 | success | `ASSET_REPRO_GATE=GREEN` |
| stage-6 compile | success | `BUILD_EXIT=0 SECONDS=123` / `COMPILE_GATE=GREEN` |
| stage-7 test | success | `SUM                          tests=1105 failed=0 errors=0 skipped=2 modules=26` / `SUMMARY_GATE=GREEN` / `TEST_GATE=GREEN` |
| stage-8 assemble | success | `JAR_COUNT=56` / `PACKAGE_GATE=GREEN` |

**逐条对照任务书期望值**：

```
期望 GATE_SELFTEST=GREEN（PASS=167 FAIL=0）        ✅
期望 COMPILE_GATE=GREEN                            ✅ (BUILD_EXIT=0)
期望 TEST_GATE=GREEN                               ✅
期望 SUMMARY_GATE=GREEN（SUM tests=1105 failed=0 errors=0 skipped=2 modules=26） ✅ 逐字一致
期望 PACKAGE_GATE=GREEN（JAR_COUNT=56）            ✅
期望 RESOURCE_PACK_GATE=GREEN                      ✅
期望 ASSET_BASELINE_GATE=GREEN                     ✅
期望 ASSET_REPRO_GATE=GREEN                        ✅
期望 DDL_CONSUMER_GATE=GREEN                       ✅
期望 DDL_REGISTRATION_GATE=GREEN                   ✅
期望 CMD_REFERENCE_GATE=GREEN                      ✅
期望 SINGLE_SOURCE_OF_TRUTH_GATE=GREEN             ✅
期望 VERDICT_MATRIX_CONSISTENCY_GATE=GREEN         ✅（见上：stage-1 打印 N=14，即构建时最新矩阵）
期望 MECHANICS_SPEC_GATE=GREEN                     ✅ (ENTRIES=137 / WEAK=0)
期望 MECHANICS_SPEC_REPORT=FRESH                   ✅
```

⇒ **十门在 M14（`c241979`）上全绿**，无一条与期望不符。**没有**「查不到」或「与期望不符」的情况。

### 9.2 本分支自身不含构建输入

```
$ git diff --name-only c2419799d93c751c5c5e486db6cb12dbe5f721dc HEAD
(在分叉点为 空 —— 本分支从 M14 = c241979 分叉)

$ # 本任务只写允许路径（写完后重跑，见 §12）
$ git diff --name-only c241979 — HEAD
plan/gap-matrix-v15.md
plan/verdict-v15.json
plan/tasks-w31.json
state/world-state.json
doc/cnb/gap-registry.md
.cnb/agent-out/W31-A1-verdict-v15.json
```

⇒ 只含 `plan/**`、`state/**`、`doc/cnb/**`、`.cnb/agent-out/**`，**不含任何构建输入**
（`plugins/**` / `scripts/**` / `.cnb.yml` / `resource-pack/**` / `docs/**` 一行未动）。

### 9.3 其它回读核对

- `plan/world-state.json` **不存在**；`git ls-files | grep world-state` 只有 `state/world-state.json`（1 条）
  ⇒ `SINGLE_SOURCE_OF_TRUTH_GATE` 的口径在本分支上仍成立。
- `sh scripts/check-ddl-consumers.sh` → `EXIT=0` / `CMD_REFERENCE_GATE=GREEN` / `DDL_REGISTRATION_GATE=GREEN` / `DDL_CONSUMER_GATE=GREEN`（与 stage-2 判据行一致）。

## 10. 与 `plan/verdict-v15.json` 的一致性自查（`vmc_gate` 同口径）

用 `scripts/selftest-gates.sh` 的 `vmc_gate()` **原函数**自查（不重写一份）：

```
$ . <(sed -n '1468,1540p' scripts/selftest-gates.sh) ; vmc_gate "$PWD" ; echo EXIT=$?
VERDICT_MATRIX_CONSISTENCY_GATE=GREEN
N=15 done=true remaining=0 basis=c241979
EXIT=0
```

⇒ 本文件前 20 行内 `> verdict: done=true remaining=0`（:3）+ `> basis: c241979`（:4），
与 `plan/verdict-v15.json` 的 `done`/`remaining`/`basis` **逐字一致**。

`diff` 核对（机械）：

```
$ sed -n '1,20p' plan/gap-matrix-v15.md | grep -E '^> verdict:|^> basis:'
> verdict: done=true remaining=0
> basis: c241979
$ python3 -c "import json;d=json.load(open('plan/verdict-v15.json'));print(d['done'],d['remaining'],d['basis'])"
True 0 c241979
# 一致
```

## 11. `G-FLAKY-GATE`：本轮**未采到 RED**

- `c241979` 上权威构建 `cnb-jpu-1k46t3ft2`（`event=api_trigger_gate`，`targetRef=main`）**success**，
  `pipelineSuccessCount=1 / pipelineFailCount=0`，test stage `success`，`SUMMARY_GATE=GREEN`
  （`SUM tests=1105 failed=0 errors=0 skipped=2 modules=26`）。
- 该缺陷的原始形态是「同 sha 上须有一个 RED」（见 `gap-registry.md` §I.4：`f014b447` 先绿后红）。
  本轮在该 sha 上**无任何 RED 观测** ⇒ 未复发，也未被证伪。
- ⚠️ **如实说明**：本轮我只从 CNB 取了任务书指定的一条权威构建记录（`api_trigger_gate`），
  **不**据此断言「同 sha 两次构建全绿」（v14 曾在 `7c23a35a` 上采到两条且都 success，
  但那是上一个 sha 的采样，**不可沿用**）。处理：登记进 `plan/verdict-v15.json` 的 `risks`，
  **不**列入 `needs_human`（它不是「只能由人做的事」；调度器在本 sha 上多触发一次构建即可把该项从
  「单点采样」变为「两点采样」）。

## 12. 差集矩阵总表（声明未接线 / 可派工实现缺口）

| 类别 | 条目 | 本轮判定 | 证据 |
|---|---|---|---|
| **「声明未接线」类**（枚举/表/注册点存在但无消费者） | `scripts/**` 23 个脚本 | **0 条** | §2：23/23 逐个有真实调用点原文 |
| | DDL 表 → `src/main` 引用 | **0 条** | §9.3：`DDL_CONSUMER_GATE=GREEN` + `DDL_REGISTRATION_GATE=GREEN`（81 表 / 豁免 0） |
| | 缓存发布版本号取号点（`G-CACHE-STAMP-BEFORE-LOCK`） | **0 条（闭环）** | §8：`wrap` 不取号 / `markLocked` 惰性一次 / 5/5 发布点在 `FOR UPDATE` 之后 / eager 调用为零 / 3 回归测试在 |
| | 玩法规格 ↔ 代码绑定（`MECHANICS-SPEC-BINDING`） | **0 条（闭环）** | §7：137 条 / `WEAK=0` / 报告 FRESH / 零 `import yaml` / 独立负向对照点名 `M-CORE-003` |
| | 源码字符串断言（`G-302-RESIDUAL` 系列） | **0 条（清零，判据未放宽）** | §6：`SOURCE_STRING_TESTS=0`；4 类方法体复核；3 组负夹具 RED→GREEN；反例未误判；规格 469/79/3 ≥ 466/78/2 |
| | 账本唯一真相源（`G-DUP-WORLDSTATE`） | **0 条（闭环）** | §9.3：`plan/world-state.json` 不存在 |
| | verdict/矩阵结论分裂（`G-VERDICT-MATRIX-SPLIT`） | **0 条（闭环）** | §10：`vmc_gate()` 原函数 → GREEN / `N=15 done=true remaining=0 basis=c241979` |
| **「可派工的实现缺口」类** | 全部 | **0 条** | 上述各项均为 0 / 闭环；无任何待派工项 |

⇒ `plan/tasks-w31.json` = `{"tasks":[]}`。

## 13. 剩余问题（**只有** REQUIRES_HUMAN）

| id | 事项 | 为什么只能由人做 |
|---|---|---|
| **H-001** | 原版世界方形边界缺 5435 个四角区块，需无人维护窗口在生产机 `lamb:/home/lamb/minecraft` 跑 `scripts/maintain-world-coverage.sh` | 生产机**写操作**；`decisions-locked.md` L-006 锁死 `lamb` 只读，需人类显式批准（`GOAL.md` §4.5） |
| **U-003** | 纯原版客户端能否真握手进服 | 需**真实 Paper + 真实客户端**；本仓与 CNB 无此环境 |
| **W7-A2 的 4 条 `[R]`** | G-207 计数器内存态重启会丢当天场次 / G-205 启动对账需真实 Bukkit / G-209 的 20 tick 是表现层推理值 / G-208 启动 fail-closed 未在真实 Paper 验证 | 均需**真实运行期**（`[R]` 级），非静态判据可验 |

⚠️ **实现路线的选择不属于 REQUIRES_HUMAN**（用户已授权「不需要拍板，完全由 AI 决定」）。
⇒ 除上述 3 类外，**无其它剩余问题** ⇒ `plan/verdict-v15.json`：`done=true` / `remaining=0`。
