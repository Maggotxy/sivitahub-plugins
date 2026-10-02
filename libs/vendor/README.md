# libs/vendor —— 无法公开获取的编译期依赖（vendor 二进制）

这两个 jar 是 **Sivita 自改版**，公开源不存在，只能从生产机取回后入库。
它们只用于 `compileOnly`（编译期），**不参与运行时加载**（运行时由服务器 plugins 目录提供）。

| 文件 | 引用方 | 生产机来源 | sha1 |
|---|---|---|---|
| `MyPet-4.0.0-Sivita.jar` | `plugins/rpg-pets/build.gradle.kts` | `lamb:/home/lamb/minecraft/server/vendor/mypet/MyPet-4.0.0-Sivita.jar` | `4a17f3b5511edba5a39c813fcd4ad68bfd904957` |
| `worldguard-bukkit-7.0.18-Sivita.jar` | `plugins/rpg-hub/build.gradle.kts` | `lamb:/home/lamb/minecraft/server/runtime/plugins/worldguard-bukkit-7.0.18-Sivita.jar` | `10e328f3238699ea9d9f866fba3acdb06d416d9f` |

**更新方式**（只有人类能做，CNB 进不了内网）：
```bash
scp lamb:/home/lamb/minecraft/server/vendor/mypet/MyPet-4.0.0-Sivita.jar libs/vendor/
scp "lamb:/home/lamb/minecraft/server/runtime/plugins/worldguard-bukkit-7.0.18-Sivita.jar" libs/vendor/
sha1sum libs/vendor/*.jar    # 与上表核对，不一致就把上表一起改掉并在 PR 里说明
```

**缺失时的行为**：`sh scripts/fetch-deps.sh` **不下载**这两个 jar，但会检查存在性，
缺失时以非零码退出并打印本文件的路径。也就是说干净克隆缺少 vendor jar 时，
门禁会在编译前就明确失败，而不是等到 `compileJava` 报“package 不存在”。

其余 7 个有公开源的编译期依赖（含 worldedit 的 core + bukkit 两个 artifact）由
`scripts/fetch-deps.sh` 拉取到 `libs/`，**不入库**（`.gitignore` 已忽略 `libs/*.jar` 与
`libs/vendor/*.jar`）。清单与校验值见该脚本，机械门禁见 `scripts/check-file-deps.sh`
与 `doc/cnb/decisions-locked.md` L-009。
