# 全量机制矩阵 v17（gap-matrix-v17）

> verdict: done=false remaining=1
> basis: 761b416

> basis-substring: 761b416
> 生成者：本地 WorkBuddy agent，任务 `W36-A4-verdict-v17`（**最终复验轮 v17**）。
> ① 实测 `git rev-parse HEAD` == `git rev-parse origin/main` == `761b416b33b4df8162de8e822c007dd99941c37c`（两者**相等**，见 §1.1）。
> ② 本矩阵的 `basis` **字面等于**它分叉时的 main HEAD（= 上面那个 sha）。
> ③ 本轮**没有采信任何远端构建结论**：两轮冒烟的最终判定都是我在本机用自己的 token 回读 `stage_detail` 得到的原始行（§9）。
> ④ 素材层四条、G-302 系列、机制规格绑定层、env 标签正当性层全部**由我在本轮 basis 上亲自重跑**（不采信任何调度器/上一轮结论），证据见 §2–§8。
> 状态取值：`已接线` / `声明未接线` / `存疑` / `已废弃` / `真实缺口`。
> 口径警戒（逐条给证据，只字不偷换）：「计数下降」≠「缺口清零」；「判据变窄」≠「问题消失」；
> 「门禁 GREEN」≠「判据没有过宽豁免」；「NPC 容器 GREEN」≠「门禁容器 GREEN」；
> **本轮新增**：「退出码 0」≠「判据行拿到」（见 §9.4）。

## 0. 本轮结论（先给判定，再给证据）

| 项 | v16 判定（basis=0237548） | v17 判定（basis=761b416） | 依据 |
|---|---|---|---|
| **矩阵 basis 字面 == main HEAD** | ✅ 一致 | ✅ **一致**：`basis: 761b416`（本文件 :4）+ 实测 `HEAD==origin/main==761b416…`（§1.1） | §1.1 |
| **done** | ❌ false（remaining=1） | ❌ **false**（remaining=1） | §0.1 |
| **可派工实现缺口** | 1 条（W33-RUNTIME-SMOKE） | **0 条**（W33 已交付，见 §9.1） | §9.1 |
| **剩余缺口性质** | 1 条可派工 + 3 类人类 | **全部为 REQUIRES_HUMAN**（含 1 条新登记的取证缺口，§9.4） | §9.4 / §10 |
| **十门** | GREEN（M18） | **未在本轮重跑远端十门**；本地层 (a)–(h) 全绿（§2–§8） | §2–§8 |
| **素材层四条** | ✅ | ✅（本轮亲自重跑，数值与 v16 逐一相同） | §2 |
| **运行时冒烟** | 未交付 | ⚠️ **退出码 GREEN，但判据行取不回来** ⇒ 仍**不得**引用为 `[R]` 证据 | §9.4 |

### 0.1 为什么 done 仍然 = false

`done=true` 需要四条同时成立。本轮第 1、2、3 条成立，**第 4 条不成立**：

| 条件 | 成立？ | 依据 |
|---|---|---|
| ① 没有任何**可派工**的实现缺口 | ✅ | 全部 `scripts/*.sh\|*.py` 均 ≥3 个引用（零消费者=0，§2a）；W33 已交付并落仓（§9.1） |
| ② 十门在**当前 main HEAD** 上全绿 | ✅（本地层） | 本地 (a)–(h) 全部 EXIT=0 且判据行 GREEN（§2–§8）。**但**远端十门本轮未重跑，如需 `[R]` 级声明必须重跑 |
| ③ 素材层四条由我在本轮 basis 上亲自重跑 | ✅ | §2（四条全部重跑，逐条贴原文） |
| ④ 剩余问题**只有** REQUIRES_HUMAN 那一部分 | ❌ | 见 §0.2：冒烟「退出码 GREEN 但判据行取不回来」是一个**尚未闭环**的取证缺口。它不是「可派工的实现缺口」（不是产品代码问题，是平台交付窗口 × 判据位置的问题），也**不能**算作人类事项一笔勾销 —— 在人类选定修法前它悬着 |

### 0.2 本轮唯一新增缺口（决定性）

