# 玩法规格 ↔ 代码 机械绑定（W28-A1 框架 + W28-A2 全量）

`docs/GAME_DESIGN.md`（标题即「完整玩法规格」）是唯一真相源的一半（另一半是代码，
见 `doc/cnb/GOAL.md` §0）。但规格与代码之间长期**没有机械判据**，漂移只能靠人工审计发现。
本项目实测已发生 **3 次**：

| 编号 | 漂移 | 后果 |
|---|---|---|
| G-106 | 文档把血契写成二转，前置技能 `vanished-horizon` 是 `minimum-ascension: 3` | 二转玩家**永远拿不到**血契 |
| G-202 | 文档把「胚材槽上限」写成固定区间，实际是随胚材等级上浮的公式 | 玩家按文档算错构筑 |
| G-209 | 文档承诺主动能力预警 0.4–0.8 秒，`config.yml` 是 20 tick ≈ 1.0 秒 | 文档承诺本身不可达 |

本任务把这类漂移变成**门禁红灯**。

> W28-A1 建立框架并覆盖 `docs/GAME_DESIGN.md` 前 8 节；W28-A2 把剩余 7 节
> （强化与精炼 / 市场与交易限制 / 游戏内黑心商人 / 菜单、帮助与新手引导 /
> 任务与剧情 / 原版主城、地图与 NPC / 商城、会员与累计奖励）一并纳入，
> 并新增可重生成的**全量汇总报告**（见 §7）。两波共用同一份 spec 与同一个门禁，
> 不另起一套。

## 1. 声明式规格

`scripts/mechanics-spec.json` 逐条声明「规格里的这条断言 → 由哪个代码载体承载 → 期望值」：

```json
{
  "id": "M-EQP-002",
  "section": "装备自制与槽位",
  "doc": "docs/GAME_DESIGN.md",
  "doc_lines": "42",
  "claim": "锻造基础成功率 65%，最大成功率 95%",
  "carrier": {"kind": "config", "path": "plugins/rpg-items/src/main/resources/config.yml",
               "key": "forge.base-success-rate", "expect": 0.65}
}
```

`carrier.kind` 只允许三种：

- **`config`**（首选）：玩法数值真相在 `plugins/<mod>/src/main/resources/config.yml`。
  给 `path` + `key`（点分路径，`*` 通配下标；命中多条时用 `expect_count`）。门禁直接读 YAML 比较。
- **`behavior`**：Java 里的**公式/规则**。必须给一条**已存在的、行为级 JUnit 测试**的
  `class#method`。门禁断言该测试方法**存在**；它是否 PASS 由 test 门禁保证。
  **不允许**用「读 Java 源码做字符串匹配」承载公式类断言 —— 那正是本项目明令不算判据的形态
  （`GOAL.md` §2、`L-008`）。
- **`java-const`**：Java 里的**编译期常量**。门禁做**结构化解析**
  （`static final <类型> <名> = <值>;`，或返回单字面量/`switch -> 字面量` 的方法），
  不是自由正则匹配任意文本。每条必须在 `note` 里写清**为什么不能放进 `config.yml`**，
  并尽量给 `behavior_ref`。没有 `behavior_ref` 的条目在判据行里计入 `MECHANICS_SPEC_WEAK`。

## 2. 门禁

`scripts/check-mechanics-spec.sh` 逐条核对，任一条不符 ⇒ RED，打印 id / claim / 期望 / 实际。

### 2.1 依赖：只用 Python 标准库（W28-A3）

门禁**不得**依赖 PyYAML / pip。事实依据：

- 门禁镜像（`eclipse-temurin:25-jdk` + `.cnb.yml` 里 `apt-get install -y curl ca-certificates
  coreutils python3`）实测：`python3 -c 'import yaml'` ⇒ `ModuleNotFoundError: No module named
  'yaml'`，且**连 pip 都没有** ⇒ 「让调用方自己装」不是可选项。
- 本仓既有 python 工具（`resource-pack-asset-check.py` 等）没有任何一处 `import yaml`，
  一律用 JSON 或手写解析 ⇒ 「门禁容器无 PyYAML」是**既有事实**，不是新约束。

