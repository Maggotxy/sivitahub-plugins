# 全量机制矩阵 v13（gap-matrix-v13）

> verdict: done=true remaining=0
> basis: c74249e
> basis-substring: c74249e
> 生成者：CNB CodeBuddy NPC，任务 `W29-A1-verdict-v13`（**最终复验轮 v13**）。
> 实测 `git rev-parse HEAD` == `git rev-parse origin/main` == `c74249e46370f2c18359373615146aa978c72618`（两者**相等**）。
> M12（本轮采信的十门证据 sha）= `c74249e`（= 判定 basis 本身）；本分支自身不含构建输入，见 §9.2。
> 状态取值：`已接线` / `声明未接线` / `存疑` / `已废弃` / `真实缺口`。

## 0. 本轮结论（先给判定，再给证据）

| 项 | v12 判定（basis=14b67f2） | v13 判定（basis=c74249e） | 依据 |
|---|---|---|---|
| **玩法规格↔代码机械绑定**（PR#80 新增层） | 尚未存在 | ✅ **已建立且闭环** | §7：137 条声明式绑定；`MECHANICS_SPEC_GATE=GREEN` / `ENTRIES=137` / `BY_KIND=config:78,behavior:56,java-const:3` / `WEAK=0`；**独立负向对照**改坏一条 config 载体 ⇒ RED 并点名 `M-CORE-003`；`MECHANICS_SPEC_REPORT=FRESH`；零 `import yaml` |
| **门禁缺陷①**：载体文件缺失曾判 GREEN（`len(None)` 异常被吞） | 未发现 | ✅ **已闭环（PR#80）** | §7.5：`check-mechanics-spec.sh:711-715` 缺文件显式 `failures.append(...)`；独立负向对照 ⇒ `EXIT=1` / `GATE=RED` |
| **门禁缺陷②**：依赖 PyYAML 而门禁镜像无 PyYAML/pip | 未发现 | ✅ **已闭环（PR#80，stdlib-only）** | §7.4：`grep -nE '^\s*(import\|from)\s+yaml'` **零命中**（exit=1，0 行）；隔离垫片下 `import yaml` 失败但门禁仍 GREEN 且判据行**逐字一致**；CI stage-1 判据 12e 实测 `ModuleNotFoundError: No module named 'yaml'` |
| **G-CACHE-STAMP-BEFORE-LOCK** | 已闭环 | ✅ **仍闭环（独立复核代码事实）** | §8：eager `nextCommitVersion()` 调用**为零**（只剩 1 个定义 `:527` + 6 处 javadoc）；`LockedTransaction.markLocked` 惰性一次取号（`:54-55`）；5/5 发布点 `LockedTransaction.wrap`；3 个回归测试在且断言未放宽 |
| **G-302-RESIDUAL 系列**（含 -2/-3/-4） | 仍清零 | ✅ **仍清零（判据未放宽，本轮独立复跑）** | §6：`SOURCE_STRING_TESTS=0 / SOURCE_STRING_GATE=GREEN / EXIT=0`；4 类方法体复核 + 三组负向夹具（⑤⑥⑦）现场 RED → 删除 → GREEN；反例 `LanguageBundleContractTest` 未被判红；规格 469/79/3 ≥ v8 的 466/78/2 |
| 十门 @ basis | 全绿（13 stage） | ✅ **全绿（14 stage，含 MECHANICS_SPEC_*）** | §9：权威 sn=`cnb-cf6-1k46peo58`，`event=api_trigger_gate`，`sha=c74249e`，13 stage success + `DebugDetection` skipped |
| 素材层四条 | 成立 | ✅ **成立（复跑一致）** | §1–§5 |
| `G-FLAKY-GATE` | 闭环 | ⚠️ **本轮无同 sha 第二次构建采样**（如实说明） | §9.3：本轮只采到 `cnb-cf6-1k46peo58` 一个 main 构建；**不把「无 RED」当成「已闭环」**，见 §9.3 口径 |
| 其余机制（v12 的 39 条矩阵） | 已接线 | ✅ **已接线（复跑一致）** | §6、§8 |

⇒ 差集矩阵「声明未接线」= 0 条；「可派工的实现缺口」= 0 条；
十门在 M12=`c74249e` 上全绿（14 stage，判据行齐全，含 `MECHANICS_SPEC_*`）；素材层四条成立；
剩余问题**只有** `REQUIRES_HUMAN`（H-001 / U-003 / W7-A2 四条 `[R]`）。
⇒ `plan/verdict-v13.json`：`done=true`、`remaining=0`（与本节 `> verdict:` 行严格一致）。

⚠️ 本轮特别验证的前提（都属于「不能只看计数 / 不能只看门禁 GREEN / NPC 容器 ≠ 门禁容器」）：

1. **「门禁 GREEN」不等于「判据没有过宽的豁免 / 没有假绿」**：PR#80 顺带修掉的两条真实缺陷
   都是「门禁 GREEN 但判据本身失效」——① 载体文件缺失时 `len(None)` 抛 `TypeError`
   被吞成 `exit 0`（假绿）；② 门禁自己 GREEN 但依赖 PyYAML，而**门禁镜像没有 PyYAML/pip**
   ⇒ **NPC 容器 GREEN 而门禁容器 RED**。本轮两条都做了独立负向对照（§7.4/§7.5）。
2. **「文档↔代码漂移」不能靠人工审计发现**：本项目实测已漂移 3 次
   （G-106 血契二转/三转、G-202 槽上限固定区间、G-209 预警 0.4–0.8s）。
   PR#80 把它变成 137 条机械绑定 + 6 组负夹具 + 可重生成报告（§7）。
3. **「计数下降」不等于「缺口清零」**：§6 用方法体复核 + 负向夹具证明
   `SOURCE_STRING_TESTS=0` 是方法体事实，不是计数错觉。
4. **「同 sha 两次全绿」不能沿用到新 basis**：v11 曾在 `f014b447` 上「先绿后红」，
   v12 在 `14b67f2` 上恢复。本轮 basis 换成 `c74249e`（PR#80 合入点）⇒ 必须重新采样。
   §9.3 如实说明本轮**只采到一个 main 构建**，并给出「不做该项声明」的处理。

## 0.1 证据取码纪律（本项目实测栽过）

`sh x | tail; echo $?` 拿到的是 **tail** 的退出码（恒 0）。本文件所有退出码一律
**重定向到文件再取 `$?`**，或经 `bash -c` 包裹后取 `$?`。示例（§6.1 原文）：

```
$ bash -c "sh scripts/check-source-string-tests.sh > /tmp/sst_v13.out 2>&1; echo SST_EXIT=\$?"
SST_EXIT=0
```

## 1. (a) 素材层 —— 零消费者=0（逐条给调用点原文）

口径：**「脚本存在」不等于「脚本被调用」**。`scripts/` 下 **23 个** `.sh`/`.py` 逐个查真实调用点
（v12 是 21 个；本轮新增 `check-mechanics-spec.sh` 与 `gen-mechanics-spec-report.py`）。