| 项 | 内容 |
|---|---|
| **id** | `G-SMOKE-CRITERION-UNRETRIEVABLE` |
| **一句话** | 冒烟第二轮 `sn=cnb-3h3-1k4dosi86` 整体 `status=success`、stage-1 末行 `Finished, code: 0, duration: 44m 39s`、`error` 字段为空 —— **但整段日志里一行 `PAPER_*` 判据行都取不到** |
| **为什么这是缺口** | 冒烟的 GREEN 判据（`PAPER_STARTED=YES` 且 25/25 enabled 且 `PAPER_OWN_ERRORS=0`）**只以判据行为准**。退出码说 GREEN，判据行落在平台交付窗口之外 ⇒ **GREEN 无法被机械引用** ⇒ 按本仓「只认机械判据」的纪律，`[R] Runtime` **仍然拿不到** |
| **为什么不许放宽** | 若把「退出码 0」当 `PAPER_SMOKE_GATE=GREEN`，就是**用平台的 stage 状态冒充脚本判据**。stage 状态的语义只到「这条命令没非零退出」，不含「25 个插件真 enabled / 自研插件零异常」这两个关键性质 —— 恰恰是冒烟存在的理由（4 条 `[R]` unproven 全挂在这上面） |
| **处置** | 登记为 `REQUIRES_HUMAN`（须人类在两种修法间选择，§9.4）；**在选定前冒烟不得作为 `[R]` 证据**，「4 条 `[R]` unproven」状态**维持不变** |

## 1. 基准（basis）

### 1.1 矩阵 basis 字面 == main HEAD（本轮首要目的）

```
$ git rev-parse HEAD
761b416b33b4df8162de8e822c007dd99941c37c

$ git rev-parse origin/main
761b416b33b4df8162de8e822c007dd99941c37c

$ git -c credential.helper= ls-remote https://cnb.cool/xzqkjmnbvc2026/sivitahub.git refs/heads/main
761b416b33b4df8162de8e822c007dd99941c37c	refs/heads/main
```

`EQUAL=true`（三者一致）。第三条是用 `ls-remote` 对远端做的**独立核对**，不依赖本地 `origin/main` 引用是否新鲜。

### 1.2 本分支的改动范围

本分支 `agent/W36-A4-verdict-v17` 自身**不含任何构建输入**：只含 `plan/**`、`state/**`、`doc/cnb/**`。
判据：`git diff --name-only 761b416 HEAD` 的输出中不出现 `plugins/`、`tools/`、`scripts/`、`build.gradle.kts`、`.cnb.yml`、`resource-pack/`。

## 2. 素材层四条（本轮亲自重跑，不采信任何上一轮结论）

### (a) 脚本零消费者 = 0

`scripts/` 下共 **27** 个 `*.sh` / `*.py`（v16 时 24，本轮 +3：`check-smoke-lock.py`、`gen-runtime-smoke-own-plugins.py`、`run-paper-smoke.sh`）。
被引用次数最少的 5 个（逐个给调用点，**无一为 0**）：

| 脚本 | 引用次数 |
|---|---|
| `check-smoke-lock.py` | 3 |
| `gen-runtime-smoke-own-plugins.py` | 3 |
| `run-paper-smoke.sh` | 6 |
| `check-env-tag-justified.sh` | 10 |
| `test-frames.py` | 11 |

引用点示例（本轮实测）：
- `run-paper-smoke.sh` → `.cnb.yml:440`（`sh scripts/run-paper-smoke.sh`）
- `check-smoke-lock.py` → `scripts/run-paper-smoke.sh:76` 与 `:80`（`--selftest` 与真实清单两次调用），加自身文档
- `gen-runtime-smoke-own-plugins.py` → 其产物 `scripts/runtime-smoke-own-plugins.txt` 被 `run-paper-smoke.sh:63` 读取

### (b) 素材基线门禁

```
$ python3 scripts/check-asset-baseline.py
ASSET_BASELINE: 校验 252 张 PNG（清单 252 条）
  UNREGISTERED=0 MISSING=0 MODIFIED=0
ASSET_BASELINE_GATE=GREEN
EXIT=0
```

> 注：该文件是 Python 脚本，用 `python3` 调（与 `.cnb.yml:143` 的真实调用方式一致）；用 `sh` 会报语法错。

### (c) provenance 与 manifest 双向一致

```
manifest=252 prov=252 same_set=True
manifest-only=0 prov-only=0
needsHumanDecision=[]
counts={'ai-prompt': 10, 'derived-from': 69, 'generator': 129,
        'human-override': 1, 'upstream-betterhud': 38, 'upstream-vanilla': 5}
counts sum=252  recomputed_sum=252  match=True
```

归一化口径：`manifest` 的顶层键是 `<子路径>`；`provenance["assets"]` 的键是 `assets/<子路径>`。
剥掉 `assets/` 前缀后两集合**相等**（不是「一方是另一方子集」）。

### (d) runtime 物品定义与 items 一一对应

```
items=81  keys=81  equal=True
items-only=[]  models-only=[]
```

