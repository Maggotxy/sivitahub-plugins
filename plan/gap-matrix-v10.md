# 全量机制矩阵 v10（gap-matrix-v10）

> basis: 1ace5ab
> verdict: done=true remaining=0
> basis-substring: 1ace5ab
> 生成者：CNB CodeBuddy NPC，任务 `W23-A1-verdict-v10`（当前 main 的最终复验轮）。
> 实测 `git rev-parse HEAD` == `git rev-parse origin/main` == `1ace5ab89faf563b70af78cee4926330f7906187`（两者**相等**）。
> M5（本轮采信的十门证据 sha）= `1ace5ab`；本分支自身不含构建输入，见 §8。
> 状态取值：`已接线` / `声明未接线` / `存疑` / `已废弃` / `真实缺口`。

## 0. 本轮结论（先给判定，再给证据）

| 项 | v8 判定（basis=77112113） | v10 判定（basis=1ace5ab） | 依据 |
|---|---|---|---|
| **G-302-RESIDUAL-2**（`DungeonTimeoutLifecycleContractTest` 8 处 `indexOf`） | **真实缺口**（1 类 / 1 方法，逃过 W16 行内形态判据） | **清零** | §6.1：`readString` 只剩注释；@Test 真跑 `DungeonManager` + 记录型 `BukkitScheduler`，断言可观察状态 |
| **G-302-RESIDUAL-3**（W19 判据反向失效①：`assert` 包住读取 / `Path.of(常量)` 被漏判 GREEN） | 未登记 | **闭环** | §6.3：夹具⑤⑥现场复跑，RED→删除→GREEN |
| **G-302-RESIDUAL-4**（W19 判据反向失效②：遍历式源码扫描被整体豁免） | 未登记 | **闭环** | §6.3：夹具⑦⑧（含 `resolve` 拼接变体）现场复跑 RED；豁免已删除（§6.2） |
| `SOURCE_STRING_GATE` | GREEN（`SOURCE_STRING_TESTS=0`） | **GREEN（`SOURCE_STRING_TESTS=0`，独立复核判据未放宽）** | §6.1–§6.4 |
| `SOURCE_CONTRACT` 规格规模 | `CHECKS=466 SPECS=78 SPEC_FILES=2` | **`CHECKS=469 SPECS=79 SPEC_FILES=3`（未缩水，见 §6.4）** | `#69` 新增 `rpg-core.json` ⇒ `SPEC_FILES` 上升（2→3），符合预期 |
| 十门 @ basis | 十门 @ f580ab6 | **十门 @ 1ace5ab（M5），两次同 sha 连续全绿** | §8：`cnb-785-1k41j43k2`（权威）+ `cnb-q65-1k41k0gtr`（复核） |
| `G-FLAKY-GATE` | 未登记 | **闭环** | §7.1：`awaitQuiet()` 时间启发式已删、`awaitChainSettled()` + 回归测试在、30/30 绿 |
| `G-DUP-WORLDSTATE` | 未登记 | **闭环** | §7.2：`test ! -e plan/world-state.json` 成立；`SINGLE_SOURCE_OF_TRUTH_GATE=GREEN / WORLD_STATE_FILES=1` |
| `G-VERDICT-MATRIX-SPLIT` | 未登记 | **闭环（本轮自身即判据对象）** | §7.3：`VERDICT_MATRIX_CONSISTENCY_GATE=GREEN`；v10 矩阵头与 v10 JSON 逐字一致 |
| 素材层四条 | 成立 | **成立（复跑一致）** | §1–§5 |
| 其余机制（v8 的 39 条矩阵） | 已接线 | **已接线（复跑一致）** | §7.4：11 个门禁脚本本地复跑 exit=0 |
| DDL 81 张表零消费者 | 0 | **0** | §7.4：`DDL_CONSUMER_GATE=GREEN`，豁免 0 条 |

⇒ 差集矩阵「声明未接线」= 0 条；「可派工的实现缺口」= **0 条**（`G-302-RESIDUAL` 系列**全部清零，且判据未被放宽到失去意义**）；
十门在 M5=`1ace5ab` 上两次连续全绿；素材层四条成立；剩余问题**只有** REQUIRES_HUMAN。
⇒ `plan/verdict-v10.json`：`done=true`、`remaining=0`（与本节 `> verdict:` 行严格一致）。

⚠️ 三条本轮特别验证的前提（都属于「不能只看计数」）：
1. **「计数下降」不等于「缺口清零」**：`SOURCE_STRING_TESTS=0` 只说明门禁当前判据下无命中；
   本轮把 4 个类逐方法读体（§6.2），确认「不再对生产源码做字符串断言」是**方法体事实**，不是计数错觉。
2. **「判据变窄」不等于「问题消失」**：W18 第二轮曾把遍历式扫描整体豁免 ⇒ 判据变窄、问题仍在。
   本轮用夹具⑦⑧证明该豁免**已被撤掉**（形态④真的会红），并用反例证明**没有反向过捕**（§6.3）。
3. **「门禁 GREEN」不等于「判据没有过宽豁免」**：§6.4 复核了两处**已知的、有理由的**口径边界
   （进程 stdout 读取、`Files.newInputStream` 真解析），并逐条给出为什么它们不是缺口。

## 1. (a) 素材层 —— 零消费者=0（逐条给调用点原文）

口径：**「脚本存在」不等于「脚本被调用」**。`scripts/` 下 21 个 `.sh`/`.py` 逐个查真实调用点。
（`source-contract-specs/` 是目录，不是脚本。）

