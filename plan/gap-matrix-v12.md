# 全量机制矩阵 v12（gap-matrix-v12）

> verdict: done=true remaining=0
> basis: 14b67f2
> basis-substring: 14b67f2
> 生成者：CNB CodeBuddy NPC，任务 `W27-A1-verdict-v12`（当前 main 的最终复验轮 v12）。
> 实测 `git rev-parse HEAD` == `git rev-parse origin/main` == `14b67f27706861035b321c31e2b9ea1699b60a74`（两者**相等**）。
> M10（本轮采信的十门证据 sha）= `14b67f2`（= 判定 basis 本身）；本分支自身不含构建输入，见 §8.2。
> 状态取值：`已接线` / `声明未接线` / `存疑` / `已废弃` / `真实缺口`。

## 0. 本轮结论（先给判定，再给证据）

| 项 | v11 判定（basis=f014b447） | v12 判定（basis=14b67f2） | 依据 |
|---|---|---|---|
| **G-CACHE-STAMP-BEFORE-LOCK**（取号点仍在锁外） | 真实缺口（可派工） | ✅ **已闭环** | §7：新增 `LockedTransaction`，取号下沉到「已持有 `SELECT ... FOR UPDATE` 行锁之后」；5 个发布点全部改为 `LockedTransaction.wrap` 且**源码里不再有 eager `nextCommitVersion()`**；三个回归测试都在且 PASS；W26 三段证据齐全（改前 FAIL / 改后 PASS / 负向对照必红） |
| **G-302-RESIDUAL 系列**（含 -2/-3/-4） | 清零 | ✅ **仍清零** | §6：`SOURCE_STRING_TESTS=0 / SOURCE_STRING_GATE=GREEN / EXIT=0`；4 类方法体复核 + 三组负向夹具（⑤⑥⑦）现场 RED → 删除 → GREEN；反例未被判红；规格规模 469/79/3 ≥ v8 的 466/78/2 |
| 十门 @ basis | 权威构建全绿，同 sha 另构建 RED | ✅ **十门全绿，且同 sha 两次构建连续全绿** | §8：13 stage 全 success；`GATE_SELFTEST=PASS=132 FAIL=0` / `SUM tests=1105 failed=0 errors=0 skipped=2 modules=26` / `JAR_COUNT=56` 等判据行逐条回读 |
| `G-FLAKY-GATE` | 回退（复发） | ✅ **闭环（同 sha 两次全绿）** | §8.3：`14b67f2` 上 `cnb-lpt-1k46f0u8b`(api_trigger_gate) + `cnb-m3o-1k46f0gvf`(push) **均 success**，判据行一致 |
| 素材层四条 | 成立 | ✅ **成立（复跑一致）** | §1–§5 |
| 其余机制（v11 的 39 条矩阵） | 已接线 | ✅ **已接线（复跑一致）** | §6、§7 |

⇒ 差集矩阵「声明未接线」= 0 条；「可派工的实现缺口」= 0 条；
十门在 M10=`14b67f2` 上全绿（13 stage，判据行齐全）；素材层四条成立；
剩余问题**只有** `REQUIRES_HUMAN`（H-001 / U-003 / W7-A2 四条 `[R]`）。
⇒ `plan/verdict-v12.json`：`done=true`、`remaining=0`（与本节 `> verdict:` 行严格一致）。

⚠️ 本轮特别验证的前提（都属于「不能只看计数 / 不能只看门禁 GREEN」）：
1. **「缺口被声明关闭」不等于「缺口已闭环」**：v11 判 `done=false` 的唯一依据就是
   「取号点在锁外 + 同 sha 下游构建 RED」；本轮**独立复核代码事实**（不是采信 PR 描述）：
   eager `nextCommitVersion()` 在 5 个 `inTransaction` 里**已不存在**，
   `grep -rn 'nextCommitVersion()' plugins/rpg-core/src/main` 只剩 1 个定义（:527）+ 6 处 javadoc 引用（§7.2）。
2. **「计数下降」不等于「缺口清零」**：§6 用方法体复核 + 负向夹具证明 G-302 系列的
   `SOURCE_STRING_TESTS=0` 是方法体事实，不是计数错觉。
3. **「门禁 GREEN」不等于「判据没有过宽的豁免」**：§6.3 现场造三组夹具证判据真有力，
   §6.4 复核规格规模未缩水。
4. **「负载下才出现」不等于「只是负载问题」**：v11 的 RED 是**真并发缺陷**（取号在临界区外），
   本轮以代码事实 + 同 sha 两次全绿双证闭环（§7、§8.3）。

## 0.2 ★ 三轮收敛史（G-CACHE-STAMP-BEFORE-LOCK 最有价值的一条）

