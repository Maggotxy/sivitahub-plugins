# libs/vendor —— 无法从公开源获取的 vendor 二进制

这些 jar 是 **Sivita 自改版**或**上游未发布构建**，公开源不存在（或与生产机上的那份不一致），
只能从生产机取回后入库。

**必须入库**：`.gitignore` 里 `libs/*.jar` 之后跟了一条 `!libs/vendor/*.jar` 放行。
干净克隆缺少它们时，门禁会在编译前就明确失败，而不是等到 `compileJava` 报“package 不存在”。

同一份文件可能同时服务两个用途：

| 用途 | 消费者 | 缺了会怎样 |
|---|---|---|
| **编译期** | `plugins/*/build.gradle.kts` 的 `compileOnly(files(rootProject.file("libs/vendor/...")))` | `compileJava` 报 package 不存在 |
| **运行时** | `scripts/runtime-smoke-plugins.lock.json` 里的 `local` 条目 —— 真实 Paper 冒烟时装入服务端 `plugins/` | 冒烟在「社区插件就绪」这一步 RED |

## 清单

| 文件 | 用途 | 引用方 | 生产机来源 | sha1 |
|---|---|---|---|---|
| `EssentialsX-2.22.1-dev-382de4f.jar` | 编译期 + 运行时 | `plugins/rpg-essentials/build.gradle.kts` | `lamb:/home/lamb/minecraft/server/vendor/essentialsx/EssentialsX-2.22.1-dev-382de4f.jar` | `f546f02e4ca9af07fa503221df487d9d1aecd30a` |
| `MyPet-4.0.0-Sivita.jar` | 编译期 + 运行时 | `plugins/rpg-pets/build.gradle.kts` | `lamb:/home/lamb/minecraft/server/vendor/mypet/MyPet-4.0.0-Sivita.jar` | `4a17f3b5511edba5a39c813fcd4ad68bfd904957` |
| `worldguard-bukkit-7.0.18-Sivita.jar` | 编译期 + 运行时 | `plugins/rpg-hub/build.gradle.kts` | `lamb:/home/lamb/minecraft/server/runtime/plugins/worldguard-bukkit-7.0.18-Sivita.jar` | `10e328f3238699ea9d9f866fba3acdb06d416d9f` |
| `BetterHud-bukkit-2.1.0-SNAPSHOT-447.jar` | 编译期 + 运行时 | `plugins/rpg-integrations/build.gradle.kts` | `lamb:/home/lamb/minecraft/server/runtime/plugins/BetterHud-bukkit-2.1.0-SNAPSHOT-447.jar` | `5ce055ae5fede854840806d45e1c7dd1705df3c9` |
| `AuthMe-6.0.0-Sivita-Paper.jar` | **仅运行时** | 无 `compileOnly` 引用；冒烟锁清单 `local` 条目 | `lamb:/home/lamb/minecraft/server/vendor/authme/AuthMe-6.0.0-Sivita-Paper.jar` | `698030ba9309a8ab6af28dcbdde280911ebb9c94` |
| `AuthMe-6.0.0-Sivita.patch` | 溯源（不是 jar） | 人读 | `lamb:/home/lamb/minecraft/server/vendor/authme/sivita-authme-6.0.0.patch` | 见下 |

> **`AuthMe-6.0.0-Paper.jar` 与 `AuthMe-6.0.0-Sivita-Paper.jar` 是两份不同的东西**，别混：
> - 编译期（`rpg-classes` / `rpg-guide` / `rpg-skills`）用的是 **上游 6.0.0**，
>   由 `scripts/fetch-deps.sh` 下到 `libs/AuthMe-6.0.0-Paper.jar`（不入库），sha1 `aefb475df26c72fe39c48e0ef9daf5e8b0574e28`。
> - 运行时（真实 Paper 冒烟）用的是**这份自改版** `libs/vendor/AuthMe-6.0.0-Sivita-Paper.jar`。
> 生产机 `runtime/plugins/` 里它叫 `AuthMe-6.0.0-Paper.jar`（sha512 与自改版一致，已实测核对），
> 锁清单里为求 `file == basename(local)` 一致写成 `AuthMe-6.0.0-Sivita-Paper.jar`。

## AuthMe 自改版的溯源

它是**可复现**的：对上游 `6.0.0` 打一个补丁再构建，不是不可考的黑盒。

- 上游：`https://github.com/AuthMe/AuthMeReloaded.git`，tag `6.0.0`，
  commit `2ff4970ad405d083f858198a1cd0a3e9f6bda587`（构建脚本会 `rev-parse` 校验这个值）
- 补丁：`AuthMe-6.0.0-Sivita.patch` —— 13 个文件（4 个主代码 + 4 个测试在 `authme-core`，
  2 个主代码 + 2 个测试在 `authme-paper-common`，外加 `authme-paper-common/pom.xml`）
- 改动意图（引自生产机同目录 README）：让 Paper 的登录/注册对话框保持紧凑且确定；
  密码输入长度由 AuthMe 的 `passwordMaxLength` 推导；让「加入前对话框失败」与
  「命令登录失败」共用同一套 captcha 与临时封禁计数器；并附了这些路径的回归测试
- 构建身份可机检：产物 `plugin.yml` 里必须是 `version: 6.0.0-bSIVITA1`
- 构建配方（生产机 `build-authme.sh` 的做法，未入库因为路径绑定在 `server/` 布局上）：
  Maven `3.9.11`（sha512 `bcfe4fe3…c9978`），
  `mvn -pl authme-paper -am -PskipLongHashTests,skipJavadocGeneration clean package`
  并带 `-Dproject.buildNumber=SIVITA1 -Dproject.build.outputTimestamp=<commit 时间戳>`
  —— 固定时间戳是为了让产物**逐字节可复现**
- ⚠️ 换掉运行时 jar **不会**热重载 AuthMe：生效要等下一次计划内的整服重启

## 更新方式

```bash
# 只读取回（本地 agent 能进内网，CNB 进不了）
scp lamb:/home/lamb/minecraft/server/vendor/authme/AuthMe-6.0.0-Sivita-Paper.jar libs/vendor/
scp lamb:/home/lamb/minecraft/server/vendor/authme/sivita-authme-6.0.0.patch libs/vendor/AuthMe-6.0.0-Sivita.patch
sha1sum libs/vendor/*.jar          # 与上表核对
sha512sum libs/vendor/AuthMe-6.0.0-Sivita-Paper.jar
# 上表的 sha1 与 scripts/runtime-smoke-plugins.lock.json 里的 sha512 都要同步改，
# 并在 PR 里说明为什么换。
```

## 其余编译期依赖

另有 7 个**有公开源**的编译期依赖（含 worldedit 的 core + bukkit 两个 artifact）
由 `scripts/fetch-deps.sh` 拉到 `libs/`，**不入库**（`.gitignore` 已忽略 `libs/*.jar`）。
清单与校验值见该脚本，机械门禁见 `scripts/check-file-deps.sh` 与 `doc/cnb/decisions-locked.md` L-009。
