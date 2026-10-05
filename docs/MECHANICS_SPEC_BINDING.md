# 玩法规格 ↔ 代码 机械绑定（W28-A1）

`docs/GAME_DESIGN.md`（标题即「完整玩法规格」）是唯一真相源的一半（另一半是代码，
见 `doc/cnb/GOAL.md` §0）。但规格与代码之间长期**没有机械判据**，漂移只能靠人工审计发现。
本项目实测已发生 **3 次**：

| 编号 | 漂移 | 后果 |
|---|---|---|
| G-106 | 文档把血契写成二转，前置技能 `vanished-horizon` 是 `minimum-ascension: 3` | 二转玩家**永远拿不到**血契 |
| G-202 | 文档把「胚材槽上限」写成固定区间，实际是随胚材等级上浮的公式 | 玩家按文档算错构筑 |
| G-209 | 文档承诺主动能力预警 0.4–0.8 秒，`config.yml` 是 20 tick ≈ 1.0 秒 | 文档承诺本身不可达 |

本任务把这类漂移变成**门禁红灯**。

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

本波登记 `docs/GAME_DESIGN.md` 前 8 节：
`核心循环` / `角色成长` / `装备自制与槽位` / `原版生存共鸣` / `26.2 硫灵锻纹` /
`动态怪物与原版世界` / `副本` / `战斗与技能限制`。

**未发现需要改文档或改代码的漂移**：G-202 / G-209 在此前波次已把文档改成与代码一致
（历史先例：代码是 shipped 玩法，文档是规格，默认改文档）。本次登记不改变任何一侧取值。

## 6. 用法

```sh
sh scripts/check-mechanics-spec.sh              # 退出码 0 = GREEN
sh scripts/check-mechanics-spec.sh --spec <path>  # 指定 spec（自测夹具用）
sh scripts/selftest-gates.sh                    # §12 / §12b 负向对照
```