给「异步回调里发布本地读缓存」做顺序守卫需要一个**提交版本号**，它必须满足
「取号顺序 == 加锁顺序 == 提交顺序」。三轮各差一段：

| 轮 | 取号点 | 残留问题 | 机械证据 | 结论 |
|---|---|---|---|---|
| PR#73（`839639f`） | `connection.commit()` **之后** | 「提交后、取号前」被抢占 ⇒ 倒置 | `readCacheIsNotRolledBackByALateCommit()`（延迟第 20 次 `Connection.close`） | ❌ 窗口仍在 |
| PR#75（`f014b447`） | `inTransaction` 开头（**加锁之前**） | 「取号后、加锁前」被抢占 ⇒ 倒置 | `lateCommitStampCannotRollBackTheReadCache()`（延迟真实 `commit()` 返回之后）；同 sha 下游构建 `cnb-ok2-1k46acmji` test stage RED，message `expected: <20> but was: <19>` | ❌ **取号在临界区外，无论前后都留倒置窗口** |
| **PR#78（`14b67f2`）** | **已持有 `SELECT ... FOR UPDATE` 行锁之后**（新增 `LockedTransaction`；`loadForUpdate`/`loadSnapshot` 在 `FOR UPDATE` 返回后触发 `markLocked`，版本值惰性取一次） | **倒置窗口归零** | `commitVersionMustBeTakenAfterTheRowLockIsHeld()`（延迟「第 18 次含 `FOR UPDATE` 的 `executeQuery` 执行之前」）⇒ 改前 `expected: <20> but was: <19>`，改后 PASS | ✅ 闭环 |

**这条史的方法论价值**：`取号在临界区之外`——无论放在 `commit()` 之后还是放在 `apply()` 之前——
都**留倒置窗口**；只有把它移进「已持行锁」这段临界区内部，才能让「取号顺序 == 加锁顺序 == 提交顺序」
成为**代码事实**而不是注释声明。三轮的判据也从「延迟 close / 延迟 commit 返回」收紧到
「延迟 `FOR UPDATE` 执行之前」——即精确打在「取号之后、加锁之前」的那个窗口上。

## 1. (a) 素材层 —— 零消费者=0（逐条给调用点原文）

口径：**「脚本存在」不等于「脚本被调用」**。`scripts/` 下 21 个 `.sh`/`.py` 逐个查真实调用点。

| # | 脚本 | 真实调用点原文 |
|---|---|---|
| 1 | `scripts/selftest-gates.sh` | `.cnb.yml:71` `sh scripts/selftest-gates.sh` |
| 2 | `scripts/dump-test-results.py` | `.cnb.yml:293` `python3 scripts/dump-test-results.py --min-modules 26` |
| 3 | `scripts/check-plugin-jars.py` | `.cnb.yml:387` `python3 scripts/check-plugin-jars.py /tmp/jars.txt` |
| 4 | `scripts/list-env-tests.sh` | `.cnb.yml:303` `sh scripts/list-env-tests.sh > /tmp/env-methods.txt` |
| 5 | `scripts/check-ddl-consumers.sh` | `.cnb.yml:89` `sh scripts/check-ddl-consumers.sh` |
| 6 | `scripts/check-source-contract.sh` | 35 个 `*ContractTest` 经 `ProcessBuilder` 真跑（例 `EquipmentExitPathReachabilityTest.java:334`） |
| 7 | `scripts/check-source-string-tests.sh` | `scripts/selftest-gates.sh:497/:499`（夹具真跑） |
| 8 | `scripts/check-config-fallbacks.sh` | `EnhancementFallbackContractTest.java:35` `root.resolve("scripts/check-config-fallbacks.sh")` |
| 9 | `scripts/check-trusted-menu-holders.sh` | `TrustedMenuBoundaryContractTest.java:30` + 真跑路径 |
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

判定：**成立**。21/21 都至少有一个真实调用点（逐个 grep 计数均 >0），无「声明未接线」。

## 2. (b) 素材逐像素基线

```
$ sh -c 'python3 scripts/check-asset-baseline.py > /tmp/ab.txt 2>&1; echo "code=$?" > /tmp/ab_code.txt'; cat /tmp/ab_code.txt
code=0
$ tail -3 /tmp/ab.txt
ASSET_BASELINE: 校验 252 张 PNG（清单 252 条）
  UNREGISTERED=0 MISSING=0 MODIFIED=0
ASSET_BASELINE_GATE=GREEN
```
判定：**成立**。252↔252，UNREGISTERED/MISSING/MODIFIED 全 0。

## 3. (c) provenance ↔ manifest 同集合

