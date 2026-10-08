# 全量机制矩阵 v16（gap-matrix-v16）

> verdict: done=false remaining=1
> basis: 0237548

> basis-substring: 0237548
> 生成者：CNB CodeBuddy NPC，任务 `W34-A1-verdict-v16`（**最终复验轮 v16**）。
> ① 实测 `git rev-parse HEAD` == `git rev-parse origin/main` == `0237548269545974520f073a3ff254a9885004de`（两者**相等**，见 §1.1）。
> ② 本矩阵的 `basis` **字面等于**它分叉时的 main HEAD（= 上面那个 sha）。
> ③ M18（本轮采信的十门证据 sha）= `0237548`（= 判定 basis 本身，见 §9）；本分支自身不含构建输入，见 §9.2。
> ④ 素材层四条、G-302 系列、机制规格绑定层、G-CACHE-STAMP-BEFORE-LOCK、env 标签正当性层全部**由我在本轮 basis 上亲自重跑**（不采信调度器结论），证据见 §2–§5、§6、§7、§8、§11。
> 状态取值：`已接线` / `声明未接线` / `存疑` / `已废弃` / `真实缺口`。
> 口径警戒（逐条给证据，只字不偷换）：「计数下降」≠「缺口清零」；「判据变窄」≠「问题消失」；
> 「门禁 GREEN」≠「判据没有过宽豁免」；「NPC 容器 GREEN」≠「门禁容器 GREEN」。

## 0. 本轮结论（先给判定，再给证据）

| 项 | v15 判定（basis=c241979） | v16 判定（basis=0237548） | 依据 |
|---|---|---|---|
| **矩阵 basis 字面 == main HEAD**（本轮首要目的） | ✅ 一致 | ✅ **一致**：`basis: 0237548`（本文件 :4）+ 实测 `HEAD==origin/main==0237548…`（§1.1） | §1.1：`git rev-parse HEAD` / `origin/main` 两条命令原文，`EQUAL=true` |
| **素材层四条**（零消费者 / 基线 / 溯源 / items+七项） | 成立 | ✅ **成立（本轮 basis 上亲自重跑，贴原文）** | §2（a）/§3（b）/§4（c）/§5（d）：命令 + 退出码 + 输出原文 |
| **G-302-RESIDUAL 系列**（含 -2/-3/-4） | 仍清零 | ✅ **仍清零（判据未放宽，本轮独立复跑 + 4 类抽样 + 3 组负夹具）** | §6：`SOURCE_STRING_TESTS=0 / SOURCE_STRING_GATE=GREEN / EXIT=0`；4 类方法体复核；夹具⑤⑥⑦各自 RED → 删除 → GREEN；反例未被判红 |
| **机制规格绑定层** | 已建立并闭环 | ✅ **闭环（本轮独立复核 + 独立负向对照）** | §7：`MECHANICS_SPEC_GATE=GREEN` / `ENTRIES=137` / `WEAK=0`；`MECHANICS_SPEC_REPORT=FRESH`；零 `import yaml`；**我把一条 config 载体 expect 改坏 ⇒ RED 且点名 `M-CORE-003`** |
| **G-CACHE-STAMP-BEFORE-LOCK** | 仍闭环 | ✅ **仍闭环（独立复核代码事实）** | §8：eager `nextCommitVersion()` 调用为零；`markLocked` 惰性一次取号；5/5 发布点 `LockedTransaction.wrap`；3 个回归测试在 |
| **env 标签正当性层（本轮新增）** | （v15 时不存在门禁） | ✅ **建立并闭环（本轮独立负向对照）** | §11：`ENV_TAG_JUSTIFIED_GATE=GREEN` / `ENV_TAG_METHODS=7` / `ENV_TAG_UNJUSTIFIED=0`；`list-env-tests.sh` 恰好 7 条；**我把 W32-A1 去掉的标签加回 ⇒ RED 且点名该方法，还原 ⇒ GREEN** |
| **十门 @ basis（M18）** | M14 全绿 | ✅ **全绿（回读核对）** | §9：权威 sn=`cnb-8rl-1k4db6u49`，`event=api_trigger_gate`，`sha=0237548`，13 stage success + `DebugDetection` skipped，判据行逐条回读 |
| **`G-FLAKY-GATE`**（同 sha 是否仍复现假红） | 单点采样（无 RED） | ✅ **同 sha 两次构建均 success（RED=0）** | §9.3：`0237548` 上 `cnb-8rl-1k4db6u49`(api_trigger_gate) + `cnb-l5u-1k4db6aug`(push) 都 success，判据行一致 |

⇒ 差集矩阵「声明未接线」= **0 条**；但**有 1 条可派工的实现缺口 = `W33-RUNTIME-SMOKE`**
（调度器已尝试建立「真实 Paper 运行时冒烟」harness 但**未交付**：分支 `agent/W33-A1-runtime-smoke`
相对 main HEAD 零提交、无 PR、无产出文件 —— 见 §12）。
⇒ `plan/verdict-v16.json`：`done=false`、`remaining=1`、`remaining_ids=["W33-RUNTIME-SMOKE"]`
（与本节 `> verdict:` 行**严格一致**，见 §10）。
> 本轮 PR：<见 `.cnb/agent-out/W34-A1-verdict-v16.json` 的 `pr_url`>（分支 `agent/W34-A1-verdict-v16`）。

## 1. basis 实测（本轮首要目的）

### 1.1 `HEAD` 与 `origin/main` 是否相等

命令与输出原文：

```
$ git rev-parse HEAD
0237548269545974520f073a3ff254a9885004de

$ git rev-parse origin/main
0237548269545974520f073a3ff254a9885004de

$ # 判定
EQUAL=true   # 两者相等

$ git rev-parse --short=7 HEAD
0237548

$ git status --porcelain
(空 —— 干净)
```

⇒ `HEAD == origin/main == 0237548269545974520f073a3ff254a9885004de`，两者**相等**（`EQUAL=true`）。
`basis` 取实测短 sha **`0237548`**（7 位），**不是抄任务书里的数字**。

**口径澄清（防误读）**：上表的 `EQUAL=true` 是**分叉那一刻**的实测（本分支的**起点**）。
本分支提交交付物后，分支 `HEAD` 自然前移，因此**事后**再跑
`git rev-parse HEAD` 会得到分支自己的 HEAD（≠ `origin/main`），这是**预期**的、不是矛盾。
可机械核验分叉点：

```
$ git merge-base HEAD origin/main            # 本分支分叉点
0237548269545974520f073a3ff254a9885004de
$ git rev-parse origin/main                  # 交付后 origin/main 未变
0237548269545974520f073a3ff254a9885004de
```

⇒ 两者一致 ⇒ `basis: 0237548` **字面等于本矩阵分叉时的 main HEAD**。

