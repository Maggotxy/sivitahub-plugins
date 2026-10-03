# 资源包

资源包面向 Minecraft Java Edition 26.2，资源格式为 `88.0`，采用现代 `min_format` / `max_format` 和 `assets/originalrpg/items` 物品模型定义。Paper 停止时运行 `./build.sh` 会校验 JSON、生成 `server/web/resourcepacks/sivita-hub-26.2.zip` 并输出客户端要求的 SHA-1；Paper 运行中会拒绝覆盖正式包，可用 `RESOURCE_PACK_OUTPUT_DIR=<临时目录> SKIP_PROPERTY_UPDATE=1 ./build.sh` 只生成候选包。

当前审核总览包含 36 张项目内生成的原创 32x32 像素素材：28 张内容素材覆盖职业武器方向、盾牌、护甲、核心材料、强化物、技能书、拉弓帧、独立弩、独立三叉戟、硫灵锻纹、传承蓝图和旅者罗盘；另有 8 张为新手旅程逐步绘制的专属图标，分别表达主城、职业、五维、探索、狩猎、锻造、槽位揭示与副本，不再借用无关掉落物。六张旧精英/Boss 方块头已移出生产目录并保留在拒绝档案，稀有怪改走完整骨骼模型。八张旅程图现在还通过 `originalrpg:journey` 字体复用到 `/rpghelp` 进度条、当前步骤标题与低频侧栏，不增加实体或粒子包；仅在客户端确认资源包成功加载后显示，未加载时回退到 `✓ / ◆ / ·` 与普通文字。旅者罗盘另由 `GenerateCompassFrames.java` 在构建时派生 32 张转针帧；背包、热栏与手持全部沿用 26.2 的 `minecraft:compass` 角度分发和 `spawn` 目标，审核过的静态图只在角度无效时兜底，因此 `Player#setCompassTarget` 导航不会被自定义材质破坏。`GenerateHudAtlas.java` 还会构建血月、法力、暴击、处决、锻造、防护、稀有掉落、技能、主城导航、当前目标和服务 NPC 十一枚专用 HUD 符号，避免把复杂物品图硬缩小后失真。

`tools/GenerateTextures.java` 提供可复现的离线素材基线；默认只补齐缺失文件，设置 `REGENERATE_FALLBACK_TEXTURES=1` 才会覆盖现有纹理，补位图也会用最近邻放大到统一 32x32。锻造弓、弩、三叉戟和盾牌均通过 26.2 的 `condition`、`select` 与 `range_dispatch` 接入原创图标和使用姿态；首次客户端验收仍要逐项检查拉弓、蓄力、投掷、格挡和罗盘四向转针的最终手感。

`runtime-item-models.txt` 是生产插件可能下发的 81 个 `originalrpg:*` 显示键清单。`build.sh` 会在打包前强制验证：清单与 `items/*.json` 一一对应、插件里的直接模型引用属于清单、全部 JSON 可解析、每个物品定义能闭包到模型和纹理、没有孤儿模型/纹理、全部 83 张物品 PNG 为带二值透明通道的 32x32 像素图、HUD 图集尺寸/透明度/唯一性与 U+E000..U+E00A 十一码位严格一致、八个旅程字体提供者与 U+E100..U+E107 严格一致、旅者罗盘保持 26.2 的 33 段方向分发、中英文资源键一致，以及安装中的 26.2 服务端确实声明资源格式 88。任一检查失败都不会覆盖生产 ZIP 或 SHA-1。

## 素材来源登记（`ASSET-PROVENANCE.md` / `asset-provenance.json`）

`ASSET-PROVENANCE.md` 是**逐张**的来源登记台账，覆盖 `assets/**` 下全部 **252 张 PNG**
（item 83 + 罗盘帧 32 + betterhud 116 + bettermodel 16 + 字体图集 2 + minecraft 3），
与磁盘一一对应。机器可读版本是 `asset-provenance.json`（键 = **相对仓库根的路径**，形如
`assets/originalrpg/textures/item/xxx.png`；值与 `ASSET-PROVENANCE.md` 的逐张表**同源同构**）。

`category` 六类口径：