```
$ python3 - <<'PY'
import json
m=json.load(open('resource-pack/assets-manifest.json'))
p=json.load(open('resource-pack/asset-provenance.json'))
def norm(s): return s[len('assets/'):] if s.startswith('assets/') else s
ms={norm(k) for k in m.keys()}
ps={norm(k) for k in p['assets'].keys()}
print("manifest(len)",len(ms),"prov assets(len)",len(p['assets']))
print("manifest-only",sorted(ms-ps),len(ms-ps))
print("prov-only",sorted(ps-ms),len(ps-ms))
print("same set",ms==ps)
print("counts",json.dumps(p['$counts'],ensure_ascii=False),"sum",sum(p['$counts'].values()))
print("needsHuman",p['$needsHumanDecision'])
PY
manifest(len) 252 prov assets(len) 252
manifest-only [] 0
prov-only [] 0
same set True
counts {"ai-prompt": 10, "derived-from": 69, "generator": 129, "human-override": 1, "upstream-betterhud": 38, "upstream-vanilla": 5} sum 252
needsHuman []
```
判定：**成立**。同集合；六类合计 129+69+38+10+5+1 = 252；`$needsHumanDecision=[]`。

## 4. (d) items(81) ↔ runtime-item-models(81) ↔ 七项素材判据

```
$ ls resource-pack/assets/originalrpg/items/*.json | sed 's#.*/##; s#\.json$##' | sort > /tmp/defs.txt; wc -l < /tmp/defs.txt
81
$ awk 'NF && $0 !~ /^#/' resource-pack/runtime-item-models.txt | sort > /tmp/mani.txt; wc -l < /tmp/mani.txt
81
$ diff /tmp/defs.txt /tmp/mani.txt && echo IDENTICAL
IDENTICAL

$ for c in closure compass hud journey lang packmeta textures; do
    python3 scripts/resource-pack-asset-check.py --root . --check $c >/tmp/rp-$c.txt 2>&1; echo "$c exit=$?"; done
closure exit=0
compass exit=0
hud exit=0
journey exit=0
lang exit=0
packmeta exit=0
textures exit=0
```
判定：**成立**。81 == 81（`diff` IDENTICAL）；七项素材判据全部 `exit=0`。

## 5. 素材可复现性（CI 真跑，本仓无 JDK 未本地复跑 —— 如实说明）

CI（M10 stage-5，sn=`cnb-lpt-1k46f0u8b`）判据行原文：
```
[00:11:34   +0ms]   REPRODUCIBLE=70 MISMATCH=0 MISSING=0 UNREGISTERED=0
[00:11:34   +0ms] ASSET_REPRO_GATE=GREEN
```
判定：**成立（以 CI 为准）**。⚠️ 本容器**没有 JDK**，`check-asset-reproducible.sh` 需现场 `javac`
⇒ **本轮未本地复跑**，以 M10 的 stage-5 `status=success` + 上述判据行为证据（不断言本地跑过）。

## 6. G-302-RESIDUAL 系列是否仍清零 + 判据是否被放宽

### 6.1 判据行与退出码（**重定向到文件再取 `$?`**，不走管道）

```
$ sh scripts/check-source-string-tests.sh > /tmp/sst.txt 2>&1; echo "EXIT=$?"
EXIT=0
SOURCE_STRING_TESTS=0
SOURCE_STRING_GATE=GREEN 本仓生产源码已无源码字符串断言（豁免口径见脚本头部注释）
```
判定：`SOURCE_STRING_TESTS=0` / `SOURCE_STRING_GATE=GREEN` / `EXIT=0`。

### 6.2 独立抽样复核 ≥4 个类（读的是什么？）

| 类 | 判定依据（文件:行号） | 读的是什么 |
|---|---|---|
| `DungeonTimeoutLifecycleContractTest`（必含） | `plugins/rpg-dungeons/src/test/java/cn/originalrpg/dungeons/DungeonTimeoutLifecycleContractTest.java`：`grep -n 'readString\|readAllBytes\|Files\.'` 全类**只命中 `:19` 注释**（点原名用 `Files.readString`）；`:46/:74/:96` 三个 `@Test` 真跑 `DungeonBukkitTestHarness.newManager(scheduler)` 构造 `DungeonManager` | 真跑 `DungeonManager` + 记录型 `BukkitScheduler`（`:48/:49/:76/:77/:98/:99`）⇒ **行为/夹具** |
| `EquipmentExitPathReachabilityTest`（必含） | `plugins/rpg-items/src/test/java/cn/originalrpg/items/forge/EquipmentExitPathReachabilityTest.java:332-340`：`assertTrue(Files.isRegularFile(gate))` + `new String(Files.readAllBytes(spec))` + `process.getInputStream().readAllBytes()` + `assertEquals(0, process.exitValue())`；`:37/:42` 是注释（点原名用 `Files.readString(...).contains()`） | **契约规格 JSON + 进程 stdout（真跑门禁脚本）** |
| `SeasonAttunementSettlementWiringTest`（必含） | `plugins/rpg-core/src/test/java/cn/originalrpg/core/persistence/SeasonAttunementSettlementWiringTest.java:243` `Files.isRegularFile(gate)` / `:250` `readAllBytes()` 取 stdout / `:299` `Files.newInputStream(path)`；`:214-215` 是注释 | **进程 stdout + 真解析（newInputStream）** |
| `DungeonEntryCommitAmbiguityContractTest`（自选第 4 个） | `plugins/rpg-dungeons/src/test/java/cn/originalrpg/dungeons/DungeonEntryCommitAmbiguityContractTest.java:65` `Files.isRegularFile(gate)` / `:70` `readAllBytes()` 取 stdout / `:77` `Files.isRegularFile(current.resolve("settings.gradle.kts"))`；`:14` 是注释 | **进程 stdout + 仓库根定位** |

