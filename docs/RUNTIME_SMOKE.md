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

### 方式一：CNB 事件 `api_trigger_smoke`

`api_trigger_smoke` 与本仓十门（`push` / `api_trigger_gate`）**完全分离**：
十门是编译 + 测试 + 打包 + 静态门禁，冒烟要下载 60MB+ 的 Paper 服务端并真跑一遍 JVM，
十分钟起步。把它塞进十门只会让每次 push 都变慢，并且一旦 Paper 下载源抖动就整门假红。

```sh
cnb build start-build --repo xzqkjmnbvc2026/sivitahub \
  --branch main --event api_trigger_smoke --sync false
```

内联 config 触发（不依赖分支上的 `.cnb.yml`，用于验证脚本改动）：

```sh
cnb build start-build --repo xzqkjmnbvc2026/sivitahub \
  --branch agent/W35-A1-runtime-smoke-harness --event api_trigger_smoke --sync false \
  --config '
$:
  api_trigger_smoke:
    - name: Paper 运行时冒烟
      docker:
        image: eclipse-temurin:25-jdk
      stages:
        - name: 运行时冒烟
          script: |
            set -e
            chmod +x scripts/*.sh gradlew
            sh scripts/run-paper-smoke.sh
'
```

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

**GREEN 的条件（三条缺一不可，脚本不对任何一条放宽）**：

1. `PAPER_STARTED=YES`
2. 25 个自研插件**全部** enabled（`PAPER_PLUGINS_ENABLED=25`）
3. `PAPER_OWN_ERRORS=0`

**社区插件异常不判 RED**：它们不是本仓代码，第三方插件在真实 Paper 上的
版本漂移与本仓无关；但脚本必须把它们逐条打印出来，避免「用社区插件异常掩盖自研插件异常」。

退出码：`0` = GREEN，`1` = RED（判据行已打全），`2` = 用法错误。

## 脚本实际做了什么

1. **解析 Paper 26.2 最新 STABLE build**：读 `https://fill.papermc.io/v3/projects/paper/versions/26.2/builds`，
   取最新 STABLE 的 `.downloads["server:default"].url` 与 `.checksums.sha256`。
   JSON 用 `python3` 解析（门禁镜像**没有 jq**），不是硬编码 build 号，也不跳过校验。
2. **下载并校验 sha256**，不匹配即 RED（先落缓存，复跑命中缓存仍重新核对 sha256）。
3. **下载 15 个社区插件并逐个校验 sha512**：清单在
   `scripts/runtime-smoke-plugins.lock.json`，来源是 W33 派单 goal 里那份内嵌清单
   （逐字节复制的校验值，不改动）；其中 4 条从仓库 `libs/vendor/**` 取本地文件。
4. `./gradlew --no-daemon assemble`，把 25 个 `plugins/*/build/libs/*.jar`（**排除 `-sources.jar`**）
   装进临时目录的 `plugins/`。模块 → 插件名的对应关系由
   `scripts/gen-runtime-smoke-own-plugins.py` 从 `plugin.yml` 机械生成到
   `scripts/runtime-smoke-own-plugins.txt`。
5. 写最小 `server.properties`：`online-mode=false`、`max-players=1`、
   `level-type=minecraft\:flat`、`spawn-protection=0`、`view-distance=4`、
   `simulation-distance=4`、`allow-nether=false`、`allow-end=false`；`eula=true`。
6. `java -Xmx2G -jar paper.jar --nogui` 后台启动（stdin 接 FIFO，避免读到 EOF 直接关控制台），
   轮询日志等 **`Done (`**，上限 300 秒；然后写 `stop` 优雅停服，超时再 kill。
7. 分类统计异常行并打印判据行。

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

## 与十门的关系

`api_trigger_smoke` **不接进** `push` / `api_trigger_gate` 的十门。
理由：十门是「提交即可判定」的静态/单元层门禁，秒级到分钟级；
冒烟要外网下载 + 真跑服务端，属于**运行期证据采集**，按需触发。
两者的判据行互不影响，冒烟红了不会让十门红，反之亦然。

## 维护

- 改插件清单（新增 `plugins/rpg-*`）后：重跑
  `python3 scripts/gen-runtime-smoke-own-plugins.py`，它会在数量不等于 25 时非零退出。
- 改社区插件版本后：更新 `scripts/runtime-smoke-plugins.lock.json` 的
  `file` / `sha512` / `url|local` 三元组，**必须来自实际下载后重算的值**。
