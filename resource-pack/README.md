# 资源包

资源包面向 Minecraft Java Edition 26.2，资源格式为 `88.0`，采用现代 `min_format` / `max_format` 和 `assets/originalrpg/items` 物品模型定义。Paper 停止时运行 `./build.sh` 会校验 JSON、生成 `server/web/resourcepacks/sivita-hub-26.2.zip` 并输出客户端要求的 SHA-1；Paper 运行中会拒绝覆盖正式包，可用 `RESOURCE_PACK_OUTPUT_DIR=<临时目录> SKIP_PROPERTY_UPDATE=1 ./build.sh` 只生成候选包。

当前审核总览包含 36 张项目内生成的原创 32x32 像素素材：28 张内容素材覆盖职业武器方向、盾牌、护甲、核心材料、强化物、技能书、拉弓帧、独立弩、独立三叉戟、硫灵锻纹、传承蓝图和旅者罗盘；另有 8 张为新手旅程逐步绘制的专属图标，分别表达主城、职业、五维、探索、狩猎、锻造、槽位揭示与副本，不再借用无关掉落物。六张旧精英/Boss 方块头已移出生产目录并保留在拒绝档案，稀有怪改走完整骨骼模型。八张旅程图现在还通过 `originalrpg:journey` 字体复用到 `/rpghelp` 进度条、当前步骤标题与低频侧栏，不增加实体或粒子包；仅在客户端确认资源包成功加载后显示，未加载时回退到 `✓ / ◆ / ·` 与普通文字。旅者罗盘另由 `GenerateCompassFrames.java` 在构建时派生 32 张转针帧；背包、热栏与手持全部沿用 26.2 的 `minecraft:compass` 角度分发和 `spawn` 目标，审核过的静态图只在角度无效时兜底，因此 `Player#setCompassTarget` 导航不会被自定义材质破坏。`GenerateHudAtlas.java` 还会构建血月、法力、暴击、处决、锻造、防护、稀有掉落、技能、主城导航、当前目标和服务 NPC 十一枚专用 HUD 符号，避免把复杂物品图硬缩小后失真。

`tools/GenerateTextures.java` 提供可复现的离线素材基线；默认只补齐缺失文件，设置 `REGENERATE_FALLBACK_TEXTURES=1` 才会覆盖现有纹理，补位图也会用最近邻放大到统一 32x32。锻造弓、弩、三叉戟和盾牌均通过 26.2 的 `condition`、`select` 与 `range_dispatch` 接入原创图标和使用姿态；首次客户端验收仍要逐项检查拉弓、蓄力、投掷、格挡和罗盘四向转针的最终手感。

`runtime-item-models.txt` 是生产插件可能下发的 81 个 `originalrpg:*` 显示键清单。`build.sh` 会在打包前强制验证：清单与 `items/*.json` 一一对应、插件里的直接模型引用属于清单、全部 JSON 可解析、每个物品定义能闭包到模型和纹理、没有孤儿模型/纹理、全部 83 张物品 PNG 为带二值透明通道的 32x32 像素图、HUD 图集尺寸/透明度/唯一性与 U+E000..U+E00A 十一码位严格一致、八个旅程字体提供者与 U+E100..U+E107 严格一致、旅者罗盘保持 26.2 的 33 段方向分发、中英文资源键一致，以及安装中的 26.2 服务端确实声明资源格式 88。任一检查失败都不会覆盖生产 ZIP 或 SHA-1。

## 素材来源登记（`ASSET-PROVENANCE.md` / `asset-provenance.json`）

`ASSET-PROVENANCE.md` 是**逐张**的来源登记台账，覆盖 `assets/**` 下全部 **252 张 PNG**
（item 83 + 罗盘帧 32 + betterhud 116 + bettermodel 16 + 字体图集 2 + minecraft 3），
与磁盘一一对应。机器可读版本是 `asset-provenance.json`（键 = **相对 `resource-pack/assets/` 的路径**，
与 W12-A3 的 `scripts/gen-asset-provenance.py` 同口径；值含 `category` / `source` / `reproduce_cmd` / `reviewed`），
两边的路径集合与内容**同源同构**。

`category` 五类口径（用 W12-A3 门禁词表；任务书口径 `derived-from` 在 `categoryA2` 字段保留）：

| 类别 | 含义 | 覆盖量 |
| --- | --- | --- |
| `generator` | 可由仓库内生成器**确定性重跑** | 124 |
| `generator-derived` | 由另一张**已登记素材**派生（skill 图标 42 + 罗盘帧 32 + forged 变体帧 5） | 74 |
| `ai-prompt` | `ai/` 生产线 Image API 产出，**不可复现**（已登记提示词文件:行号） | 9 |
| `reviewed-baseline` | 人工审核基线，**仓库内既无生成器也无提示词** | 44 |
| `human-override` | 人工/AI 覆盖、同名生成器输出已弃用（`ancient_echo_skill_book`）；A3 据此做反向断言 | 1 |

重新生成与自检：

登记表由**一次性推导脚本**从生成器源码（`tools/*.java|py|mjs`）与提示词文件（`ai/*.jsonl`、`ai/prompts/**`）
逐张推导，脚本本体未入库（本任务只允许改登记文档与 JSON，不得新增 `resource-pack/tools/` 下的文件）。
推导脚本原文与四条判据的实测输出见 `.cnb/agent-out/W12-A2-asset-provenance.json` 的 `evidence` 字段。