判定：4/4 读的是**测试夹具 / 源规格 JSON / 进程 stdout / 真解析**，**不是**生产源码正文的字符串断言。判据未放宽。

### 6.3 反向验证判据仍有力（现场造夹具，跑完删除）

`sh scripts/check-source-string-tests.sh`（重定向取码），三组夹具同一次造出：

```
$ sh scripts/check-source-string-tests.sh > /tmp/fx.txt 2>&1; echo "FIXTURE_EXIT=$?"
SOURCE_STRING_TESTS=3
  plugins/rpg-core/src/test/java/_gatefixture/Fixture5WrappedAssertTest.java:11 -> ['plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/LockedTransaction.java'] tests=['wrapped']
  plugins/rpg-core/src/test/java/_gatefixture/Fixture6ConstPathTest.java:13 -> ['plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/LockedTransaction.java'] tests=['constPath']
  plugins/rpg-core/src/test/java/_gatefixture/Fixture7TraversalTest.java:16 -> ['plugins/rpg-core/src/main'] tests=['traversal']
SOURCE_STRING_GATE=RED 仍有测试在「读本仓生产源码 + 源码字符串断言」
$ sh -c 'sh scripts/check-source-string-tests.sh >/dev/null 2>&1; echo "code=$?" > /tmp/fx_code.txt'; cat /tmp/fx_code.txt
code=1
```

| 夹具 | 形态 | 结果 |
|---|---|---|
| `Fixture5WrappedAssertTest.java:11` | ⑤ `assertTrue(Files.readString(Path.of("plugins/…/LockedTransaction.java")).contains("markLocked"))` | `SOURCE_STRING_TESTS=3` / **EXIT=1（变红）** |
| `Fixture6ConstPathTest.java:13` | ⑥ `Files.readString(Path.of(PRODUCTION))`（常量路径）+ `contains` | 同上，**EXIT=1（变红）** |
| `Fixture7TraversalTest.java:16` | ⑦ `Files.walk(root.resolve("plugins/rpg-core/src/main"))` + `readString(f).contains(id)` 聚合成列表 → `anyMatch` | 同上，**EXIT=1（变红）** |

删除后复跑：
```
$ rm -rf plugins/rpg-core/src/test/java/_gatefixture
$ sh -c 'sh scripts/check-source-string-tests.sh > /tmp/sst_after.txt 2>&1; echo "code=$?" > /tmp/sst_after_code.txt'; cat /tmp/sst_after_code.txt
code=0
$ cat /tmp/sst_after.txt
SOURCE_STRING_TESTS=0
SOURCE_STRING_GATE=GREEN 本仓生产源码已无源码字符串断言（豁免口径见脚本头部注释）
$ git status --porcelain
（空 —— 工作区干净）
```
反例（**不**被判红）：`plugins/rpg-core/src/test/java/cn/originalrpg/core/i18n/LanguageBundleContractTest.java`
—— `:465` `Files.newInputStream(file)` + `:466` `new Yaml(new SafeConstructor(options)).load(input)`
真解析 YAML（不 `readString`、不对正文做 `contains`）⇒ 三组夹具全红时它 **0 命中**。

判定：判据**真有力**（造缺陷必红、删缺陷即绿），且**没有反向过捕**（反例不被误伤）。

### 6.4 规格规模相对 v8 基线未缩水

```
$ sh -c 'sh scripts/check-source-contract.sh --all > /tmp/sc.txt 2>&1; echo "code=$?" > /tmp/sc_code.txt'; cat /tmp/sc_code.txt
code=0
$ tail -3 /tmp/sc.txt
SOURCE_CONTRACT_SPEC_FILES=3
SOURCE_CONTRACT_CHECKS=469 SPECS=79
SOURCE_CONTRACT_GATE=GREEN
```
v8 基线 `CHECKS=466 SPECS=78 SPEC_FILES=2` ⇒ 469/79/3 **均未缩水**（`SPEC_FILES` 上升符合预期）。