### 1.2 与 v15 的差别（为什么必须有这一轮）

- v15 的 basis = `c241979`；本轮 basis = `0237548`（= 分叉时的 main HEAD）。
- 自 v15 以来 main **有新的构建输入改动**（这正是本轮必须重判的原因）：
  1. **PR#84 / W32-A1**：`plugins/rpg-guide/.../HelpTopicTest.java` 的
     `everyCustomHelpModelIsDeclaredByTheRuntimeResourcePack()` 去掉过宽的 `@Tag(env)`
     （唯一依赖 `resource-pack/runtime-item-models.txt` **在本仓**）⇒ 模块用例 56→57、
     全量 1105→**1106**、`scripts/list-env-tests.sh` 从 8 条降到 **7 条**。
  2. **PR#84 / W32-A2**：新增 `scripts/check-env-tag-justified.sh`（env 标签正当性判据）。
  3. **commit `0237548`（调度器修）**：修 §12c 夹具的 `git` 依赖
     （门禁镜像 `eclipse-temurin:25-jdk` + apt curl/coreutils/python3 **没有 git**）
     ⇒ 改 `shutil.copytree` + `plugins/` sha256 清单 + 新增 §12f 回归判据。
- ⇒ v15 在 `c241979` 上的 `tests=1105` / `PASS=167` **不可沿用**；本轮实测
  `tests=1106` / `PASS=181`（见 §9.1）。

## 2. 素材层 (a)：`scripts/` 下零消费者 = 0

**判据**：`scripts/` 下每个 `.sh` / `.py` 都要有**真实调用点**（不是「文件存在」）。

`ls scripts/*.sh scripts/*.py | wc -l` → **24**（v15 为 23；本轮新增 `check-env-tag-justified.sh`）。

逐个调用点原文（`.cnb.yml` 直接入口 + `scripts/selftest-gates.sh` 内部调用 +
插件 `*ContractTest` 经 `ProcessBuilder` 真实执行，三类合计）：

| # | 脚本 | 真实调用点（原文） |
|---|---|---|
| 1 | `selftest-gates.sh` | `.cnb.yml:71` `sh scripts/selftest-gates.sh` |
| 2 | `check-ddl-consumers.sh` | `.cnb.yml:89` `sh scripts/check-ddl-consumers.sh`；`selftest-gates.sh:200` `(cd "$GW" && sh scripts/check-ddl-consumers.sh >"$GW/out.txt" 2>&1)` |
| 3 | `check-resource-pack.sh` | `.cnb.yml:117` `sh scripts/check-resource-pack.sh`；`selftest-gates.sh:883` |
| 4 | `check-asset-baseline.py` | `.cnb.yml:143` `python3 scripts/check-asset-baseline.py`；`selftest-gates.sh:1112`（复制到夹具）/:1118（执行） |
| 5 | `check-asset-reproducible.sh` | `.cnb.yml:171` `sh scripts/check-asset-reproducible.sh`；`selftest-gates.sh:1389` |
| 6 | `check-asset-reproducible.py` | `scripts/check-asset-reproducible.sh:128` `exec python3 scripts/check-asset-reproducible.py --root "$ROOT" --generated "$GEN"`（链：`.cnb.yml:171` → `.sh:128` → `.py`） |
| 7 | `asset_fingerprint.py` | `scripts/check-asset-reproducible.sh:127` 注释 + `scripts/check-asset-reproducible.py` 内 `from asset_fingerprint import fingerprint_png`（被真实执行链 import）；`selftest-gates.sh:1112`/`:1274` 显式复制进夹具 |
| 8 | `fetch-deps.sh` | `.cnb.yml` 内 `sh scripts/fetch-deps.sh` |
| 9 | `check-file-deps.sh` | `.cnb.yml:181` `sh scripts/check-file-deps.sh`；`.cnb.yml:56-57` 注明其依赖 python3 |
| 10 | `dump-test-results.py` | `.cnb.yml` test stage `python3 scripts/dump-test-results.py --min-modules 26`；`selftest-gates.sh:82`/`:91` |
| 11 | `list-env-tests.sh` | `.cnb.yml:33` 注释 + test stage `sh scripts/list-env-tests.sh`；`selftest-gates.sh:159` `ENV_LIST=$(sh scripts/list-env-tests.sh)` |
| 12 | `check-plugin-jars.py` | assemble stage `python3 scripts/check-plugin-jars.py /tmp/jars.txt`；`selftest-gates.sh:117`/`:132` |
| 13 | `check-repo-artifacts.sh` | `selftest-gates.sh:726`（绿）/:738（红夹具） |
| 14 | `install-datapacks.sh` | `selftest-gates.sh` §4c 三处（绿夹具/红夹具/真实仓） |
| 15 | `gen-asset-manifest.py` | `selftest-gates.sh` §9「重跑 gen-asset-manifest.py 后清单字节一致」 |
| 16 | `check-source-contract.sh` | `selftest-gates.sh:456` `sh scripts/check-source-contract.sh --all >"$TMP/specs-real.txt" 2>&1`；**另** 40 个插件测试类 `root.resolve("scripts/check-source-contract.sh")` 经 `ProcessBuilder` 真跑 |
| 17 | `check-source-string-tests.sh` | `selftest-gates.sh:504` `cp scripts/check-source-string-tests.sh "$SST_FIXTURE/scripts/"`；`:506` `( cd "$SST_FIXTURE" && sh scripts/check-source-string-tests.sh …)` |
| 18 | `check-mechanics-spec.sh` | `selftest-gates.sh:1711`（夹具）/:1717（真实仓 `sh scripts/check-mechanics-spec.sh`）/:1811（弱夹具） |
| 19 | `gen-mechanics-spec-report.py` | `selftest-gates.sh:1941` `(cd "$ROOT" && python3 scripts/gen-mechanics-spec-report.py --check …)`；：1972（陈旧夹具） |
| 20 | `resource-pack-asset-check.py` | `scripts/check-resource-pack.sh:131` `python3 "$ROOT/scripts/resource-pack-asset-check.py" --root "$ROOT" --check closure`（链：`.cnb.yml:117` → `check-resource-pack.sh:131..161` 七项） |
| 21 | `test-frames.py` | `selftest-gates.sh:188` `python3 scripts/test-frames.py build ddl-consumer-root "$GW" "$@" >/dev/null 2>&1` |
| 22 | `check-config-fallbacks.sh` | `plugins/rpg-enhancement/.../EnhancementFallbackContractTest.java:35` `Path gate = root.resolve("scripts/check-config-fallbacks.sh");`（+ `ProcessBuilder` 真实执行） |
| 23 | `check-trusted-menu-holders.sh` | `plugins/rpg-market/.../TrustedMenuBoundaryContractTest.java:62` `Path gate = root.resolve("scripts/check-trusted-menu-holders.sh");`（+ `ProcessBuilder` 真实执行） |
| 24 | **`check-env-tag-justified.sh`（本轮新增）** | `scripts/selftest-gates.sh:2027` 节标题 / `:2038` `sh scripts/check-env-tag-justified.sh > "$TMP/envtag-green.txt" 2>&1` / `:2097`（红夹具 `sh scripts/check-env-tag-justified.sh "$ENVTAG_FIXTURE"`）/:2135（还原）；`selftest-gates.sh` §12d 断言「selftest-gates.sh 调用 check-env-tag-justified.sh（5 处）」且「.cnb.yml 执行 sh scripts/selftest-gates.sh」 |

