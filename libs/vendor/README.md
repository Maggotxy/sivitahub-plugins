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

其余 6 个有公开源的编译期依赖由 `scripts/fetch-deps.sh` 拉取，**不入库**（见 `doc/cnb/gap-registry.md` §F）。
