# 真实 Paper 运行时冒烟（Runtime Smoke）

## 为什么需要它

本仓按设计**只含 `plugins/`、`docs/`、`tools/`，不含 `server/`**：生产机的服务端目录
（`server/`、世界、`.env`、第三方插件 jar）不进仓库。后果是：

- 全仓 1106 个测试都是**进程内**测试 —— H2 内存库 + 桩 Bukkit + 桩调度器。
  它们能证明「逻辑在纸面上对」，**不能**证明「真 Paper 加载这 25 个 jar 之后还能起来」。
- `plugin.yml` 的 `depend` / `softdepend` 链、`libraries:` 运行期依赖下载、
  跨插件 `ServicesManager` 解析、`onEnable` 里的启动对账与 fail-closed 断言、
  `/reload` 之外的插件装载顺序 —— 这些**只有真实服务端才会执行**。
- 于是本仓有 4 条长期挂着 `[R]` 级 unproven：
  G-205（启动对账需真实 Bukkit）、G-207（计数器内存态重启会丢当天场次）、
  G-208（启动 fail-closed 未在真实 Paper 验证）、G-209（20 tick 是表现层推理值）。

本冒烟脚本做的是**最基本也最值钱的一步机械化**：把「能不能干净地起来」变成
一条可复现、带机械判据的命令。「起不来」也是有效结论 —— 它把「未验证」变成「已知卡点」。

## 怎么跑

### 方式二：本地 / 生产机直接跑

```sh
sh scripts/run-paper-smoke.sh                 # 全量：下载 Paper + 插件 + assemble + 启动
sh scripts/run-paper-smoke.sh --skip-build    # 复用已有 build/libs（本地调试）
sh scripts/run-paper-smoke.sh --timeout 600   # 覆盖「等 Done( 」的上限秒数
```

脚本在 `mktemp -d` 的临时目录里建服务端，**不在仓库里生成 `server/`**，
跑完打印临时目录路径，日志留在那里供诊断。

## 判据行

冒烟在 stdout 末尾打印一组机器可读的判据行：

| 判据行 | 含义 |
| --- | --- |
| `PAPER_SMOKE_GATE=GREEN\|RED` | 总判据。GREEN 的三个条件见下 |
| `PAPER_STARTED=YES\|NO` | 日志里是否出现 **`Done (`**（Paper 完成启动的标志行） |
| `PAPER_PLUGINS_ENABLED=<n>` | 25 个自研插件里，日志出现 `Enabling <Name>` 的个数，并列出 enabled / 未 enabled 清单 |
| `PAPER_OWN_ERRORS=<n>` | **自研插件相关**的 `Exception` / `Error` / `Caused by:` 行数（按插件名或 `cn.originalrpg` 归属） |
| `PAPER_COMMUNITY_ERRORS=<n>` | **社区插件**的异常行数，单独计数 |
| `PAPER_UNATTRIBUTED_ERRORS=<n>` | 两种归属都不命中的异常行数（Paper 自身 / 库），仅报告 |
| `SMOKE_LOCK_SELFTEST=PASS=<n> FAIL=<m>` | 锁清单判据的**负向夹具**自测（10 组变异必须都红在预期原因上） |
| `SMOKE_LOCK_CHECK=PASS\|FAIL` | 锁清单静态预检结果 |
| `SMOKE_LOCK_ENTRIES=<n>` / `SMOKE_LOCK_LOCAL=<n>` | 清单条数 / 其中取本地 vendor 文件的条数 |
| `SMOKE_LOCK_PROBLEMS=<n>` | 预检发现的问题条数（`>0` 即 FAIL） |

**GREEN 的条件（三条缺一不可，脚本不对任何一条放宽）**：

1. `PAPER_STARTED=YES`
2. 25 个自研插件**全部** enabled（`PAPER_PLUGINS_ENABLED=25`）
3. `PAPER_OWN_ERRORS=0`

**社区插件异常不判 RED**：它们不是本仓代码，第三方插件在真实 Paper 上的
版本漂移与本仓无关；但脚本必须把它们逐条打印出来，避免「用社区插件异常掩盖自研插件异常」。