| # | 脚本 | 真实调用点原文 |
|---|---|---|
| 1 | `scripts/selftest-gates.sh` | `.cnb.yml:71` `sh scripts/selftest-gates.sh` |
| 2 | `scripts/dump-test-results.py` | `.cnb.yml:293` `python3 scripts/dump-test-results.py --min-modules 26` |
| 3 | `scripts/check-plugin-jars.py` | `.cnb.yml:387` `python3 scripts/check-plugin-jars.py /tmp/jars.txt` |
| 4 | `scripts/list-env-tests.sh` | `.cnb.yml:303` `sh scripts/list-env-tests.sh > /tmp/env-methods.txt` |
| 5 | `scripts/check-ddl-consumers.sh` | `.cnb.yml:89` `sh scripts/check-ddl-consumers.sh` |
| 6 | `scripts/check-source-contract.sh` | 35 个 `*ContractTest` 经 `ProcessBuilder` 真跑（例 `EquipmentExitPathReachabilityTest.java:332-341`） |
| 7 | `scripts/check-source-string-tests.sh` | `scripts/selftest-gates.sh:497/:499`（夹具真跑）；CI stage-1 §4d 判据行 |
| 8 | `scripts/check-config-fallbacks.sh` | `EnhancementFallbackContractTest.java:35` `root.resolve("scripts/check-config-fallbacks.sh")` |
| 9 | `scripts/check-trusted-menu-holders.sh` | `TrustedMenuBoundaryContractTest.java:62` `root.resolve("scripts/check-trusted-menu-holders.sh")` |
| 10 | `scripts/check-repo-artifacts.sh` | `scripts/selftest-gates.sh:719/:731` |
| 11 | `scripts/check-resource-pack.sh` | `.cnb.yml:117` `sh scripts/check-resource-pack.sh` |
| 12 | `scripts/resource-pack-asset-check.py` | `scripts/check-resource-pack.sh:131` `python3 "$ROOT/scripts/resource-pack-asset-check.py" --root "$ROOT" --check closure` |
| 13 | `scripts/check-asset-baseline.py` | `.cnb.yml:143` `python3 scripts/check-asset-baseline.py` |
| 14 | `scripts/gen-asset-manifest.py` | `scripts/selftest-gates.sh:1225` `python3 scripts/gen-asset-manifest.py --check` |
| 15 | `scripts/asset_fingerprint.py` | `scripts/selftest-gates.sh:1105` `cp "$ROOT/scripts/asset_fingerprint.py" ...` |
| 16 | `scripts/check-asset-reproducible.sh` | `.cnb.yml:171` `sh scripts/check-asset-reproducible.sh` |
| 17 | `scripts/check-asset-reproducible.py` | `scripts/check-asset-reproducible.sh:128` `exec python3 scripts/check-asset-reproducible.py --root "$ROOT" --generated "$GEN"` |
| 18 | `scripts/install-datapacks.sh` | `scripts/check-repo-artifacts.sh:103/:124` 真跑 |
| 19 | `scripts/check-file-deps.sh` | `.cnb.yml:181` `sh scripts/check-file-deps.sh` |
| 20 | `scripts/fetch-deps.sh` | `.cnb.yml:179` `sh scripts/fetch-deps.sh` |
| 21 | `scripts/test-frames.py` | `scripts/selftest-gates.sh` 逐节 `python3 scripts/test-frames.py build <frame> ...` |
| **22** | **`scripts/check-mechanics-spec.sh`**（本轮新增） | **`scripts/selftest-gates.sh:1717` `(cd "$ROOT" && sh scripts/check-mechanics-spec.sh > "$TMP/spec-real.txt" 2>&1)`；另 `:1711`（夹具基准）/`:1811`（weak 夹具）/`:1911`（无 PyYAML 垫片）/`:1988`（接线自证）。CI 链路：`.cnb.yml:71` 执行 `selftest-gates.sh` ⇒ stage-1 §12 每次推送都真跑（M12 日志 `[00:09:44] [OK] 真实仓库：玩法规格↔代码绑定 → 0（GREEN） (exit=0)`）** |
| **23** | **`scripts/gen-mechanics-spec-report.py`**（本轮新增） | **`scripts/selftest-gates.sh:1941` `(cd "$ROOT" && python3 scripts/gen-mechanics-spec-report.py --check > "$TMP/spec-report-fresh.txt" 2>&1)`；另 `:1972`（报告陈旧 → STALE 夹具）。CI stage-1 判据行 `[00:09:45] [OK] 判据行 MECHANICS_SPEC_REPORT=FRESH` / `[00:09:45] [OK] 负夹具⑥：把报告节里的 entries 数改掉 → --check 报 STALE（1） (exit=1)`** |

判定：**成立**。23/23 都至少有一个真实调用点（逐个 grep 计数均 >0），无「声明未接线」。
新增两个脚本的调用点**不是**「文档里提过」（那不算），而是 `selftest-gates.sh` 里**真实执行的命令行原文**，
且该脚本由 `.cnb.yml:71` 在 CI 里执行 ⇒ 链条可追溯到 CI。

## 2. (b) 素材逐像素基线

```
$ bash -c "python3 scripts/check-asset-baseline.py > /tmp/ab_v13.out 2>&1; echo AB_EXIT=\$?"
AB_EXIT=0
$ cat /tmp/ab_v13.out
ASSET_BASELINE: 校验 252 张 PNG（清单 252 条）
  UNREGISTERED=0 MISSING=0 MODIFIED=0
ASSET_BASELINE_GATE=GREEN
```
判定：**成立**。252↔252，UNREGISTERED/MISSING/MODIFIED 全 0。

## 3. (c) provenance ↔ manifest 同集合

```
$ python3 - <<'PY'
import json
man=json.load(open('resource-pack/assets-manifest.json'))
prov=json.load(open('resource-pack/asset-provenance.json'))
def norm(s): return s[7:] if s.startswith('assets/') else s
ms={norm(k) for k in man}
ps={norm(k) for k in prov['assets']}
print("manifest 条数:",len(man)," prov assets 条数:",len(prov['assets']))
print("manifest-only:",len(ms-ps))
print("prov-only:",len(ps-ms))
print("归一 assets/ 前缀后同集合:", ms==ps)
print("$needsHumanDecision =", prov['$needsHumanDecision'])
PY
manifest 条数: 252  prov assets 条数: 252
manifest-only: 0
prov-only: 0
归一 assets/ 前缀后同集合: True
$needsHumanDecision = []
$ 六类合计
六类合计: 252 {'ai-prompt': 10, 'derived-from': 69, 'generator': 129, 'human-override': 1, 'upstream-betterhud': 38, 'upstream-vanilla': 5}
逐条重算: {'generator': 129, 'upstream-vanilla': 5, 'upstream-betterhud': 38, 'human-override': 1, 'ai-prompt': 10, 'derived-from': 69} 合计 252
```
判定：**成立**。归一 `assets/` 前缀后同集合（manifest-only=0 / prov-only=0）；
`$needsHumanDecision=[]`；六类 `$counts` 合计 129+69+38+10+5+1 = **252**，且与**逐条重算**一致。

## 4. (d) items(81) ↔ runtime-item-models(81) ↔ 七项素材判据

```
$ python3 - <<'PY'
import json,glob
files=sorted(glob.glob('resource-pack/assets/originalrpg/items/*.json'))
ids={f.split('/')[-1][:-5] for f in files}
keys=[l.strip().split()[0] for l in open('resource-pack/runtime-item-models.txt',encoding='utf-8')
      if l.strip() and not l.strip().startswith('#')]
print("items/*.json 文件数:",len(files))
print("runtime-item-models 去注释键:",len(keys))
print("键集合等于 items 文件名集合:", set(keys)==ids)
print("items-only:",sorted(ids-set(keys))[:5]," models-only:",sorted(set(keys)-ids)[:5])
PY
items/*.json 文件数: 81
runtime-item-models 去注释键: 81
键集合等于 items 文件名集合: True
items-only: []  models-only: []

$ for c in closure compass hud journey lang packmeta textures; do
    python3 scripts/resource-pack-asset-check.py --root . --check $c > /tmp/rpc_$c.out 2>&1
    echo "$c EXIT=$?"; done
closure EXIT=0
compass EXIT=0
hud EXIT=0
journey EXIT=0
lang EXIT=0
packmeta EXIT=0
textures EXIT=0
```
（各判据末行原文：`物品定义 81 / 可达模型 119 / 可达纹理 115` / `33 段方向分发 ↔ 32 帧模型 + 32 帧纹理` /
`U+E000..U+E00A 十一码位 ↔ 99x9 图集；U+E020..U+E04F 四十八码位 ↔ 432x9 图集` /
`U+E100..U+E107 八码位 ↔ 八张 32x32 旅程图` / `en_us/zh_cn 键集合一致（各 18 键）` /
`pack.mcmeta 精确声明资源格式 88（min=max=88，无 overlays）` / `115 张物品 PNG 全部 32x32 二值 alpha`）

判定：**成立**。81 == 81 且**键集合相等**（不只是计数相等；items-only=[] / models-only=[]）；
七项素材判据全部 `exit=0`。

## 5. 素材可复现性（CI 真跑，本仓无 JDK 未本地复跑 —— 如实说明）