因此脚本自带一个**最小 YAML 子集解析器**（纯 `re` / `json` / `os` / `sys`），只覆盖本仓
`config.yml` 实际用到的形态，**不需要**完整 YAML 语义：

| 形态 | 支持 |
|---|---|
| 缩进映射（任意层级） | ✅ |
| 标量：整数 / 浮点 / 布尔 / 引号字符串 / 裸字符串 | ✅ |
| `"350.00"` 这类**带引号数字** | ✅ 保持 `str`（`expect` 写 `"350.00"` 才对得上） |
| 行内映射 `{k: v, k2: v2}`（本仓 252 处） | ✅ |
| 行内序列 `[a, b]` / 序列项 `- x` / `- {k: v}` | ✅ |
| 注释 `#`（整行 + 行尾，`#` 前须有空白才当注释） | ✅ |
| 块标量 `\|` / `\|-` / `>` / `>-` | ✅ |
| 键名 `kebab-case` / `snake_case` / 数字段 / `01-x` | ✅ |
| 锚点 `&` / 别名 `*` / 标签 `!!` / 多文档 `---` / 顶层序列 / 制表符缩进 | ❌ **显式报错**，绝不静默猜 |

解析错误与「键不存在」一样是 **RED**：覆盖不了的形态不会被当成「值没对上」而蒙混过关。
等价性已机械核对：24 份 `config.yml` 与 `yaml.safe_load` **逐层逐型完全一致**，78 条
`config` 载体的 `WildcardDfs` 解析结果（值 + miss 集合）也完全一致。

**回归夹具**：`selftest-gates.sh` §12 断言脚本本体不含 `^\s*(import|from)\s+yaml`
（静态判据），有人再把 PyYAML 依赖加回来就当场变红。

判据行：

```
MECHANICS_SPEC_GATE=GREEN|RED
MECHANICS_SPEC_ENTRIES=<n>
MECHANICS_SPEC_BY_KIND=config:<a>,behavior:<b>,java-const:<c>
MECHANICS_SPEC_WEAK=<n>
```

## 3. 自测（正负夹具）

`scripts/selftest-gates.sh` §12 用 `mktemp -d` 的临时副本证明门禁**真的会红**：

| 夹具 | 破坏 | 期望 |
|---|---|---|
| 正 | 真实仓库 | `MECHANICS_SPEC_GATE=GREEN`（exit=0） |
| 负①（config） | `forge.base-success-rate` 0.65→0.55 | RED（exit=1） |
| 负②（behavior） | `M-EQP-007` 指向不存在的测试方法 | RED（exit=1） |
| 负③（java-const） | `LOCAL_LEVEL_CAP` 30→25 | RED（exit=1） |
| 负④（weak） | `M-CORE-001` 去掉 `behavior_ref` | 仍 GREEN，但 `MECHANICS_SPEC_WEAK` 变 1 |
| 负⑤（后 8 节 config） | `refinement.success-rate` 0.65→0.55 | RED（exit=1），点名 `M-REF-001` |
| 负⑥（报告陈旧） | 把报告节里的 `MECHANICS_SPEC_ENTRIES` 改掉 | `--check` 报 `MECHANICS_SPEC_REPORT=STALE`（exit=1） |
| 负⑦（PyYAML 依赖回潮，W28-A3） | 往**夹具副本**的脚本里插入 `import yaml` | RED（exit=1），且未插入时必须 GREEN |

每条破坏后**还原必须 GREEN**，且收尾断言夹具未在真实仓库留残留。

**接线**：本任务改动白名单不含 `.cnb.yml`，因此接线方式是
`selftest-gates.sh` §12 自己调用新门禁 —— `.cnb.yml` 早已执行
`sh scripts/selftest-gates.sh`。§12b 断言这一条可 grep 的事实，并用拼出来的假路径做负向对照。

## 4. 不可机械核对清单

以下断言**不登记为 spec 条目**（硬凑成 `config` 条目比不登记更危险）。理由逐条列出：