退出码：`0` = GREEN，`1` = RED（判据行已打全），`2` = 用法错误或**前置检查失败**
（缺文件 / 锁清单预检不过 —— 这类问题在下载任何东西之前就红，不浪费 10 分钟云上时间）。

## 脚本实际做了什么

0. **锁清单静态预检（不碰网络，毫秒级）**：`scripts/check-smoke-lock.py`。
   先跑 `--selftest` —— 10 组变异（local 文件不存在 / sha512 不符 / 来源重复 / 来源缺失 /
   sha512 形态错 / file 或 name 重复 / 清单空 / 清单不是 JSON）必须**都红在预期原因上**；
   再查真实清单。顺序有意如此：只查真实清单只能证明「现在没报错」，
   不能证明「判据还认得出错」。
   加这一步的直接原因是 W36 的事故：AuthMe 一条把自改版的 sha512 配了公网的 url，
   纯静态可查的错误却花了一次 10 分钟的云上冒烟才发现。
1. **解析 Paper 26.2 最新 STABLE build**：读 `https://fill.papermc.io/v3/projects/paper/versions/26.2/builds`，
   取最新 STABLE 的 `.downloads["server:default"].url` 与 `.checksums.sha256`。
   JSON 用 `python3` 解析（门禁镜像**没有 jq**），不是硬编码 build 号，也不跳过校验。
2. **下载并校验 sha256**，不匹配即 RED（先落缓存，复跑命中缓存仍重新核对 sha256）。
3. **下载 15 个社区插件并逐个校验 sha512**：清单在
   `scripts/runtime-smoke-plugins.lock.json`，来源是 W33 派单 goal 里那份内嵌清单；
   其中 5 条从仓库 `libs/vendor/**` 取本地文件。
   W36 更正：AuthMe 一条原为「Sivita 自改版的 sha512 + 公网 Modrinth 的 url」——
   两个来源对不上，必然校验失败（实测 sn=cnb-kus-1k4djem3f：14/15 OK，只有 AuthMeReloaded 红）。
   已改为 `local` 条目，**校验值一个字节没动**。溯源与更新方式见 `libs/vendor/README.md`。
4. **`sh scripts/fetch-deps.sh` + `sh scripts/check-file-deps.sh`（在 gradle 之前）**。
   ⚠️ 这一步**必须有**：干净克隆里 `libs/*.jar` 不在版本控制（`.gitignore` 忽略），
   必须由 `fetch-deps.sh` 拉；十门的两处编译门都在 gradle 前跑它
   （`.cnb.yml:179` 与 `:458`）。本 harness 最初漏了这一步，于是 `assemble` 在
   `rpg-classes` 报 `package fr.xephi.authme.api.v3 does not exist` / `symbol: variable AuthMeApi`
   （实测 `sn=cnb-3gl-1k4dlibvp`）。
   **规矩：凡是要跑 gradle 的地方，都要先 `fetch-deps` + `check-file-deps`。**
   「本地能编」不等于「干净克隆能编」—— 本地 `libs/` 里那些 jar 早就下过了。
5. `./gradlew --no-daemon assemble`，把 25 个 `plugins/*/build/libs/*.jar`（**排除 `-sources.jar`**）
   装进临时目录的 `plugins/`。模块 → 插件名的对应关系由
   `scripts/gen-runtime-smoke-own-plugins.py` 从 `plugin.yml` 机械生成到
   `scripts/runtime-smoke-own-plugins.txt`。
6. 写最小 `server.properties`：`online-mode=false`、`max-players=1`、
   `level-type=minecraft\:flat`、`spawn-protection=0`、`view-distance=4`、
   `simulation-distance=4`、`allow-nether=false`、`allow-end=false`；`eula=true`。
7. `java -Xmx2G -jar paper.jar --nogui` 后台启动（stdin 接 FIFO，避免读到 EOF 直接关控制台），
   轮询日志等 **`Done (`**，上限 300 秒；然后写 `stop` 优雅停服，超时再 kill。
8. 分类统计异常行并打印判据行。