### 6.5 sn=cnb-lpt-1k46f0u8b 里 §4d 的判据行（回读核对）

```
[00:09:26  +30ms]   [OK  ] 夹具④遍历式源码扫描（walk src/main + readString + contains 聚合成列表）→ 1 (exit=1)
[00:09:26  +27ms]   [OK  ] 夹具④b豁免：遍历目录 + 真解析 YAML（不 readString/不 contains）→ 0 (exit=0)
[00:09:26  +27ms]   [OK  ] 夹具⑤：assert 块包住读取调用（assertTrue(readString(...).contains(x))）→ 1 (exit=1)
[00:09:26  +26ms]   [OK  ] 夹具⑥：Files.readString(Path.of(常量)) + contains → 1 (exit=1)
[00:09:26  +47ms]   [OK  ] 真实仓库已无此类断言 → 0 (exit=0)
[00:09:26   +2ms]   [OK  ] 真实规格目录已加载且未缩水（CHECKS=469≥466 SPECS=79≥2 GREEN）
```

## 7. ★ 核心：G-CACHE-STAMP-BEFORE-LOCK 的闭环复核（逐条机械证据）

### 7.1 `LockedTransaction` 存在，且 `markLocked` 只在第一次调用时取号

```
$ ls plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/LockedTransaction.java
plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/LockedTransaction.java
$ grep -n 'markLocked\|version == 0L\|nextVersion.getAsLong\|NO_STAMP' \
    plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/LockedTransaction.java
54:    private void markLocked() {
55:        if (version == 0L) version = nextVersion.getAsLong();
68:    static void markLocked(Connection connection) {
70:        if (locked != null) locked.markLocked();
25:    static final long[] NO_STAMP = new long[1];
```
原文（`:52-56`）：
```java
/** 行锁到手：登记一次提交版本号（重复调用只生效一次，号必须最早那个）。 */
private void markLocked() {
    if (version == 0L) version = nextVersion.getAsLong();
}
```
判定：**成立**。取号是**惰性一次**（`version == 0L` 守卫），只有 `markLocked(Connection)` 这条
「已持锁」路径会触发；`wrap` 只做代理包装、**不取号**（`:44-51`）。

### 7.2 5 个服务的取号触发点都在 `FOR UPDATE` 之后

**旧形态已彻底消失**：
```
$ grep -rn 'nextCommitVersion()' plugins/rpg-core/src/main/java/
…/JdbcAscensionService.java:277:     *（{@link JdbcProfileService#nextCommitVersion()}）。取号由
…/JdbcAdminIncidentCompensationService.java:715:     *（{@link JdbcProfileService#nextCommitVersion()}）。取号由
…/JdbcDungeonRewardService.java:311:     *（{@link JdbcProfileService#nextCommitVersion()}）。取号由
…/Committed.java:7: *（见 {@link JdbcProfileService#nextCommitVersion()}），因此取号顺序 == 锁顺序 == 提交顺序。
…/JdbcAdminTestService.java:291:     *（{@link JdbcProfileService#nextCommitVersion()}）。取号由
…/JdbcProfileService.java:503: * @param version 调用方事务**持锁时**取得的版本号（{@link #nextCommitVersion()}）。
…/JdbcProfileService.java:518: * @param version 该事务**持锁时（commit 之前）**取得的版本号（{@link #nextCommitVersion()}）
…/JdbcProfileService.java:527: long nextCommitVersion() {
```
⇒ 全部命中都是 **javadoc / 注释 / 1 个定义本身**（`:527`）；**没有任何 eager 调用**。
对照 v11 的 `:796 version = nextCommitVersion();`（在 `apply` 之前）**已不存在**。

**注册点（`FOR UPDATE` 之后触发 `markLocked`）**：