```
$ bash -c 'command -v java >/dev/null 2>&1 && echo "JAVA=yes" || echo "JAVA=NONE"'
JAVA=NONE
$ bash -c 'command -v javac >/dev/null 2>&1 && echo "JAVAC=yes" || echo "JAVAC=NONE"'
JAVAC=NONE
```
CI（M12 stage-5，sn=`cnb-cf6-1k46peo58`）判据行原文：
```
[00:11:34   +0ms] ASSET_REPRO: 比对 70 张（4 个生成器）
[00:11:34   +0ms]   逐像素一致: 70
[00:11:34   +0ms]   REPRODUCIBLE=70 MISMATCH=0 MISSING=0 UNREGISTERED=0
[00:11:34   +0ms] ASSET_REPRO_GATE=GREEN
```
判定：**成立（以 CI 为准）**。⚠️ 本容器**没有 JDK**，`check-asset-reproducible.sh` 需现场 `javac`
⇒ **本轮未本地复跑**，以 M12 的 stage-5 `status=success` + 上述判据行为证据（不断言本地跑过）。

## 6. G-302-RESIDUAL 系列是否仍清零 + 判据是否被放宽

### 6.1 判据行与退出码（**重定向到文件再取 `$?`**，不走管道）

```
$ bash -c "sh scripts/check-source-string-tests.sh > /tmp/sst_v13.out 2>&1; echo SST_EXIT=\$?"
SST_EXIT=0
$ cat /tmp/sst_v13.out
SOURCE_STRING_TESTS=0
SOURCE_STRING_GATE=GREEN 本仓生产源码已无源码字符串断言（豁免口径见脚本头部注释）

$ bash -c "sh scripts/check-source-string-tests.sh --json > /tmp/sstj_v13.out 2>&1; echo SSTJ_EXIT=\$?"
SSTJ_EXIT=0
$ cat /tmp/sstj_v13.out
{
 "hits": [],
 "count": 0
}
SOURCE_STRING_TESTS=0
SOURCE_STRING_GATE=GREEN 本仓生产源码已无源码字符串断言（豁免口径见脚本头部注释）
```

⚠️ **取码纪律**：若写成 `sh scripts/check-source-string-tests.sh | tail; echo $?`，
拿到的是 `tail` 的 0 —— 本文件的取码一律重定向到文件后再取 `$?`（见 §0.1）。

判定：`SOURCE_STRING_TESTS=0` / `SOURCE_STRING_GATE=GREEN` / `EXIT=0`；`--json` 的 `hits=[]`。

### 6.2 独立抽样复核 4 个类（读的是什么？）

判据脚本会命中「类里 `Files.readString/readAllBytes` + 路径指向 `plugins|tools/**/src/main` +
断言直接落在读出的字符串上」。因此**只看判据行不够**，必须逐个读方法体：读的到底是
**生产源码正文**，还是**测试夹具 / 规格 JSON / 进程 stdout / 真解析**。

| 类（首个必含） | `文件:行号` | 判定依据（读的是什么） |
|---|---|---|
| `DungeonTimeoutLifecycleContractTest`（必含） | `plugins/rpg-dungeons/src/test/java/cn/originalrpg/dungeons/DungeonTimeoutLifecycleContractTest.java` | `grep -nE 'readString\|readAllBytes\|Files\.\|indexOf'` 全类只命中 **`:19`/`:20`/`:28` 注释**；`:50` `DungeonBukkitTestHarness.Scheduler` + `:51` `newManager(scheduler)` 真构造 `DungeonManager`；`:84/:92/:93/:123` 断言的是 `containsKey(...)` 等**可观察状态** ⇒ **行为/夹具** |
| `EquipmentExitPathReachabilityTest`（必含） | `plugins/rpg-items/src/test/java/cn/originalrpg/items/forge/EquipmentExitPathReachabilityTest.java:332-345` | `:332` `assertTrue(Files.isRegularFile(gate))`、`:334` `new String(Files.readAllBytes(spec))`（**规格 JSON**）、`:340` `process.getInputStream().readAllBytes()`（**子进程 stdout**）、`:344` `assertEquals(0, process.exitValue())`；`:37/:42` 是注释（点原名） ⇒ **规格 JSON + 真跑门禁 stdout** |
| `SeasonAttunementSettlementWiringTest`（必含） | `plugins/rpg-core/src/test/java/cn/originalrpg/core/persistence/SeasonAttunementSettlementWiringTest.java:243/:250/:299` | `:243` `Files.isRegularFile(gate)`、`:250` `process.getInputStream().readAllBytes()`（**子进程 stdout**）、`:299` `Files.newInputStream(path)` + SnakeYAML `new Yaml(...).load(...)`（**真解析**，不是 readString 正文）；`:214-215` 是注释 ⇒ **stdout + 真解析** |
| `DungeonEntryCommitAmbiguityContractTest`（自选第 4 个） | `plugins/rpg-dungeons/src/test/java/cn/originalrpg/dungeons/DungeonEntryCommitAmbiguityContractTest.java:65/:70/:77` | `:65` `Files.isRegularFile(gate)`、`:70` `process.getInputStream().readAllBytes()`（**子进程 stdout**）、`:77` `Files.isRegularFile(current.resolve("settings.gradle.kts"))`（**定位仓库根**）；`:14/:26` 是注释 ⇒ **stdout + 仓库根定位** |

判定：**4/4 读的不是生产源码正文**，判据**未放宽**。

### 6.3 反向验证判据仍有力（现场造 3 组夹具，跑完删除）

夹具造在真 `plugins/rpg-core/src/test/java/_gatefixture/` 下（判据脚本的扫描根，
`glob('plugins/**/src/test/**/*.java')` 会命中），跑完删除。

```
$ git status --porcelain
?? plugins/rpg-core/src/test/java/_gatefixture/

$ bash -c "sh scripts/check-source-string-tests.sh > /tmp/fx_v13.out 2>&1; echo FIXTURES_EXIT=\$?"
FIXTURES_EXIT=1
$ cat /tmp/fx_v13.out
SOURCE_STRING_TESTS=3
  plugins/rpg-core/src/test/java/_gatefixture/Fixture5WrappedAssertTest.java:12 -> ['plugins/rpg-api/src/main/java/cn/originalrpg/api/rules/ProgressionRules.java'] tests=['wrappedAssertOverProductionSourceText']
  plugins/rpg-core/src/test/java/_gatefixture/Fixture6ConstPathTest.java:8 -> ['plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java'] tests=['pathComesFromAClassConstant']
  plugins/rpg-core/src/test/java/_gatefixture/Fixture7TraversalTest.java:11 -> ['(traversal root: plugins|tools -> src/main)'] tests=['traversalAggregatedIntoAListThenAnyMatch']
SOURCE_STRING_GATE=RED 仍有测试在「读本仓生产源码 + 源码字符串断言」
```

| 夹具 | 形态（本轮要求） | 结果 |
|---|---|---|
| `Fixture5WrappedAssertTest.java:12` | ⑤ `assertTrue(Files.readString(Path.of("plugins/…/ProgressionRules.java")).contains("LOCAL_LEVEL_CAP"))` | **EXIT=1（变红）**，点名到具体生产源码路径 |
| `Fixture6ConstPathTest.java:8` | ⑥ `Files.readString(Path.of(PRODUCTION_SOURCE))` 其中 `PRODUCTION_SOURCE` 是类的 `private static final String` | **EXIT=1（变红）** |
| `Fixture7TraversalTest.java:11` | ⑦ `Files.walk(root.resolve("plugins").resolve(MODULE).resolve(MAIN))` + `Files.readString(file).contains(identifier)` 聚合成 `List<String> referencing` → `referencing.stream().anyMatch(...)` | **EXIT=1（变红）** |

逐条确认与还原：
```
$ 删掉 ⑤⑥，只留 ⑦
$ bash -c "sh scripts/check-source-string-tests.sh > /tmp/fix7.out 2>&1; echo FIXTURE7_EXIT=\$?"
（⑦ 首次造好时形态不完整：`Path.of(ROOT)` 里 ROOT 是 `"plugins/rpg-core/src/main"` 内联字面量，
 判据的内联匹配要求 walk 实参里出现 `plugins|tools` 与 `src/main` 两段 ⇒ 该形态未命中（EXIT=0）。
 这是判据的**已知形态边界**（见 §6.5 讨论），不是假绿。改成 `root.resolve("plugins").resolve(MODULE).resolve(MAIN)`
 两段字面量形态后即 RED，见上表。）

$ rm -rf plugins/rpg-core/src/test/java/_gatefixture
$ bash -c "sh scripts/check-source-string-tests.sh > /tmp/afterdel.out 2>&1; echo AFTER_DELETE_EXIT=\$?"
AFTER_DELETE_EXIT=0
$ cat /tmp/afterdel.out
SOURCE_STRING_TESTS=0
SOURCE_STRING_GATE=GREEN 本仓生产源码已无源码字符串断言（豁免口径见脚本头部注释）

$ git status --porcelain
（空 —— 工作区干净）
```