| 类别 | 含义 | 覆盖量 |
| --- | --- | --- |
| `generator` | 可由仓库内生成器**确定性重跑** | 129 |
| `derived-from` | 由另一张**已登记素材**派生（skill 图标 37 + 罗盘帧 32） | 69 |
| `ai-prompt` | `ai/` 生产线 Image API 产出，**不可复现**（已登记提示词文件:行号） | 10 |
| `human-override` | 人工覆盖，**生成器旧设计已弃用** ⇒ 按定义不可复现；必须带 `override_reason` + `source_prompt`，并由 `ASSET_REPRO_GATE` **反向断言** | 1 |
| `upstream-betterhud` | BetterHud **编译期**生成的字体图集分页（本仓无 `server/`，只给可重建步骤） | 38 |
| `upstream-vanilla` | 上游/原版发行版自带占位贴图，改动会与上游不兼容 | 5 |
| `reviewed-baseline` | 人工审核基线，**仓库内既无生成器也无提示词** | 0 |

重新生成与自检（**JSON 与那份 .md 都是生成物，不要手改**）：

```bash
# 一次产出 asset-provenance.json + ASSET-PROVENANCE.md（含附录的逐张总表）
python3 resource-pack/tools/generate-provenance.py --write --write-doc
```

**交付给 W12-A3 门禁的四条一致性判据**（均已在本任务实测通过）：

1. `asset-provenance.json` 的键集合 == `cd resource-pack && find assets -name '*.png'`
   （键形如 `assets/...`，**不带** `resource-pack/` 前缀；请勿与 `find resource-pack/assets` 的输出直接比较）；
2. 每个值都有 `category` / `source` / `reproduce_cmd` / `reviewed` 四个字段；
3. `category == "generator"` 的项，其 `source` 必须指向仓库内真实存在的 `tools/*`；
4. `category == "ai-prompt"` 的项，其 `source` 必须形如 `<file>:<line>` 且该行存在。

**对账修正了任务书里的三处口径**（详见 `ASSET-PROVENANCE.md` §2）：

- 42 张 `skill_*` **不是 AI 直出**：`ai/` 下 0 / 42 有对应提示词；
  37 张是 `tools/generate_betterhud_panels.py#skill_menu_items` 从 18px HUD 语义图标派生的
  `derived-from`，另外 5 张（2x2 强调色块）的派生 recipe 已回同步进 `GenerateTextures.java`，
  因此提为 `generator`。
- `ai/prompts.jsonl` 的条目里，**绝大多数不能单独作为当前磁盘纹理的来源证明**
  （多条指向已删除的方块头、多条指向被 `GenerateTextures` 同名覆盖的图）。
- 5 张 forged 变体帧是**独立素材**而非主纹理派生：与 `forged_ranger_weapon` 的不透明像素
  Jaccard ≤ 0.29；其中 crossbow 的 5 个状态、trident 的 2 个姿态在模型层**回指同一张 `layer0`**。

**旧口径下的 6 组待决项已全部裁决**（W13-A2 五组 + W13-A1 三条出清，合并后
`asset-provenance.json.$needsHumanDecision` 为**空数组**）：38 张 BetterHud 字库分页 →
`upstream-betterhud`；5 张上游占位贴图 → `upstream-vanilla`；`sulfur_resonance_imprint`
相似度判定最高 Jaccard 0.099 ⇒ 独立素材，补登 `ai/prompts.jsonl:28`；
生成器脚本经 `git ls-files` 确认**已在库**；20 张法力帧切分与 5 张 skill 的 4 px 差异
则由 W13-A1 回同步进生成器（见下一节的 `human-override` 与 §7 可复现断言）。

## 逐像素基线：`assets-manifest.json`

`resource-pack/assets-manifest.json` 为 `assets/**` 下**每一张** PNG 登记一条记录：

```json
"originalrpg/textures/item/hunt_crystal.png": {
  "fingerprint": "32x32:016c08c5b1ab04770b7e9a65a031f8e0594a959fa0a0574d98997f9a42f79c4f",
  "bytes": 1043
}
```

`fingerprint` 是**解码后像素**的 SHA-256：摘要输入 = 大端 `width`(4) + `height`(4) +
逐像素大端 ARGB(4)，逐行 `y`、逐列 `x`。它**不是** PNG 文件字节的哈希 ——
编码器换元数据 / 换滤波方式 / 换压缩率都会让文件字节变而画面不变，
拿文件字节做基线会天天假红。这套算法与 `tools/PngFingerprint.java` 同构，
仓内实现是 `scripts/asset_fingerprint.py`（本仓没有 JDK，CI 只有 python3）。

清单覆盖 252 张：`originalrpg` 117（83 张物品纹理 + 32 张罗盘帧 + 2 张字体图集）
+ `betterhud` 116 + `bettermodel` 16 + `minecraft` 3。

### 为什么需要它