**两条指定的行号**（任务要求逐条给出）：

```
$ grep -n 'check-mechanics-spec.sh' scripts/selftest-gates.sh
1711:  (cd "$SPEC_FIXTURE" && sh scripts/check-mechanics-spec.sh >"$1" 2>&1)
1717:(cd "$ROOT" && sh scripts/check-mechanics-spec.sh > "$TMP/spec-real.txt" 2>&1)
1811:(cd "$SPEC_FIXTURE" && sh scripts/check-mechanics-spec.sh > "$TMP/spec-weak.txt" 2>&1)
```

```
$ grep -n 'check-env-tag-justified.sh' scripts/selftest-gates.sh
2027:echo "== 12c. W32-A2 env 标签正当性门禁（scripts/check-env-tag-justified.sh）必须能真的变红 =="
2038:sh scripts/check-env-tag-justified.sh > "$TMP/envtag-green.txt" 2>&1
2097:sh scripts/check-env-tag-justified.sh "$ENVTAG_FIXTURE" > "$TMP/envtag-red.txt" 2>&1
2135:sh scripts/check-env-tag-justified.sh "$ENVTAG_FIXTURE" > "$TMP/envtag-restored.txt" 2>&1
```

**接线闭环**：`selftest-gates.sh` 自身被 `.cnb.yml:71` 执行 ⇒ 上表「只由 selftest-gates 调用」
的脚本每次推送都真跑（CI stage-1 日志原文见 §9.1，含 §12c 的
`[OK  ] 真实仓库 ENV_TAG_JUSTIFIED_GATE=GREEN / ENV_TAG_METHODS=7 / ENV_TAG_UNJUSTIFIED=0`）。
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
manifest: 252    prov: 252
same_set: True   manifest-only: 0   prov-only: 0
recount: {'generator': 129, 'upstream-vanilla': 5, 'upstream-betterhud': 38, 'human-override': 1, 'ai-prompt': 10, 'derived-from': 69}   sum 252
counts:  {'ai-prompt': 10, 'derived-from': 69, 'generator': 129, 'human-override': 1, 'upstream-betterhud': 38, 'upstream-vanilla': 5}
match: True
needsHumanDecision: []
PY
```

⇒ 归一 `assets/` 前缀后**同集合**（`manifest-only=0` / `prov-only=0`）、`$needsHumanDecision` 为**空**、
六类 `$counts` 合计 **252** 且与逐条重算一致（`match: True`）。

## 5. 素材层 (d)：items 定义 81 == runtime-item-models 去注释键 81 + 七项检查全 exit=0

```
$ ls resource-pack/assets/originalrpg/items/*.json | wc -l
81
$ # 去注释键
runtime-item-models keys: 81
items defs: 81
key-set equal: True
items-only: []   models-only: []
```

七项 `--check`（`python3 scripts/resource-pack-asset-check.py --root . --check <name>`）：

```
closure  EXIT=0
compass  EXIT=0
hud      EXIT=0
journey  EXIT=0
lang     EXIT=0
packmeta EXIT=0
textures EXIT=0
```

⇒ **七项全 `exit=0`**。

## 6. `G-302-RESIDUAL` 系列：仍清零且**判据未被放宽**（本轮独立复跑）

### 6.1 门禁判据行（**重定向取码，不走管道**）

```
$ sh scripts/check-source-string-tests.sh > /tmp/sst.txt 2>&1 ; echo EXIT=$?
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
| `DungeonTimeoutLifecycleContractTest` | `plugins/rpg-dungeons/.../DungeonTimeoutLifecycleContractTest.java`：**全类唯一 `Files.` 命中 = `:19` 注释**（`* G-302-RESIDUAL-2：本类原用 {@code Files.readString} 读 DungeonManager.java 生产源码，`） | 3 个 `@Test` 用 `DungeonBukkitTestHarness` **真构造** `DungeonManager`，断言 scheduler/instanceSessions 等**可观察状态**。**不读生产源码正文** |
| `EquipmentExitPathReachabilityTest` | `plugins/rpg-items/.../EquipmentExitPathReachabilityTest.java`：`:332 Files.isRegularFile(gate)` / `:333 Files.isRegularFile(spec)` / `:334 Files.readAllBytes(spec)` / `:336 ProcessBuilder("sh", gate…)` / `:340 process.getInputStream().readAllBytes()` | `:332-333` 判脚本与**规格 JSON**存在；`:334` 读**规格 JSON**（非生产源码）；`:336-344` 真跑门禁并断言 `exitValue()`。**读规格 JSON + 进程 stdout** |
| `SeasonAttunementSettlementWiringTest` | `plugins/rpg-core/.../SeasonAttunementSettlementWiringTest.java`：`:243 Files.isRegularFile(gate)` / `:244 ProcessBuilder("sh", gate…)` / `:250 process.getInputStream().readAllBytes()` / `:299 Files.newInputStream(path)` | `:244-258` 真跑契约门禁；`:250` 读**进程 stdout**；`:299 newInputStream` 是部署产物/夹具读取。**读进程 stdout + 真解析** |
| `DungeonEntryCommitAmbiguityContractTest`（自选第 4 个） | `plugins/rpg-dungeons/.../DungeonEntryCommitAmbiguityContractTest.java`：`:65 Files.isRegularFile(gate)` / `:66 ProcessBuilder("sh", gate…)` / `:70 process.getInputStream().readAllBytes()` / `:77 repositoryRoot` | 四个 `@Test` 全走 `assertContractHolds` → 真跑 `check-source-contract.sh` → 读**进程 stdout** → 断言 `exit==0`。**不读生产源码正文** |

⇒ 4 个类读的都是**测试夹具 / 源规格 JSON / 进程 stdout / 真解析出的配置**，**不是生产源码正文**。

### 6.3 反向验证判据仍有力：**三组**夹具现场造（跑完删除）

夹具造在 `plugins/rpg-core/src/test/java/_gatefixture16/` 下（插件测试目录，非生产源码）：

| 夹具 | 形态 | 命中行 | 结果 |
|---|---|---|---|
| ⑤ `Fixture5WrappedAssertTest` | 内联 `assertTrue(Files.readString(Path.of("plugins/...")).contains("markLocked"))` | `:11` | `EXIT=1` / `SOURCE_STRING_TESTS=3`（三组同时存在）/ `GATE=RED`，点名 `:11 -> ['plugins/rpg-core/src/main/java/.../LockedTransaction.java']` |
| ⑥ `Fixture6ConstPathTest` | `Files.readString(Path.of(<常量>))`（常量 `PROD`） | `:13` | 同上合并 RED，点名 `:13 -> ['plugins/rpg-core/src/main/java/.../LockedTransaction.java']` |
| ⑦ `Fixture7TraversalTest` | `Files.walk(Path.of(".").resolve("plugins").resolve(MODULE).resolve(MAIN))` + `readString(f).contains(id)` 聚合成 `List` 后 `anyMatch` | `:17` | 同上合并 RED，点名 `:17 -> ['(traversal root: plugins\|tools -> src/main)']` |

三组**同时**存在时合并计数（命令原文与输出）：

```
$ sh scripts/check-source-string-tests.sh > /tmp/sstfix.txt 2>&1 ; echo EXIT=$?
EXIT=1
SOURCE_STRING_TESTS=3
  .../Fixture5WrappedAssertTest.java:11 -> ['plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/LockedTransaction.java'] tests=[...]
  .../Fixture6ConstPathTest.java:13 -> ['plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/LockedTransaction.java'] tests=[...]
  .../Fixture7TraversalTest.java:17 -> ['(traversal root: plugins|tools -> src/main)'] tests=[...]
SOURCE_STRING_GATE=RED 仍有测试在「读本仓生产源码 + 源码字符串断言」
```

**删除后**：

```
$ rm -rf plugins/rpg-core/src/test/java/_gatefixture16
$ sh scripts/check-source-string-tests.sh > /tmp/sstfix2.txt 2>&1 ; echo EXIT=$?
EXIT=0
SOURCE_STRING_TESTS=0
SOURCE_STRING_GATE=GREEN
$ git status --porcelain
(空 —— 干净)
```

**反例**：`LanguageBundleContractTest`（`Files.newInputStream` + SnakeYAML **真解析**部署产物）
**未**被判红（`sh scripts/check-source-string-tests.sh | grep -c LanguageBundleContractTest` → **0**）。

⇒ 三组各自变红、删除后恢复 GREEN、`git status --porcelain` 干净、反例未被误判。

**已知形态边界（如实登记，与 v13 §6.5 / v14 §6.3 / v15 §6.3 同源，不构成本轮缺口）**：
形态 ⑦ 的命中要求 `Files.walk` 实参里**同时**出现 `plugins|tools` 与 `src/main` 两个**独立字面量**。
我本轮直接按两段独立字面量（`resolve("plugins").resolve(MODULE).resolve(MAIN)`）写夹具 ⇒ 一次命中（`EXIT=1`）。
判定不构成缺口的三条理由：(1) 三个原历史形态都在命中范围内；(2) `selftest-gates.sh` §4d 的夹具
①②④各带退出码断言，判据每次推送都被负向对照；(3) `SOURCE_STRING_TESTS=0` 是**方法体事实**（§6.2 逐条复核），
不依赖该边界是否可绕。改判据属构建输入，本任务不允许改。

### 6.4 规格规模未缩水（相对 v8 基线 `CHECKS=466 SPECS=78 SPEC_FILES=2`）

```
$ sh scripts/check-source-contract.sh --all > /tmp/sc.txt 2>&1 ; echo EXIT=$?
SOURCE_CONTRACT_SPEC_FILES=3
SOURCE_CONTRACT_CHECKS=469 SPECS=79
SOURCE_CONTRACT_GATE=GREEN
EXIT=0
```

⇒ `CHECKS=469 ≥ 466` / `SPECS=79 ≥ 78` / `SPEC_FILES=3 ≥ 2`，**没有缩水**。

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
$ sh scripts/check-mechanics-spec.sh --spec /tmp/mxfix16/spec.json ; echo EXIT=$?
MECHANICS_SPEC_GATE=RED
MECHANICS_SPEC_ENTRIES=137
MECHANICS_SPEC_BY_KIND=config:78,behavior:56,java-const:3
MECHANICS_SPEC_WEAK=0
  [FAIL] M-CORE-003
         claim  : 0/1/2/3 转分别开放技能 Lv.2/3/4/5
         期望   : [2, 3, 4, 9]
         实际   : ['2', '3', '4', '5']
EXIT=1

$ sh scripts/check-mechanics-spec.sh --spec /tmp/mxfix16/spec.restored.json ; echo EXIT=$?
MECHANICS_SPEC_GATE=GREEN
EXIT=0
```

⇒ 改坏 ⇒ **RED 且点名到具体 id `M-CORE-003`**；还原 ⇒ **GREEN**（不是恒红）。
收尾 `git status --porcelain scripts/` 为空（仓库内 spec 未被改动）。

## 8. `G-CACHE-STAMP-BEFORE-LOCK`：仍闭环（独立复核代码事实）

### 8.1 `LockedTransaction.java` 存在且 `markLocked` 只在第一次调用时取号

```
$ test -f plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/LockedTransaction.java && echo YES
YES

$ sed -n '40,70p' .../LockedTransaction.java
    static Connection wrap(Connection connection, LongSupplier nextVersion) {     # :40
        if (handlerOf(connection) != null) return connection;                     # :41-45 只做代理包装，不取号
        return (Connection) Proxy.newProxyInstance(...);
    }
    /** 行锁到手：登记一次提交版本号（重复调用只生效一次，号必须最早那个）。 */
    private void markLocked() {                                                   # :54
        if (version == 0L) version = nextVersion.getAsLong();                      # :55 惰性一次取号
    }