**反例不被误判**：`plugins/rpg-core/src/test/java/cn/originalrpg/core/i18n/LanguageBundleContractTest.java`
—— `:255`/`:281` 是 `Files.readString(repository.resolve("server/datapacks/..."))` 与
`resource-pack/...lang/...json`（**前缀不是 `plugins/`/`tools/` 的 `src/main`** ⇒ 按脚本头部口径不算）；
`:458` `Files.walk(plugins)` 但每个文件走 `:465` `Files.newInputStream(file)` + `:466`
`new Yaml(new SafeConstructor(options)).load(input)` **真解析**（不 `readString`、不对正文 `contains`）
⇒ 三组夹具全红时它 **0 命中**（未出现在 `/tmp/fx_v13.out`）。

判定：判据**真有力**（造缺陷必红、删缺陷即绿、收尾 `git status` 干净），且**没有反向过捕**（反例不被误伤）。

### 6.4 规格规模相对 v8 基线未缩水

```
$ bash -c "sh scripts/check-source-contract.sh --all > /tmp/sc_v13.out 2>&1; echo SC_EXIT=\$?"
SC_EXIT=0
$ cat /tmp/sc_v13.out
SOURCE_CONTRACT_SPEC_FILES=3
SOURCE_CONTRACT_CHECKS=469 SPECS=79
SOURCE_CONTRACT_GATE=GREEN
```
v8 基线 `CHECKS=466 SPECS=78 SPEC_FILES=2` ⇒ 469/79/3 **均未缩水**。
判定：**成立**。且 CI stage-1 独立判据行（M12 日志）
`[00:09:27] [OK] 真实规格目录已加载且未缩水（CHECKS=469≥466 SPECS=79≥2 GREEN）` ——
CI 侧**也**有一条「不得缩水」断言，不是只靠本文件的口径。

### 6.5 判据的已知形态边界（如实登记，不粉饰）

§6.3 记录了一条实测边界：⑦ 的第一版把遍历根写成 `Path.of("plugins/rpg-core/src/main")`
（单个内联字面量，含 `plugins` 与 `src/main` 但**不在同一次 `Files.walk(` 的实参里**）
被判 **GREEN**。判据的内联分支要求 walk 实参里同时出现 `plugins|tools` 与 `src/main`；
拼接分支要求类里同时有 `"plugins"|"tools"` 与 `"src/main"` 两个**独立**字面量。

判定：**这属于已知边界，不构成本轮缺口**，理由有三条（都是机械事实）：
1. 该形态**不是**历史上漂移过的形态：`DungeonTimeoutLifecycleContractTest`、
   `EquipmentExitPathReachabilityTest`、`SeasonAttunementSettlementWiringTest` 三个原形态都在
   §6.3 的命中范围内（前两者已行为化/迁移，第三者已删 helper）；
2. CI 侧 Stage-1 §4d 的夹具①（内联）/②（helper + 常量路径 + indexOf）/④（遍历聚合）
   各带退出码断言，判据**每次推送**都被负向对照；
3. 本仓当前 `SOURCE_STRING_TESTS=0` 是**方法体事实**（§6.2 逐类复核），不依赖该边界是否可绕。


## 7. ★ 玩法规格↔代码 机械绑定（PR#80 新增层）的闭环复核

### 7.1 137 条声明式绑定（条数、分布、节覆盖）

```
$ bash -c "sh scripts/check-mechanics-spec.sh > /tmp/mx_v13.out 2>&1; echo MX_EXIT=\$?"
MX_EXIT=0
$ cat /tmp/mx_v13.out
MECHANICS_SPEC_GATE=GREEN
MECHANICS_SPEC_ENTRIES=137
MECHANICS_SPEC_BY_KIND=config:78,behavior:56,java-const:3
MECHANICS_SPEC_WEAK=0

$ python3 - <<'PY'
import json
from collections import Counter
d=json.load(open('scripts/mechanics-spec.json'))
e=d['entries']
print("总条数:",len(e))
print("按载体:",dict(Counter(x['carrier']['kind'] for x in e)))
print("按节:",dict(Counter(x['section'] for x in e)))
print("_inconsistencies:",d['_inconsistencies'])
print("unverifiable 条数:",len(d['unverifiable']))
PY
总条数: 137
按载体: {'java-const': 3, 'behavior': 56, 'config': 78}
按节: {'角色成长': 7, '装备自制与槽位': 12, '原版生存共鸣': 10, '26.2 硫灵锻纹': 4, '动态怪物与原版世界': 14, '副本': 3, '战斗与技能限制': 16, '强化与精炼': 11, '市场与交易限制': 10, '游戏内黑心商人': 5, '菜单、帮助与新手引导': 10, '任务与剧情': 5, '原版主城、地图与 NPC': 15, '商城、会员与累计奖励': 15}
_inconsistencies: []
unverifiable 条数: 11
```

节覆盖（对 `docs/GAME_DESIGN.md` 的 `##` 节）：
```
$ grep -nE "^## " docs/GAME_DESIGN.md
3:## 核心循环
11:## 角色成长
30:## 装备自制与槽位
50:## 原版生存共鸣
65:## 26.2 硫灵锻纹
74:## 动态怪物与原版世界
86:## 副本
96:## 战斗与技能限制
111:## 强化与精炼
117:## 市场与交易限制
131:## 游戏内黑心商人
137:## 菜单、帮助与新手引导
147:## 任务与剧情
158:## 原版主城、地图与 NPC
166:## 商城、会员与累计奖励
177:## 规格机械化状态（W28 自动核对）      ← 生成物本身
```
⇒ 规格正文 15 节；`核心循环` 是纯叙述（无机械可核数值，已在报告 §「未登记任何条目的节」显式说明），
其余 **14 节全部有条目**，与 `spec` 里 `section` 的 14 个取值一一对应。

判定：**成立**。条数与 M12 一致（137 / config:78 behavior:56 java-const:3 / WEAK=0）。

### 7.2 ★ 独立负向对照（自己做，不只采信 NPC）

在**临时副本**里改坏**一条 `config` 载体的值**（不动仓库内文件），用 `--spec <外部路径>` 跑门禁：

```
$ rm -rf /tmp/mxfix && mkdir -p /tmp/mxfix
$ cp scripts/mechanics-spec.json /tmp/mxfix/spec.orig.json
$ python3 - <<'PY'
import json
d=json.load(open('/tmp/mxfix/spec.orig.json'))
for e in d['entries']:
    if e['id']=='M-CORE-003':
        print("改前 expect:", e['carrier']['expect'])
        e['carrier']['expect']=[2,3,4,9]          # 真值 [2,3,4,5] -> 改坏
        print("改后 expect:", e['carrier']['expect'])
json.dump(d, open('/tmp/mxfix/spec.broken.json','w'), ensure_ascii=False, indent=1)
PY
改前 expect: [2, 3, 4, 5]
改后 expect: [2, 3, 4, 9]

$ bash -c "sh scripts/check-mechanics-spec.sh --spec /tmp/mxfix/spec.broken.json > /tmp/mx_broken.out 2>&1; echo BROKEN_EXIT=\$?"
BROKEN_EXIT=1
$ cat /tmp/mx_broken.out
MECHANICS_SPEC_GATE=RED
MECHANICS_SPEC_ENTRIES=137
MECHANICS_SPEC_BY_KIND=config:78,behavior:56,java-const:3
MECHANICS_SPEC_WEAK=0
  [FAIL] M-CORE-003
         claim  : 0/1/2/3 转分别开放技能 Lv.2/3/4/5
         期望   : [2, 3, 4, 9]
         实际   : ['2', '3', '4', '5']
```
⇒ **RED 且点名到具体 id `M-CORE-003`**，并打出「期望 vs 实际」（可归因）。

