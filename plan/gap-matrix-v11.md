# 全量机制矩阵 v11（gap-matrix-v11）

> verdict: done=false remaining=1
> basis: f014b447
> basis-substring: f014b447
> 生成者：CNB CodeBuddy NPC，任务 `W25-A1-verdict-v11`（当前 main 的最终复验轮 v11）。
> 实测 `git rev-parse HEAD` == `git rev-parse origin/main` == `f014b447fa5ac51170fc9450009ec6c901633487`（两者**相等**）。
> M8（本轮采信的十门证据 sha）= `f014b447`（= 判定 basis 本身）；本分支自身不含构建输入，见 §8。
> 状态取值：`已接线` / `声明未接线` / `存疑` / `已废弃` / `真实缺口`。

## 0. 本轮结论（先给判定，再给证据）

| 项 | v10 判定（basis=1ace5ab） | v11 判定（basis=f014b447） | 依据 |
|---|---|---|---|
| **G-CACHE-STAMP-BEFORE-LOCK**（取号点仍在锁外） | 不存在（v10 basis 早于 #73/#75） | **真实缺口（可派工）** | §7：5 个发布点的 `inTransaction` 都是「**先** `nextCommitVersion()` → 再 `operation.apply(connection)`」，而 `SELECT ... FOR UPDATE` 发生在 `apply` 内部 ⇒ 「取号顺序 == 锁顺序」**未实现**；#75 新增的回归测试 `lateCommitStampCannotRollBackTheReadCache()` 在**同一 sha** 的下游构建里实测 FAILED（`expected: <20> but was: <19>`） |
| **G-302-RESIDUAL 系列**（含 -2/-3/-4） | 清零 | **仍清零** | §6：`SOURCE_STRING_TESTS=0 / SOURCE_STRING_GATE=GREEN / EXIT=0`；4 类方法体复核 + 三组负向夹具（⑤⑥⑦）现场 RED → 删除 → GREEN；反例未被判红；规格规模 469/79/3 ≥ v8 的 466/78/2 |
| 十门 @ basis | 十门 @ 1ace5ab，两次同 sha 全绿 | **十门 @ f014b447 全绿（权威 sn=cnb-4f2-1k46ad3pv）**，但**同 sha 的另一构建 test stage RED** | §8：判据行逐条回读；⚠️ 同 sha 的 push 构建 `cnb-ok2-1k46acmji` test stage **error** |
| `G-FLAKY-GATE` | 闭环 | **回退（复发，且落在 #75 新测试上）** | §7.3：改后**不是**连续全绿 —— f014b447 上 `cnb-ok2-1k46acmji` RED / `cnb-4f2-1k46ad3pv` GREEN |
| 素材层四条 | 成立 | **成立（复跑一致）** | §1–§5 |
| 其余机制（v10 的 39 条矩阵） | 已接线 | **已接线（复跑一致）** | §6、§7 |

⇒ 差集矩阵「声明未接线」= 0 条；但存在 **1 条可派工的实现缺口**（`G-CACHE-STAMP-BEFORE-LOCK`）；
十门在 M8=`f014b447` 上**权威构建全绿**（10/10 判据行齐全）；
素材层四条成立；剩余问题除 `REQUIRES_HUMAN` 外还有 1 条代码缺口。
⇒ `plan/verdict-v11.json`：`done=false`、`remaining=1`（与本节 `> verdict:` 行严格一致）。

⚠️ 本轮特别验证的前提（都属于「不能只看计数 / 不能只看门禁 GREEN」）：
1. **「门禁 GREEN」不等于「判据没有过宽的豁免」，也不等于「缺陷已闭环」**：
   权威 `api_trigger_gate` 构建判据行全 GREEN、`FAILURE_XML_COUNT=0`；
   但**同一 sha** 的另一构建 test stage RED，失败用例正是 #75 为关闭该窗口而新增的回归测试。
   ⇒ 生产代码里那个窗口**仍然可达**（§7）。
2. **「改后连续全绿」需要实测，不能引用上一轮的结论**：v10 写「同 sha 两次连续全绿」是
   在 `1ace5ab` 上成立的；本轮 basis 前进到 `f014b447` 后**不再成立**。
