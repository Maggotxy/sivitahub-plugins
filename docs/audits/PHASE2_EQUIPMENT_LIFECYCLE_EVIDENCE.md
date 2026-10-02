# Phase 2 装备生命周期权威化 — 完成证据（[D][S][T]）

> 分支：本地直接落地 | 目标：将 12 态 `EquipmentLifecycle` 从死代码变为市场托管与装备加工事务强制执行的唯一权威（C-001, C-008, C-009, C-013），并为 C-013 的「自动系统只能冻结」补上人工核实出口。

## [D] Design
- 单一持久权威：新表 `rpg_equipment_states`（`item_id` 主键、`state`、`owner_uuid`、`last_operation`、`updated_at`）；每次状态判定前 `SELECT … FOR UPDATE` 锁行，使市场托管事务与装备加工 outbox 对同一 `item_id` 互斥——一件装备不可能同时被挂牌且被付费加工（C-009 单一托管）。
- 转移图收敛于 `EquipmentLifecycle`（rpg-api，纯函数，无 Bukkit/JDBC 依赖）：`TERMINAL={SALVAGED, LEGACY_EXTRACTED, DESTROYED}`；`LISTED`/`QUARANTINED` 拒绝一切玩家加工（C-013 冻结不改值）；付费 `/unbind` 接受 `BOUND`，并容忍 `UNBOUND` 自转移（见「漂移模型」）。
- 遗留兼容：注册表之前的装备无行。市场托管首次接触时按 `LISTED` 收养；加工首次接触时按命令侧既有不变量收养（精炼/破限种子 `CREATED`，解绑/分解/传承种子 `BOUND`）；领取/回退遇缺行按无操作放行。老装备既不会被砖，新权威又对所有已注册身份生效。
- 漂移模型（C-001 的边界）：战斗绑定 `ForgeListener.bind()` 与精炼/破限的归属写入是**同步 PDC 变更，从不写账本**。因此账本只在「钱与托管」上作权威，PDC 只在「 gameplay 归属」上作权威，两条规则必须互相容忍：买家领取按 `SOLD→UNBOUND`（市场只接受无主装备，领取物在 PDC 中同样无主，账本必须一致，否则转售死锁）；付费解绑容忍 `UNBOUND` 自转移（挂牌回退后再次战斗绑定会让行落后于实物）。命令侧仍按 PDC 拒绝无主解绑费用，所以自转移不可能是白花的一笔钱。
- 人工核实出口（C-013 的另一半）：`EquipmentLedgerAuditService` 提供隔离与解除隔离。冻结保留 `owner_uuid`，解冻后状态由生命周期规则决定（`RESTORED`）而非操作者挑选；两者都必须在同一事务内写入 `rpg_equipment_lifecycle_audit`（操作者 + 备注 + 前后状态），拒绝的裁定既不写状态也不写轨迹。状态行的 `last_operation` 只存 24 字符短标记，长备注留在审计表。
- 冻结不可被解绑洗白（深审遗留疑问，查毕无需改码）：`UnbindCommand` 的 `unlockForTrade()` 只改 `resultItem`——一个 `original.clone()`；实物直到 `setAmount(0)` 才动。解锁后的克隆能否交到玩家手里完全由账本门裁决：`COMMITTED`（账本放行 UNBIND）走 committed lane 交付克隆；`OPERATION_REFUSED`（`QUARANTINED` 行使 `afterOperation(UNBIND, …)` 返回 null）走 recovery lane 退回**仍锁定的 `original`**、不扣费、信封清除。故 PDC 变更是投机的，只在 COMMITTED 时成真，而冻结态永远到不了 COMMITTED——实物归还与其冻结账本行一致。
- 重放语义不变：装备加工的持久 receipt 幂等门（`rpg_equipment_operations`）仍在生命周期门之前执行；取消/过期后的重新挂牌仍由 `LISTED→LISTED` 允许，`open_item_identity` 唯一索引继续作为 DB 侧双挂牌守卫。
- 信封滞留砖（深审发现 #4）的持久解法：`EquipmentOperationRequest.settleable()` 是请求形状的唯一谓词，staging（`stage()` 写信封前拒绝）、解码（`Envelope.decode()`）、账本（`settle()` 入口）三缝共享同一判据。动机：形状非法的请求若进了信封，账本只会回 `INVALID`/`CONFLICT`——非终态、不清信封，该账号此后一切装备操作被拒，托管资产只能等人工介入。`stage()` 抛异常发生在清空实物之前，五个命令侧均已 `catch(RuntimeException)` 后释放点击门，玩家只损失一次点击。`Receipt.matches()` 对 `goldCost` 逐 `equals` 比较（scale 敏感），因此重放一致性依赖构造器已做的 `setScale(2, HALF_UP)` 归一，这一点由 `costBoundSurvivesTheConstructorScaleNormalization` 钉住。
- 可信容器边界（结构债清偿）：转移限制策略原先豁免「类名前缀为 `cn.originalrpg.` 的一切 holder」——信任随命名空间自动扩张，任何未来的自定义 GUI 编译进来就绕过交易限制。现改为显式标记接口 `TrustedMenuHolder`（rpg-api ui 包，继承 `InventoryHolder`）：想要豁免必须声明，忘标则**失败关闭**（restricted 物品只是不能经该 GUI 移动，不是漏防）。现存 14 个一方菜单全部 `final` 且包私有（无第三方继承面）、且各自只被本模块引用（grep 全仓核毕，无跨模块 `instanceof` 会被改标波及），已逐一改标。仓库级源码契约（rpg-market）保证任何重新出现的裸 `implements InventoryHolder` 在此变红，而不是悄悄重获豁免。