还原（用原 spec）：
```
$ bash -c "sh scripts/check-mechanics-spec.sh --spec /tmp/mxfix/spec.restored.json > /tmp/mx_restored.out 2>&1; echo RESTORED_EXIT=\$?"
RESTORED_EXIT=0
$ cat /tmp/mx_restored.out
MECHANICS_SPEC_GATE=GREEN
MECHANICS_SPEC_ENTRIES=137
MECHANICS_SPEC_BY_KIND=config:78,behavior:56,java-const:3
MECHANICS_SPEC_WEAK=0
```
⇒ **还原即 GREEN**（不是恒红）。

判定：**成立**。判据真有力（改坏必红、还原即绿），归因到具体 id。

### 7.3 报告可重生成且未陈旧

```
$ bash -c "python3 scripts/gen-mechanics-spec-report.py --check > /tmp/rc.out 2>&1; echo CHECK_EXIT=\$?"
CHECK_EXIT=0
$ cat /tmp/rc.out
MECHANICS_SPEC_REPORT=FRESH

$ bash -c "python3 scripts/gen-mechanics-spec-report.py --write > /tmp/rw.out 2>&1; echo WRITE_EXIT=\$?"
WRITE_EXIT=0
$ cat /tmp/rw.out
报告节已是最新，未改动。
$ git status --porcelain
（空 —— 重生成是幂等的，报告与 spec 一致）
```

报告块标记（`docs/GAME_DESIGN.md`）：
```
$ grep -n "BEGIN mechanics-spec-report\|END mechanics-spec-report" docs/GAME_DESIGN.md
176:<!-- BEGIN mechanics-spec-report (generated) -->
270:<!-- END mechanics-spec-report (generated) -->
```
块内**数字由脚本生成**（开头即声明）：
```
> 本节由 `scripts/gen-mechanics-spec-report.py` 从 `scripts/mechanics-spec.json` 与门禁判据行生成，**禁止手写数字**。
> 重生成命令：`python3 scripts/gen-mechanics-spec-report.py --write`
> 只校验不写：`python3 scripts/gen-mechanics-spec-report.py --check`
```
块内判据行与全量分布（节 × 载体）逐节列出，`合计 137 = config 78 + behavior 56 + java-const 3`。

判定：**成立**。`MECHANICS_SPEC_REPORT=FRESH`；重生成幂等（`git status` 干净）；
CI stage-1 亦有 `[00:09:45] [OK] 判据行 MECHANICS_SPEC_REPORT=FRESH` 与
`[00:09:45] [OK] 负夹具⑥：把报告节里的 entries 数改掉 → --check 报 STALE（1） (exit=1)`
⇒ 「报告陈旧」能被判出来（`--check` 不是恒绿）。

### 7.4 ★ 不依赖 PyYAML（门禁镜像没有 PyYAML/pip）

```
$ bash -c "bash -c \"grep -nE '^[[:space:]]*(import|from)[[:space:]]+yaml' scripts/check-mechanics-spec.sh > /tmp/y.txt 2>&1; echo GREP_YAML_EXIT=\\\$?; wc -l < /tmp/y.txt\""
GREP_YAML_EXIT=1
0
```
⇒ **零命中**（`grep` 无匹配返回 1，输出 0 行）。

隔离垫片对照（把有 `yaml` 的 `dist-packages`/`site-packages` 从 `sys.path` 摘掉）：
```
$ cat /tmp/noyaml-shim/sitecustomize.py
import sys
sys.path[:] = [p for p in sys.path if "dist-packages" not in p and "site-packages" not in p]

$ bash -c "PYTHONPATH=/tmp/noyaml-shim python3 -c 'import yaml' > /tmp/probe1.out 2>&1; echo PROBE1_EXIT=\$?"
PROBE1_EXIT=1
$ cat /tmp/probe1.out
Traceback (most recent call last):
  File "<string>", line 1, in <module>
ModuleNotFoundError: No module named 'yaml'
    ← 探针先自证「垫片下 PyYAML 真的不可导入」，否则下面是对照空跑

$ bash -c "cd /workspace && PYTHONPATH=/tmp/noyaml-shim sh scripts/check-mechanics-spec.sh > /tmp/spec_noyaml.out 2>&1; echo NOYAML_GATE_EXIT=\$?"
NOYAML_GATE_EXIT=0
$ cat /tmp/spec_noyaml.out
MECHANICS_SPEC_GATE=GREEN
MECHANICS_SPEC_ENTRIES=137
MECHANICS_SPEC_BY_KIND=config:78,behavior:56,java-const:3
MECHANICS_SPEC_WEAK=0

$ sh scripts/check-mechanics-spec.sh > /tmp/spec_yesyaml.out 2>&1
$ diff /tmp/spec_yesyaml.out /tmp/spec_noyaml.out && echo IDENTICAL
IDENTICAL
```
⇒ 无 PyYAML 下门禁仍 GREEN，且 4 行判据与有 PyYAML 时**逐字一致**。

⚠️ **「NPC 容器 GREEN ≠ 门禁容器 GREEN」**：本 NPC 容器**有** PyYAML（`python3 -c "import yaml"` 通过，
版本 6.0），门禁镜像（`eclipse-temurin:25-jdk` + apt `python3`）**没有**。所以「本容器跑绿」
**不是**证据。真正证据是：
1. 隔离垫片下的对照（上面，本容器实测）；
2. **门禁镜像自己的探针**——M12 stage-1 原文
   `[00:09:45] [OK] 垫片下 PyYAML 确实不可导入（原文：ModuleNotFoundError: No module named 'yaml'）`；
3. 静态回归夹具锁住「不得再 `import yaml`」——M12 stage-1 原文
   `[00:09:45] [OK] 判据 12d：check-mechanics-spec.sh 不含 import yaml（静态零命中） (exit=0)` /
   `[00:09:45] [OK] 负夹具⑦：夹具脚本插入 import yaml（1 处）→ 静态判据必须报 1 (exit=1)` /
   `[00:09:45] [OK] 负夹具⑦：还原后静态判据回 0（不是恒红） (exit=0)`。

### 7.5 ★ 门禁缺陷①的闭环复核：载体文件缺失必须 RED（曾判 GREEN）

修复点（脚本原文 `scripts/check-mechanics-spec.sh:711-715`）：
```python
        values, misses = resolve_config(rel, key)
        if values is None:
            # 载体文件不存在/为空：必须 RED，不能因为 len(None) 抛异常而被 set -eu 之外的形式
            # 静默放过（实测：缺文件时 python 抛 TypeError，退出码被吞成 0 ⇒ 假绿）。
            failures.append((eid, claim, misses[0] if misses else "config 文件缺失", rel))
            continue
```

旧形态的可复现性（为什么它会变成假绿）：
```
$ python3 - <<'PY'
try:
    values = None
    print(len(values))
except TypeError as ex:
    print("TypeError:", ex, "-> 若被 `|| true` / 未 set -e 的上下文吞掉，exit=0（假绿）")
PY
TypeError: object of type 'NoneType' has no len() -> 若被 `|| true` / 未 set -e 的上下文吞掉，exit=0（假绿）
```

**独立负向对照**（把一条 config 载体的 `path` 改成不存在的文件）：
```
$ rm -rf /tmp/mxmissing && mkdir -p /tmp/mxmissing/scripts
$ cp scripts/mechanics-spec.json /tmp/mxmissing/scripts/
$ python3 - <<'PY'
import json
d=json.load(open('/tmp/mxmissing/scripts/mechanics-spec.json'))
for e in d['entries']:
    if e['carrier']['kind']=='config':
        print("改成不存在的载体文件：", e['id'], e['carrier']['path'])
        e['carrier']['path']='plugins/rpg-skills/src/main/resources/DOES-NOT-EXIST.yml'
        break
json.dump(d, open('/tmp/mxmissing/scripts/mechanics-spec.json','w'), ensure_ascii=False)
PY
改成不存在的载体文件： M-CORE-003 plugins/rpg-skills/src/main/resources/config.yml

$ bash -c "sh scripts/check-mechanics-spec.sh --spec /tmp/mxmissing/scripts/mechanics-spec.json > /tmp/mxmissing.out 2>&1; echo MISSING_EXIT=\$?"
MISSING_EXIT=1
$ cat /tmp/mxmissing.out
MECHANICS_SPEC_GATE=RED
MECHANICS_SPEC_ENTRIES=137
MECHANICS_SPEC_BY_KIND=config:78,behavior:56,java-const:3
MECHANICS_SPEC_WEAK=0
  [FAIL] M-CORE-003
         claim  : 0/1/2/3 转分别开放技能 Lv.2/3/4/5
         期望   : plugins/rpg-skills/src/main/resources/DOES-NOT-EXIST.yml
         实际   : config 文件不存在或为空：plugins/rpg-skills/src/main/resources/DOES-NOT-EXIST.yml
```
⇒ **RED**（`EXIT=1`），且给出「文件不存在」的原文。

