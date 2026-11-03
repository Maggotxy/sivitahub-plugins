# English–Chinese terminology

[English documentation](README.md) · [Player guide](PLAYER_GUIDE.md)

Use these terms consistently in documentation and translations. Command IDs and code identifiers remain unchanged. Display terms are aligned with the checked-in [English bundle](../../plugins/rpg-core/src/main/resources/lang/en_us.yml).

| Chinese | Preferred English | Meaning or usage |
|---|---|---|
| 希维塔大陆 | SivitaHub | Project and server identity |
| 原创生存 RPG | Custom survival RPG | The project's gameplay description |
| 原版生存 | Vanilla survival | Minecraft's underlying survival mechanics |
| 职业 | Class | A starting character archetype |
| 自由职业 | Freeform | The `freeform` starting class |
| 转生 / 转数 | Ascension | Progression stage after satisfying the advancement requirements |
| 本地等级 | Local level | Level 1–30 within an ascension stage |
| 有效等级 | Effective level | `ascension × 30 + local level`, from 1 to 120 |
| 章节 | Server chapter | Shared gate controlling available ascension stages |
| 分支 | Class branch | A route within class progression |
| 隐藏路线 | Hidden path | Additional eligibility-gated path, such as Bloodpact or Talisman |
| 血契 | Bloodpact | Hidden-path display name; ID `bloodpact` |
| 符箓 | Talisman | Hidden-path display name; ID `talisman` |
| 防御 | Defense | Core attribute; ID `defense` |
| 力量 | Strength | Core attribute; ID `strength` |
| 敏捷 | Agility | Core attribute; ID `agility` |
| 智力 | Intelligence | Core attribute; ID `intelligence` |
| 耐力 | Stamina | Core attribute; ID `stamina` |
| 体力 | Energy | Activity resource, including dungeon entry costs |
| 法力 | Mana | Skill-casting resource |
| 金币 | Gold | Tradable gameplay wealth |
| 功勋 | Merit | Account-bound gameplay currency |
| 星辉 | Starlight | Account-bound gameplay currency; distinct from paid support |
| 现实付费积分 | Support Credit | Separate paid-support ledger; identifier `support_credit` |
| 锻造 | Forging | Creating RPG equipment from eligible inputs |
| 胚材 | Base item | The vanilla item used as the foundation for forging |
| 属性槽揭示 | Slot revelation | Revealing an equipment attribute slot through progression |
| 精炼 | Refinement | Developing a revealed equipment attribute |
| 突破 | Limit break | Increasing an equipment limit within the defined rules |
| 变异 | Skill mutation | A previewed/confirmed change to a learned skill |
| 锻纹 | Forging imprint | Signed resonance input used in equipment development |
| 绑定 / 解绑 | Binding / unbinding | Ownership/trading restrictions and the controlled release of them |
| 分解 | Salvage | Consuming equipment for its permitted recovery materials |
| 传承蓝图 | Legacy blueprint | A blueprint created through eligible equipment sacrifice |
| 托管市场 | Escrow market | Server-held listings with authoritative settlement |
| 邮箱 | Item mailbox / delivery mailbox | Durable item delivery, distinct from chat or Essentials mail |
| 委托 | Resource commission | A configured resource submission with eligibility and limits |
| 劳作证明 | Labor evidence | Bound evidence of eligible active gameplay |
| 副本 | Dungeon / dungeon instance | A shared encounter instance |
| 隐藏门票 | Hidden-room ticket | Eligibility item for a hidden dungeon variant |
| 血月 | Blood Moon | World event |
| 连猎 | Hunt chain | Consecutive hunting activity under the game's rules |
| 共鸣 | Resonance | Shared terminology for the associated development and cosmetic systems |
| 主城 | Hub | Protected central service area |
| 同行者 | AI companion / traveling companion | Xiaoqi, the read-only guide; distinguish her from a pet |
| 同行灵契 | Companion Bond | The MyPet-backed companion interface |
| 亲密度 | Bond | Companion progression quantity |
| 社群 | Community | Persistent player group and its related systems |
| 软赛季 | Soft season | Season transition designed to preserve defined permanent progress |
| 赛季调谐 | Season attunement | A settlement-controlled equipment lifecycle operation |
| 幂等键 | Idempotency key | Identity that makes a retry refer to the same operation |
| 冲正 | Reversal | Audited correction tied to a real original transaction |
| 隔离 | Quarantine | Restricted state pending inspection or resolution |
| 产出 / 回收 | Faucet / sink | Creation/removal of economic value in analysis |

**Do not translate `/class`, `en_us`, `gold-to-merit`, or other literal identifiers.** Translate their explanation. In prose, capitalize currency and attribute names when referring to the game-defined quantities.