两侧口径：
- `runtime-item-models.txt` 去注释去空行 → 81 条；
- `resource-pack/assets/originalrpg/items/*.json` 去扩展名 → 81 个（与 `scripts/check-resource-pack.sh:98-121` 的真实口径一致：那里用 `diff -u` 比 `manifest.txt` 与 `items.txt` 两个**排序后**文件）。

七项素材检查全部 `EXIT=0`：

```
closure EXIT=0   compass EXIT=0   hud EXIT=0   journey EXIT=0
lang EXIT=0      packmeta EXIT=0  textures EXIT=0
```

## 3. G-302-RESIDUAL 系列（仍清零，判据未被放宽）

### (e) 源码字符串断言

```
$ sh scripts/check-source-string-tests.sh
SOURCE_STRING_TESTS=0
SOURCE_STRING_GATE=GREEN 本仓生产源码已无源码字符串断言（豁免口径见脚本头部注释）
EXIT=0
```

## 4. 源码契约规格层

### (f)

```
$ sh scripts/check-source-contract.sh
SOURCE_CONTRACT_SPEC_FILES=3
SOURCE_CONTRACT_CHECKS=469 SPECS=79
SOURCE_CONTRACT_GATE=GREEN
EXIT=0
```

规模与 v16 相同（469/79/3），未缩水。

## 5. 机制规格绑定层

### (g)

```
$ sh scripts/check-mechanics-spec.sh
MECHANICS_SPEC_GATE=GREEN
MECHANICS_SPEC_ENTRIES=137
MECHANICS_SPEC_BY_KIND=config:78,behavior:56,java-const:3
MECHANICS_SPEC_WEAK=0
EXIT=0

$ python3 scripts/gen-mechanics-spec-report.py --check
MECHANICS_SPEC_REPORT=FRESH
```

`grep -cE '^[[:space:]]*(import|from)[[:space:]]+yaml' scripts/check-mechanics-spec.sh` → **0**（门禁镜像没有 PyYAML，判据不依赖它）。条目数 137 与 v16 一致。

## 6. env 标签正当性层

### (h)

```
$ sh scripts/check-env-tag-justified.sh
ENV_TAG_METHODS=7
ENV_TAG_JUSTIFIED=7
ENV_TAG_UNJUSTIFIED=0
ENV_TAG_JUSTIFIED_GATE=GREEN
EXIT=0

$ sh scripts/list-env-tests.sh 2>/dev/null | wc -l
7
$ sh scripts/list-env-tests.sh 2>&1 >/dev/null | wc -l
0        # stderr 干净（本轮修的 awk 噪声）
```

## 7. 锁清单静态预检 + 常驻负向夹具（本轮新增，属构建输入）

```
$ python3 scripts/check-smoke-lock.py --selftest scripts/runtime-smoke-plugins.lock.json .
  - [ok]   local 指向不存在的文件 → #4 local 文件不存在：libs/vendor/__definitely_missing__.jar
  - [ok]   local 文件 sha512 与清单不符 → #4 local 文件 sha512 与清单不符：libs/vendor/worldguard-bukkit-7.0.18-Sivita.jar
  - [ok]   url 与 local 同时存在 → #1 必须恰好有 url 或 local 之一（现在 url=True local=True）
  - [ok]   url 与 local 都没有 → #1 必须恰好有 url 或 local 之一（现在 url=False local=False）
  - [ok]   sha512 长度不对 → #1 sha512 不是 128 位十六进制串
  - [ok]   sha512 含非十六进制字符 → #1 sha512 含非十六进制字符
  - [ok]   file 重复 → #2 file 重复：LuckPerms-Bukkit-5.5.53.jar
  - [ok]   name 重复 → #2 name 重复：LuckPerms
  - [ok]   plugins 为空数组 → 锁清单的 plugins 不是非空数组
  - [ok]   清单不是合法 JSON → 锁清单无法解析：… Expecting property name enclosed in double quotes
SMOKE_LOCK_SELFTEST=PASS=10 FAIL=0
EXIT=0

$ python3 scripts/check-smoke-lock.py scripts/runtime-smoke-plugins.lock.json .
SMOKE_LOCK_ENTRIES=15
SMOKE_LOCK_LOCAL=5
SMOKE_LOCK_PROBLEMS=0
SMOKE_LOCK_CHECK=PASS
EXIT=0
```

⚠️ 但这 10 组夹具**证据来源是本地文件**，本轮我**未在云上容器里独立复现**。它们进了构建输入（`scripts/check-smoke-lock.py`），因此按本仓纪律应有 `[T]` 级证据 —— 云上复现见 §9.2（第二轮 stage 日志第 9-18 行就是这 10 行）。