3. **「计数下降」不等于「缺口清零」**：§6 用方法体复核 + 负向夹具证明 G-302 系列的
   `SOURCE_STRING_TESTS=0` 是方法体事实，不是计数错觉。

## 1. (a) 素材层 —— 零消费者=0（逐条给调用点原文）

口径：**「脚本存在」不等于「脚本被调用」**。`scripts/` 下 21 个 `.sh`/`.py` 逐个查真实调用点。

| # | 脚本 | 真实调用点原文 |
|---|---|---|
| 1 | `scripts/selftest-gates.sh` | `.cnb.yml:71` `sh scripts/selftest-gates.sh` |
| 2 | `scripts/dump-test-results.py` | `.cnb.yml:289`（注释）`python3 scripts/dump-test-results.py --min-modules 26`；`selftest-gates.sh` 真跑 |
| 3 | `scripts/check-plugin-jars.py` | `.cnb.yml:387` `python3 scripts/check-plugin-jars.py /tmp/jars.txt` |
| 4 | `scripts/list-env-tests.sh` | `.cnb.yml:224`（注释）/ `:297` `sh scripts/list-env-tests.sh > /tmp/env-methods.txt` |
| 5 | `scripts/check-ddl-consumers.sh` | `.cnb.yml:89` `sh scripts/check-ddl-consumers.sh` |
| 6 | `scripts/check-source-contract.sh` | `selftest-gates.sh:285/:449` 真跑；被 35 个 `*ContractTest` 经 `ProcessBuilder` 真跑（例 `EquipmentExitPathReachabilityTest.java:334`） |
| 7 | `scripts/check-source-string-tests.sh` | `selftest-gates.sh` §4d 夹具必须 `exit=1` / 真实仓必须 `exit=0`（本轮实测见 §6.3） |
| 8 | `scripts/check-config-fallbacks.sh` | `EnhancementFallbackContractTest.java:35` `root.resolve("scripts/check-config-fallbacks.sh")` + `ProcessBuilder` 真跑 |
| 9 | `scripts/check-trusted-menu-holders.sh` | `TrustedMenuBoundaryContractTest.java:30`（注释引用）+ 真跑路径 |
| 10 | `scripts/check-repo-artifacts.sh` | `selftest-gates.sh:719` `sh scripts/check-repo-artifacts.sh` |
| 11 | `scripts/check-resource-pack.sh` | `.cnb.yml:117` `sh scripts/check-resource-pack.sh` |
| 12 | `scripts/resource-pack-asset-check.py` | `scripts/check-resource-pack.sh:131` `python3 "$ROOT/scripts/resource-pack-asset-check.py" --root "$ROOT" --check closure`（另 7 处） |
| 13 | `scripts/check-asset-baseline.py` | `.cnb.yml:143` `python3 scripts/check-asset-baseline.py` |
| 14 | `scripts/gen-asset-manifest.py` | `.cnb.yml:136`（注释唯一更新路径）；`selftest-gates.sh` 真跑 `--check` |
| 15 | `scripts/asset_fingerprint.py` | `selftest-gates.sh:1105` `cp "$ROOT/scripts/asset_fingerprint.py" ...`、`:1267` 真跑 |
| 16 | `scripts/check-asset-reproducible.sh` | `.cnb.yml:171` `sh scripts/check-asset-reproducible.sh` |
| 17 | `scripts/check-asset-reproducible.py` | `selftest-gates.sh:1266` `cp ... check-asset-reproducible.py` 进夹具 |
| 18 | `scripts/install-datapacks.sh` | `selftest-gates.sh:22`（注释）/ 真跑 `--check` |
| 19 | `scripts/check-file-deps.sh` | `.cnb.yml:57`（注释）/ 真跑 |
| 20 | `scripts/fetch-deps.sh` | `.cnb.yml:179` `sh scripts/fetch-deps.sh` |
| 21 | `scripts/test-frames.py` | `selftest-gates.sh:171/:181` `python3 scripts/test-frames.py build ddl-consumer-root "$GW"` |

判定：**成立**。21/21 都至少有一个真实调用点（逐个 grep 计数均 >0），无「声明未接线」。

## 2. (b) 素材逐像素基线