素材门禁（`scripts/check-resource-pack.sh`）判的是「素材**自洽**」：闭包、32x32、
二值 alpha、HUD 码位、语言键。它判不了「这张图**还是不是原来那张**」——
换一张同样 32x32、同样二值 alpha 的图，八条判据全绿。基线门禁补的就是这个洞：
**未登记**（磁盘有清单无）、**丢失**（清单有磁盘无）、**被改动**（指纹不一致）三种形态都会红。

### 有意更新基线（唯一的正路）

改素材 → 重算清单 → 提交清单，三步缺一不可：

```sh
# 1. 改素材（生成器 / ai/ 生产线 / 手工，都行）
# 2. 重算基线清单（键排序、稳定输出，重复跑字节一致）
python3 scripts/gen-asset-manifest.py
#    只想确认清单是否最新、不写盘：
python3 scripts/gen-asset-manifest.py --check
# 3. 与素材改动一起提交（两步必须同一个 PR）
git add resource-pack/assets resource-pack/assets-manifest.json
git commit -m "feat(resource-pack): 更新某张纹理并同步基线清单"

# 本地自检（CI 里由 `素材逐像素基线门禁（W12-A1）` stage 跑同一条命令）
python3 scripts/check-asset-baseline.py     # 0=全一致 1=有差异 2=清单缺失
```

提交后的 diff 就是评审材料：改了哪几张图（清单里那几行）、新旧指纹各是什么。

### 为什么不能靠 `--force` 跳过

**门禁有意不提供 `--skip` / `--force` / `--update` 之类的开关。**
理由很直接：基线门禁唯一会红的时候，就是「有人改了素材没更新清单」——
而这恰好也是「有人偷偷换了素材」的样子。这两件事在机械层面**无法区分**，
只能靠人看 diff。

一旦留了跳过开关，它就会变成出事时的默认动作：先 `--force` 把 CI 弄绿，
评审时没人再看得见那张图被换了。所以这里没有开关可跳 ——
想让门禁转绿，就只有「重算清单 + 提交」这一条路，那条路会留下可评审的 diff。

配套地，`scripts/selftest-gates.sh` §9 用四组负向对照证明这道门禁**真的会红**：
改一个像素 → RED 并指出是哪张（打印期望/实际指纹）、删一张图 → 报「丢失」、
加一张未登记的图 → 报「未登记」、还原后 → GREEN；另外还断言清单缺失时 exit 2
（不是 0，也不是 1）。同一节做接线自证，断言 `.cnb.yml` 真的在跑这条命令 ——
「脚本写好了但没人调用」比「脚本有 bug」更隐蔽。

## 四道素材门禁的分工（不要复制粘贴第二套逻辑）

同一批素材有**四个**校验入口，职责不同、判据有重叠但**不共享代码**（避免多套会漂移的实现）：

| 入口 | 跑在哪 | 依赖 | 定位 |
| --- | --- | --- | --- |
| `resource-pack/build.sh` | 生产机（Paper 停机窗口） | bash + jq + rg + java + zip/unzip/sha1sum + `server/` | **打包真相源**：闭包、纹理、字体、语言键、SHA-1、稳定 UUID、`server.properties` 一致性、Paper 运行中拒绝覆盖 |
| `scripts/check-resource-pack.sh` | CNB 质量门（`素材门禁（W7-A1）` stage） | **只用 POSIX sh + python3 stdlib**（PNG 解码走 zlib，不用 jq / 不用 ImageMagick / 不碰 Paper） | **仓内静态子集**：拦「素材坏着进 main」（判素材是否**自洽**） |
| `scripts/check-asset-baseline.py` | CNB 质量门（`素材逐像素基线门禁（W12-A1）` stage，紧随素材门禁） | 只用 python3 stdlib（zlib 解码 + hashlib） | **逐像素对账**：拦「素材被悄悄换掉」（判素材是否**还是原来那张**），口径见上一节 |
| `scripts/check-asset-reproducible.sh` | CNB 质量门（`素材可复现性门禁（W13-A1）` stage，紧随基线门禁） | **需要 JDK**（CI 镜像自带；开发容器自取 Temurin 25）+ python3 stdlib | **再造一次**：拦「生成器与仓库静默漂移」（判素材**还造不造得出来**），口径见下一节 |

重叠判据（清单一一对应 / 闭包 / 32x32 二值 alpha / HUD 码位 / 旅程码位 /
中英语言键 / 罗盘方向分发 / `pack.mcmeta` 格式）**以 `scripts/check-resource-pack.sh` 为准**：
它跑在 CI 上、每次推送都执行、且每条判据都被 `scripts/selftest-gates.sh` §7 用
「破坏 → 必须红 → 还原 → 必须绿」证明过能真的变红。若两者口径出现分歧，
改 `build.sh` 去对齐脚本，**不要**在 `build.sh` 里另写一套新逻辑。

