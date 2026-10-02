# docs/reference —— 实现参考图（不是资源包产物）

本目录放**实现用的视觉基准**，不参与资源包打包，也不进 `runtime-item-models.txt`。

| 文件 | 用途 | 来源 |
|---|---|---|
| `hud-layout-v2.png` | 战斗 HUD 的布局参考（右中垂直技能轨 + 法力条 + 职业徽章 + 冷却数字 + 原版血/饥饿/经验/热栏不被遮挡） | 由 `docs/hud-imagegen-prompt-v2.md` 的提示词生成（2026-10-02） |
| `hud-layout.json` | **实现侧尺寸的唯一来源（single source of truth）**：轨道 26×91、图标 49 张 18×18、徽章 29×26、法力 1×87、槽位影格 | W7-A4 从 implementation note 提炼（2026-10-04） |

**用法**：只作为**布局与信息层级**的基准。生产实现按 `docs/hud-imagegen-prompt-v2.md` 末尾的
implementation note 落地 —— 26×91 逻辑像素右侧轨、4 个原生 18×18 语义图标、独立的 29×26 职业徽章、
1×87 垂直法力填充；生成图里的装饰细节要**简化**，不要照着描。

**不要**把这张图当成美术交付物塞进资源包：它带场景背景、含中文 UI 文本、尺寸与像素网格都不符合
32×32 二值透明通道的素材规范（`resource-pack/build.sh` 会拒绝）。

## `hud-layout.json` 的消费者

改这个文件就等于改 HUD 尺寸。它有四个消费者，全部是机器判据，单边改动会被拦下：

| 消费者 | 断言什么 | 什么时候跑 |
|---|---|---|
| `resource-pack/tools/verify_hud_layout.py` | 每张 PNG 的 IHDR 尺寸 == 唯一来源；每张图在 BetterHud 字体里都有字形；图标张数 == 契约张数；槽位影格不越界；徽章右留白 ≥ 硬下限；`build.sh` 的 `HUD_PANEL_*` / `HUD_ICON_*` / `HUD_BADGE_*` / `HUD_MANA_*` / `HUD_SLOT_COUNT` 常量 == 唯一来源；原始图集常量与 `hud.json` 自洽 | `resource-pack/build.sh`（打包前）+ `scripts/selftest-gates.sh` §5b |
| `resource-pack/build.sh` | 轨道 PNG 的 IHDR 宽高十六进制从唯一来源现算，不写死 | 打包时 |
| `HudRailSpec`（`plugins/rpg-integrations`） | 插件侧槽位数取唯一来源的 `slot.count`，占位符正则 `skill_(1-N)_...` 随之变化 | 运行时 + `HudRailSpecTest` |

**为什么用文件而不是常量类**：唯一来源必须被 shell（`build.sh`）、Python（门禁）、
Java（插件）三边同时读取。放进能直接文本解析的 JSON 是这几个环境里唯一不需要
各自解析器/依赖的方案；`hud-layout.json` 因此在 `allowed_paths` 里被列为契约文件。

**为什么门禁只用 Python**：CI 质量门跑在 `eclipse-temurin:25-jdk` 镜像里，那里没有 node；
`build.sh` 里的 node 只服务于可复现的 mob 模型生成，与 HUD 尺寸契约无关。
尺寸门禁因此全程只用 Python 标准库，不引入新的运行时依赖。

**注意**：`hud-layout.json` 里的 `baseline` 是设计基准值，`width`/`height` 是当前可行实现值。
两者不一致时以 `width`/`height` 为准，理由必须写在该段的 `notes` 里。