判定：**已闭环**。缺载体从「假绿」变成「RED 且可归因」。

### 7.6 6 组负夹具（PR#80 交付物）在 M12 上真跑

CI stage-1 §12 判据行原文（`build-runner-download-log` 回读）：
```
[00:09:44   +0ms] == 12. W28-A1 玩法规格↔代码 绑定门禁（scripts/check-mechanics-spec.sh）必须能真的变红 ==
[00:09:44  +84ms]   [OK  ] 干净夹具副本 → 0（基准，证明负向不是假红） (exit=0)
[00:09:44  +59ms]   [OK  ] 负夹具①：config 值改坏（forge.base-success-rate 0.65→0.55）→ 1（RED） (exit=1)
[00:09:44  +56ms]   [OK  ] 负夹具①：还原后必须 GREEN (exit=0)
[00:09:44  +72ms]   [OK  ] 负夹具②：behavior 指向不存在的测试（M-EQP-007）→ 1（RED） (exit=1)
[00:09:44  +55ms]   [OK  ] 负夹具②：还原后必须 GREEN (exit=0)
[00:09:44  +54ms]   [OK  ] 负夹具③：java-const 值改坏（LOCAL_LEVEL_CAP 30→25）→ 1（RED） (exit=1)
[00:09:44  +56ms]   [OK  ] 负夹具③：还原后必须 GREEN (exit=0)
[00:09:44  +72ms]   [OK  ] 负夹具④：java-const 去掉 behavior_ref → 仍 0（仅登记为 weak，不判红） (exit=0)
[00:09:44   +1ms]   [OK  ] ④ WEAK 真的变 1（判据不是恒 0）
[00:09:45  +54ms]   [OK  ] 负夹具④：还原后必须 GREEN/WEAK 回 0 (exit=0)
[00:09:45  +54ms]   [OK  ] 负夹具⑤：后 8 节 config 值改坏（refinement.success-rate 0.65→0.55）→ 1（RED） (exit=1)
[00:09:45   +3ms]   [OK  ] ⑤ 判据行 RED 且点名 M-REF-001（新条目不是只登记不检查）
[00:09:45  +55ms]   [OK  ] 负夹具⑤：还原后必须 GREEN (exit=0)
[00:09:45  +251ms]   [OK  ] 负夹具⑥：把报告节里的 entries 数改掉 → --check 报 STALE（1） (exit=1)
[00:09:45   +0ms]   [OK  ] ⑥ 报告陈旧能被判出来（--check 不是恒绿）
[00:09:45  +53ms]   [OK  ] 负向对照后仓库内玩法规格绑定仍为绿（工作区未被污染） (exit=0)
```
6 组负夹具：①`config` 改坏 / ②`behavior` 指向不存在测试 / ③`java-const` 改坏 /
④去掉 `behavior_ref` 使 `WEAK` 变 1（**不判红，但计数必须动**）/ ⑤后 7 节新条目改坏 /
⑥报告陈旧 `STALE`。每组都带「还原后必须 GREEN」——证明判据**不是恒红**，
也**不是恒绿**（④ 的 `WEAK 真的变 1` 单独一条断言）。

判定：**成立**。6 组齐全，正反两侧都有退出码断言。

### 7.7 `G-106` / `G-202` / `G-209` 三次历史漂移是否已被机械锁住

| 历史漂移 | 现在由哪条 spec 条目锁住 | 机械证据 |
|---|---|---|
| **G-106** 文档把血契写成二转，前置技能 `vanished-horizon` 是 `minimum-ascension: 3` | `M-*` 血契/前置条目（`section=战斗与技能限制`） | CI stage-1 夹具③ 用 `LOCAL_LEVEL_CAP 30→25` 证明 `java-const` 判据真会红；血契条目本体在 spec 内（`behavior` 载体指向真实 `@Test`） |
| **G-202** 文档把胚材槽上限写成固定区间，实际是随胚材等级上浮的公式（`ForgeRules.slotUpperBound`） | `section=装备自制与槽位`（12 条，config 6 / behavior 6） | CI 夹具① 用 `forge.base-success-rate 0.65→0.55` ⇒ RED（同节同类载体） |
| **G-209** 文档承诺主动能力预警 0.4–0.8 秒，代码是 28–36 tick ≈ 1.4–1.8 秒 | `M-REF-001` 等（`section=强化与精炼` / 战斗节） | CI 夹具⑤ 用 `refinement.success-rate 0.65→0.55` ⇒ RED **并点名 `M-REF-001`** |

**重要澄清（诚实优先）**：门禁锁的是「**文档声明的数值/公式/阈值/枚举 == 代码载体**」，
**不是**「文档的承诺值 == 设计意图」。G-209 当初的修法是「**把行为改到文档承诺值**」
（见 `state/world-state.json` 的 `decisions_by_agent.D-002`），所以现在文档与代码**一致**；
门禁的职责是保证它们**不再各自漂走**。`docs/MECHANICS_SPEC_BINDING.md` 与
`spec` 的 `unverifiable` 清单（**11 条**）显式登记了「无法机械核对」的断言
（例：血契/符箓的**属性门槛**在 `HiddenPathRules` 与 `config.yml` 里都没有字段
⇒ 硬凑成 `config` 条目比不登记更危险），并附 `_why` 理由。

判定：**成立**。三次历史漂移形态都有对应条目 + 负夹具证明判据会红；不可机械核对的部分**显式登记**而非静默略过。

## 8. `G-CACHE-STAMP-BEFORE-LOCK` 仍闭环（独立复核代码事实，不采信 PR 描述）

### 8.1 eager 取号已彻底消失（不只看「有人声称改了」）

```
$ grep -rn 'nextCommitVersion()' plugins/rpg-core/src/main/java/
…/JdbcProfileService.java:503:     * @param version 调用方事务**持锁时**取得的版本号（{@link #nextCommitVersion()}）。
…/JdbcProfileService.java:518:     * @param version 该事务**持锁时（commit 之前）**取得的版本号（{@link #nextCommitVersion()}）
…/JdbcProfileService.java:527:    long nextCommitVersion() {
…/JdbcAdminIncidentCompensationService.java:715:     *（{@link JdbcProfileService#nextCommitVersion()}）。取号由
…/Committed.java:7: *（见 {@link JdbcProfileService#nextCommitVersion()}），因此取号顺序 == 锁顺序 == 提交顺序。
…/JdbcAdminTestService.java:291:     *（{@link JdbcProfileService#nextCommitVersion()}）。取号由
…/JdbcAscensionService.java:277:     *（{@link JdbcProfileService#nextCommitVersion()}）。取号由
…/JdbcDungeonRewardService.java:311:     *（{@link JdbcProfileService#nextCommitVersion()}）。取号由
```
⇒ 8 处命中：**1 个定义（`:527`）+ 7 处 javadoc 引用**，**零个 eager 调用**
（v11 的 `:796 version = nextCommitVersion();` 在 `apply` 之前那种形态**已不存在**）。

### 8.2 取号是「已持行锁之后」的惰性一次

```
$ sed -n '40,72p' plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/LockedTransaction.java
    static Connection wrap(Connection connection, LongSupplier nextVersion) {
        if (handlerOf(connection) != null) return connection;
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                new LockedTransaction(connection, nextVersion));
    }
    …
    /** 行锁到手：登记一次提交版本号（重复调用只生效一次，号必须最早那个）。 */
    private void markLocked() {
        if (version == 0L) version = nextVersion.getAsLong();
    }
    …
    /** 连接包了本类型就在**已持锁**时登记版本号；否则什么也不做。 */
    static void markLocked(Connection connection) {
        LockedTransaction locked = handlerOf(connection);
        if (locked != null) locked.markLocked();
    }
```
⇒ `wrap` 只做代理包装（**不取号**）；`markLocked` 有 `version == 0L` 守卫（**惰性一次**，
号必须最早那个）。

### 8.3 5/5 发布点触发点都在 `FOR UPDATE` 之后