基线门禁（`check-asset-baseline.py`）与上面两道都不同源：它不判断素材好坏，
只比对「当前像素 vs `assets-manifest.json` 里登记的像素」，因此**不复制任何判据逻辑**，
也不与 `build.sh` 重叠 —— `build.sh` 不读这份清单（生产机打包不依赖基线，
基线是「防篡改」而不是「防打包错」）。

可复现性门禁（`check-asset-reproducible.sh`）与上面三道都不同源：它现场编译并运行
`resource-pack/tools/` 下的 Java 生成器到**临时目录**，再逐像素比对仓库里的对应 PNG。
它补的是基线门禁**结构上管不到**的那个洞：

> 基线门禁问的是「磁盘像素 == 清单里的像素」。有人手改一张纹理、再重跑
> `scripts/gen-asset-manifest.py` 把新像素登记进清单，**两道门禁全绿** ——
> 但生成器再也产不出那张图了（生成器与仓库不同步是**静默**的，没有任何东西在看着它）。

覆盖规格在 `resource-pack/asset-reproducible.json`（70 条资产 / 4 个生成器）：
`GenerateTextures` 36（31 原有 + 5 张 `skill_*`）、`GenerateHudAtlas` 1、
`GenerateSkillHudAtlas` 1、`GenerateCompassFrames` 32 帧（另加 32 个罗盘模型 JSON 逐字节）。

**有意不纳入 `GenerateSivitaHudPanel`**：它是 132x44 的遗留物，`build.sh` 不调用
（现役 26x91 面板由 `generate_betterhud_panels.py` 产出），输出在
`assets-manifest.json` 里没有条目。把没有对应仓库产物的生成器拖进门禁，
只会得到一条**恒真的假判据** —— 那比没有门禁更坏。

### `human-override`：人工覆盖要反向断言，不能豁免

`ancient_echo_skill_book.png` 是 `ai/skill-book-prompts.jsonl:1`（v2）的人工覆盖，
生成器 `GenerateTextures.book()` 的旧紫皮设计已弃用。它**按定义不可复现**，
因此登记为 `category: human-override`，并在门禁里做**反向断言**：

> 仓库像素必须**不等于**生成器输出。若有人把生成器的旧设计盖回仓库，必须 RED。

为什么不用「豁免」：豁免只能证明「这张图没被检查」，反向断言能证明
「这张图**是人工覆盖的那个版本**」。前者在有人覆盖回旧设计时静默放行，后者立刻变红。
登记必须带 `override_reason` + `source_prompt`，缺了门禁同样 RED ——
否则 `human-override` 会变成一个可以随便贴的免罚标签。

### 有意更新生成器覆盖

改了生成器（或要新增覆盖）后的正路：

```sh
# 1. 跑生成器，逐张确认真的一致（判据行必须是 GREEN）
sh scripts/check-asset-reproducible.sh

# 2. 若新增/改名了生成器输出，同步覆盖规格
$EDITOR resource-pack/asset-reproducible.json

# 3. 若像素真的变了（例如生成器修好了某张图）：同步基线清单
python3 scripts/gen-asset-manifest.py
git add resource-pack/assets resource-pack/assets-manifest.json
git commit -m "feat(resource-pack): 同步生成器与基线"

# 4. 同步来源登记与登记文档（两份都是生成物）
python3 resource-pack/tools/generate-provenance.py --write --write-doc
```

与基线门禁同样，**不做** `--skip` / `--force` 开关：想转绿只有
「改生成器」或「改素材并同步基线」两条路。
`scripts/selftest-gates.sh` §10 用三组负向对照证明它真的会红
（改生成器常量 / 人工覆盖被盖回去 / `human-override` 登记被改）。

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

`ai/` 提供 Image API 美术生产线：28 条批量提示词 + 3 条技能书提示词，加上独立的旅者罗盘提示词，全部通过 `/v1/images/generations` 生成绿幕原稿，去背后压缩成统一 32x32 调色板；单资产修改通过 `/v1/images/edits` 版本化输出。只有显式传入 `--apply` 才覆盖当前资源，具体命令见 `ai/README.md`。当前 Sub2API Key 的分组必须开启 `allow_image_generation`，否则网关会返回 403。

物品身份仍以服务端签名 PDC 和数据库 `item_id` 为准，模型键只负责显示，绝不作为交易或属性校验依据。