## [S] Source
- `plugins/rpg-api/.../rules/EquipmentLifecycle.java`：转移图 + `afterOperation(kind, current)`（含 FORGE→CREATED、终态拒绝、托管/冻结拒绝、unbind 前置与 `UNBOUND` 容忍）+ `seedForOperation(kind)`；`RESTORED` 保留全部出口（分解/传承/挂牌/再冻结），仅拒绝解绑费用。
- `plugins/rpg-api/.../model/MarketOperationResult.java`：新增 `ITEM_NOT_TRADEABLE`。
- `plugins/rpg-api/.../model/EquipmentOperationResult.java`：新增 `OPERATION_REFUSED`（未扣费、托管资产走 recovery 邮箱退回）。
- `plugins/rpg-api/.../service/EquipmentLedgerAuditService.java`（新）：`entry / watchlist / quarantine / restore / auditTrail`；`AuditResult.Status` 七值（APPLIED、ALREADY_IN_STATE、NOT_FOUND、NOT_FROZEN、TERMINAL、RULES_REFUSED、INVALID_NOTE）。
- `plugins/rpg-core/.../persistence/EquipmentStateLedger.java`（新）：`operationOutcome / beginEscrow / completeSale / returnToSeller / quarantineEscrow / collectPurchase`；upsert 采用 UPDATE-first + `ON CONFLICT DO NOTHING` 的方言中立写法（H2 测试 / PostgreSQL 生产通用）。`beginEscrow` 额外接受 `RESTORED`，否则解冻后的装备没有任何再入场生产者。
- `plugins/rpg-core/.../persistence/JdbcMarketService.java`：`prepare()` 收养并置 `LISTED`（拒绝→`ITEM_NOT_TRADEABLE`）；`purchase()` 在首笔余额写入前 `LISTED→SOLD(buyer)`；`cancel()`/`expireListings()` 回退 `→UNBOUND(seller)`（尽力而为：实物必回邮箱，不因状态拒绝扣回）；`quarantinePrepared()` 冻结身份；`markDelivered()` 依 `market-purchase:` 回执 `SOLD→UNBOUND(buyer)`。
- `plugins/rpg-core/.../persistence/JdbcEquipmentLedgerAuditService.java`（新）：读不加行锁（审计列表不得拖住市场热路径）；写走 `FOR UPDATE` 事务，与托管/加工门串行。冻结不碰已终结身份，解冻只解真冻结，且两者都必须再过 `isValidTransition`。
- `plugins/rpg-core/.../RpgCorePlugin.java`：`EquipmentLedgerAuditService` 经 ServicesManager 注册。
- `JdbcEquipmentOperationService.settle()`：入口先过 `request.settleable()`（非法形状直接回 `INVALID`，不开事务、不占行锁），其余走原路径；资金充足后、扣费前执行 `operationOutcome`，非法转移落 `OPERATION_REFUSED` 持久回执（可重放、不计费、不转移状态）；FORGE 无既有身份不进门。原有私有 `valid()/validPayload()` 与四个形状常量已删除，改由 `settleable()` 单一来源。
- `EquipmentOperationRequest.settleable()`（新）+ `EquipmentOperationEnvelope`：删除 `Envelope` 内重复的手写 `validRequest()/validDelivery()`，`decode()` 改调 `settleable()`；三缝（staging/decode/settle）共享同一形状谓词，杜绝「一处放行、一处拒绝」的信封滞留。
- `EquipmentOperationCoordinator`：`OPERATION_REFUSED` 纳入 TERMINAL（信封清除、资产经 recovery lane 退回，与余额不足同路）；`stage()` 在写入 PDC 信封与清空实物前拒绝非 `settleable()` 请求（见「信封滞留砖」）。
- `plugins/rpg-api/.../ui/TrustedMenuHolder.java`（新）+ 14 个一方菜单改标 + `TradeRestrictionListener.isTrustedInventory()`：容器转移豁免从命名空间前缀猜测改为显式标记接口（见「可信容器边界」）。
- `plugins/rpg-admin/.../RpgAdminPlugin.java`：`rpgadmin equipment watchlist | inspect <item> | audit <item> | quarantine <item> <备注…> | restore <item> CONFIRM <备注…>`，沿用 `AUDIT actor=…` 日志与 CONFIRM 门槛；TabCompleter 与 `plugin.yml` usage 同步。权限沿用命令级 `rpg.admin`（default: op）。
- `MarketCommand`：`ITEM_NOT_TRADEABLE` → `market.item-not-tradeable`；`items.operation-lifecycle-refused`；`admin.equipment-*` 语言键（en/zh 各 14 条）。
- `Database.java` + `tools/db-migration/SchemaManifest.java`：`rpg_equipment_states`（+ `(owner_uuid, state)`）与 `rpg_equipment_lifecycle_audit`（+ `(item_id, created_at)`）表与索引同步登记。