```
$ python3 scripts/check-asset-baseline.py > /tmp/ab.txt 2>&1; echo "EXIT=$?"
EXIT=0
ASSET_BASELINE: 校验 252 张 PNG（清单 252 条）
  UNREGISTERED=0 MISSING=0 MODIFIED=0
ASSET_BASELINE_GATE=GREEN
```
判定：**成立**。252↔252，UNREGISTERED/MISSING/MODIFIED 全 0。

## 3. (c) provenance ↔ manifest 同集合

```
$ python3 - <<'PY'   # 归一 assets/ 前缀后比集合
manifest len 252 / prov assets len 252
manifest-only: [] 0
prov-only: [] 0
same set: True
$ python3 -c "print(prov['$counts'])"
{"ai-prompt": 10, "derived-from": 69, "generator": 129, "human-override": 1, "upstream-betterhud": 38, "upstream-vanilla": 5}
$ python3 -c "print(prov['$needsHumanDecision'])"
[]
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

CI（M8 stage-5，sn=`cnb-4f2-1k46ad3pv`）判据行原文：
```
[00:12:59   +0ms]   REPRODUCIBLE=70 MISMATCH=0 MISSING=0 UNREGISTERED=0
[00:12:59   +0ms] ASSET_REPRO_GATE=GREEN
```
判定：**成立（以 CI 为准）**。⚠️ 本容器**没有 JDK**（`command -v java/javac` 无输出），
`check-asset-reproducible.sh` 需现场 `javac` ⇒ **本轮未本地复跑**，以 M8 的 stage-5
`status=success` + 上述判据行为证据（不断言本地跑过）。

## 6. G-302-RESIDUAL 系列是否仍清零 + 判据是否被放宽

### 6.1 判据行与退出码（**重定向到文件再取 `$?`**，不走管道）

```
$ sh scripts/check-source-string-tests.sh > /tmp/sst.txt 2>&1; echo "EXIT=$?"
EXIT=0
SOURCE_STRING_TESTS=0
SOURCE_STRING_GATE=GREEN 本仓生产源码已无源码字符串断言（豁免口径见脚本头部注释）

