# 资源包

资源包面向 Minecraft Java Edition 26.2，资源格式为 `88.0`，采用现代 `min_format` / `max_format` 和 `assets/originalrpg/items` 物品模型定义。Paper 停止时运行 `./build.sh` 会校验 JSON、生成 `server/web/resourcepacks/sivita-hub-26.2.zip` 并输出客户端要求的 SHA-1；Paper 运行中会拒绝覆盖正式包，可用 `RESOURCE_PACK_OUTPUT_DIR=<临时目录> SKIP_PROPERTY_UPDATE=1 ./build.sh` 只生成候选包。

当前审核总览包含 36 张项目内生成的原创 32x32 像素素材：28 张内容素材覆盖职业武器方向、盾牌、护甲、核心材料、强化物、技能书、拉弓帧、独立弩、独立三叉戟、硫灵锻纹、传承蓝图和旅者罗盘；另有 8 张为新手旅程逐步绘制的专属图标，分别表达主城、职业、五维、探索、狩猎、锻造、槽位揭示与副本，不再借用无关掉落物。六张旧精英/Boss 方块头已移出生产目录并保留在拒绝档案，稀有怪改走完整骨骼模型。八张旅程图现在还通过 `originalrpg:journey` 字体复用到 `/rpghelp` 进度条、当前步骤标题与低频侧栏，不增加实体或粒子包；仅在客户端确认资源包成功加载后显示，未加载时回退到 `✓ / ◆ / ·` 与普通文字。旅者罗盘另由 `GenerateCompassFrames.java` 在构建时派生 32 张转针帧；背包、热栏与手持全部沿用 26.2 的 `minecraft:compass` 角度分发和 `spawn` 目标，审核过的静态图只在角度无效时兜底，因此 `Player#setCompassTarget` 导航不会被自定义材质破坏。`GenerateHudAtlas.java` 还会构建血月、法力、暴击、处决、锻造、防护、稀有掉落、技能、主城导航、当前目标和服务 NPC 十一枚专用 HUD 符号，避免把复杂物品图硬缩小后失真。

`tools/GenerateTextures.java` 提供可复现的离线素材基线；默认只补齐缺失文件，设置 `REGENERATE_FALLBACK_TEXTURES=1` 才会覆盖现有纹理，补位图也会用最近邻放大到统一 32x32。锻造弓、弩、三叉戟和盾牌均通过 26.2 的 `condition`、`select` 与 `range_dispatch` 接入原创图标和使用姿态；首次客户端验收仍要逐项检查拉弓、蓄力、投掷、格挡和罗盘四向转针的最终手感。

`runtime-item-models.txt` 是生产插件可能下发的 37 个 `originalrpg:*` 显示键清单。`build.sh` 会在打包前强制验证：清单与 `items/*.json` 一一对应、插件里的直接模型引用属于清单、全部 JSON 可解析、每个物品定义能闭包到模型和纹理、没有孤儿模型/纹理、全部 71 张物品 PNG 为带二值透明通道的 32x32 像素图、HUD 图集尺寸/透明度/唯一性与 U+E000..U+E00A 十一码位严格一致、八个旅程字体提供者与 U+E100..U+E107 严格一致、旅者罗盘保持 26.2 的 33 段方向分发、中英文资源键一致，以及安装中的 26.2 服务端确实声明资源格式 88。任一检查失败都不会覆盖生产 ZIP 或 SHA-1。

构建成功后会生成稳定文件名 `server/web/resourcepacks/sivita-hub-26.2.zip`，更新 SHA-1，并让线上模板与局域网运行配置使用同一个稳定资源包 UUID；两个环境仍保留各自域名或局域网 URL。黑紫方块排查与客户端验收清单见 `docs/RESOURCE_PACK_QA.md`。

`ai/` 提供 Image API 美术生产线：26 个批量提示词以及独立的旅者罗盘提示词通过 `/v1/images/generations` 生成绿幕原稿，去背后压缩成统一 32x32 调色板；单资产修改通过 `/v1/images/edits` 版本化输出。只有显式传入 `--apply` 才覆盖当前资源，具体命令见 `ai/README.md`。当前 Sub2API Key 的分组必须开启 `allow_image_generation`，否则网关会返回 403。

物品身份仍以服务端签名 PDC 和数据库 `item_id` 为准，模型键只负责显示，绝不作为交易或属性校验依据。