## [T] Test
```
./gradlew test --offline --rerun-tasks   # BUILD SUCCESSFUL，全仓 753 tests / 0 failures / 0 errors / 1 skipped
                                         #（skip = PostgreSqlMigrationIntegrationTest，无条件真库时才跑）
:plugins:rpg-api:test 58 —— EquipmentLifecycleTest：12 态可达性、终态拒绝、
    TRADEABLE={UNBOUND,LISTED}、afterOperation 全操作矩阵（含 LISTED/QUARANTINED/
    终态拒绝与 unbind 前置）、seedForOperation 不变量；新增 noLiveStateCanTrapAnIdentity
    （每个非终态都仍能抵达出口——正是 RESTORED 曾缺失的守卫）与 RESTORED 出口/拒绝断言；
    EquipmentOperationRequestTest（新，4 例）：settleable() 形状门——错误指纹长度/大小写、
    空/超长 requestPayload、非法 debitKey 字符集与长度、空白或 >16 托管量一律拒绝；
    FORGE 空 committed 仍放行（合法失败掷骰）；goldCost 上界与构造器 setScale 归一（1.005→1.01）
    钉住 Receipt.matches 的 scale 敏感重放一致性；完整请求编解码往返。
    EquipmentOperationEnvelopeTest 新增 decodeRefusesWhatTheCodecWouldHappilyPersist：
    编码器只量尺寸、形状门在 settleable()——指纹差一字符或拒绝 lane 为空的信封
    编得出却必须解不回，否则登录恢复会重放一枚必得 INVALID 的信封
:plugins:rpg-core:test 202 —— JdbcEquipmentOperationServiceTest：遗留收养、
    托管身份加工拒绝且重放同为拒绝、终态拒绝、付费解绑容忍漂移的 UNBOUND 行并拒绝 CREATED；
    JdbcServicesTest 端到端：prepare→LISTED、托管中 refine 被拒且状态不动、
    purchase→SOLD(buyer)、领取→UNBOUND、随后 refine 成功扣费、
    cancel/expire→UNBOUND 且可再托管（escrowSaleAndCollectionAdvanceTheLifecycleAndGateOperations
    与 cancelAndExpireReturnTheEscrowedIdentityToItsSeller:2864 证毕「归还即复活」，
    推翻深审发现 #2「永久拒绝再挂牌」）、quarantine→QUARANTINED 冻结加工与再托管；
    JdbcEquipmentLedgerAuditServiceTest（新，4 例）：冻结保归属+解冻回环+审计双轨、
    七种拒绝既不写状态也不留审计、watchlist 边界与 newest-first、缺行身份可辨识
:plugins:rpg-items:test 34 —— EquipmentAsyncLifecycleContractTest 增一条源码契约，
    钉住 stage() 的 `if (!request.settleable()) throw` 反砖缝
:plugins:rpg-market:test 30、rpg-enhancement:test 13、
    rpg-admin:test 16、db-migration 19（含 SchemaManifestContractTest 对新表/索引的镜像校验）；
    rpg-market 新增 TrustedMenuBoundaryContractTest（2 例）：豁免只认 TrustedMenuHolder
    （前缀猜测不得复活），且全仓任何裸 `implements InventoryHolder` 即红——
    正则对改标前形式与多接口形式均判红、对改标后与标记接口自身判绿（已人工验证）
```