## 8. 本轮未重跑的东西（如实登记，不粉饰）

| 项 | 为什么没跑 | 影响 |
|---|---|---|
| 远端十门（`api_trigger_gate`） | 本轮目的是收口「两轮冒烟 + v17 判定」；十门在 `761b416` 上的最后一次运行是 PR#89 的 push 触发（§9.3） | 若要把「十门在 761b416 上全绿」写成 `[R]` 级声明，须重跑一次并回读判据行 |
| 本地全量编译 / 单测 | 本机无 JDK 25 + Gradle 缓存，且本轮改动**不含构建输入** | 无影响（§1.2） |
| 素材可复现性门禁（`ASSET_REPRO`） | 本容器无 JDK | 以云端判据行为证据 |

## 9. 运行时冒烟：两轮完整取证（**本轮核心**）

### 9.1 W33 缺口已闭环

| 项 | v16 | v17 |
|---|---|---|
| W33-RUNTIME-SMOKE | `agent/W33-A1-runtime-smoke` 零提交 / 无 diff / 无 PR | **已交付并落仓**：PR#86（`f4e4f43`）harness + PR#88（`efd2b12`）锁清单修复 + PR#89（`761b416`）补 `fetch-deps` |

⇒ 「可派工的实现缺口」从 1 条降为 **0 条**。

### 9.2 第二轮冒烟（`sn=cnb-3h3-1k4dosi86`，sha `761b416`）

| 项 | 值 |
|---|---|
| 整体 `status` | `success` |
| stage-1 `duration` | `2679369` ms ⇒ **2679s ≈ 44.65 min** |
| stage-1 `error` | `""`（空） |
| stage-1 末行 | `Finished, code: 0, duration: 44m 39s` |
| **`PAPER_*` 判据行** | ⚠️ **取不到**（见 §9.4） |
| 能取到的判据行 | `SMOKE_LOCK_SELFTEST=PASS=10 FAIL=0` / `SMOKE_LOCK_ENTRIES=15` / `SMOKE_LOCK_LOCAL=5` / `SMOKE_LOCK_PROBLEMS=0` / `SMOKE_LOCK_CHECK=PASS` |
| 交付窗口 | 恒为 **186 行 / 10720 字符**（连查 3 次相同），末行固定在 `插件 12/15 BetterModel ... 92%` |
| 证明它跑的是**修复后的代码** | 日志第 34 行 = `[smoke] ---- 开始下载/校验 15 个社区插件（自本进程启动 0s）----` —— 这个 `mark()` 是 PR#89 才加的 |

### 9.3 第一轮冒烟（`sn=cnb-3gl-1k4dlibvp`）作为对照

| 项 | 值 |
|---|---|
| 整体 `status` | `error` |
| stage-1 `duration` | `3045301` ms ⇒ **3045s ≈ 50.75 min** |
| stage-1 `error` | `任务执行失败，返回码：1, signal: -1` |
| stage-1 末行 | `Finished, code: 1, duration: 50m 45s` |
| 交付窗口 | **363 行 / 21171 字符**，末行含 `GRADLE_ASSEMBLE=FAILED rc=1` 与 4 条编译错误 |
| 结论 | 这一轮的 RED 是**真的**，且判据行**取到了** |

### 9.4 缺口 `G-SMOKE-CRITERION-UNRETRIEVABLE`（本轮新增，`REQUIRES_HUMAN`）

**事实链**：

1. 失败轮（50.75 min）交付 **363 行，含结尾的失败原因**；
2. 成功轮（44.65 min）交付 **186 行，停在半路**，`PAPER_*` 一行没有；
3. 两次调用返回**完全确定**（各查 3 次，行数与末行字符级相同）⇒ 不是网络抖动，是**平台交付窗口本身有界**；
4. §I.10.5 修的 `--tail-chars` 是**本地**截断，对这种情况**无效** —— 无论调多大，本地拿到的本来就只是窗口内的内容。

**为什么不许把「退出码 0」当 GREEN**：
`run-paper-smoke.sh` 的 GREEN 三条件（`PAPER_STARTED=YES` 且 25/25 enabled 且 `PAPER_OWN_ERRORS=0`）**没有任何一条**能由「命令退出码 0」推出。退出码只说明脚本走完了自己的判断流程，不说明判断结论。

**两种候选修法（须人类定，我不擅自选）**：