$ sh scripts/check-source-string-tests.sh --json > /tmp/sst.json 2>&1; echo "EXIT_JSON=$?"
EXIT_JSON=0
{"hits": [], "count": 0}
```
判定：`SOURCE_STRING_TESTS=0` / `SOURCE_STRING_GATE=GREEN` / `EXIT=0`。

### 6.2 独立抽样复核 ≥4 个类（读的是什么？）

逐个读 `@Test` 方法体与全部 `Files.*` 命中行：

| 类 | 判定依据（文件:行号） | 读的是什么 |
|---|---|---|
| `DungeonTimeoutLifecycleContractTest` | `plugins/rpg-dungeons/src/test/java/cn/originalrpg/dungeons/DungeonTimeoutLifecycleContractTest.java:19`（注释里点名原用 `Files.readString`，现已删）；`grep -n 'readString\|readAllBytes\|Files\.'` 全类**只命中 :19 注释** | 真跑 `DungeonManager` + 记录型 `BukkitScheduler`（:60-:126 断言 `scheduler.callbacks/cancelled`、`instanceSessions` 代际、`instanceTimeouts.containsKey`）⇒ **行为/夹具** |
| `EquipmentExitPathReachabilityTest` | `.../rpg-items/src/test/java/cn/originalrpg/items/forge/EquipmentExitPathReachabilityTest.java:332-340`：`assertTrue(Files.isRegularFile(gate))` + `new String(process.getInputStream().readAllBytes())` + `assertEquals(0, process.exitValue())`；:313-327 `packagedYaml()` 用 SnakeYAML 真解析打包资源 | **进程 stdout（真跑 `check-source-contract.sh`）+ 真解析 YAML** |
| `SeasonAttunementSettlementWiringTest` | `.../rpg-core/src/test/java/cn/originalrpg/core/persistence/SeasonAttunementSettlementWiringTest.java:214-215`（注释说明原用 `Files.walk + readString().contains()`）；:243-250 `Files.isRegularFile(gate)` + `readAllBytes()` 取 stdout；:299 `Files.newInputStream(path)` | **进程 stdout + 真解析（newInputStream）** |
| `DungeonEntryCommitAmbiguityContractTest`（自选第 4 个） | `.../rpg-dungeons/src/test/java/cn/originalrpg/dungeons/DungeonEntryCommitAmbiguityContractTest.java:14`（注释点名原用 `Files.readString`）；:65-70 `Files.isRegularFile(gate)` + `readAllBytes()` | **进程 stdout** |

判定：4/4 读的是**测试夹具 / 进程 stdout / 真解析**，**不是**生产源码正文的字符串断言。判据未放宽。

### 6.3 反向验证判据仍有力（现场造夹具，跑完删除）

`sh scripts/check-source-string-tests.sh`（重定向取码），三组夹具各造一次：

| 夹具 | 形态 | 结果 |
|---|---|---|
| `Fixture5WrappedAssertTest.java:11` | `assertTrue(Files.readString(Path.of("plugins/…/JdbcProfileService.java")).contains("nextCommitVersion"))` | `SOURCE_STRING_TESTS=3` / `SOURCE_STRING_GATE=RED` / **EXIT=1（变红）** |
| `Fixture6ConstPathTest.java:13` | `Files.readString(Path.of(PRODUCTION))`（常量路径）+ `contains` | 同上，**EXIT=1（变红）** |
| `Fixture7TraversalTest.java:18` | `Files.walk(root.resolve("plugins/rpg-core/src/main"))` + `readString(f).contains(id)` 聚合成列表 → `anyMatch` | 同上，**EXIT=1（变红）** |

删除后复跑：
```
$ rm -rf plugins/rpg-core/src/test/java/_gatefixture
$ sh scripts/check-source-string-tests.sh > /tmp/sst_after.txt 2>&1; echo "EXIT_AFTER_DELETE=$?"
EXIT_AFTER_DELETE=0
SOURCE_STRING_TESTS=0
SOURCE_STRING_GATE=GREEN
$ git status --porcelain
（空 —— 工作区干净）
```
反例（**不**被判红）：`plugins/rpg-core/src/test/java/cn/originalrpg/core/i18n/LanguageBundleContractTest.java`
—— `:458` `Files.walk(root)` + `:465` `Files.newInputStream(file)` + `:466` `new Yaml(new SafeConstructor(options)).load(input)`
真解析 YAML（不 `readString`、不对正文做 `contains`）⇒ 三组夹具全红时它 **0 命中**。

判定：判据**真有力**（造缺陷必红、删缺陷即绿），且**没有反向过捕**（反例不被误伤）。

### 6.4 规格规模相对 v8 基线未缩水

```
$ sh scripts/check-source-contract.sh --all > /tmp/sc.txt 2>&1; echo "EXIT=$?"
EXIT=0
SOURCE_CONTRACT_CHECKS=469 SPECS=79
SOURCE_CONTRACT_SPEC_FILES=3
SOURCE_CONTRACT_GATE=GREEN
```
v8 基线 `CHECKS=466 SPECS=78 SPEC_FILES=2` ⇒ 469/79/3 **均未缩水**（`SPEC_FILES` 上升符合预期）。

### 6.5 sn=cnb-4f2-1k46ad3pv 里 §4d 的判据行（回读核对）

```
[00:10:33  +34ms]   [OK  ] 夹具①内联：存在「读生产源码 + contains」的测试 → 1 (exit=1)
[00:10:33  +30ms]   [OK  ] 夹具②间接：readString 藏 helper + 常量路径 + indexOf 先后比较 → 1 (exit=1)
[00:10:33  +29ms]   [OK  ] 夹具③豁免：读 src/test/resources 夹具 → 0（证明不误报） (exit=0)
[00:10:33  +28ms]   [OK  ] 夹具④遍历式源码扫描（walk src/main + readString + contains 聚合成列表）→ 1 (exit=1)
[00:10:33  +27ms]   [OK  ] 夹具④b豁免：遍历目录 + 真解析 YAML（不 readString/不 contains）→ 0 (exit=0)
[00:10:33  +30ms]   [OK  ] 夹具⑤：assert 块包住读取调用（assertTrue(readString(...).contains(x))）→ 1 (exit=1)
[00:10:33  +47ms]   [OK  ] 夹具⑥：Files.readString(Path.of(常量)) + contains → 1 (exit=1)
[00:10:33  +51ms]   [OK  ] 真实仓库已无此类断言 → 0 (exit=0)
[00:10:33   +2ms]   [OK  ] 真实规格目录已加载且未缩水（CHECKS=469≥466 SPECS=79≥2 GREEN）
```

## 7. ★ 核心：两条新缺陷（读缓存回滚 / 门禁假红）的闭环复核

### 7.1 「读缓存被旧快照回滚」—— 回归测试存在，但**窗口未真正关闭**

**（1）两条回归测试存在**（grep 原文）：
```
$ grep -n 'readCacheIsNotRolledBackByALateCommit\|lateCommitStampCannotRollBackTheReadCache' \
    plugins/rpg-core/src/test/java/cn/originalrpg/core/persistence/JdbcServicesTest.java