| 断言 | 理由 |
|---|---|
| 每件装备最多一个稀有特性（:44/:102） | 唯一性由签名 schema/PDC 结构强制，无单点数值或配置键 |
| 纸+硫磺+朱砂合成空白锻纹（:67） | 材料清单是 `RpgSulfurPlugin.registerRecipe` 的调用序列，无数值断言 |
| 66% 与 33% 生命进入新阶段（:94） | `DungeonManager.bossPhase` 的战斗循环常量（`ratio<=0.33/0.66`），无配置键；阶段缩短 tick 各副本族 16–20 不同 |
| 副本世界名以 `dungeon_` 开头（:82/:88） | 命名约定，非数值/公式/枚举 |
| 血契属性门槛 敏捷 32/耐力 18（:106） | 属性门槛不在代码载体中，无 config 键与行为断言 |
| 符箓属性门槛 智力 18/力量 13/敏捷 9/耐力 6（:107） | 同上 |
| 五维枚举 防御/力量/敏捷/智力/耐力（:13） | 声明性文案，无行为级断言 |
| 主动能力形状/颜色预警（:78） | 表现层主观判断；门禁只核对 `telegraph-ticks` 数值 |
| 粒子/声音服从 `/visuals` 与压力降级（:78） | 纯表现层降级策略，无单点阈值 |
| 精英行为特性语义（迅捷闪避、狂暴暴走等，:78） | 需真人主观判断；每种特性数值分散且非单一语义锚点 |
| 守护波次、时间限制、Boss 技能节奏（:88） | 每副本独立配置的多组区间值，无跨副本统一锚点 |

## 5. 覆盖范围

W28-A2 结束后，`docs/GAME_DESIGN.md` 的**每一个 `##` 节**都至少登记了一条可机械核对断言：
`角色成长` / `装备自制与槽位` / `原版生存共鸣` / `26.2 硫灵锻纹` / `动态怪物与原版世界` /
`副本` / `战斗与技能限制`（A1）＋ `强化与精炼` / `市场与交易限制` / `游戏内黑心商人` /
`菜单、帮助与新手引导` / `任务与剧情` / `原版主城、地图与 NPC` / `商城、会员与累计奖励`（A2）。

唯一没有条目的是 `核心循环`（:3）—— 它是一段总览，全部数值都已在其余各节逐条登记，
不存在只属于该节的可核对断言。

**未发现需要改文档或改代码的漂移**：G-202 / G-209 / G-106 / G-208 在此前波次已处理
（历史先例：代码是 shipped 玩法，文档是规格，默认改文档）。A2 逐条核对后 8 节，
文档与代码一致，未发现新的漂移，因此 `_inconsistencies` 为空数组。

## 6. 用法

```sh
sh scripts/check-mechanics-spec.sh              # 退出码 0 = GREEN
sh scripts/check-mechanics-spec.sh --spec <path>  # 指定 spec（自测夹具用）
sh scripts/selftest-gates.sh                    # §12 / §12b 负向对照

# 全量汇总报告（数字由脚本生成，禁止手写）
python3 scripts/gen-mechanics-spec-report.py            # 打印报告节
python3 scripts/gen-mechanics-spec-report.py --write    # 写进 docs/GAME_DESIGN.md 末尾
python3 scripts/gen-mechanics-spec-report.py --check    # 只校验（陈旧即退出 1）
```

## 7. 全量汇总报告

`scripts/gen-mechanics-spec-report.py` 从 `scripts/mechanics-spec.json` **与门禁的真实判据行**
生成 `docs/GAME_DESIGN.md` 末尾的 `## 规格机械化状态（W28 自动核对）` 一节，内容含：
总条数 / 按节分布 / 按载体分布 / weak 清单 / 本轮不一致清单 / 不可机械核对清单 / 一句话结论。

生成器**自己跑门禁**拿判据行，因此报告里的条数不可能与门禁独立漂移；
`--check` 会在报告陈旧时退出 1，保证「报告由脚本生成」这句话本身也是可核对的。