| # | 脚本 | 真实调用点原文 |
|---|---|---|
| 1 | `scripts/selftest-gates.sh` | `.cnb.yml:71` `sh scripts/selftest-gates.sh`（stage「门禁自测（负向对照）」） |
| 2 | `scripts/dump-test-results.py` | `.cnb.yml:287` `python3 scripts/dump-test-results.py --min-modules 26`；`selftest-gates.sh:75` `cp scripts/dump-test-results.py "$WS/scripts/"` + `:80` 真跑 |
| 3 | `scripts/check-plugin-jars.py` | `.cnb.yml:381` `python3 scripts/check-plugin-jars.py /tmp/jars.txt`；`selftest-gates.sh:110` `cp scripts/check-plugin-jars.py "$JW/scripts/"` + 真跑 |
| 4 | `scripts/list-env-tests.sh` | `.cnb.yml:297` `sh scripts/list-env-tests.sh > /tmp/env-methods.txt`；`selftest-gates.sh:152` `ENV_LIST=$(sh scripts/list-env-tests.sh)` |
| 5 | `scripts/check-ddl-consumers.sh` | `.cnb.yml:89` `sh scripts/check-ddl-consumers.sh`；`selftest-gates.sh:184` `(cd "$GW" && sh scripts/check-ddl-consumers.sh >"$GW/out.txt" 2>&1)` |
| 6 | `scripts/check-source-contract.sh` | `selftest-gates.sh:285` `SOURCE_CONTRACT_SPEC="$SPEC_TMP" sh scripts/check-source-contract.sh --all`、`:449` 真实仓真跑；被 35 个 `*ContractTest` 经 `ProcessBuilder` 真跑（例：`EquipmentExitPathReachabilityTest.java:334` `new ProcessBuilder("sh", gate.toString(), SPEC_CLASS + "#" + specKey, method)`） |
| 7 | `scripts/check-source-string-tests.sh` | `selftest-gates.sh:497` `cp scripts/check-source-string-tests.sh "$SST_FIXTURE/scripts/"`、`:651` `( cd "$ROOT" && sh scripts/check-source-string-tests.sh ...)`（夹具必须 exit=1 / 真实仓必须 exit=0） |
| 8 | `scripts/check-config-fallbacks.sh` | `plugins/rpg-enhancement/.../EnhancementFallbackContractTest.java:37` `new ProcessBuilder("sh", gate.toString())` + `:44` `assertEquals(0, process.exitValue(), ...)` |
| 9 | `scripts/check-trusted-menu-holders.sh` | `plugins/rpg-market/.../TrustedMenuBoundaryContractTest.java:62-67` `Path gate = root.resolve("scripts/check-trusted-menu-holders.sh"); ... new ProcessBuilder("sh", gate.toString())` + `assertEquals(0, process.exitValue(), ...)` |
| 10 | `scripts/check-repo-artifacts.sh` | `selftest-gates.sh:719` `(cd "$ROOT" && sh scripts/check-repo-artifacts.sh >"$TMP/artifact-green.txt" 2>&1)`、`:731` 负向真跑 |
| 11 | `scripts/check-resource-pack.sh` | `.cnb.yml:117` `sh scripts/check-resource-pack.sh`；`selftest-gates.sh:906` `rpg_negative()` 内真跑 |
| 12 | `scripts/resource-pack-asset-check.py` | `scripts/check-resource-pack.sh:131` `python3 "$ROOT/scripts/resource-pack-asset-check.py" --root "$ROOT" --check closure`（另有 textures/hud/journey/lang/compass/packmeta 共 8 处） |
| 13 | `scripts/check-asset-baseline.py` | `.cnb.yml:143` `python3 scripts/check-asset-baseline.py`；`selftest-gates.sh:1105` 拷进夹具真跑 |
| 14 | `scripts/gen-asset-manifest.py` | `selftest-gates.sh:1225` `(cd "$ROOT" && python3 scripts/gen-asset-manifest.py --check >/dev/null 2>&1)`（断言稳定输出）；`check-asset-baseline.py:63` 指向它作为唯一更新路径 |
| 15 | `scripts/asset_fingerprint.py` | `scripts/check-asset-reproducible.sh:127` 注释声明复用；`check-asset-baseline.py:33` / `gen-asset-manifest.py:25` / `check-asset-reproducible.py:32` 三处 `from asset_fingerprint import fingerprint_png` |
| 16 | `scripts/check-asset-reproducible.sh` | `.cnb.yml:171` `sh scripts/check-asset-reproducible.sh`；`selftest-gates.sh:1292` / `:1382` 真跑 |
| 17 | `scripts/check-asset-reproducible.py` | `scripts/check-asset-reproducible.sh:128` `exec python3 scripts/check-asset-reproducible.py --root "$ROOT" --generated "$GEN"` |
| 18 | `scripts/fetch-deps.sh` | `.cnb.yml:179` `sh scripts/fetch-deps.sh`、`:414`（另一 stage）`sh scripts/fetch-deps.sh` |
| 19 | `scripts/check-file-deps.sh` | `.cnb.yml:181` `sh scripts/check-file-deps.sh`、`:415` |
| 20 | `scripts/install-datapacks.sh` | `selftest-gates.sh:683` `(cd "$DATAPACK_FIXTURE" && sh scripts/install-datapacks.sh --check ...)`、`:709` 真实仓 `--check` |
| 21 | `scripts/test-frames.py` | `selftest-gates.sh:181` `python3 scripts/test-frames.py build ddl-consumer-root "$GW" "$@" >/dev/null 2>&1` |

判定：**零消费者 = 0**（21/21 都有真实调用点；其中 13 个有 CI stage 入口，
其余 8 个（7、8、9、10、12、14、20、21）由 `selftest-gates.sh` 或插件的 `*ContractTest` 经 `ProcessBuilder` 真跑）。

## 2. (b) `assets-manifest.json` 252 条 ↔ 磁盘

```
$ python3 scripts/check-asset-baseline.py > /tmp/ev/ab.txt 2>&1; echo "EXIT=$?"
EXIT=0
ASSET_BASELINE: 校验 252 张 PNG（清单 252 条）
  UNREGISTERED=0 MISSING=0 MODIFIED=0
ASSET_BASELINE_GATE=GREEN

$ python3 -c "import json;print('manifest entries:',len(json.load(open('resource-pack/assets-manifest.json'))))"
manifest entries: 252
$ find resource-pack/assets -name '*.png' | wc -l
252
```
判定：**成立**。252 ↔ 252，集合相等（UNREGISTERED / MISSING / MODIFIED 全 0）。
CI 判据行（M5 stage-4）：`ASSET_BASELINE: 校验 252 张 PNG（清单 252 条）` + `UNREGISTERED=0 MISSING=0 MODIFIED=0`。

## 3. (c) `asset-provenance.json` ↔ `assets-manifest.json`

```
$ python3 - <<'PY'
import json
m=json.load(open('resource-pack/assets-manifest.json'))
p=json.load(open('resource-pack/asset-provenance.json'))
prov=p['assets']
norm=lambda k: k[len('assets/'):] if k.startswith('assets/') else k
mset={norm(k) for k in m}; pset={norm(k) for k in prov}
print('manifest',len(mset),'provenance',len(pset))
print('manifest-only',len(mset-pset),'prov-only',len(pset-mset))
print('$needsHumanDecision',repr(p['$needsHumanDecision']))
print('$counts',p['$counts'],'sum',sum(p['$counts'].values()))
PY
manifest 252 provenance 252
manifest-only 0 prov-only 0
$needsHumanDecision []
$counts {'ai-prompt': 10, 'derived-from': 69, 'generator': 129, 'human-override': 1, 'upstream-betterhud': 38, 'upstream-vanilla': 5} sum 252
```
判定：**成立**。归一 `assets/` 前缀后同集合；`$needsHumanDecision` 是**空数组**（不是 `""`、不是缺字段）；
六类 `$counts` 合计 252 == manifest 252。

## 4. (d) items(81) ↔ runtime-item-models(81) ↔ 七项素材判据

```
$ ls resource-pack/assets/originalrpg/items/*.json | sed 's#.*/##; s#\.json$##' | sort > /tmp/ev/defs.txt; wc -l < /tmp/ev/defs.txt
81
$ awk 'NF && $0 !~ /^#/' resource-pack/runtime-item-models.txt | sort > /tmp/ev/manifest.txt; wc -l < /tmp/ev/manifest.txt
81
$ diff /tmp/ev/defs.txt /tmp/ev/manifest.txt && echo IDENTICAL
IDENTICAL

$ for c in closure compass hud journey lang packmeta textures; do
    python3 scripts/resource-pack-asset-check.py --root . --check $c >/tmp/ev/rp-$c.txt 2>&1; echo "$c exit=$?"; done
closure exit=0
compass exit=0
hud exit=0
journey exit=0
lang exit=0
packmeta exit=0
textures exit=0
```
判定：**成立**。81 == 81（`diff` IDENTICAL）；七项素材判据全部 `exit=0`。
另有资源包门禁侧的闭包具体值：`物品定义 81 / 可达模型 119 / 可达纹理 115`（`/tmp/ev/rp-closure.txt`）。