1035:    void readCacheIsNotRolledBackByALateCommit() throws Exception {
1088:    void lateCommitStampCannotRollBackTheReadCache() throws Exception {
```

**（2）5 个发布点的 `inTransaction` 顺序**（逐文件行号）：**「先 `nextCommitVersion()`、再 `operation.apply()`、再 `commit()`、提交成功才写 `versionStamp`」**：

| 服务 | 取号行 | apply 行 | commit 行 | versionStamp 行 |
|---|---|---|---|---|
| `JdbcProfileService` | :796 | :797 | :798 | :799 |
| `JdbcAscensionService` | :285 | :286 | :287 | :288 |
| `JdbcDungeonRewardService` | :318 | :319 | :320 | :321 |
| `JdbcAdminIncidentCompensationService` | :726 | :727 | :728 | :729 |
| `JdbcAdminTestService` | :297 | :298 | :299 | :300 |

**（3）#75 自报证据**：`.cnb/agent-out/W24-A1-cache-publish-order.json` 有「改动前新测试 FAIL
（`expected: <20> but was: <19>`）/ 改动后 PASS / 负向对照必红（把取号点改回 commit 之后）」三段证据。

**（4）⚠️ 但「取号顺序 == 锁顺序」这一条**在代码里**不成立**：#75 把取号点从 commit **之后**
移到了 `operation.apply(connection)` **之前** —— 而 `SELECT ... FOR UPDATE` 是在 `apply` **内部**
由 `loadForUpdate` / `loadSnapshot` 发出的：

```
$ grep -n 'FOR UPDATE' plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcProfileService.java
627:        String sql = "SELECT * FROM rpg_profiles WHERE player_uuid = ?" + (lock ? " FOR UPDATE" : "");
$ sed -n '792,799p' …/JdbcProfileService.java
    private <T> T inTransaction(SqlOperation<T> operation, long[] versionStamp) {
        try (Connection connection = dataSource.getConnection()) {
            long version;
            try {
                version = nextCommitVersion();      // ← 取号时【尚未】加行锁
                T result = operation.apply(connection);   // ← FOR UPDATE 在 apply 内部（:627）
                connection.commit();
                versionStamp[0] = version;
```
即顺序是「**取号 → 加锁 → 提交**」，而注释（:779）声称的是「**在仍持有行锁时取号**」。
两个并发事务在同一玩家上仍可：T1 取号 #100 后被调度切走（尚未加锁）→ T2 取号 #101、
拿到行锁、提交、发布 #101 → T1 恢复拿到行锁，读到的是 **T2 提交后**的行（更新），
但 T1 的号 #100 < #101 ⇒ `publish` 的 `version > existing.version()` **拒绝**这份更新的快照
⇒ `get()` 返回**更旧**的数据。窗口仍在，只是方向从「旧盖新」变成「新被旧挡」。

**（5）实测（本章最关键的一条）**：#75 的回归测试在**同一 sha `f014b447` 的下游构建上真失败了**。

```
$ cnb build get-build-logs --repo xzqkjmnbvc2026/sivitahub --sha f014b447fa5ac51170fc9450009ec6c901633487
cnb-4f2-1k46ad3pv api_trigger_gate main success f014b447     ← 权威构建（task 指定的 sn）
cnb-ok2-1k46acmji push           main error   f014b447     ← ★ 同 sha，test stage error
```
`cnb-ok2-1k46acmji` 的 test stage 日志原文（`build-runner-download-log` 回读）：
```
[00:12:54   +0ms] FAILURE_XML_COUNT=1
[00:12:54  +49ms] FAILURE cn.originalrpg.core.persistence.JdbcServicesTest#lateCommitStampCannotRollBackTheReadCache()
[00:12:54   +0ms]    message: org.opentest4j.AssertionFailedError: 取号必须在 commit 之前（持锁时），
                   缓存不得被倒置的版本号回滚（实际缓存值见左） ==> expected: <20> but was: <19>
[00:12:54   +0ms]   plugins/rpg-core  tests= 320 failed=1 errors=0 skipped=0 FAIL
[00:12:54   +0ms]   SUM  tests=1104 failed=1 errors=0 skipped=2 modules=26
[00:12:54   +0ms] SUMMARY_GATE=RED 失败=1 错误=0
[00:12:54  +0ms] TEST_EXIT=1 SUMMARY_EXIT=1 SECONDS=68
```
而**同一 sha** 的权威构建：
```
[00:17:16  +49ms] FAILURE_XML_COUNT=0
[00:17:16   +0ms]   plugins/rpg-core  tests= 320 failed=0 errors=0 skipped=0 ok
[00:17:16   +0ms]   SUM  tests=1104 failed=0 errors=0 skipped=2 modules=26
```
两者的 env 排除口径一致（`ENV_ANNOTATIONS=8` / `EXCLUDED_TAG_SEEN_IN_LOG=26`），测试总数一致
（1104 / 26 模块）⇒ **不是环境差异、不是模块漏跑**，而是同一个测试在同一个 sha 上时红时绿。

判定：**闭环不成立**。`changed_files` 与测试都在，但「窗口关闭」这一claim 被代码结构与
同 sha 的 RED 构建**双重证伪**。⇒ 新增缺口 `G-CACHE-STAMP-BEFORE-LOCK`（可派工）。

### 7.2 「门禁假红」—— test stage 配置已收敛，但**没有取得「改后连续全绿」**

`.cnb.yml` test stage 原文（行号）：
```
245:            -Dorg.gradle.workers.max=2 \
249:            test -PexcludeTags=env > /tmp/test.log 2>&1
```
同 stage 的 gradle 调用**不含** `-Dorg.gradle.parallel=true`（`:196/:353/:423` 的
`-Dorg.gradle.parallel=true -Dorg.gradle.workers.max=4` 属**其它 stage**：下载器 / compile / assemble）。
失败时抄 `<failure message>` 原文的段落存在（`FAILURE_XML_COUNT`）：
```
284:          print('FAILURE_XML_COUNT=%d' % n)
```
对照改前两次「先红后绿」（回读核对，实测 stage 级状态）：

| sha | RED | GREEN |
|---|---|---|
| `b6d38bf` | `cnb-tn4-1k4141s0e`：`error`，失败 stage = `test` | `cnb-h3n-1k414popl`：success |
| `334fb54` | `cnb-d93-1k41bqhbe`：`error`，失败 stage = `test` | `cnb-esl-1k41cpau5`：success |

**改后「连续全绿」的次数与 sn 列表（本轮实测）**：basis `f014b447` 上共有 2 个构建：

```
cnb-4f2-1k46ad3pv  api_trigger_gate  success  f014b447
cnb-ok2-1k46acmji  push              error    f014b447   ← test stage error
```
⇒ **连续全绿 = 1 次**（`cnb-4f2-1k46ad3pv`），**不是 2 次**；同一 sha 立刻出现一次 RED。
判定：**闭环不成立**（「改后同 sha 连续两次全绿」这一条在 `f014b447` 上不成立；
且这次 RED 的失败用例正是 §7.1 的 #75 回归测试 ⇒ 与 §7.1 是同一件事）。

### 7.3 与 v10 结论的差异必须写明

v10 在 `1ace5ab` 上写「同 sha 两次连续全绿」（`cnb-785-1k41j43k2` + `cnb-q65-1k41k0gtr`），
那个结论在其 basis 上成立。本轮 basis 前进到 `f014b447`（#73/#75 已合入）后**不再成立** ——
所以**不能把 v10 的「已闭环」直接沿用**。

## 8. 十门在 M8 上全绿（逐条回读核对）+ 本分支不含构建输入

### 8.1 权威构建 sn=`cnb-4f2-1k46ad3pv`

`GET /{repo}/-/build/status/cnb-4f2-1k46ad3pv` 回读：
`status=success`，`pipelinesStatus.cnb-4f2-1k46ad3pv-001.status=success`，
13 stage（Prepare / 准备下载器 / 门禁自测 / DDL 门禁 / 素材门禁 / 素材逐像素基线 /
素材可复现性 / compile / test / assemble / BeforeEnd / DebugDetection(skipped) / End）
**全 success，且耗时全非零**。

| 门 | 判据行（stage 日志原文） |
|---|---|
| Downloader | `[00:00:29] DOWNLOADER=ready` |
| GATE_SELFTEST | `[00:10:59] ===== 门禁自测汇总：PASS=132 FAIL=0 =====` / `GATE_SELFTEST=GREEN` |
| DDL_CONSUMER | `[00:12:56] DDL_CONSUMER_GATE=GREEN`（`豁免清单: 0 条已登记`） |
| DDL_REGISTRATION | `[00:12:56] DDL_REGISTRATION_GATE=GREEN`（`反向豁免清单: 0 条`） |
| CMD_REFERENCE | `[00:12:56] CMD_REFERENCE_GATE=GREEN`（`指令豁免清单: 9 条`） |
| RESOURCE_PACK | `[00:12:57] RESOURCE_PACK_GATE=GREEN` |
| ASSET_BASELINE | `[00:12:57] ASSET_BASELINE_GATE=GREEN`（`UNREGISTERED=0 MISSING=0 MODIFIED=0`） |
| ASSET_REPRO | `[00:12:59] ASSET_REPRO_GATE=GREEN`（`REPRODUCIBLE=70 MISMATCH=0`） |
| COMPILE | `[00:16:07] TASKS_DONE=28` / `BUILD_EXIT=0 SECONDS=146` / `COMPILE_GATE=GREEN` |
| TEST | `[00:17:16] TEST_GATE=GREEN`（`TEST_EXIT=0 SUMMARY_EXIT=0 SECONDS=69`） |
| SUMMARY | `[00:17:16] SUM tests=1104 failed=0 errors=0 skipped=2 modules=26` / `NO_XML modules=0 期望模块数=26` / `SUMMARY_GATE=GREEN` |
| PACKAGE | `[00:17:24] JAR_COUNT=56` / `JAR_CHECKED=25 JAR_MISSING_PLUGIN_YML=0` / `PACKAGE_GATE=GREEN` |
| SINGLE_SOURCE_OF_TRUTH | `[00:10:59] SINGLE_SOURCE_OF_TRUTH_GATE=GREEN` / `WORLD_STATE_FILES=1`（负向夹具 `=RED` / `WORLD_STATE_FILES=2`，还原后 `=GREEN`） |
| VERDICT_MATRIX_CONSISTENCY | `[00:10:59] VERDICT_MATRIX_CONSISTENCY_GATE=GREEN`（负向夹具两次 RED → 还原 GREEN） |

⚠️ **同 sha 的另一构建**：`cnb-ok2-1k46acmji`（push, main, f014b447）`status=error`，
失败 stage = `test`，`SUM tests=1104 failed=1`，失败用例
`JdbcServicesTest#lateCommitStampCannotRollBackTheReadCache()`（见 §7.1）。
⇒ 「十门在 M8 上全绿」只对**权威构建**成立；**同一 sha 并不能稳定全绿**。

### 8.2 本分支自身不含构建输入

```
$ git diff --name-only f014b447 HEAD     # 本任务提交前为空；提交后仅含本任务写入的 4 类路径
plan/**
state/**
doc/cnb/**
.cnb/agent-out/**
```
⇒ 只含 `plan/**`、`state/**`、`doc/cnb/**`、`.cnb/agent-out/**`，**无一个构建输入**。

## 9. 判定

| 条件（done=true 的四条） | 本轮 |
|---|---|
| ① 差集矩阵无「声明未接线」，且无可派工实现缺口 | ❌ 「声明未接线」= 0，但**有 1 条可派工实现缺口** `G-CACHE-STAMP-BEFORE-LOCK` |
| ② 十门在 M8 上全绿 | ⚠️ 权威构建全绿；**同 sha 另一构建 test RED** |
| ③ 素材层四条成立 | ✅ |
| ④ 剩余问题只有 REQUIRES_HUMAN | ❌ 还有 1 条代码缺口 |

⇒ `done=false` / `remaining=1` / `remaining_ids=["G-CACHE-STAMP-BEFORE-LOCK"]`。