| 服务 | `FOR UPDATE` 行 | 触发点行 | 取号包装行 | inTransaction 顺序 |
|---|---|---|---|---|
| `JdbcProfileService` | `:627` `select(connection, playerId, lock)`（SQL 见 `:246`/`loadSnapshot:484`） | `:633` `markLocked(connection, lock);`（新建分支 `:649`） | `:841` `LockedTransaction.wrap(raw, this::nextCommitVersion)` | `apply`(:843) → `commit`(:844) → `versionOf`(:845) |
| `JdbcAscensionService` | `:81` `profiles.loadForUpdate(connection, playerId)` | 由 `loadForUpdate` 内部 `:633` 触发 | `:284` `LockedTransaction.wrap(raw, profiles::nextCommitVersion)` | `apply`(:286) → `commit`(:287) → `versionOf`(:288) |
| `JdbcDungeonRewardService` | `:116` `profiles.loadForUpdate(connection, request.playerId())` | 同上 | `:318` `LockedTransaction.wrap(raw, profiles::nextCommitVersion)` | `apply`(:320) → `commit`(:321) → `versionOf`(:322) |
| `JdbcAdminIncidentCompensationService` | `:127` `profiles.loadForUpdate(connection, value.playerId())` | 同上 | `:723` `LockedTransaction.wrap(raw, profiles::nextCommitVersion)` | `apply`(:727) → `commit`(:728) → `versionOf`(:729) |
| `JdbcAdminTestService` | `:53/:73/:103` `loadForUpdate`、`:120/:124` `loadSnapshot` | 同上 | `:297` `LockedTransaction.wrap(raw, profiles::nextCommitVersion)` | `apply`(:299) → `commit`(:300) → `versionOf`(:301) |

**「加锁在取号之前」的源码顺序原文**（`JdbcProfileService`，`FOR UPDATE` 由 `select` 发出）：
```
$ sed -n '627,633p;482,492p' plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcProfileService.java
627:        PlayerProfile existing = select(connection, playerId, lock);   ← 内含 FOR UPDATE
633:            markLocked(connection, lock);                              ← 取号在这之后
482:    void loadSnapshot(Connection connection, UUID playerId) throws SQLException {
484:                "SELECT player_uuid FROM rpg_profiles WHERE player_uuid = ? FOR UPDATE")) {
486:            try (ResultSet result = query.executeQuery()) {
492:        markLocked(connection, true);                                  ← 取号在这之后
```
判定：**成立**。5/5 服务的取号触发点都在 `FOR UPDATE` 返回**之后**，
「取号顺序 == 加锁顺序 == 提交顺序」成为代码事实。

**顺带修正的纯读路径**（`JdbcAdminTestService.status`）：
```
$ sed -n '48,57p' plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcAdminTestService.java
    public CompletionStage<AdminTestState> status(UUID playerId) {
        // 纯读、事务里只有 SELECT（不写任何行），因此不发布读缓存：没有提交就不该
        // 让读缓存跟着变。锁仍按原样持有（读一致性不变）。
        return AsyncJdbc.supply(executor, () -> {
            AdminTestState state = inTransaction(connection -> {
                PlayerProfile profile = profiles.loadForUpdate(connection, playerId);
                …
            }, LockedTransaction.NO_STAMP);
```
判定：**成立**。纯读路径改传 `LockedTransaction.NO_STAMP` —— 原来它会把读到的行发进缓存，
现在不发布。

### 7.3 三个缓存顺序回归测试都在且 PASS

```
$ grep -n 'readCacheIsNotRolledBackByALateCommit\|lateCommitStampCannotRollBackTheReadCache\|commitVersionMustBeTakenAfterTheRowLockIsHeld' \
    plugins/rpg-core/src/test/java/cn/originalrpg/core/persistence/JdbcServicesTest.java
1035:    void readCacheIsNotRolledBackByALateCommit() throws Exception {
1088:    void lateCommitStampCannotRollBackTheReadCache() throws Exception {
1151:    void commitVersionMustBeTakenAfterTheRowLockIsHeld() throws Exception {
```
新测试的夹具原文（`:1161-1170`，延迟打在「第 18 次含 `FOR UPDATE` 的 `executeQuery` 执行之前」）：
```java
if (cMethod.getName().equals("executeQuery") && cArgs != null
        && cArgs.length == 1 && cArgs[0] instanceof String sql
        && sql.contains("FOR UPDATE")) {
    if (locks.incrementAndGet() == FOR_UPDATE_DELAY_AT) {
        Thread.sleep(FOR_UPDATE_DELAY_MILLIS);
    }
}
```
断言**未放宽**（`:1205-1207`）：`assertEquals(20, fromDb, "数据库里 20 次授予必须全部落账")` +
`assertEquals(fromDb, … .experience(), "取号必须在持有行锁之后；…")`；`:1181-1183` 仍是
20 路并发 `grantExperience` + `allOf().join()`（**未串行化**）。
**CI 实测 PASS**：`cnb-lpt-1k46f0u8b` stage-7 `plugins/rpg-core tests= 321 failed=0`（v11 的 320 + 新增 1）。
判定：**成立**。三个测试都在，且在同一 sha 的**两次**构建里全绿。

### 7.4 W26 三段证据（改前 FAIL / 改后 PASS / 负向对照必红）