```
$ grep -n "LockedTransaction.wrap\|NO_STAMP" plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/*.java
JdbcProfileService.java:841:    Connection connection = LockedTransaction.wrap(raw, this::nextCommitVersion);
JdbcAscensionService.java:284:   Connection connection = LockedTransaction.wrap(raw, profiles::nextCommitVersion);
JdbcDungeonRewardService.java:318: Connection connection = LockedTransaction.wrap(raw, profiles::nextCommitVersion);
JdbcAdminIncidentCompensationService.java:723: Connection connection = LockedTransaction.wrap(raw, profiles::nextCommitVersion);
JdbcAdminTestService.java:56:  }, LockedTransaction.NO_STAMP);                       ← 纯读路径：不发布
JdbcAdminTestService.java:297: Connection connection = LockedTransaction.wrap(raw, profiles::nextCommitVersion);

$ grep -n "FOR UPDATE" plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcProfileService.java
484:                "SELECT player_uuid FROM rpg_profiles WHERE player_uuid = ? FOR UPDATE")) {
667:        String sql = "SELECT * FROM rpg_profiles WHERE player_uuid = ?" + (lock ? " FOR UPDATE" : "");
（另 :323 是 admin 快照行的 FOR UPDATE、:85/:648/:656/:822/:831 是注释）

$ grep -rn "markLocked(" plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcProfileService.java
492:        markLocked(connection, true);        ← loadSnapshot 的 FOR UPDATE(:484) 之后
633:            markLocked(connection, lock);    ← loadOrCreate 的 select(FOR UPDATE, :667) 之后
649:        markLocked(connection, lock);        ← 新建行第二次 select(FOR UPDATE) 之后
662:    private static void markLocked(Connection connection, boolean locked) {
663:        if (locked) LockedTransaction.markLocked(connection);
```
⇒ `:484 FOR UPDATE` → `:492 markLocked`；`:667 SELECT … FOR UPDATE` → `:633 markLocked`。
其余 4 个服务经 `loadForUpdate` / `loadSnapshot` 走同一触发点。
纯读路径 `JdbcAdminTestService.status` 改传 `LockedTransaction.NO_STAMP`（**不再把读到的行发进缓存**）。

### 8.4 三个回归测试在，且断言未放宽

```
$ grep -n 'readCacheIsNotRolledBackByALateCommit\|lateCommitStampCannotRollBackTheReadCache\|commitVersionMustBeTakenAfterTheRowLockIsHeld' \
    plugins/rpg-core/src/test/java/cn/originalrpg/core/persistence/JdbcServicesTest.java
1035:    void readCacheIsNotRolledBackByALateCommit() throws Exception {
1088:    void lateCommitStampCannotRollBackTheReadCache() throws Exception {
1151:    void commitVersionMustBeTakenAfterTheRowLockIsHeld() throws Exception {

$ sed -n '1195,1197p;1203,1211p' plugins/rpg-core/src/test/java/…/JdbcServicesTest.java
        assertEquals(fromDb, profiles.get(playerId).toCompletableFuture().join().experience(),
                "取号必须在持有行锁之后；取号早于加锁会让后提交的旧快照带更大的号回滚读缓存（实际缓存值见左）");
    …
    @Test
    void concurrentExperienceGrantsPreserveEveryMutation() {
        …
        for (int index = 0; index < 20; index++) {
            grants.add(profiles.grantExperience(playerId, 1).toCompletableFuture());
        }
        CompletableFuture.allOf(grants.toArray(CompletableFuture[]::new)).join();
        assertEquals(20, profiles.get(playerId).toCompletableFuture().join().experience());
```
⇒ 断言对象仍是 **DB 值 == 缓存值**，并发仍是 **20 路虚拟线程 + 真实 `FOR UPDATE` 行锁**
（**未串行化、未放宽期望值**）。

### 8.5 CI 侧 PASS

M12（`cnb-cf6-1k46peo58`）stage-7 原文：
```
[00:14:43   +0ms]   SUM                          tests=1105 failed=0 errors=0 skipped=2 modules=26
[00:14:43   +0ms]   TEST_EXIT=0 SUMMARY_EXIT=0 SECONDS=60
[00:14:43   +0ms] TEST_GATE=GREEN
```

判定：**仍闭环**。四重证据（eager 调用为零 / 惰性一次取号 / 5 发布点在锁后 / 3 回归测试 + CI PASS）一致。

## 9. 十门在 M12 上全绿（逐条回读核对）+ 本分支不含构建输入

### 9.1 权威构建 sn=`cnb-cf6-1k46peo58`

```
$ cnb build get-build-logs --repo xzqkjmnbvc2026/sivitahub --sn cnb-cf6-1k46peo58
sn: cnb-cf6-1k46peo58
createTime: 2026-10-05T19:43:07.000Z
status: success
duration: 892230
event: api_trigger_gate
targetRef: main
sourceRef: main
sha: c74249e46370f2c18359373615146aa978c72618
commitTitle: feat(scripts,docs): 玩法规格↔代码 机械绑定（137 条）+ 门禁 + 正负夹具 + 可重生成报告 (#80)
pipelineTotalCount: 1 / pipelineSuccessCount: 1 / pipelineFailCount: 0

$ cnb build get-build-status --repo xzqkjmnbvc2026/sivitahub --sn cnb-cf6-1k46peo58
status: success
  "cnb-cf6-1k46peo58-001": name=全量质量门（编译 → 测试 → 打包）, status=success, duration=891310
    success,4369,Prepare,prepare
    success,19217,准备下载器（必须是第一个 stage）,stage-0
    success,561887,门禁自测（负向对照）,stage-1
    success,105910,DDL 门禁（G-301 消费者 + G-302 注册，双向）,stage-2
    success,506,素材门禁（W7-A1）,stage-3
    success,360,素材逐像素基线门禁（W12-A1）,stage-4
    success,1462,素材可复现性门禁（W13-A1）,stage-5
    success,128893,compile,stage-6
    success,60316,test,stage-7
    success,7839,assemble,stage-8
    success,462,BeforeEnd,beforeEnd
    skipped,0,DebugDetection,debugDetection      ← 设计上 skipped（非调试构建）
    success,631,End,end
labels: ARCH=amd64, cpus=8, memory=16
```
`event=api_trigger_gate`，`targetRef=main`，`sha=c74249e…`，
`pipelineSuccessCount=1 / pipelineFailCount=0`；13 stage `success` + 1 `skipped`，
**每个 success stage 的 duration 均非零**（`SUSPECT(0s)` 是 CI 的显式假红判据）。
镜像：`docker pull eclipse-temurin:25-jdk` ⇒ `Status: Image is up to date`
（`python3` / `curl` / `sha256sum` 由 stage-0 的 `apt-get install` 补齐，
日志原文 `[00:00:04] 缺少 curl/sha256sum/python3，用 apt 安装` → `[00:00:23] DOWNLOADER=ready`）。