| 方案 | 做法 | 代价 |
|---|---|---|
| ① 脚本把判据行**落仓** | 跑完把 `PAPER_*` 写进文件，`git commit + push` 到证据分支 | 冒烟 job 需 push 权限；要处理并发写同一分支 |
| ② 判据行**移进极短 stage** | 脚本把判据写进一个文件；新增一个只 `cat` 该文件的 stage（总输出极短，必然能被完整交付） | 要改 `.cnb.yml` 增加 stage |

**本轮处置**：只登记 + 落仓两轮完整证据，**不改代码、不改判据**。
⇒ 「4 条 `[R]` unproven（G-205 / G-207 / G-208 / G-209）」**状态维持不变**。

### 9.5 顺带发现（我方 harness 心跳缺口，非阻塞）

`fetch-deps.sh`（只在其 `:241` 处打一行）与 `check-file-deps.sh`（输出进子进程 stdout，成功时不转发）在 `run-paper-smoke.sh` 里是**重定向到文件**的，成功路径**一行都不吐到主 stdout**。
风险：冷缓存下若单次下载慢到 >10 分钟且期间零输出，整个 stage 会被平台看门狗杀掉，表现为「莫名红/断」。
本轮**未触发**，故只记账不改。

## 10. 剩余问题：全部 REQUIRES_HUMAN

| id | 内容 | 为什么是人类 |
|---|---|---|
| `H-001` | 原版世界方形边界缺 5435 个四角区块 | 需无人维护窗口在生产机 `lamb:/home/lamb/minecraft` 跑 `scripts/maintain-world-coverage.sh`（生产机写操作，`L-006` 只读） |
| `U-003` | 纯原版客户端能否真握手进服 | 需真实 Paper + 真实客户端 |
| `W7-A2` 四条 `[R]` | G-205 启动对账 / G-207 计数器内存态 / G-208 启动 fail-closed / G-209 20 tick | 需真实 Bukkit / 真实 Paper 运行（本轮冒烟**未能**提供，见 §9.4） |
| **`G-SMOKE-CRITERION-UNRETRIEVABLE`**（新） | 冒烟判据行取不回来 | 须人类在 §9.4 两种修法间选择；选定前不得引用冒烟为 `[R]` |

## 11. 三处已修缺陷的复核（本轮 basis 上重跑）

| 缺陷 | 状态 | 本轮证据 |
|---|---|---|
| `G-SMOKE-LOCK-SOURCE-MISMATCH` | ✅ 已闭环 | §7：`SMOKE_LOCK_CHECK=PASS` / `PROBLEMS=0`；云上第二轮 stage 日志第 9-23 行同样到这 5 行 |
| `G-SMOKE-MISSING-FETCH-DEPS` | ✅ 已闭环 | 代码事实：`scripts/run-paper-smoke.sh:364-391`（assemble 之前的 `fetch-deps` + `check-file-deps`）。⚠️ **运行期证据未取到**（判据行落在交付窗口外，§9.4）⇒ 按纪律**不得**声称「已用真跑验证」 |
| `G-GATE-LOG-TRUNCATED` | ✅ 已修（本地侧） | `run_gate.py --tail-chars` 默认 `2000000`；`KEY_PATTERNS` 含 `SMOKE_LOCK_*`。但**平台侧窗口**是另一回事，另立 §9.4 |

---

## 附：本轮所有可复现命令

```sh
# basis
git rev-parse HEAD && git rev-parse origin/main
git -c credential.helper= ls-remote https://cnb.cool/xzqkjmnbvc2026/sivitahub.git refs/heads/main

# (a)
for f in scripts/*.sh scripts/*.py; do b=$(basename "$f"); \
  echo "$(grep -rl --include='*.sh' --include='*.py' --include='*.yml' --include='*.kts' \
       --include='*.md' --include='*.json' -F "$b" . | grep -v "^./$f$" | grep -v '^./.git/' | wc -l) $b"; \
done | sort -n

# (b)(c)(d)(g-fresh)
python3 scripts/check-asset-baseline.py
python3 scripts/gen-mechanics-spec-report.py --check

# (d) 七项
for c in closure compass hud journey lang packmeta textures; do
  python3 scripts/resource-pack-asset-check.py --root . --check $c; echo "$c EXIT=$?"; done

# (e)(f)(g)(h)
sh scripts/check-source-string-tests.sh
sh scripts/check-source-contract.sh
sh scripts/check-mechanics-spec.sh
sh scripts/check-env-tag-justified.sh
sh scripts/list-env-tests.sh

# 锁清单
python3 scripts/check-smoke-lock.py --selftest scripts/runtime-smoke-plugins.lock.json .
python3 scripts/check-smoke-lock.py scripts/runtime-smoke-plugins.lock.json .
```