`.cnb/agent-out/W26-A1-cache-stamp-after-lock.json` 的 `evidence` 回读（python3 抽取）：
```
pre_fix_exit 1
pre_fix_msg <failure message="org.opentest4j.AssertionFailedError: 取号必须在持有行锁之后；取号早于加锁会让后提交的旧快照带更大的号回滚读缓存（实际缓存值见左） ==> expected: <20> but was: <19>" …
post_fix_exit 0  → tests 1 failures 0 errors 0 -> PASS
neg_exit 1
neg_msg org.opentest4j.AssertionFailedError: 取号必须在持有行锁之后；… ==> expected: <20> but was: <19>
revert_exit 0 → tests 1 failures 0 -> PASS
```
| 段 | 内容 | 机械证据 |
|---|---|---|
| ① 改前 FAIL | 把 5 个 main 文件回退到 `f014b447`，新测试 `commitVersionMustBeTakenAfterTheRowLockIsHeld()` | `pre_fix_exit_code=1`，XML `<failure message=… ==> expected: <20> but was: <19>` |
| ② 改后 PASS | 恢复修复版 | `post_fix_exit_code=0`，`tests 1 failures 0 -> PASS` |
| ③ 负向对照必红 | 把取号点插回 `inTransaction` 开头 | `negative_control_attempt2.exit_code=1`（同一 `<failure>` 原文）；`revert_exit_code=0` 还原即绿 |

判定：**成立**。三段齐全，且负向对照的失败值**非空**（`19 != 20`）⇒ 断言没被「等没了」。

**结论**：`G-CACHE-STAMP-BEFORE-LOCK` **已闭环** —— 代码事实（取号在持锁之后、无 eager 调用）
+ 三个回归测试在（含新确定性复现测试）+ 同 sha 两次构建全绿 + 负向对照必红，四重证据一致。

## 8. 十门在 M10 上全绿（逐条回读核对）+ 本分支不含构建输入

### 8.1 权威构建 sn=`cnb-lpt-1k46f0u8b`

```
$ cnb build get-build-status --repo xzqkjmnbvc2026/sivitahub --sn cnb-lpt-1k46f0u8b
status: success
pipelinesStatus:
  "cnb-lpt-1k46f0u8b-001": status: success, duration: 903334
    success,3736,Prepare,prepare
    success,18180,准备下载器（必须是第一个 stage）,stage-0
    success,563501,门禁自测（负向对照）,stage-1
    success,106420,DDL 门禁（G-301 消费者 + G-302 注册，双向）,stage-2
    success,527,素材门禁（W7-A1）,stage-3
    success,339,素材逐像素基线门禁（W12-A1）,stage-4
    success,1555,素材可复现性门禁（W13-A1）,stage-5
    success,143649,compile,stage-6
    success,57248,test,stage-7
    success,7528,assemble,stage-8
    success,594,BeforeEnd,beforeEnd
    skipped,0,DebugDetection,debugDetection
    success,863,End,end
```
`event=api_trigger_gate`，`targetRef=main`，`sha=14b67f27706861035b321c31e2b9ea1699b60a74`，
`pipelineSuccessCount=1 / pipelineFailCount=0`。

| 门 | 判据行（stage 日志原文，`build-runner-download-log` 回读） |
|---|---|
| Downloader | `[00:00:22] DOWNLOADER=ready` |
| GATE_SELFTEST | `[00:09:45] ===== 门禁自测汇总：PASS=132 FAIL=0 =====` / `[00:09:45] GATE_SELFTEST=GREEN` |
| DDL_CONSUMER | `[00:11:32] DDL_CONSUMER_GATE=GREEN` |
| DDL_REGISTRATION | `[00:11:32] DDL_REGISTRATION_GATE=GREEN` |
| CMD_REFERENCE | `[00:11:31] CMD_REFERENCE_GATE=GREEN` |
| RESOURCE_PACK | `[00:11:32] RESOURCE_PACK_GATE=GREEN` |
| ASSET_BASELINE | `[00:11:32] ASSET_BASELINE_GATE=GREEN`（`UNREGISTERED=0 MISSING=0 MODIFIED=0`，`校验 252 张 PNG（清单 252 条）`） |
| ASSET_REPRO | `[00:11:34] ASSET_REPRO_GATE=GREEN`（`REPRODUCIBLE=70 MISMATCH=0 MISSING=0 UNREGISTERED=0`） |
| COMPILE | `[00:13:58] TASKS_DONE=28` / `BUILD_EXIT=0 SECONDS=108` / `COMPILE_GATE=GREEN` |
| TEST | `[00:14:55] TEST_EXIT=0 SUMMARY_EXIT=0 SECONDS=57` / `TEST_GATE=GREEN` |
| SUMMARY | `[00:14:55] SUM tests=1105 failed=0 errors=0 skipped=2 modules=26` / `NO_XML modules=0 期望模块数=26` / `SUMMARY_GATE=GREEN` |
| PACKAGE | `[00:15:02] JAR_COUNT=56` / `[00:15:02] PACKAGE_GATE=GREEN` |
| SINGLE_SOURCE_OF_TRUTH | `[00:09:45] SINGLE_SOURCE_OF_TRUTH_GATE=GREEN` / `WORLD_STATE_FILES=1`（负向夹具 `=RED` / `WORLD_STATE_FILES=2`，还原后 `=GREEN`） |
| VERDICT_MATRIX_CONSISTENCY | `[00:09:45] VERDICT_MATRIX_CONSISTENCY_GATE=GREEN`（负向夹具两次 RED → 还原 GREEN） |