**各阶段耗时**：脚本每个阶段都打一行 `[smoke] ---- <阶段>（自脚本启动 <n>s）----`
（shell 侧用 `tick()`，python 侧用 `mark()`）。加它的原因：这个 stage 实测能跑到
**51 分钟**（`sn=cnb-3gl-1k4dlibvp`），没有分段时间就只能猜时间花在哪一步。
调优前先看这些行，不要凭直觉改。

## 独立校验（不是自证）

- Paper jar 的 sha256 由 `sha256sum` 本地重算后与 API 返回的 `checksums.sha256` 对比。
- 社区插件 sha512 逐个重算后与锁清单对比。
- 这两步**任何一条不过都直接 RED**，不存在「下载失败就拿旧文件凑」的分支
  （缓存分支也要求 sha256/sha512 命中）。

## 已知边界（明确不覆盖的东西）

1. **不覆盖真实客户端握手**。`online-mode=false` 且不接客户端 ⇒ 登录、资源包下发、
   皮肤、输入法/IME、UI 交互、原版 K/I/T 键这些只有真人客户端才能验的路径**没被证明**。
   纯原版客户端能否真握手进服仍是 `[H]`。
2. **不覆盖需要生产机世界的场景**。临时目录是全新空世界（flat）⇒ 已存在的档案、
   已有经济账本、跨赛季数据、生产机的既有区块与实体都不在场。
   G-207（计数器内存态重启会丢当天场次）这类**需要「带状态重启」**的断言，本冒烟只启动一次、
   停一次，因此**不足以判定**；它只是提供了做该实验的底座。
3. **不覆盖长稳态运行**。只跑到 `Done (` 再停 ⇒ TPS/MSPT 稳定性、内存泄漏、
   长期调度漂移、20 tick 预警窗口的真实可读性（G-209）都不在覆盖范围。
4. **不覆盖多人 / 并发**。`max-players=1`、无真人 ⇒ 多人竞争、跨服 Session Lease、
   市场并发这些仍由进程内测试与生产机压测负责。
5. **社区插件版本漂移仍是风险**。锁清单固定的是 2026-10 的构建；
   上游删构建或改 artifact 会让下载失败（那时是 RED，不是静默降级）。这是有意的：
   宁可红，也不引入「看起来跑过了但装的不是同一份」。
6. **构建时长**。`assemble` 是 26 个子工程，加上 60MB+ Paper 下载，
   在慢网络上可能接近 20 分钟；脚本的 `Done (` 等待窗口是 300 秒，
   而**下载**不受该窗口保护（受 `curl --max-time 900`）。CI 超时要按仓库实际预算调整。
7. **G-205 / G-208 只能算部分推进**：脚本能证明「启动对账没抛异常」「fail-closed
   没有阻止启动」，但**反向**（故意把配置改坏，验证它**确实** fail-closed）
   需要实验分组，本脚本不做 —— 不做就是不做，不写成「已验证」。

## 定位

冒烟是**独立**的运行期验证，**不在**编译/CI 门禁链上，按需手动触发。
理由：冒烟要外网下载 60MB+ Paper 服务端并真跑 JVM，分钟级起步；
它回答的是「插件装上去能不能真起来」这个问题，与「代码写得对不对」是两层，判据互不影响。

## 维护

- 改插件清单（新增 `plugins/rpg-*`）后：重跑
  `python3 scripts/gen-runtime-smoke-own-plugins.py`，它会在数量不等于 25 时非零退出。
- 改社区插件版本后：更新 `scripts/runtime-smoke-plugins.lock.json` 的
  `file` / `sha512` / `url|local` 三元组，**必须来自实际下载后重算的值**。
- `local` 条目指向的文件必须真的在 `libs/vendor/` 里**入库**
  （`.gitignore` 已用 `!libs/vendor/*.jar` 放行），并在 `libs/vendor/README.md`
  登记「用途 / 引用方 / 生产机来源 / sha1」。
  只把文件放进工作区而不入库，干净克隆/CI 里必然是 RED —— 这类错误只有实跑才暴露。