## 剩余未证明（不得宣称）
- [M] 经济仿真器未将生命周期纳入产销场景（Phase 3 范围）。
- [R] 未在运行中的 Paper 上验证真实 H2/PostgreSQL 迁移（含 `rpg_equipment_lifecycle_audit` 建表）与登录恢复路径；需下一次启动窗口以 `latest.log` 佐证。
- [R] `rpgadmin equipment` 各分支未在服务器内人工执行过；AUDIT 日志格式与 TabCompleter 仅有源码与惯例一致性证据。
- [R]/[H] `TrustedMenuHolder` 改标后未在游戏内验证 14 个菜单（尤其精炼/锻造/市场浏览）仍能正常投放与取出受限物品——标记接口 + `instanceof` 是编译期与源码契约可证的，「改标后这些 GUI 依旧被豁免、且没有 GUI 意外失去豁免」需真人开一次每个菜单确认。
- [H] 未做真人回归：绑定装备挂牌被拒、市场购买领取后归属变更、冻结装备全链路只读、核实后解冻可再流通，均需游戏内人工确认。
- 已知结构债（本轮未动）：`rpg_equipment_operations.item_id` 无指向 `rpg_equipment_states` 的外键；`FORGE` 从不落 `CREATED` 行（`operationOutcome` 明确拒绝该 kind），故账本是「按需入场」的覆盖层，只能约束已经过它的身份；克隆/`/give` 一个已签名 NBT 会产生携带同一 `item_id` 的多个实物，而账本按身份单行裁决（`uq_rpg_market_open_item` 只挡双挂牌，不挡重复实物）。（原第三条债 `isTrustedInventory` 命名空间前缀放行已于本轮以 `TrustedMenuHolder` 清偿。）
- SACRIFICED / SEASON_ATTUNED 为预留态，尚无生产转移入口（专用 `/sacrifice`、赛季调谐属 Phase 4+ 退出路径工作）。RESTORED 自本轮起有生产入口（`rpgadmin equipment restore`）。
- **W6-A2 更新**：SEASON_ATTUNED 已接线，触发者是换季结算而非玩家指令 ——
  `SeasonSettlementService`（`JdbcSeasonSettlementService`）在 SETTLING 阶段调用
  `SeasonAttunementService`（`JdbcSeasonAttunementService`），后者经唯一状态机
  `EquipmentStateLedger#attune` 落 `SEASON_ATTUNED`，产物经市场邮箱交还原持有者；
  复用 Kill Switch `season.settlement.enabled`，配置 `season-attunement.enabled` 默认关。
  装备插件（rpg-items）的 plugin.yml / config.yml 仍然零入口。SACRIFICED 仍为预留态。