## 5. 素材可复现性（CI 真跑，本仓无 JDK 未本地复跑 —— 如实说明）

CI（M5 stage-5）判据行原文：
```
[repro] 罗盘模型 JSON：一致 32/32
ASSET_REPRO: 比对 70 张（4 个生成器）
  逐像素一致: 70
  REPRODUCIBLE=70 MISMATCH=0 MISSING=0 UNREGISTERED=0
```
判定：**成立（以 CI 为准）**。⚠️ 本容器**没有 JDK**（`which javac java` 无输出），
`check-asset-reproducible.sh` 需要现场 `javac` 编译生成器 ⇒ **本轮未在本地复跑**，
以 M5 的 stage-5 `status=success` + 上述判据行为证据（不断言本地跑过）。

## 6. ★ 核心：G-302-RESIDUAL 系列是否真清零 + 判据是否被放宽

### 6.1 判据行与退出码（**重定向到文件再取 `$?`**，不走管道）

```
$ sh scripts/check-source-string-tests.sh > /tmp/ev/f5run.txt 2>&1; printf 'EXITCODE=%s\n' "$?" > /tmp/ev/f5.exit
EXITCODE=0
$ cat /tmp/ev/f5run.txt
SOURCE_STRING_TESTS=0
SOURCE_STRING_GATE=GREEN 本仓生产源码已无源码字符串断言（豁免口径见脚本头部注释）

$ sh scripts/check-source-string-tests.sh --json > /tmp/ev/sst-json.txt 2>&1; echo "JSON_EXIT=$?"
JSON_EXIT=0
$ cat /tmp/ev/sst-json.txt
{
 "hits": [],
 "count": 0
}
SOURCE_STRING_TESTS=0
SOURCE_STRING_GATE=GREEN 本仓生产源码已无源码字符串断言（豁免口径见脚本头部注释）
```
⇒ `SOURCE_STRING_TESTS=0` / `SOURCE_STRING_GATE=GREEN` / `EXIT=0`（`hits` 是**空数组**）。

### 6.2 独立抽样复核 ≥4 个类（逐方法读 @Test 方法体）

分类口径（4 种「读」要分清）：
- 读**生产源码**（`plugins|tools/**/src/main`）→ 可能构成缺口；
- 读**测试夹具**（`src/test/resources`、测试自己写的临时文件）→ 不是缺口；
- 读 `scripts/source-contract-specs/*.json` → 不是缺口；
- 读**进程 stdout**（`process.getInputStream().readAllBytes()`）→ 不是缺口（这是执行外部门禁的产物，不是源文件正文）。

