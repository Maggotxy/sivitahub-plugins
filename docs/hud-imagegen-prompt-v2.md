# SivitaHub HUD reference v2 prompt

Use case: ui-mockup
Asset type: implementation reference for a vanilla-client Minecraft 26.2 server HUD rendered through a resource pack and BetterHud
Primary request: Create a polished in-game screenshot mockup showing a compact, immediately understandable combat HUD for a Minecraft survival RPG. The layout must be technically feasible on an unmodded client and must preserve the original Minecraft hotbar, hearts, hunger and experience bar.
Scene/backdrop: first-person view in a handcrafted Minecraft stone-and-dark-oak adventure hub at dusk; the world is only context and must remain visually quieter than the UI.
Style/medium: authentic crisp pixel art, 16×16 Minecraft visual language, hard one-pixel edges, dark deepslate frames, oxidized-copper corners, restrained cyan magic highlights. No smooth mobile-game UI, no photorealistic panels, no high-resolution painted ornaments.
Composition/framing: 1920×1080 gameplay screenshot. Keep the center, crosshair, ActionBar, chat and complete bottom survival HUD unobstructed. Place exactly four compact square skill slots in one vertical rail at the right-side midpoint. Keep the rail below the optional upper-right scoreboard and at least eight logical pixels from the screen edge. Put a one-pixel cyan mana line immediately to the left of the rail and fill it upward. Each slot has one readable pixel icon, a small slot number, and either a centered cooldown number or no number when ready. The selected cast slot has one gold corner frame, not a large glow. Attach one small class crest and level to the left of the rail. Do not place a permanent control sentence around the skills; a short native prompt may say that tapping F casts and double-tapping F cycles while a crafted weapon is held. No client mod is required.
Text (verbatim): “法师 18”, “霜冻新星 · 12 法力 · 8秒”
Constraints: exactly four vertically stacked skill slots; preserve vanilla hearts, hunger, experience and nine-slot hotbar; readable at GUI scales 2, 3 and 4; no UI element touches the screen edge; strong spacing and hierarchy; show one frost icon, one fire icon, one arcane icon and one mobility icon; the frost icon is selected and ready, the fire icon shows cooldown “6”, the mobility icon shows cooldown “12”.
Avoid: horizontal skills above the hotbar, top-screen icon rows, permanent compass, minimap, quest tracker, overlap with an upper-right scoreboard, boss bar, giant left panel, giant character portrait, inventory menu, more than four skills, duplicated health or hunger bars, tiny paragraph text, black or purple missing-texture squares, private-use glyph boxes, overlapping text, smooth gradients, rounded mobile buttons, ornate fantasy painting, logos, watermark.

Implementation note: this image is a layout reference only. Production uses a 26×91 logical-pixel right-side rail with four native 18×18 semantic icons, a separate 29×26 class badge and a 1×87 vertical mana fill; generated decorative detail must be simplified rather than traced literally.

## Implementation note 的机器可读版本与实测对齐（W7-A4）

本节的数字不是散文，而是**唯一来源** `docs/reference/hud-layout.json` 的文字版本。改尺寸必须同时改
三者：这份 note + `hud-layout.json` + 实际 PNG；只改一处会被门禁拦下
（`resource-pack/tools/verify_hud_layout.py`，接在 `resource-pack/build.sh` 与
`scripts/selftest-gates.sh` §5b 上；只用 Python 标准库，不依赖 CI 镜像里没有的 node）。

### 设计基准值 vs 实现实际值

| 项 | 设计基准 | 实现实际 | 差 |
|---|---|---|---|
| 右侧轨 | 26×91 | 26×91（`image_sivita_skill_panel.png`） | 一致 |
| 技能槽位 | 4 个，18×18 单格 | 4 个，内容区 18×18，22px 影格 | 见下「槽位影格」 |
| 语义图标 | 4×(18×18) | 49 张全 18×18（42 技能 + 7 职业） | 一致 |
| 职业徽章 | 29×26 | 29×26（`image_sivita_class_badge.png`） | 一致 |
| 法力填充 | 1×87 | 1×87 × 20 帧（`image_image_sivita_mana_fill_up_20_*.png`） | 一致 |
| 选中框 | （未在 note 里定尺寸） | 18×18 | 与槽位同格，旧值 21×21 已废弃 |

### 三处「基准值做不到 / 需要解释」的实测结论

1. **槽位影格 22px，不是 4×22=88 等距。** 面板 91 高，`y = 1 + slot*22` → 槽位底边
   1/23/45/67+18 = 19/41/63/85，剩 6px 收边。若按基准的 4×22=88 排布，第 4 槽底边会到 87
   并压掉面板右下收边角。因此采用可行值 **85**，`hud-layout.json` 里用
   `slot.firstTop/pitch/lastBottom/baselineLastBottom` 四个字段把这件事写成可机器校验的等式。

2. **徽章右侧留白 1px，不是 note 里画的 2px。** 轨道 26（偶）与徽章 29（奇）奇偶不同，
   1px 梯度下 `26 - 29 = -3`，不可能同时满足「贴齐右边缘」与「≥2px 右留白」。
   取 1px = `hardRightInsetMin`（原版 HUD 的硬下限：任何元素不得贴屏幕边），
   `hud-layout.json` 里 `badge.rightInset` 与 `badge.hardRightInsetMin` 两个字段共同断言它。

3. **BetterHud 定位字体的 `height` 不是素材像素高度。** 同一张 18px 图，BetterHud 会按
   GUI 缩放档位选定「视觉等价行高」：实测同一批图标在不同页里是 8 / 11 / 18。
   所以门禁的判据是：像素尺寸一律以 **PNG IHDR** 为准，字体只断言
   「每张图都有字形定义」（缺字形会在客户端变成方框，而这正是本 note 要防的）。

### 本轮实现改动（对齐基准）

- 49 张 `glyph_skill_*` / `glyph_class_*` 由 16×18 补成 **18×18 画布**（左右各 1px 透明留白，
  可见内容 16×18 逐像素未动）。
- `image_sivita_skill_selected.png` 由 21×21 改为 **18×18**，对齐单格槽位；
  旧的 21×21 比槽位宽 3px，会压到相邻槽之间的 4px 间隙。
- 以上两项都不改变玩家看到的信息层级：图标仍是原来的像素图，选中框仍是四角金框。

### 仍然做不到、需要真人复验的部分

- 原版十字准星 / ActionBar / 聊天 / 血 / 饥饿 / 经验 / 9 格热栏**不遮挡**这一点，静态与
  单元测试无法证明。`resource-pack/tools/generate_betterhud_panels.py` 的
  `safe_area_review()` 会按 GUI 缩放 2/3/4 与 1408×792、1280×720、960×540 真实视口
  重新渲染并断言「轨道不越界、离边缘 ≥8px」，但那只是**几何**证据；
  真正的遮挡判定需要在 Paper + BetterHud 运行时截图，属于 `[R] Runtime` 证据。