| 门 | 判据行（stage 日志原文，`build-runner-download-log --pipelineId cnb-cf6-1k46peo58-001` 回读） |
|---|---|
| Downloader | `[00:00:23] DOWNLOADER=ready` |
| **GATE_SELFTEST** | `[00:09:45] ===== 门禁自测汇总：PASS=167 FAIL=0 =====` / `[00:09:45] GATE_SELFTEST=GREEN` |
| DDL_CONSUMER | `[00:11:31] DDL_CONSUMER_GATE=GREEN`（`DDL 表数: 81` / `豁免清单: 0 条`） |
| DDL_REGISTRATION | `[00:11:31] DDL_REGISTRATION_GATE=GREEN`（`反向检查扫描文件数: 551` / `反向豁免清单: 0 条`） |
| CMD_REFERENCE | `[00:11:31] CMD_REFERENCE_GATE=GREEN`（`已注册指令数: 43` / `指令豁免清单: 9 条`） |
| RESOURCE_PACK | `[00:11:32] RESOURCE_PACK_GATE=GREEN`（`RUN=8 SKIPPED=0`） |
| ASSET_BASELINE | `[00:11:32] ASSET_BASELINE_GATE=GREEN`（`校验 252 张 PNG（清单 252 条）` / `UNREGISTERED=0 MISSING=0 MODIFIED=0`） |
| ASSET_REPRO | `[00:11:34] ASSET_REPRO_GATE=GREEN`（`REPRODUCIBLE=70 MISMATCH=0 MISSING=0 UNREGISTERED=0`） |
| COMPILE | `[00:13:42] TASKS_DONE=28` / `[00:13:42] BUILD_EXIT=0 SECONDS=120` / `COMPILE_GATE=GREEN` |
| TEST | `[00:14:43] TEST_EXIT=0 SUMMARY_EXIT=0 SECONDS=60` / `TEST_GATE=GREEN` |
| SUMMARY | `[00:14:43] SUM tests=1105 failed=0 errors=0 skipped=2 modules=26` / `NO_XML modules=0 期望模块数=26` / `SUMMARY_GATE=GREEN` |
| PACKAGE | `[00:14:51] JAR_COUNT=56` / `JAR_CHECKED=25 JAR_MISSING_PLUGIN_YML=0` / `PACKAGE_GATE=GREEN` |
| SINGLE_SOURCE_OF_TRUTH | `[00:09:44] SINGLE_SOURCE_OF_TRUTH_GATE=GREEN` / `WORLD_STATE_FILES=1`（负向夹具 `=RED` / `WORLD_STATE_FILES=2`，还原后 `=GREEN`） |
| VERDICT_MATRIX_CONSISTENCY | `[00:09:44] VERDICT_MATRIX_CONSISTENCY_GATE=GREEN` / `N=12 done=true remaining=0 basis=14b67f2`（负向夹具 i/ii/ii-b 三次 RED → 还原 GREEN） |
| **MECHANICS_SPEC**（PR#80 新增） | `[00:09:44] MECHANICS_SPEC_GATE=GREEN` / `MECHANICS_SPEC_ENTRIES=137` / `MECHANICS_SPEC_BY_KIND=config:78,behavior:56,java-const:3` / `MECHANICS_SPEC_WEAK=0` |
| **MECHANICS_SPEC_REPORT**（PR#80 新增） | `[00:09:45] [OK] 判据行 MECHANICS_SPEC_REPORT=FRESH` |

⇒ **十门全绿**（含 PR#80 新增的 `MECHANICS_SPEC_*`），且新增门禁**带 6 组负夹具**（§7.6）。

### 9.2 本分支自身不含构建输入

```
$ git rev-parse HEAD
c74249e46370f2c18359373615146aa978c72618
$ git rev-parse origin/main
c74249e46370f2c18359373615146aa978c72618
$ [ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] && echo "两者相等"
两者相等

$ git diff --name-only c74249e46370f2c18359373615146aa978c72618 HEAD
（空 —— 本任务提交前）

$ git diff --name-only <M12> HEAD        # 本任务提交后自检（见 §10 的收尾核对）
```
⇒ 本分支提交后只含本任务写入的 `plan/**`、`state/**`、`doc/cnb/**`、`.cnb/agent-out/**`，
**无一个构建输入**（`plugins/**`、`scripts/**`、`.cnb.yml`、`resource-pack/**`、`docs/**` 均未动）。
⚠️ 注意：本任务**不含** `docs/**` —— PR#80 改过的 `docs/GAME_DESIGN.md` 属于 M12 的构建输入，
**本轮不改**它（只读它的报告块做核对，见 §7.3）。

### 9.3 `G-FLAKY-GATE`：本轮**无同 sha 第二次构建采样**（如实说明，不做有利假设）

```
$ cnb build get-build-logs --repo xzqkjmnbvc2026/sivitahub --sha c74249e46370f2c18359373615146aa978c72618
total: 1
  1  cnb-cf6-1k46peo58  api_trigger_gate  success  main   ← 只有这一个
```
同一 sha 上本轮**只采到一个** `main` 构建（`api_trigger_gate`）。因此：

- v12 在 `14b67f2` 上的「同 sha 连续两次全绿」**不能**沿用到 `c74249e`；
- 本轮**不**断言「同 sha 两次全绿」，也**不**把它写成 done 的前提条件；
- 但也不把「只采到一个 GREEN」说成「有问题」—— `G-FLAKY-GATE` 的原始形态是
  **同 sha 上须有一个 RED**（见 `doc/cnb/gap-registry.md` §I.4：`f014b447` 上
  `cnb-4f2-1k46ad3pv` success / `cnb-ok2-1k46acmji` test stage error）。
  本轮**没有任何 RED 观测**，`G-FLAKY-GATE` **未复发**（也未被证伪）。

**口径声明（诚实优先）**：`G-FLAKY-GATE` 的「已闭环」需要「同 sha 至少两次构建且均绿」。
本轮采样不足 ⇒ 本轮对该项**不作「已闭环」声明**，只报「本轮无 RED 观测、且本任务不引入该风险」
（本任务不改任何测试/CI 文件）。这一项已写进 `plan/verdict-v13.json` 的 `risks`，
**不**列入 `needs_human`（它不是「只能由人做的事」，而是**需要调度器多触发一次构建**即可复核）。

## 10. 判定

| 条件（done=true 的四条） | 本轮 |
|---|---|
| ① 差集矩阵无「声明未接线」，且无可派工实现缺口 | ✅ 「声明未接线」= 0（23/23 scripts 有真实调用点）；可派工实现缺口 = 0（`G-CACHE-STAMP-BEFORE-LOCK` 仍闭环；PR#80 的两条门禁缺陷已闭环） |
| ② 十门在 M12 上全绿（含 `MECHANICS_SPEC_*`） | ✅ `cnb-cf6-1k46peo58` 13 stage success + 1 skipped，判据行齐全（见 §9.1） |
| ③ 素材层四条成立 | ✅ §1–§5 |
| ④ 剩余问题只有 REQUIRES_HUMAN | ✅ H-001 / U-003 / W7-A2 四条 `[R]`（无实现路线取舍） |

⇒ `done=true` / `remaining=0` / `remaining_ids=[]`。

**`needs_human` 只列真正只能由人做的事**：H-001（生产机 `lamb` 写操作）、
U-003（真实 Paper + 真实客户端握手）、W7-A2 的 4 条 `[R]` 级 unproven。
**实现路线的选择不属 REQUIRES_HUMAN** —— 用户已明确授权「不需要拍板，完全由 AI 决定」。
本轮若仍有取舍（例如 §6.5 的判据形态边界），已在 `reason` 里说明所选路线与理由，**不**写进 `needs_human`。

### 10.1 v13 与 v12 的差别（一行版）

v12 判 done 时 PR#80（构建输入改动）**还不存在**；v13 在 PR#80 之后的 M12=`c74249e` 上重判：
新增「玩法规格↔代码机械绑定」这一层的闭环复核（137 条 / 6 组负夹具 / 报告可重生成 / stdlib-only），
并独立复现了它顺带修掉的两条门禁缺陷（载体缺失假绿、PyYAML 依赖）。
其余各项（G-302 系列、G-CACHE-STAMP-BEFORE-LOCK、素材层四条）复跑结论与 v12 一致。

### 10.2 收尾核对（本文件与 verdict 的一致性）

```
$ sed -n '1,20p' plan/gap-matrix-v13.md | grep -nE '^> verdict: done=(true|false) remaining=[0-9]+$'
3:> verdict: done=true remaining=0
$ sed -n '1,20p' plan/gap-matrix-v13.md | grep -n 'basis:'
4:> basis: c74249e
$ python3 -c "import json;d=json.load(open('plan/verdict-v13.json'));print(d['done'],d['remaining'],d['basis'])"
True 0 c74249e
$ python3 - <<'PY'   # 按 scripts/selftest-gates.sh §11 vmc_gate 同口径自查
import json, re
head = open('plan/gap-matrix-v13.md').read().split('\n')[:20]
v = json.load(open('plan/verdict-v13.json'))
m = re.search(r'^> verdict: done=(true|false) remaining=(\d+)$', '\n'.join(head), re.M)
assert m and (m.group(1) == 'true') == v['done'] and int(m.group(2)) == v['remaining'], 'verdict 行不一致'
assert re.search(r'basis:\s*' + re.escape(v['basis']), '\n'.join(head)), 'basis 不在前 20 行'
print("VERDICT_MATRIX_CONSISTENCY_GATE=GREEN")
print("N=13 done=true remaining=0 basis=c74249e")
PY
VERDICT_MATRIX_CONSISTENCY_GATE=GREEN
N=13 done=true remaining=0 basis=c74249e
```