判定：**十门全绿**（13 stage 全 success，耗时全非零；`DebugDetection` 为 `skipped` 属设计）。

### 8.2 本分支自身不含构建输入

```
$ git diff --name-only 14b67f27706861035b321c31e2b9ea1699b60a74 HEAD
（空 —— 本任务提交前）
$ git diff --name-only origin/main HEAD
（空）
```
⇒ 本分支提交后只含本任务写入的 `plan/**`、`state/**`、`doc/cnb/**`、`.cnb/agent-out/**`，
**无一个构建输入**（`plugins/**`、`scripts/**`、`.cnb.yml`、`resource-pack/**`、`docs/**` 均未动）。

### 8.3 `G-FLAKY-GATE`：同 sha 两次构建连续全绿（v11 的回退已恢复）

```
$ cnb build get-build-logs --repo … --sha 14b67f27706861035b321c31e2b9ea1699b60a74
cnb-n0o-1k46fu13f  push              pending  agent/W27-A1-verdict-v12   ← 本任务分支构建（进行中）
cnb-gi9-1k46fu121  api_trigger_npc   pending  agent/W27-A1-verdict-v12   ← 同上
cnb-lpt-1k46f0u8b  api_trigger_gate  success  main
cnb-m3o-1k46f0gvf  push              success  main
```
复核构建 `cnb-m3o-1k46f0gvf`（push, main, `14b67f2`）回读判据行：
```
[00:09:52] ===== 门禁自测汇总：PASS=132 FAIL=0 =====
[00:09:52] GATE_SELFTEST=GREEN
[00:11:42] CMD_REFERENCE_GATE=GREEN / DDL_REGISTRATION_GATE=GREEN / DDL_CONSUMER_GATE=GREEN
[00:11:42] RESOURCE_PACK_GATE=GREEN / ASSET_BASELINE_GATE=GREEN
[00:11:44] ASSET_REPRO_GATE=GREEN
[00:14:00] TASKS_DONE=28 / BUILD_EXIT=0 SECONDS=122 / COMPILE_GATE=GREEN
[00:14:59] SUM tests=1105 failed=0 errors=0 skipped=2 modules=26 / SUMMARY_GATE=GREEN
[00:15:00] TEST_EXIT=0 SUMMARY_EXIT=0 SECONDS=58 / TEST_GATE=GREEN
[00:15:07] JAR_COUNT=56 / ASSEMBLE_EXIT=0 JARCHECK_EXIT=0 SECONDS=7 / PACKAGE_GATE=GREEN
```
判定：**闭环**。同一 sha `14b67f2` 上两个构建（`api_trigger_gate` + `push`）**均 success**，
判据行一致（PASS=132 / tests=1105 / JAR_COUNT=56）⇒ 「同 sha 连续两次全绿」成立，
v11 的「先绿后红」回退已恢复。失败用例所属的 `rpg-core` 本轮 `tests=321 failed=0`（两次构建一致）。

## 9. 判定

| 条件（done=true 的四条） | 本轮 |
|---|---|
| ① 差集矩阵无「声明未接线」，且无可派工实现缺口 | ✅ 「声明未接线」= 0；`G-CACHE-STAMP-BEFORE-LOCK` 已闭环 ⇒ 可派工缺口 = 0 |
| ② 十门在 M10 上全绿 | ✅ `cnb-lpt-1k46f0u8b` 13 stage 全 success，判据行齐全；同 sha 两次构建连续全绿 |
| ③ 素材层四条成立 | ✅ |
| ④ 剩余问题只有 REQUIRES_HUMAN | ✅ H-001 / U-003 / W7-A2 四条 `[R]`（无实现路线取舍） |

⇒ `done=true` / `remaining=0` / `remaining_ids=[]`。

**`needs_human` 只列真正只能由人做的事**：H-001（生产机写操作）、U-003（真实 Paper + 真实客户端握手）、
W7-A2 的 4 条 `[R]` 级 unproven。**实现路线的选择不属 REQUIRES_HUMAN** —— 用户已授权由 AI 决定；
本轮若仍有取舍（例如 G-CACHE 的修法），已在 §7 说明所选路线与理由，不写进 `needs_human`。