```

⇒ `wrap` 只包装**不取号**；`markLocked`（`:54-55`）`if (version == 0L)` **只在第一次调用时取号**。

### 8.2 5 个服务的取号触发点都在 `FOR UPDATE` **之后**

| 服务 | 发布点（`LockedTransaction.wrap`） | 取号触发点（`FOR UPDATE` 之后） |
|---|---|---|
| `JdbcProfileService` | `:841 Connection connection = LockedTransaction.wrap(raw, this::nextCommitVersion);` | `:484 "SELECT player_uuid FROM rpg_profiles WHERE player_uuid = ? FOR UPDATE"` → `:492 markLocked(connection, true);`；`:667 SELECT … FOR UPDATE` → `:633 markLocked(connection, lock);`；`:649 markLocked(...)`（`:648` 注释「第二次 select 已经持有行锁，取号点在其之后」） |
| `JdbcAscensionService` | `:284 LockedTransaction.wrap(raw, profiles::nextCommitVersion)` | `loadForUpdate` → `JdbcProfileService` `FOR UPDATE` → `markLocked` |
| `JdbcDungeonRewardService` | `:318 LockedTransaction.wrap(raw, profiles::nextCommitVersion)` | 同上（`loadForUpdate`） |
| `JdbcAdminIncidentCompensationService` | `:723 LockedTransaction.wrap(raw, profiles::nextCommitVersion)` | 同上（`loadForUpdate`） |
| `JdbcAdminTestService` | `:297 LockedTransaction.wrap(raw, profiles::nextCommitVersion)` | 同上（`loadForUpdate` / `loadSnapshot`） |

**eager 取号已彻底消失**（v11 的缺陷形态）：

```
$ grep -rn 'nextCommitVersion()' plugins/rpg-core/src/main/java/ | grep -v '* @param\|{@link\|{@code' | grep -v '@link'
LockedTransaction 无；JdbcProfileService.java:527:    long nextCommitVersion() {          # ← 唯一「定义」
```

⇒ **零个 eager 调用**（只剩 1 个定义 `:527` + 7 处 javadoc 引用）。

### 8.3 三个缓存顺序回归测试都在且断言未放宽

```
$ grep -n 'readCacheIsNotRolledBackByALateCommit\|lateCommitStampCannotRollBackTheReadCache\|commitVersionMustBeTakenAfterTheRowLockIsHeld' plugins/rpg-core/src/test/java/cn/originalrpg/core/persistence/JdbcServicesTest.java
1035:    void readCacheIsNotRolledBackByALateCommit() throws Exception {
1088:    void lateCommitStampCannotRollBackTheReadCache() throws Exception {
1151:    void commitVersionMustBeTakenAfterTheRowLockIsHeld() throws Exception {
```

断言**未放宽**（`JdbcServicesTest.java`）：

```
:1195  assertEquals(20, fromDb, "数据库里 20 次授予必须全部落账");
:1196  assertEquals(fromDb, profiles.get(playerId)…experience(), "取号必须在持有行锁之后…");
:1202  concurrentExperienceGrantsPreserveEveryMutation    # 仍是 20 路并发
```

⇒ 仍是「缓存 == 数据库」「数据库 == 20」，**没有**放宽期望值、**没有**把并发改串行。
PASS 证据见 §9（CI stage-7 `SUMMARY_GATE=GREEN` / `SUM tests=1106 failed=0 errors=0 skipped=2 modules=26`，
其中 `plugins/rpg-core tests=321 failed=0`）。

## 9. 十门在 M18 上全绿（回读核对）

### 9.1 权威构建 `cnb-8rl-1k4db6u49` 的元信息与 stage 判据行

**元信息**（`cnb build get-build-logs --repo xzqkjmnbvc2026/sivitahub --targetRef main`）：

```
.sn                = cnb-8rl-1k4db6u49
.status            = success
.event             = api_trigger_gate
.targetRef         = main
.sha               = 0237548269545974520f073a3ff254a9885004de      # == 本轮 basis
.pipelineSuccessCount = 1
.pipelineFailCount    = 0
```

**stage 状态**（`cnb build get-build-status --repo … --sn cnb-8rl-1k4db6u49`）：
13 个 stage `success` + `DebugDetection` `skipped`（设计上跳过），每个 success stage 的 duration 均非零：

```
success,4057,Prepare                                          → prepare
success,30082,准备下载器（必须是第一个 stage）                  → stage-0
success,617079,门禁自测（负向对照）                            → stage-1
success,121589,DDL 门禁（G-301 消费者 + G-302 注册，双向）      → stage-2
success,554,素材门禁（W7-A1）                                  → stage-3
success,347,素材逐像素基线门禁（W12-A1）                        → stage-4
success,1700,素材可复现性门禁（W13-A1）                         → stage-5
success,163358,compile                                        → stage-6
success,67391,test                                            → stage-7
success,7667,assemble                                         → stage-8
success,1090,BeforeEnd                                        → beforeEnd
skipped,0,DebugDetection                                      → debugDetection
success,649,End                                               → end
```

**每个 stage 的 status 与判据行**（逐条回读）：

| stage | status | 判据行（回读原文） |
|---|---|---|
| stage-0 准备下载器 | success | `curl=yes python3=yes sha256sum=yes` / `DOWNLOADER=ready` |
| stage-1 门禁自测 | success | `===== 门禁自测汇总：PASS=181 FAIL=0 =====` / `GATE_SELFTEST=GREEN`；`§3 [OK  ] 抽出 7 个 @Tag(env) 测试方法`；`SINGLE_SOURCE_OF_TRUTH_GATE=GREEN` / `WORLD_STATE_FILES=1`（负夹具打出 `=RED` / `WORLD_STATE_FILES=2`）；`VERDICT_MATRIX_CONSISTENCY_GATE=GREEN`（打印 `N=15 done=true remaining=0 basis=c241979` —— 那是 M18 构建时仓库最新矩阵仍为 v15；本轮 v16 矩阵一致性按 §10 自查）；**`§12c [OK  ] 真实仓库 ENV_TAG_JUSTIFIED_GATE=GREEN / ENV_TAG_METHODS=7 / ENV_TAG_UNJUSTIFIED=0`**；`§12c [OK  ] 负夹具（把 A1 去掉的标签加回去）→ 1 (exit=1)` / `[OK  ] RED 输出点名 HelpTopicTest#everyCustomHelpModelIsDeclaredByTheRuntimeResourcePack` / `[OK  ] 夹具恢复（标签去掉）→ 0`；`§12f [OK  ] 判据 12f：scripts/ 层没有对「git」的调用（门禁镜像无 git） (exit=0)`；`MECHANICS_SPEC_GATE=GREEN` / `MECHANICS_SPEC_ENTRIES=137` / `MECHANICS_SPEC_BY_KIND=config:78,behavior:56,java-const:3` / `MECHANICS_SPEC_WEAK=0` / `[OK  ] 判据行 MECHANICS_SPEC_REPORT=FRESH` |
| stage-2 DDL 门禁 | success | `DDL 表数: 81` / `豁免清单: 0 条` / `已注册指令数: 43` / `指令豁免清单: 9 条` / `反向豁免清单: 0 条`/ 反向检查扫描文件数 551 ⇒ `DDL_CONSUMER_GATE=GREEN` / `DDL_REGISTRATION_GATE=GREEN` / `CMD_REFERENCE_GATE=GREEN` |
| stage-3 素材门禁 | success | `RESOURCE_PACK_GATE: 校验 /workspace/resource-pack` … `RUN=8 SKIPPED=0` ⇒ `RESOURCE_PACK_GATE=GREEN` |
| stage-4 素材逐像素基线 | success | `ASSET_BASELINE: 校验 252 张 PNG（清单 252 条）` / `UNREGISTERED=0 MISSING=0 MODIFIED=0` ⇒ `ASSET_BASELINE_GATE=GREEN` |
| stage-5 素材可复现性 | success | `逐像素一致: 70` / `REPRODUCIBLE=70 MISMATCH=0 MISSING=0 UNREGISTERED=0` ⇒ `ASSET_REPRO_GATE=GREEN` |
| stage-6 compile | success | `26 actionable tasks: 26 executed` ⇒ `TASKS_DONE=28` / `BUILD_EXIT=0` / `COMPILE_GATE=GREEN` |
| stage-7 test | success | `SUM                          tests=1106 failed=0 errors=0 skipped=2 modules=26` / `NO_XML                       modules=0  期望模块数=26` / `ENV_ANNOTATIONS=7` / `EXCLUDED_TAG_SEEN_IN_LOG=26` / `TEST_EXIT=0 SUMMARY_EXIT=0 SECONDS=67` / `SUMMARY_GATE=GREEN` / `TEST_GATE=GREEN` |
| stage-8 assemble | success | `JAR_CHECKED=25 JAR_MISSING_PLUGIN_YML=0` / `JAR_COUNT=56` ⇒ `PACKAGE_GATE=GREEN` |

**stage-7 逐模块明细**（回读原文，末三行是判据行）：

```
  plugins/rpg-admin            tests=  35 failed=0 errors=0 skipped=0 ok
  plugins/rpg-ai-npc           tests=  14 failed=0 errors=0 skipped=0 ok
  plugins/rpg-api              tests=  94 failed=0 errors=0 skipped=0 ok
  plugins/rpg-classes          tests=  10 failed=0 errors=0 skipped=0 ok
  plugins/rpg-combat           tests=  12 failed=0 errors=0 skipped=0 ok
  plugins/rpg-commerce         tests=   8 failed=0 errors=0 skipped=0 ok
  plugins/rpg-community        tests=  26 failed=0 errors=0 skipped=0 ok
  plugins/rpg-core             tests= 321 failed=0 errors=0 skipped=0 ok
  plugins/rpg-cosmetics        tests=  15 failed=0 errors=0 skipped=0 ok
  plugins/rpg-dungeons         tests=  72 failed=0 errors=0 skipped=0 ok
  plugins/rpg-economy          tests=  31 failed=0 errors=0 skipped=0 ok
  plugins/rpg-enhancement      tests=  13 failed=0 errors=0 skipped=0 ok
  plugins/rpg-essentials       tests=   3 failed=0 errors=0 skipped=0 ok
  plugins/rpg-guide            tests=  57 failed=0 errors=0 skipped=0 ok     # ← 56→57（W32-A1 去标签后真跑）
  plugins/rpg-hub              tests=  40 failed=0 errors=0 skipped=0 ok
  plugins/rpg-integrations     tests=   7 failed=0 errors=0 skipped=0 ok
  plugins/rpg-items            tests= 103 failed=0 errors=0 skipped=0 ok
  plugins/rpg-market           tests=  50 failed=0 errors=0 skipped=0 ok
  plugins/rpg-mobs             tests=  31 failed=0 errors=0 skipped=0 ok
  plugins/rpg-performance      tests=  16 failed=0 errors=0 skipped=0 ok
  plugins/rpg-pets             tests=  17 failed=0 errors=0 skipped=0 ok
  plugins/rpg-skills           tests=  72 failed=0 errors=0 skipped=0 ok
  plugins/rpg-story            tests=  18 failed=0 errors=0 skipped=0 ok
  plugins/rpg-sulfur           tests=   3 failed=0 errors=0 skipped=0 ok
  plugins/rpg-world            tests=  12 failed=0 errors=0 skipped=0 ok
  tools/db-migration           tests=  26 failed=0 errors=0 skipped=2 ok
  SUM                          tests=1106 failed=0 errors=0 skipped=2 modules=26
  NO_XML                       modules=0  期望模块数=26
  ENV_ANNOTATIONS=7
  EXCLUDED_TAG_SEEN_IN_LOG=26 ENV_TAG_LINE=26
```

**逐条对照任务书期望值**：

```
期望 GATE_SELFTEST=GREEN（PASS=181 FAIL=0）        ✅
期望 COMPILE_GATE=GREEN                            ✅ (BUILD_EXIT=0)
期望 TEST_GATE=GREEN                               ✅
期望 SUMMARY_GATE=GREEN（SUM tests=1106 failed=0 errors=0 skipped=2 modules=26） ✅ 逐字一致
期望 PACKAGE_GATE=GREEN（JAR_COUNT=56）            ✅
期望 RESOURCE_PACK_GATE=GREEN                      ✅
期望 ASSET_BASELINE_GATE=GREEN                     ✅
期望 ASSET_REPRO_GATE=GREEN                        ✅
期望 DDL_CONSUMER_GATE=GREEN                       ✅
期望 DDL_REGISTRATION_GATE=GREEN                   ✅
期望 CMD_REFERENCE_GATE=GREEN                      ✅
期望 SINGLE_SOURCE_OF_TRUTH_GATE=GREEN             ✅
期望 VERDICT_MATRIX_CONSISTENCY_GATE=GREEN         ✅（见上：stage-1 打印 N=15，即构建时最新矩阵）
期望 MECHANICS_SPEC_GATE=GREEN                     ✅ (ENTRIES=137 / WEAK=0)
期望 MECHANICS_SPEC_REPORT=FRESH                   ✅
期望 ENV_TAG_JUSTIFIED_GATE=GREEN（METHODS=7/UNJUSTIFIED=0） ✅（§12c 回读）
```

⇒ **十门（含 MECHANICS_SPEC_* 与 ENV_TAG_*）在 M18（`0237548`）上全绿**，无一条与期望不符。
**没有**「查不到」或「与期望不符」的情况。

### 9.2 本分支自身不含构建输入

```
$ git diff --name-only 0237548269545974520f073a3ff254a9885004de HEAD
(本分支从 M18 = 0237548 分叉；交付后本分支新增文件见下)

$ git diff --name-only 0237548 HEAD   # 交付后重跑（本任务只写允许路径）
plan/gap-matrix-v16.md
plan/verdict-v16.json
plan/tasks-w34.json
state/world-state.json
doc/cnb/gap-registry.md
.cnb/agent-out/W34-A1-verdict-v16.json
```

⇒ 只含 `plan/**`、`state/**`、`doc/cnb/**`、`.cnb/agent-out/**`，**不含任何构建输入**
（`plugins/**` / `scripts/**` / `.cnb.yml` / `resource-pack/**` / `docs/**` 一行未动）。

### 9.3 `G-FLAKY-GATE`：同 sha 两次构建均 success（RED=0）

```
$ cnb build get-build-logs --repo xzqkjmnbvc2026/sivitahub --sha 0237548269545974520f073a3ff254a9885004de
- sn: cnb-8rl-1k4db6u49   event: api_trigger_gate   targetRef: main   status: success   sha: 0237548…
- sn: cnb-l5u-1k4db6aug   event: push              targetRef: main   status: success   sha: 0237548…
```

⇒ 同 sha `0237548` 上**两个 main 构建都 success**，RED 计数 = **0** ⇒ v11 的「同 sha 先绿后红」
形态本轮**未复现**（既未复发，也未被证伪）。同 sha 的 `cnb-l5u-1k4db6aug` 判据行一致（见其 stage 日志）。

### 9.4 其它回读核对

- `plan/world-state.json` **不存在**；`git ls-files | grep world-state` 只有 `state/world-state.json`（1 条）
  ⇒ `SINGLE_SOURCE_OF_TRUTH_GATE` 的口径在本分支上仍成立。
- `sh scripts/check-ddl-consumers.sh` → `EXIT=0`（与 stage-2 判据行一致）。

## 10. 与 `plan/verdict-v16.json` 的一致性自查（`vmc_gate` 同口径）

用 `scripts/selftest-gates.sh` 的 `vmc_gate()` **原函数**自查（不重写一份）：

```
$ . <(sed -n '1468,1540p' scripts/selftest-gates.sh) ; vmc_gate "$PWD" ; echo EXIT=$?
VERDICT_MATRIX_CONSISTENCY_GATE=GREEN
N=16 done=false remaining=1 basis=0237548
EXIT=0
```

⇒ 本文件 `:3` `> verdict: done=false remaining=1` 与 `:4` `> basis: 0237548`
与 `plan/verdict-v16.json` 的 `done` / `remaining` / `basis` **严格一致**（diff 结果见交付证据）。

## 11. env 标签正当性层（本轮新增）：独立复核 + **独立负向对照**

### 11.1 正向

```
$ sh scripts/check-env-tag-justified.sh ; echo EXIT=$?
ENV_TAG_METHODS=7
ENV_TAG_JUSTIFIED=7
ENV_TAG_UNJUSTIFIED=0
ENV_TAG_JUSTIFIED_GATE=GREEN
EXIT=0
```

⇒ `ENV_TAG_JUSTIFIED_GATE=GREEN` / `ENV_TAG_METHODS=7` / `ENV_TAG_UNJUSTIFIED=0`。

### 11.2 `sh scripts/list-env-tests.sh` 输出**恰好 7 条**

```
$ sh scripts/list-env-tests.sh ; echo EXIT=$?
chunkLoadingUsesPaperConcurrencyWithoutDiscardingSavedEntities
controlledGenerationCoversTheCompleteBorderAndItsChunkMargin
mapsAndClaimsFailClosedForDynamicWorlds
naturalSpawningKeepsTheReviewedExperienceAndEntityEnvelope
projectYamlFilesParseWithoutDuplicateKeys
qizhiConversationUsesTheExecutableFirstForgeOrder
quickActionTaskButtonUsesTheUnifiedTaskCenter
EXIT=0
```

`wc -l` → **7**（v15 之前为 8；W32-A1 去掉过宽标签后降为 7）。
CI stage-1 亦有对应断言 `[OK  ] 抽出 7 个 @Tag(env) 测试方法`（§9.1）。

### 11.3 **我自己独立做的**负向对照（不采信 NPC）

在 `/tmp/envtagfix16` 建一份**含 `plugins/` + `resource-pack/` 的整树副本**（先跑基线：7 方法 GREEN），
再把 W32-A1 去掉的那个标签加回 `HelpTopicTest#everyCustomHelpModelIsDeclaredByTheRuntimeResourcePack`：

```
$ sh scripts/check-env-tag-justified.sh /tmp/envtagfix16 ; echo BASELINE_EXIT=$?
ENV_TAG_METHODS=7
ENV_TAG_JUSTIFIED=7
ENV_TAG_UNJUSTIFIED=0
ENV_TAG_JUSTIFIED_GATE=GREEN
BASELINE_EXIT=0

$ # 加回 @Tag(DeploymentEnvironmentFixtures.TAG)
$ sh scripts/check-env-tag-justified.sh /tmp/envtagfix16 ; echo RED_EXIT=$?
ENV_TAG_METHODS=8
ENV_TAG_JUSTIFIED=7
ENV_TAG_UNJUSTIFIED=1
UNJUSTIFIED plugins/rpg-guide/.../HelpTopicTest.java#everyCustomHelpModelIsDeclaredByTheRuntimeResourcePack -> 全部引用路径都在本仓: resource-pack/runtime-item-models.txt
check-env-tag-justified: 过宽的 env 标签 —— 这些方法的引用路径全部在本仓，本该在门禁里跑，却被打上 env 排除
ENV_TAG_JUSTIFIED_GATE=RED
RED_EXIT=1

$ # 去掉标签（还原）
$ sh scripts/check-env-tag-justified.sh /tmp/envtagfix16 ; echo RESTORE_EXIT=$?
ENV_TAG_METHODS=7
ENV_TAG_JUSTIFIED=7
ENV_TAG_UNJUSTIFIED=0
ENV_TAG_JUSTIFIED_GATE=GREEN
RESTORE_EXIT=0
```

⇒ 加回过宽标签 ⇒ **RED** 且 `UNJUSTIFIED` 行**点名** `HelpTopicTest#everyCustomHelpModelIsDeclaredByTheRuntimeResourcePack`
（引用路径全部在本仓）；还原 ⇒ **GREEN**（不是恒红）。全程在 `/tmp` 副本上做，
收尾 `grep -c 'DeploymentEnvironmentFixtures.TAG' plugins/rpg-guide/.../HelpTopicTest.java` → **0**、
`git status --porcelain` 为空。

## 12. 「声明未接线」与「可派工实现缺口」清点

### 12.1 声明未接线 = 0

「声明未接线」的定义（GOAL §2）：机制 / 表 / 注册点 / 状态机**存在但没有消费者**。本轮逐项复核：

- `scripts/**`：24/24 有真实调用点（§2）⇒ 0。
- DDL：81 张表有真实消费者、豁免 0 条（§9.1 stage-2）⇒ 0。
- 装备退出路径 / `ProgressionRules` / `AuthoritativeItemPolicy` / `JdbcNodeLease` /
  `EquipmentOperationCoordinator` / 赛季结算 / session-lease / 社群七角色 / 副本 world 键：
  W2 已全部接线（历史记录见 `state/world-state.json` `merged_gaps`）。
- `G-CACHE-STAMP-BEFORE-LOCK`：仍闭环（§8）⇒ 0。

⇒ **「声明未接线」= 0 条**。

### 12.2 可派工实现缺口 = **1**：`W33-RUNTIME-SMOKE`（真实 Paper 运行时冒烟 harness 未交付）

**如实登记，不粉饰、不当作已完成证据**：

```
$ git log --oneline origin/main..origin/agent/W33-A1-runtime-smoke
(空 —— 零提交)
$ git log -1 --format='%H %ci %s' origin/agent/W33-A1-runtime-smoke
0237548269545974520f073a3ff254a9885004de 2026-10-08 16:48:02 +0800 fix(selftest): 修 §12c 的 git 依赖 …
# ↑ 分支指针 == main HEAD（0237548），即分支未动
$ git diff --name-only origin/main origin/agent/W33-A1-runtime-smoke
(空 —— 无差异)
$ cnb pulls list-pulls --repo xzqkjmnbvc2026/sivitahub --state open
total: 0        # 无任何 open PR
$ grep -rln 'W33' plan/ .cnb/agent-out/ doc/cnb/ state/ 2>/dev/null
(空 —— 无任何产出文件登记)
```

⇒ 调度器为「真实 Paper 运行时冒烟」建立了分支 `agent/W33-A1-runtime-smoke`，但：
- 分支相对 main **零提交**、**无 diff**；
- **无 PR**（open PR 总数 0）；
- **无产出文件**（`W33` 在 `plan/`、`.cnb/agent-out/`、`doc/cnb/`、`state/` 零命中）；
- 已知失败模式：构建超过 60 分钟超时。

**这构成一个可派工的实现缺口**（不是 REQUIRES_HUMAN）：它不要求人类操作，
只需要在 CNB 上做出一个能真实启动 Paper / 采集运行时证据的 harness（或把超时收敛到 CI 预算内）。
本轮判定 `done=false`、`remaining=1`、`remaining_ids=["W33-RUNTIME-SMOKE"]`。

**为什么不能直接判 done**：`done=true` 的第 1 个条件要求「没有可派工的实现缺口」。
W33 的**目标**（真实 Paper 运行时冒烟）覆盖了 W7-A2 四条 `[R]` 级 unproven 的**验证手段**，
是一个本仓可以做、尚未做的实现任务 ⇒ 它是**可派工**的，不能豁免。

## 13. 剩余问题清单

### 13.1 可派工（1 条）

| id | 缺口 | 为什么可派工 | 建议方向 |
|---|---|---|---|
| `W33-RUNTIME-SMOKE` | 真实 Paper 运行时冒烟 harness 未交付（分支零提交 / 无 PR / 无产出） | 不需要人类操作，只需在 CNB 上做一个能真实启动 Paper 并采集运行时证据的 harness（或把构建收敛到 CI 超时预算内） | 见 `plan/tasks-w34.json` |

### 13.2 REQUIRES_HUMAN（**只许** H-001 / U-003 / W7-A2 的 4 条，不扩列）

| id | 内容 |
|---|---|
| H-001 | 原版世界方形边界缺 5435 个四角区块，需无人维护窗口在生产机 `lamb:/home/lamb/minecraft` 跑 `scripts/maintain-world-coverage.sh`（生产机**写操作**，L-006 只读）。 |
| U-003 | 纯原版客户端能否真握手进服，需**真实 Paper + 真实客户端**。 |
| W7-A2 / G-205 | 启动对账需真实 Bukkit。 |
| W7-A2 / G-207 | 计数器是内存态，重启会丢当天场次。 |
| W7-A2 / G-208 | 启动 fail-closed 未在真实 Paper 验证。 |
| W7-A2 / G-209 | 20 tick 是表现层推理值。 |

⇒ 剩余问题 = **1 条可派工（W33-RUNTIME-SMOKE） + 6 条 REQUIRES_HUMAN**（3 类：H-001 / U-003 / W7-A2 四条）。
⇒ `done=false`：第 1 个条件（无可派工实现缺口）**不成立**。其余三条件成立（§9 十门全绿含 `MECHANICS_SPEC_*` 与 `ENV_TAG_*`；
§2–§5 素材层四条在本轮 basis 上亲自重跑成立；剩余问题中只有 REQUIRES_HUMAN 那一部分属人类）。

## 14. 本轮登记的两条**已闭环**缺陷（机械证据）

| id | 缺陷 | 机械证据 | 状态 |
|---|---|---|---|
| `G-ENV-TAG-OVERBROAD` | **env 标签过宽**：`HelpTopicTest.everyCustomHelpModelIsDeclaredByTheRuntimeResourcePack()` 的唯一依赖 `resource-pack/runtime-item-models.txt` **在本仓**，却被 `@Tag(env)` 排除 ⇒ **从来没跑过** | 修前：`sh scripts/list-env-tests.sh \| wc -l` = 8；修后 `= 7`；CI stage-7 `plugins/rpg-guide tests=57`（56→57）；新增判据 `sh scripts/check-env-tag-justified.sh` → `ENV_TAG_METHODS=7 / UNJUSTIFIED=0 / GREEN`；**我独立负向对照**：加回标签 ⇒ `ENV_TAG_GATE=RED` / `ENV_TAG_UNJUSTIFIED=1` / 点名该方法（§11.3） | **DONE** PR#84 + `a326cf9`（selftest-gates.sh §12c/§12d 防复发） |
| `G-GATE-FIXTURE-NEEDS-GIT` | **§12c 夹具依赖 `git` 而门禁镜像无 `git`**：`git archive HEAD` / `git status --porcelain` 在 `eclipse-temurin:25-jdk` + apt curl/coreutils/python3 上报 `git: not found` ⇒ `tarfile.ReadError: empty file` ⇒ M17 门禁 error、main 变红 | 修法（commit `0237548`）：整树副本改 python `shutil.copytree`（跳过 `.git`）、残留判据改 `plugins/` 的 **sha256 清单前后比对**、新增 **§12f 回归判据**（`scripts/**` 不得对「git」发起调用，注释除外）。机械证据：M18 stage-1 `[OK  ] 判据 12f：scripts/ 层没有对「git」的调用（门禁镜像无 git） (exit=0)`；`[OK  ] env 标签判据的夹具未在真实仓库留下残留（plugins/ 的 sha256 清单前后一致）`；`GATE_SELFTEST=GREEN` / `PASS=181 FAIL=0` | **DONE** `0237548`（M18 十门全绿即闭环证据） |