**交付给 W12-A3 门禁的四条一致性判据**（均已在本任务实测通过）：

1. `asset-provenance.json` 的键集合 == `find resource-pack/assets -name '*.png'`（相对 `assets/` 的路径）；
2. 每个值都有 `category` / `source` / `reproduce_cmd` / `reviewed` 四个字段；
3. `category == "generator"` 的项，其 `source` 必须指向仓库内真实存在的 `tools/*`；
4. `category == "ai-prompt"` 的项，其 `source` 必须形如 `<file>:<line>` 且该行存在。

**对账修正了任务书里的三处口径**（详见 `ASSET-PROVENANCE.md` §2）：

- 42 张 `skill_*` **不是 AI 直出**：`ai/` 下 0 / 42 有对应提示词；它们是
  `tools/generate_betterhud_panels.py#skill_menu_items` 从 18px HUD 语义图标派生的
  `generator-derived`（42 张里 37 张与生成器 recipe 逐像素一致，5 张差 4 px）。
- `ai/prompts.jsonl` 的 27 条里，**26 条不能单独作为当前磁盘纹理的来源证明**
  （6 条指向已删除的方块头、20 条指向被 `GenerateTextures` 同名覆盖的图）。
- 5 张 forged 变体帧是**独立素材**而非主纹理派生：与 `forged_ranger_weapon` 的不透明像素
  Jaccard ≤ 0.29；其中 crossbow 的 5 个状态、trident 的 2 个姿态在模型层**回指同一张 `layer0`**。

仍有 **6 组需人工决策项**（`asset-provenance.json.$needsHumanDecision`）：38 张 BetterHud 字库分页、
20 张法力帧切分、4 张上游占位贴图、`sulfur_resonance_imprint` 的提示词缺口、上述 5 张 skill 的
4 px 差异，以及生成器脚本自身是否入库。

## 两道素材门禁的分工（不要复制粘贴第二套逻辑）

同一批素材有**两个**校验入口，职责不同、判据有重叠但**不共享代码**（避免两套会漂移的实现）：

| 入口 | 跑在哪 | 依赖 | 定位 |
| --- | --- | --- | --- |
| `resource-pack/build.sh` | 生产机（Paper 停机窗口） | bash + jq + rg + java + zip/unzip/sha1sum + `server/` | **打包真相源**：闭包、纹理、字体、语言键、SHA-1、稳定 UUID、`server.properties` 一致性、Paper 运行中拒绝覆盖 |
| `scripts/check-resource-pack.sh` | CNB 质量门（`素材门禁（W7-A1）` stage） | **只用 POSIX sh + python3 stdlib**（PNG 解码走 zlib，不用 jq / 不用 ImageMagick / 不碰 Paper） | **仓内静态子集**：拦「素材坏着进 main」 |

重叠判据（清单一一对应 / 闭包 / 32x32 二值 alpha / HUD 码位 / 旅程码位 /
中英语言键 / 罗盘方向分发 / `pack.mcmeta` 格式）**以 `scripts/check-resource-pack.sh` 为准**：
它跑在 CI 上、每次推送都执行、且每条判据都被 `scripts/selftest-gates.sh` §7 用
「破坏 → 必须红 → 还原 → 必须绿」证明过能真的变红。若两者口径出现分歧，
改 `build.sh` 去对齐脚本，**不要**在 `build.sh` 里另写一套新逻辑。

**CI 判不了、只能由 `build.sh` 判的**（需要 Paper / 仓外产物，本仓没有）：
- `pack.mcmeta` 声明的格式 88 是否与**安装中的** 26.2 服务端一致 ——
  要读 `server/runtime/cache/mojang_26.2.jar` 里的 `version.json`（仓外产物）。
  CI 侧替代：判据 8 只断言包**自己**声明了 `min=max=88`、`supported_formats=[88,88]`
  且无 `overlays`（静态事实，可判定）。
- ZIP 产出、SHA-1、稳定 UUID、`server.properties` 的 `resource-pack` / `-sha1` 一致性。
- BetterHud 的 YAML → `build.zip` 再编译（`generate_betterhud_panels.py` 需要 Pillow）。
- 插件源码里**动态拼接**的 `originalrpg:*` 模型键（`build.sh` 用 rg 扫 `plugins/`；
  CI 素材门禁的边界是 `runtime-item-models.txt` 这份权威清单本身）。

构建成功后会生成稳定文件名 `server/web/resourcepacks/sivita-hub-26.2.zip`，更新 SHA-1，并让线上模板与局域网运行配置使用同一个稳定资源包 UUID；两个环境仍保留各自域名或局域网 URL。黑紫方块排查与客户端验收清单见 `docs/RESOURCE_PACK_QA.md`。

`ai/` 提供 Image API 美术生产线：27 条批量提示词 + 3 条技能书提示词，加上独立的旅者罗盘提示词，全部通过 `/v1/images/generations` 生成绿幕原稿，去背后压缩成统一 32x32 调色板；单资产修改通过 `/v1/images/edits` 版本化输出。只有显式传入 `--apply` 才覆盖当前资源，具体命令见 `ai/README.md`。当前 Sub2API Key 的分组必须开启 `allow_image_generation`，否则网关会返回 403。

物品身份仍以服务端签名 PDC 和数据库 `item_id` 为准，模型键只负责显示，绝不作为交易或属性校验依据。