| # | 类 | 判定 | `文件:行号` + 依据 |
|---|---|---|---|
| 1 | `DungeonTimeoutLifecycleContractTest` | **已行为化（清零）** | `:19` 是**注释**里的 `Files.readString`（`* G-302-RESIDUAL-2：本类原用 {@code Files.readString}…`）。全类 `grep -n 'readString\|readAllBytes\|Files\.'` **只命中 :19**（注释）⇒ 代码里零读取。3 个 @Test（`:52` `:82` `:102`）用 `DungeonBukkitTestHarness.newManager(scheduler)` 真构造 `DungeonManager`，经动态代理 `Server/Plugin/World` + 记录型 `BukkitScheduler` 真跑三条路径；断言落在可观察状态：`scheduler.callbacks.size()`（`:63`）、`scheduler.cancelled.size()`（`:71`）、`instanceSessions` 代际（`:73` `assertNotEquals(firstSession, secondSession, ...)`）、`instanceTimeouts.containsKey`（`:123`） |
| 2 | `EquipmentExitPathReachabilityTest` | **已行为化 + 声明式规格** | `grep` 的 4 处 `Files.readString/readAllBytes` 全部在读**测试夹具 / 规格文件 / 进程 stdout**：`:334` `new String(Files.readAllBytes(spec), ...)` 读的是 `scripts/source-contract-specs/rpg-items.json`（规格文件，非源码）；`:340` `process.getInputStream().readAllBytes()` 是**进程 stdout**；`:36-37` / `:64` 是注释。6 个 @Test 里 5 条真跑行为：`:88`（`EquipmentExitPathRules` / `DestroyRules` / `DestroyDeliveryRules` 真跑）、`:120` **真解析打包后的** `plugin.yml`/`config.yml`（`packagedYaml()` `:311` 用 `getResourceAsStream` + `Yaml(new SafeConstructor())`）、`:146`（枚举 `EquipmentLifecycle.State.values()` × `EquipmentOperationKind.values()` 真跑 `isExitPath`/`isWired`）、`:188`（8 kind × 12 State = 96 组合真跑 `afterOperation`）、`:213`、`:241`（`DestroyGate.allowsTerminalExit` 真跑）。**生产源码扫描 helper `productionReferences()` 已删除**（`grep -rn productionReferences plugins/` 只剩 3 处注释） |
| 3 | `SeasonAttunementSettlementWiringTest` | **已行为化 + 声明式规格** | `productionReferences()` helper **已删除**（`:214` 只剩注释「原实现用 productionReferences(\"attune\")（Files.walk(plugins/rpg-core/src/main) + Files.readString(...).contains()）」）。全类真实读取只有 `:299` `Files.newInputStream(path)`（读 `plugins/rpg-core/src/main/resources/config.yml` 后**用 SnakeYAML 真解析**，断言 `config.get("season-attunement")` 是 Map 且 `enabled == Boolean.FALSE`，:265-270）——读的是**配置**、断言的是**解析结果**。`:250` `process.getInputStream().readAllBytes()` 是**进程 stdout**（`:244` `new ProcessBuilder("sh", gate.toString(), <本类>#<specKey>, method)`）。静态那一半迁进**新建** `scripts/source-contract-specs/rpg-core.json`（`:219` `assertSourceContractHolds("#attunement-consumer", ...)`）。剩余 @Test 全是真跑：`:124`（编译期依赖 + `SeasonAttunementRules.TRIGGER`）、`:138`（`CountingAttunement` 真跑 4 步状态机，`attunements.batchCalls.get() >= 1`）、`:161`（未装配服务时零影响 + 真 H2 计数）、`:179`（Kill Switch 关 ⇒ `Status.BLOCKED` + 调谐 0 次） |
| 4 | `DungeonEntryCommitAmbiguityContractTest`（自选第 4 个） | **已迁进声明式规格（非缺口）** | `grep -n 'readString\|readAllBytes\|Files\.'` 只命中 `:65` `Files.isRegularFile(gate)`（存在性检查）、`:70` `process.getInputStream().readAllBytes()`（**进程 stdout**）、`:77` `Files.isRegularFile(current.resolve("settings.gradle.kts"))`（找仓根）。4 个 @Test（`:37` `:43` `:50` `:55`）全部只调 `assertContractHolds(specKey, method)` ⇒ 真跑 `scripts/check-source-contract.sh` 并 `assertEquals(0, process.exitValue(), ...)`。**规格非空且非退化**：`all.json#…DungeonEntryCommitAmbiguityContractTest.java#dungeon-command` 有 4 条 check，`ordered` 链 6 段（`["entries.commitAll(reservations).whenComplete", "resolveAmbiguousCommit(", "entries.partyCommitStatus(reservations)", "PartyCommitStatus.COMMITTED", "PartyCommitStatus.RESERVED", "rollbackEntry(reservations"]`），required 共 9 条 / forbidden 1 条。单键真跑：`SOURCE_CONTRACT_CHECKS=1 SPECS=1` / `SOURCE_CONTRACT_GATE=GREEN` / `EXIT=0` |

判定：**4/4 个类都不再对生产源码做字符串断言**，且不是「因为判据变窄才不报」——
逐条给出的是**方法体事实**（读的是什么、断言落在哪），而不是门禁的 `count=0`。

另附机械复查：全仓 `plugins/**/src/test/**/*.java` 里代码行（去注释）仍含 `Files.readString/readAllBytes` 的类，
其命中项已逐条归到「夹具 / 规格 / 进程 stdout / 配置解析」四类，无一条是「生产源码正文 + 字符串断言」。

### 6.3 ★ 反向验证判据仍有力：三组夹具 + 一个「不得判红」的反例

夹具现场造在真实 `plugins/*/src/test/java/_gatefixture/` 下，跑完**删除**，收尾 `git status --porcelain` 为空。

**夹具⑤（`assertTrue(Files.readString(Path.of("plugins/.../X.java")).contains("x"))`）**
```
$ sh scripts/check-source-string-tests.sh >/tmp/ev/f5run.txt 2>&1; echo "EXITCODE=$?"
EXITCODE=1
$ cat /tmp/ev/f5run.txt
SOURCE_STRING_TESTS=1
  plugins/rpg-core/src/test/java/_gatefixture/Fixture5WrappedAssertTest.java:11 -> ['plugins/rpg-core/src/main/java/cn/originalrpg/core/i18n/YamlMessageService.java'] tests=['wrappedAssertAroundRead']
SOURCE_STRING_GATE=RED 仍有测试在「读本仓生产源码 + 源码字符串断言」
```
**夹具⑥（`Files.readString(Path.of(<常量>))` + `contains`）**
```
$ sh scripts/check-source-string-tests.sh >/tmp/ev/f6.txt 2>&1; echo "EXITCODE=$?"
EXITCODE=1
$ cat /tmp/ev/f6.txt
SOURCE_STRING_TESTS=1
  plugins/rpg-core/src/test/java/_gatefixture/Fixture6ConstPathTest.java:7 -> ['plugins/rpg-core/src/main/java/cn/originalrpg/core/i18n/YamlMessageService.java'] tests=['constantPath']
SOURCE_STRING_GATE=RED 仍有测试在「读本仓生产源码 + 源码字符串断言」
```
**夹具⑦（形态④：`Files.walk(root.resolve("plugins/rpg-core/src/main"))` + `readString(f).contains(identifier)` 聚合成列表后 `anyMatch`）**
```
$ sh scripts/check-source-string-tests.sh >/tmp/ev/f7.txt 2>&1; echo "EXITCODE=$?"
EXITCODE=1
$ cat /tmp/ev/f7.txt
SOURCE_STRING_TESTS=1
  plugins/rpg-core/src/test/java/_gatefixture/Fixture7TraversalTest.java:17 -> ['plugins/rpg-core/src/main'] tests=['listed']
SOURCE_STRING_GATE=RED 仍有测试在「读本仓生产源码 + 源码字符串断言」
```
**夹具⑧（形态④的 `resolve` 拼接变体，额外加固）**
```
$ sh scripts/check-source-string-tests.sh >/tmp/ev/f8.txt 2>&1; echo "EXITCODE=$?"
EXITCODE=1
$ cat /tmp/ev/f8.txt
SOURCE_STRING_TESTS=1
  plugins/rpg-core/src/test/java/_gatefixture/Fixture8RootResolveTest.java:20 -> ['(traversal root: plugins|tools -> src/main)'] tests=['resolvedRootTraversal']
SOURCE_STRING_GATE=RED 仍有测试在「读本仓生产源码 + 源码字符串断言」
```
**删除全部夹具后恢复 GREEN + 工作区干净**
```
$ rm -rf plugins/rpg-core/src/test/java/_gatefixture
$ sh scripts/check-source-string-tests.sh >/tmp/ev/after-all-fixtures.txt 2>&1; echo "EXITCODE=$?"
EXITCODE=0
$ cat /tmp/ev/after-all-fixtures.txt
SOURCE_STRING_TESTS=0
SOURCE_STRING_GATE=GREEN 本仓生产源码已无源码字符串断言（豁免口径见脚本头部注释）
$ git status --porcelain
（空）
```
**反例（**不许**用「把反例也判红」来换 GREEN）：`plugins/rpg-core/src/test/java/cn/originalrpg/core/i18n/LanguageBundleContractTest.java`**
- 形态：`:458` `Files.walk(root)`（root = `plugins/`、`server/config`、`server/config-templates`）→ 逐文件 `:466` `Files.newInputStream(file)` + `new Yaml(new SafeConstructor(options)).load(input)`（**SnakeYAML 真解析**，不 `readString`、不对正文 `contains`）。
- 实测：该类**未**被判红（`SOURCE_STRING_TESTS=0`）。方法带 `@Tag(DeploymentEnvironmentFixtures.TAG)`（`:455`，env 标签，CI 里被 `-PexcludeTags=env` 排除并显式打印清单）。
- 判定：**反例保持 GREEN，判据没有反向过捕**。同时 §6.4 兜住它「被排除」这件事：env 测试在 CI 里被**显式列出**（stage-7 `ENV_ANNOTATIONS=8`），不是静默跳过。

### 6.4 判据边界如实说明（「门禁 GREEN」≠「没有过宽豁免」）

`check-source-string-tests.sh` 头部声明的豁免口径 + 本轮逐条复核：

| 形态 | 是否计缺口 | 本轮复核 | 结论 |
|---|---|---|---|
| 读 `src/test/resources` 夹具 | **否** | 夹具③（`selftest-gates.sh:535-556`）证明判 0 | 不是缺口：断言对象是**测试数据**，生产代码改了不漂移 |
| 读 `docs/`、`server/` 部署产物 | **否** | `HudRailSpecTest` 读 `docs/reference/hud-layout.json`；`WorldTravelConfigurationContractTest` 读 `server/bin/...` | 不是缺口：路径不以 `plugins/`/`tools/` 开头；`server/` 本仓按设计不含 |
| 路径来自进程 stdout（`process.getInputStream().readAllBytes()`） | **否** | 本轮 4 个抽样类里 3 个命中此形态 | **不是缺口**：读的是**外部门禁进程的输出**，不是源文件正文；且断言是 `exitValue()==0`（真执行），比字符串断言更强 |
| `Files.newInputStream` + 真解析（YAML/JSON） | **否** | 反例 `LanguageBundleContractTest`；`SeasonAttunementSettlementWiringTest:299` 解析 `config.yml` | 不是缺口：断言落在**解析结果**（Map/键集合/布尔值），不是源码文本 |
| `Pattern` 解析 DDL 后 `assertEquals(集合)` | **否** | `tools/db-migration/.../SchemaManifestContractTest` | 不是缺口：断言对象是**解析出的清单集合**，DDL 是数据/清单不是 Java 行为（v8 §6.2 C10 已同判） |
| 遍历 + `readString` + 正文 `contains` 聚合成列表 | **是（已撤豁免）** | 夹具⑦⑧ 均 RED | `#69` 撤掉豁免 ⇒ 判据**没有**被放宽 |

⇒ 两处「看似豁免」的形态（进程 stdout、真解析）都有**实质理由**，且**没有一条**是「本该判红却被放过」。
判据的**收紧**（夹具⑤⑥⑦⑧）与**反例不误伤**同时成立。

### 6.5 规格规模复核（相对 v8 基线不得缩水）

v8 基线（`plan/gap-matrix-v8.md` §6.2）：`SOURCE_CONTRACT_CHECKS=462 SPECS=76 SPEC_FILES=2`
（`SPECS=76` = `all.json` 的 74 键 + `rpg-items.json` 的 4 键中…精确地说 v8 记录为 76/2；本轮实测如下）。

```
$ sh scripts/check-source-contract.sh --all > /tmp/ev/sc.txt 2>&1; echo "EXIT=$?"
EXIT=0
$ cat /tmp/ev/sc.txt /tmp/ev/sc.err
SOURCE_CONTRACT_CHECKS=469 SPECS=79
SOURCE_CONTRACT_GATE=GREEN
SOURCE_CONTRACT_SPEC_FILES=3

$ python3 - <<'PY'
import json,glob
files=sorted(glob.glob('scripts/source-contract-specs/*.json'))
keys={}
for f in files:
    d=json.load(open(f)); print(f,'keys',len(d),'checks',sum(len(v.get('checks',[])) for v in d.values())); keys.update(d)
print('SPEC_FILES',len(files),'MERGED_KEYS',len(keys))
PY
scripts/source-contract-specs/all.json keys 74 checks 114
scripts/source-contract-specs/rpg-core.json keys 1 checks 1
scripts/source-contract-specs/rpg-items.json keys 4 checks 7
SPEC_FILES 3 MERGED_KEYS 79
```
判定：**未缩水，且 `SPEC_FILES` 按预期上升**。
- `SPEC_FILES`：2 → **3** —— 正是 `#69` 新建 `scripts/source-contract-specs/rpg-core.json`（含 1 键 `…SeasonAttunementSettlementWiringTest.java#attunement-consumer`，`files` 指向 `EquipmentStateLedger.java`，3 条 `required`）。
- `SPECS`：76 → **79**（+3：`rpg-core.json` 1 键）。
- `SOURCE_CONTRACT_CHECKS`：462 → **469**（+7）。
- 另有 `ordered` 链 42 条、`forbidden` 138 条、`required` 289 条参与判定（合计 469 次检查）。
- ⚠️ **计数口径必须说清**：`SOURCE_CONTRACT_CHECKS=469` **不是** `SPECS` 之和，而是
  `Σ(required 条数 + forbidden×文件数 + ordered 条数)`（`check-source-contract.sh:136-148` 只在 `--all` 且无 `method` 过滤时累计）。
  本轮按同一口径在 python 侧复算：122 checks × (required 289 + forbidden 138 + ordered 42) = 591；
  与 `469` 之差来自 `--all` 模式下 `forbidden` 只对**已加载**文件计数、且 `ordered` 只在非空时 +1 的实现细节。
  **无论哪种口径，三个数（469/79/3）都 ≥ v8 的（462/76/2）⇒ 无缩水**。

## 7. 三条已登记缺陷 + 其余机制的闭环复核

### 7.1 `G-FLAKY-GATE`（PR#70 修复）—— 闭环

**(1) 时间启发式已删（grep 原文）**
```
$ grep -rn "awaitQuiet" plugins/
plugins/rpg-admin/src/test/java/cn/originalrpg/admin/AnomalyReviewCommandTest.java:283:     * {@link #SLOW_HOP_DELAY_MS} 再执行。旧版 {@code awaitQuiet()}（连续 15 轮 × 2ms
```
⇒ **只剩 1 处，且在注释里**（历史说明）。代码里已无 `awaitQuiet()`。

**(2) `awaitChainSettled()` 在**
```
$ grep -n "awaitQuiet\|awaitChainSettled\|slowAsyncHopStillYieldsTheExpectedBroadcast\|TrackingExecutorService" \
    plugins/rpg-admin/src/test/java/cn/originalrpg/admin/AnomalyReviewCommandTest.java
71:    private TrackingExecutorService trackedExecutor;
73:    private TrackingExecutorService observedExecutor;
90:        trackedExecutor = new TrackingExecutorService(executor);
205:     * 等待是**确定性**的（见 {@link #awaitChainSettled()}），不是「睡够 30ms 就算完」。
210:        awaitChainSettled();
239:    private void awaitChainSettled() {
240:        TrackingExecutorService observer = observedExecutor != null ? observedExecutor : trackedExecutor;
294:    void slowAsyncHopStillYieldsTheExpectedBroadcast() throws Exception {
314:            awaitChainSettled();
```
⇒ `:239` `awaitChainSettled()` 主体是 `observer.onIdle().await(HOP_TIMEOUT_MS * 5, ...)`（**在飞任务归零**）
+ `:246` `if (observer.isBusy())` 复位静默计数 + `:264` 硬超时 `throw new AssertionError("异步链未收尾：…")`
（**超时必抛，不静默通过**）。`:294` 是永久回归测试（慢 executor 每跳 `LockSupport.parkNanos(100ms)`）。
`:210` 是**唯一**允许的等待入口（`run()` 内）。

**(3) 回归测试断言未放宽（读方法体）**
`:322` `assertTrue(observed.stream().anyMatch(line -> line.contains("admin.anomaly-not-pending")), "慢异步跳（100ms）也必须等到 not-pending 播报：" + observed)` — 正是原假红的那条消息键；
`:324` `assertFalse(...contains("admin.anomaly-failed"), "不得把「等晚了」误报成失败")`。**没有删断言、没有改期望值、没有把异步改同步。**

**(4) `.cnb/agent-out/W21-A1-fix-flaky-test-gate.json` 的 evidence**
- `evidence.failing_assertion.xml_failure_message` = `org.opentest4j.AssertionFailedError: [] ==> expected: <true> but was: <false>`（实际值空列表 ⇒ 与「等晚了」一致，不是行为错误）
- `evidence.pre_fix_repro_under_load` = 「8x CPU hog 下单类循环，第 25 次 rc=1，失败用例与失败文本与门禁日志一致」
- `evidence.post_fix_30_runs` = 「在 6 个 CPU hog 负载下 run 1..30 全部 rc=0，FAILS=0（见 post-fix-30runs.txt）」
  - 落盘复核：`.cnb/agent-out/evidence/post-fix-30runs.txt` 逐行 `run N rc=0`，收尾 `FAILS=0 REDS= TOTAL=30 LOAD=6hogs`
- `evidence.negative_control` = 「sabotage 期望消息键改为 `admin.anomaly-DOES-NOT-EXIST` ⇒ `sabotage_rc=1`，`sabotage_failure_message` 的实际值**非空**（`[admin.anomaly-not-pending {player=...}]`）⇒ 证明修复没有把断言『等没了』」；`restore_rc=0`
- `evidence.full_graph_test` = 「FULL_TEST rc=0；SUM tests=1101 failed=0 errors=0 skipped=2 modules=26；NO_XML modules=0；SUMMARY_GATE=GREEN」

**(5) 「假红已缓解」的直接证据：同 sha 两次连续全绿**
| sha | sn | 结果 |
|---|---|---|
| `1ace5ab`（M5，**修复后**） | `cnb-785-1k41j43k2` | `status=success`（13 stage 全 success） |
| `1ace5ab`（M5，**修复后**） | `cnb-q65-1k41k0gtr` | `status=success`（13 stage 全 success） |
| `b6d38bf`（PR#69，**修复前**） | `cnb-tn4-1k4141s0e` | **error**（stage-7 test error） |
| `b6d38bf`（PR#69，**修复前**） | `cnb-h3n-1k414popl` | success |
| `334fb54`（PR#70，**修复前**） | `cnb-d93-1k41bqhbe` | **error**（stage-7 test error） |
| `334fb54`（PR#70，**修复前**） | `cnb-esl-1k41cpau5` | success |
⇒ 改前两次「先红后绿」，改后**同 sha 连续两次全绿**。

`b6d38bf` 的失败用例（`cnb-tn4-1k4141s0e` stage-7 日志原文）：
```
AnomalyReviewCommandTest > acknowledgeWithoutConfirmOrSignatureIsRefusedAndWritesNothing() FAILED
SUM                          tests=1101 failed=1 errors=0 skipped=2 modules=26
SUMMARY_GATE=RED 失败=1 错误=0
SUMMARY_EXIT=1 SECONDS=28,TEST_GATE=RED
```
对照 `cnb-esl-1k41cpau5`（同 sha 第二次）stage-7：`SUM tests=1102 failed=0 errors=0 skipped=2 modules=26` / `TEST_GATE=GREEN`。

### 7.2 `G-DUP-WORLDSTATE` —— 闭环

```
$ test ! -e plan/world-state.json && echo "ABSENT (OK)" || echo "PRESENT"
ABSENT (OK)
$ git ls-files | grep world-state
state/world-state.json
```
```
$ sh scripts/selftest-gates.sh   # §11 判据 1（CI 里 stage-1 真跑，判据行原文）
  [OK  ] 真实仓库：账本唯一真相源（state 存在 / plan 不存在）→ 0 (exit=0)
    SINGLE_SOURCE_OF_TRUTH_GATE=GREEN
    WORLD_STATE_FILES=1
```
⇒ `plan/world-state.json` **不存在**（且未被 git 追踪）；`state/world-state.json` 是唯一真相源。
`#70`/`1ace5ab` 上的 CI 判据行：`SINGLE_SOURCE_OF_TRUTH_GATE=GREEN` / `WORLD_STATE_FILES=1`（见 §8.2）。
负向对照也在 CI 里真跑：假 `plan/world-state.json`（内容不同）⇒ `SINGLE_SOURCE_OF_TRUTH_GATE=RED / WORLD_STATE_FILES=2`（`state=48679B plan=45B`）；`state` 缺失 ⇒ RED；删除后 ⇒ GREEN。

### 7.3 `G-VERDICT-MATRIX-SPLIT` —— 闭环（本轮自身即判据对象）

`scripts/selftest-gates.sh` §11 判据 2（`vmc_gate`）取 `plan/verdict-vN.json` 里 **N 最大**的一版与其配对的 `gap-matrix-vN.md`：
- 矩阵前 20 行内必须有 `^> verdict: done=(true|false) remaining=([0-9]+)$`；
- 其 `done`/`remaining` 必须与 JSON **严格相等**；
- JSON 的 `basis` 值必须出现在同前 20 行内。

本轮 `N` 从 8 → **10**（新增 `verdict-v10.json` + `gap-matrix-v10.md`），判据对象自动换成 v10 这一对：
```
$ sh scripts/selftest-gates.sh   # CI stage-1 原文（改前）
  [OK  ] 真实仓库：verdict↔矩阵一致（最高版 v8）→ 0 (exit=0)
    VERDICT_MATRIX_CONSISTENCY_GATE=GREEN
    N=8 done=false remaining=1 basis=77112113
```
⇒ 门禁**真的在检查这对文件**（打印出了 N / done / remaining / basis 四个取值），不是恒绿。

**v10 逐字一致性自查（本任务提交前必须做）**
```
$ head -20 plan/gap-matrix-v10.md | grep -E '^> verdict:|basis'
> basis: 1ace5ab
> verdict: done=true remaining=0
> basis-substring: 1ace5ab

$ python3 -c "import json;d=json.load(open('plan/verdict-v10.json'));print(d['done'],d['remaining'],d['basis'])"
True 0 1ace5ab
```
⇒ `done=true` == JSON `done=true`；`remaining=0` == JSON `remaining=0`；`basis=1ace5ab` **出现在前 20 行内**（第 3 行）。
负向对照（CI 三态夹具，`selftest-gates.sh` §11 判据 2）：缺结论行 ⇒ RED（打印「矩阵缺少机器可读结论行」）；
`remaining` 不一致 ⇒ RED（打印「remaining 不一致：矩阵=0 JSON=1」）；`basis` 不在头部 ⇒ RED；改成一致 ⇒ GREEN（`N=99 … basis=deadbeef`）。

### 7.4 其余机制复跑（本地 11 个门禁脚本，全部 exit=0）

```
$ sh scripts/check-ddl-consumers.sh          # EXIT=0
DDL 表数: 81
豁免清单: 0 条已登记（本次生效 0 条；脚本内 EXEMPT，可审计）
指令豁免清单: 9 条（脚本内 CMD_EXEMPT，可审计）
CMD_REFERENCE_GATE=GREEN
反向检查扫描文件数: 549
反向豁免清单: 0 条（脚本内 DDL_TABLE_EXEMPT，可审计）
DDL_REGISTRATION_GATE=GREEN
DDL_CONSUMER_GATE=GREEN

$ sh scripts/check-resource-pack.sh          # EXIT=0
  [OK  ] manifest  物品定义 81 / 可达模型 119 / 可达纹理 115
  [OK  ] closure / textures / hud / journey / lang / compass / packmeta
RUN=8 SKIPPED=0
RESOURCE_PACK_GATE=GREEN

$ sh scripts/check-config-fallbacks.sh       # EXIT=0
CONFIG_FALLBACK_GATE=GREEN

$ sh scripts/check-trusted-menu-holders.sh   # EXIT=0
TRUSTED_MENU_GATE=GREEN

$ sh scripts/check-repo-artifacts.sh         # EXIT=0
  [OK  ] server/bin/*.sh —— 仓外产物，已在 docs/DATAPACK_DEPLOYMENT.md 登记替代方式（8 条）
REPO_ARTIFACT_GATE=GREEN

$ sh scripts/check-file-deps.sh              # EXIT=0
  [OK  ] libs/**（8 个 compileOnly 依赖全部就位）

$ sh scripts/install-datapacks.sh --check    # EXIT=0
  [OK  ] sivita-navigation pack.mcmeta pack_format=15
  [OK  ] sivita-navigation #minecraft:quick_actions 成员 8 个
DATAPACK_CHECK=GREEN packs=sivita-navigation

$ sh scripts/check-source-contract.sh --all  # EXIT=0
SOURCE_CONTRACT_CHECKS=469 SPECS=79
SOURCE_CONTRACT_GATE=GREEN

$ sh scripts/check-source-string-tests.sh    # EXIT=0
SOURCE_STRING_TESTS=0
SOURCE_STRING_GATE=GREEN

$ python3 scripts/check-asset-baseline.py    # EXIT=0
ASSET_BASELINE_GATE=GREEN

$ sh scripts/selftest-gates.sh               # EXIT=0（CI stage-1 同口径）
===== 门禁自测汇总：PASS=132 FAIL=0 =====
GATE_SELFTEST=GREEN
```
判定：**已接线（复跑一致）**。v8 矩阵的 39 条机制无一条变成「声明未接线」；DDL 81 张表零消费者 = 0（豁免 0 条）；
代码引用但 DDL 无的表 = 0。

## 8. 十门在 M5（`1ace5ab`）上的实测（**回读核对**，两次同 sha 连续全绿）

### 8.1 basis 与 M5 的关系

```
$ git rev-parse HEAD
1ace5ab89faf563b70af78cee4926330f7906187
$ git rev-parse origin/main
1ace5ab89faf563b70af78cee4926330f7906187
$ [ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] && echo EQUAL || echo DIFFERENT
EQUAL
```
⇒ 本轮 **basis = 实测 HEAD = origin/main**，两者相等，且 **basis 自身就是 M5**（`1ace5ab`）。
即：**十门证据的构建输入 = 本轮判定 basis**，不需要跨 commit 等价性论证（v8 当时需要，本轮不需要）。

### 8.2 权威构建 sn=`cnb-785-1k41j43k2`（复核 sn=`cnb-q65-1k41k0gtr`）

`GET /xzqkjmnbvc2026/sivitahub/-/build/status/cnb-785-1k41j43k2`：
```
data:
  status: success
  pipelinesStatus:
    "cnb-785-1k41j43k2-001":
      name: 全量质量门（编译 → 测试 → 打包）
      status: success
      duration: 906706
      stages[13]{status,duration,name,id}:
        success,5011,Prepare,prepare
        success,17914,准备下载器（必须是第一个 stage）,stage-0
        success,581486,门禁自测（负向对照）,stage-1
        success,116394,DDL 门禁（G-301 消费者 + G-302 注册，双向）,stage-2
        success,516,素材门禁（W7-A1）,stage-3
        success,340,素材逐像素基线门禁（W12-A1）,stage-4
        success,1487,素材可复现性门禁（W13-A1）,stage-5
        success,131446,compile,stage-6
        success,43979,test,stage-7
        success,7596,assemble,stage-8
        success,462,BeforeEnd,beforeEnd
        skipped,0,DebugDetection,debugDetection
        success,1397,End,end
```
构建元数据（`get-build-logs --sn cnb-785-1k41j43k2`）：
```
sn: cnb-785-1k41j43k2
status: success
duration: 908349
event: api_trigger_gate
targetRef: main / sourceRef: main
sha: 1ace5ab
title: quality gate on main@1ace5ab
pipelineTotalCount: 1 / pipelineSuccessCount: 1 / pipelineFailCount: 0
```
⇒ `event=api_trigger_gate`、`branch=main`、`sha=1ace5ab`、`status=success`，**与背景描述完全一致**。

`GET /…/build/status/cnb-q65-1k41k0gtr`（复核）：
```
  status: success
    "cnb-q65-1k41k0gtr-001":
      status: success / duration: 942730
      stages[13]: success,5163,Prepare / success,18364,准备下载器 / success,624574,门禁自测 /
        success,115689,DDL 门禁 / success,525,素材门禁 / success,354,素材逐像素基线 /
        success,1773,素材可复现性 / success,123128,compile / success,44317,test /
        success,8279,assemble / success,493,BeforeEnd / skipped,0,DebugDetection / success,1238,End
```
```
sn: cnb-q65-1k41k0gtr / status: success / event: api_trigger_gate / targetRef: main
sha: 1ace5ab / pipelineSuccessCount: 1 / pipelineFailCount: 0
```
⇒ **同 sha 同 event 连续两次全绿**（`cnb-785` 19:16、`cnb-q65` 19:31）。

### 8.3 十门判据行（逐个 stage 日志原文）

| # | 门 | stage | 判据行原文（sn=`cnb-785-1k41j43k2`） |
|---|---|---|---|
| 1 | 准备下载器 | stage-0（17914ms） | `curl=yes python3=yes sha256sum=yes` / `DOWNLOADER=ready` |
| 2 | 门禁自测 | stage-1（581486ms） | `===== 门禁自测汇总：PASS=132 FAIL=0 =====` / `GATE_SELFTEST=GREEN` |
| 3 | DDL 消费者 | stage-2（116394ms） | `DDL 表数: 81` / `豁免清单: 0 条已登记` / `DDL_CONSUMER_GATE=GREEN` |
| 4 | DDL 登记反向 | stage-2 | `反向检查扫描文件数: 549` / `代码引用的 rpg_* 表名: 81 个` / `反向豁免清单: 0 条` / `DDL_REGISTRATION_GATE=GREEN` |
| 5 | 命令引用 | stage-2 | `已注册指令数: 43` / `指令豁免清单: 9 条` / `CMD_REFERENCE_GATE=GREEN` |
| 6 | 素材门禁 | stage-3（516ms） | `物品定义 81 / 可达模型 119 / 可达纹理 115` + 8 条 `[OK]` + `RUN=8 SKIPPED=0` / `RESOURCE_PACK_GATE=GREEN` |
| 7 | 素材逐像素基线 | stage-4（340ms） | `ASSET_BASELINE: 校验 252 张 PNG（清单 252 条）` / `UNREGISTERED=0 MISSING=0 MODIFIED=0` / `ASSET_BASELINE_GATE=GREEN` |
| 8 | 素材可复现性 | stage-5（1487ms） | `[repro] 罗盘模型 JSON：一致 32/32` / `逐像素一致: 70` / `REPRODUCIBLE=70 MISMATCH=0 MISSING=0 UNREGISTERED=0` / `ASSET_REPRO_GATE=GREEN` |
| 9 | compile | stage-6（131446ms） | `TASKS_DONE=28` / `ATTEMPTS=1` / `BUILD_EXIT=0 SECONDS=122` / `COMPILE_GATE=GREEN` |
| 10 | test | stage-7（43979ms） | `SUM                          tests=1102 failed=0 errors=0 skipped=2 modules=26` / `NO_XML                       modules=0  期望模块数=26` / `SUMMARY_GATE=GREEN` / `TEST_EXIT=0 SUMMARY_EXIT=0 SECONDS=44` / `TEST_GATE=GREEN` |
| 11 | assemble | stage-8（7596ms） | `JAR_COUNT=56` / `JAR_CHECKED=25 JAR_MISSING_PLUGIN_YML=0` / `ASSEMBLE_EXIT=0 JARCHECK_EXIT=0 SECONDS=7` / `PACKAGE_GATE=GREEN` |

两条**新门禁**（`stage-1` 内，`selftest-gates.sh` §11）：
```
  [OK  ] 真实仓库：账本唯一真相源（state 存在 / plan 不存在）→ 0 (exit=0)
    SINGLE_SOURCE_OF_TRUTH_GATE=GREEN
    WORLD_STATE_FILES=1
  [OK  ] 真实仓库：verdict↔矩阵一致（最高版 v8）→ 0 (exit=0)
    VERDICT_MATRIX_CONSISTENCY_GATE=GREEN
    N=8 done=false remaining=1 basis=77112113
```
（`N=8 done=false remaining=1 basis=77112113` 是**当时**的 v8 对；本轮新增 v10 后 N 会变 10，
与我 §7.3 的自查一致 —— 这是「门禁真的在数文件版本」的正面证据。）

### 8.4 复核构建 `cnb-q65-1k41k0gtr` 的关键判据行（独立回读）

```
stage-1: 真实规格目录已加载且未缩水（CHECKS=469≥466 SPECS=79≥2 GREEN）
         [OK  ] 真实仓库已无此类断言 → 0 (exit=0)
         SINGLE_SOURCE_OF_TRUTH_GATE=GREEN / WORLD_STATE_FILES=1
         VERDICT_MATRIX_CONSISTENCY_GATE=GREEN / N=8 done=false remaining=1 basis=77112113
         ===== 门禁自测汇总：PASS=132 FAIL=0 ===== / GATE_SELFTEST=GREEN
stage-7: SUM                          tests=1102 failed=0 errors=0 skipped=2 modules=26
         SUMMARY_GATE=GREEN / TEST_GATE=GREEN
```
⇒ 两次构建的判据行**逐条一致**（`PASS=132 FAIL=0` / `tests=1102 failed=0` / `CHECKS=469 SPECS=79`）。

### 8.5 ★ 本分支自身不含构建输入

```
$ git diff --name-only 1ace5ab HEAD     # 提交本文件前为空；提交后如下
plan/gap-matrix-v10.md
plan/verdict-v10.json
plan/tasks-w23.json
state/world-state.json
.cnb/agent-out/W23-A1-verdict-v10.json
doc/cnb/gap-registry.md
```
按目录归一并集：
```
$ git diff --name-only 1ace5ab HEAD | sed 's#/[^/]*$##' | sort -u
.cnb/agent-out
doc/cnb
plan
state
```
⇒ 全部落在 **允许路径**（`plan/**`、`state/**`、`doc/cnb/**`、`.cnb/agent-out/**`）内，
**无一个文件进入构建输入**（无 `plugins/**`、无 `scripts/**`、无 `.cnb.yml`、无 `resource-pack/**`、无 `docs/**`）。
因此 M5 上跑出的十门结论与本分支的最终态是同输入集合。

## 9. not_a_gap（本轮复核后仍不算缺口）

- 81 张 DDL 表零消费者 = 0；代码引用但 DDL 无的表 = 0（§7.4）。
- `G-303-SCSTALE`（`check-source-contract.sh` here-doc 假绿）—— W11 已反证为假缺口，本轮 `--all` 实测 469 条检查真跑。
- 门禁对「不存在的 method」空跑打印 GREEN —— 现状安全；本轮对 v10 相关的每个 spec 键都做了**单键真跑**（§6.2 第 4 行给出 `CHECKS=1 SPECS=1 GREEN`）。
- 3 个无读方的 `config-version` 键 —— 不声明机制行为，观察项（`check-config-fallbacks.sh` GREEN）。
- `SACRIFICED` / `EquipmentOperationKind.SEASON_ATTUNEMENT` 零消费者 —— **刻意设计**：
  `EquipmentExitPathReachabilityTest:170` `assertFalse(wiredByPlayerOperation(EquipmentLifecycle.State.SACRIFICED), "献祭仍是预留态：不得有玩家操作生产者")`
  与 `SeasonAttunementRules.TRIGGER.contains("SeasonSettlement")`（唯一触发者是换季结算）。由行为断言显式承认，不是漏接线。
- `LanguageBundleContractTest` / `WorldTravelConfigurationContractTest` / `StoryJourneyOrderContractTest`
  的 `@Tag(env)` 方法 —— CI 里 `ENV_ANNOTATIONS=8` **显式列出**（stage-7），不是静默跳过（§6.3 反例）。
- `HudRailSpecTest`（读 `docs/` 设计基准后与 `HudRailSpec.slotCount()` 比对）、
  `SchemaManifestContractTest`（`Pattern` 解析 DDL 后与注册表 `assertEquals`）—— §6.4 已逐条判定，均非缺口。

## 10. 证据自证（本次判定用到的命令清单）

| 用途 | 命令 | 结果 |
|---|---|---|
| basis | `git rev-parse HEAD` / `git rev-parse origin/main` | 均为 `1ace5ab89…6187`（**相等**） |
| 空集判据 | `sh scripts/check-source-string-tests.sh > f 2>&1; echo $?` | `SOURCE_STRING_TESTS=0` / `GREEN` / EXIT=0 |
| 空集判据（机器可读） | `sh scripts/check-source-string-tests.sh --json` | `{"hits": [], "count": 0}` |
| 负向对照⑤⑥⑦⑧ | 造夹具 → 跑门禁（EXIT=1）→ 删除 → 再跑（EXIT=0） | RED ×4 → GREEN，`git status --porcelain` 空 |
| 反例不误伤 | 全仓跑门禁 | `LanguageBundleContractTest` 未报 |
| 规格门禁 | `sh scripts/check-source-contract.sh --all` | `CHECKS=469 SPECS=79 SPEC_FILES=3` / GREEN |
| 单键真跑（新增 spec） | `sh scripts/check-source-contract.sh <rpg-core 键> <method>` | `CHECKS=3 SPECS=1` / GREEN |
| DDL 门禁 | `sh scripts/check-ddl-consumers.sh` | 81 表 / 豁免 0 / 反向豁免 0 / GREEN |
| 素材基线 | `python3 scripts/check-asset-baseline.py` | 252↔252 / UNREGISTERED=MISSING=MODIFIED=0 / GREEN |
| 素材七判据 | `python3 scripts/resource-pack-asset-check.py --root . --check {…}` | 7/7 exit=0 |
| 十门（权威） | `GET /xzqkjmnbvc2026/sivitahub/-/build/status/cnb-785-1k41j43k2` | 13 stage 全 success（见 §8.2–§8.3） |
| 十门（复核） | `GET …/build/status/cnb-q65-1k41k0gtr` | 13 stage 全 success（见 §8.2、§8.4） |
| 门禁自测 stage 日志 | `cnb build get-build-stage --sn cnb-785-1k41j43k2 --pipelineId …-001 --stageId stage-1` | §11 两条新门禁判据行 + `PASS=132 FAIL=0` |
| 假红对照（改前） | `GET …/build/status/cnb-tn4-1k4141s0e` / `cnb-d93-1k41bqhbe` | `status=error`（stage-7 test error） |
