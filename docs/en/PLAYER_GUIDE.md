# Player guide

[English documentation](README.md) · [Glossary](GLOSSARY.md)

SivitaHub adds persistent RPG progression to Minecraft survival. You explore and gather in vanilla worlds, develop a class and equipment build, challenge encounters with other players, and trade through the market. This guide describes the checked-in gameplay and default configuration; the live server's menus show what its current chapter and settings actually permit.

## Your first session

1. **Join the server supplied by its operator and complete authentication.** This repository does not publish a server address. Follow the server's login dialog or AuthMe instructions.
2. **Choose English:** run `/language en_us`. Close and reopen menus so they redraw in the selected language.
3. **Open `/menu` and `/tasks`.** The main menu connects the systems; the Quest Center brings together your next objective, commissions, dungeons, events, and delivery entry points.
4. **Choose a class.** Open the class menu or use `/class choose <id>`. Read the description before choosing; advancement follows the class and branch rules.
5. **Follow the current objective.** `/rpghelp current` gives contextual guidance. `/story next` continues the optional story journey when its real gameplay requirements are met.
6. **Start survival from the hub.** `/wild` initiates safe wilderness travel from the hub. `/hub` returns to the protected hub subject to the server's travel restrictions.

The optional AI companion responds through `/ainpc analyze` or `/ainpc ask <question>`. It can explain your visible state and suggest a next step. It cannot grant items, move currency, execute commands for you, or override a quest requirement.

## Classes, attributes, and progression

The six starting classes use the following command IDs:

| Display name | ID |
|---|---|
| Healer | `healer` |
| Warrior | `warrior` |
| Assassin | `assassin` |
| Mage | `mage` |
| Ranger | `ranger` |
| Freeform | `freeform` |

Your five core attributes are **Defense, Strength, Agility, Intelligence, and Stamina**. Use the class menu to inspect them and allocate available points. Command IDs are `defense`, `strength`, `agility`, `intelligence`, and `stamina`.

Two different level values appear in the system:

| Ascension | Local level in this stage | Effective level |
|---|---|---|
| 0 | 1–30 | 1–30 |
| 1 | 1–30 | 31–60 |
| 2 | 1–30 | 61–90 |
| 3 | 1–30 | 91–120 |

**Effective level = ascension × 30 + local level.** For example, Ascension 1 / local level 1 is effective level 31. Local level returns to the beginning of the new stage when you ascend; effective level continues upward. Three ascensions is the fixed maximum.

Ascension requires more than reaching the local cap. Your class, branch, progression requirements, and the server's current chapter also matter. A date on a calendar does not automatically open another ascension. Check `/rpg` and the class menu for the current gate. The two hidden paths, **Bloodpact** and **Talisman**, have additional eligibility rules; they do not replace the six base classes or bypass chapter gates.

**Energy and Mana are different resources.** Energy pays for activities such as dungeon entry. Mana is used for skill casting. Stamina is an attribute, not a second name for either resource.

Implementation references: [progression rules](../../plugins/rpg-api/src/main/java/cn/originalrpg/api/rules/ProgressionRules.java), [class configuration](../../plugins/rpg-classes/src/main/resources/config.yml), and [English interface terminology](../../plugins/rpg-core/src/main/resources/lang/en_us.yml).

## Skills and combat

Open `/skills` to inspect the skill tree, requirements, and four skill slots. Skill books, class branches, learned skills, equipment eligibility, Mana, and cooldowns all affect what you can use. Prefer the menu for choosing and configuring skills; IDs used by commands are stable technical identifiers.

The default HUD instructions use the swap-hands action: hold an eligible crafted weapon, tap **F** to cast, and double-tap **F** to cycle. If you have rebound that action, use your configured key. `/skills castslot <1-4>` is the command entry point for a specific slot. `/skills hud` and `/guidehud on|off` expose related interface controls.

Overworld monsters can have additional readable behaviors. `/mobguide abilities` explains their warnings and avoidance, while `/mobguide dungeons` helps choose a suitable next encounter. Watch the marked ground during boss attacks; the warning text is part of the mechanic.

Use `/visuals full|reduced|minimal|off` to select a feedback level. The server can also reduce visual work under load. A reduced effect budget is not an increase in rewards or a different combat rule set.

## Forge, develop, and retire equipment

The equipment loop connects ordinary Minecraft materials to RPG builds:

1. Open `/forge` with an eligible vanilla base item and the materials shown in the preview.
2. Read the resulting equipment's attributes and hidden slots. Valid gameplay can develop equipment and reveal slots.
3. Use `/refine` for revealed attributes and `/limitbreak` for equipment limits. Inspect the displayed costs, chances, and consequences before confirming.
4. `/imprint` explains sulfur resonance, extraction, and forging imprints. These are a route to targeted equipment development, with their own quotas.
5. When replacing equipment, inspect `/salvage`, `/legacy`, `/unbind`, or `/destroy` as appropriate.

| Action | Meaning |
|---|---|
| Salvage | Consume the held forged equipment and recover the allowed bound material |
| Legacy extraction | Sacrifice eligible developed equipment to create a legacy blueprint, with the stated cost |
| Unbind | Pay the applicable Gold cost to make eligible bound equipment available for escrow trading |
| Destroy | Permanently retire the held forged equipment through its explicit destruction path |

Salvage, legacy extraction, and destruction consume equipment. Preview the action and use its confirmation only when you intend that result. Unbinding does not remove the permanent identity of its original forger.

An item being visible in an inventory is not sufficient proof that it is tradable. Signatures, identity, ownership, binding, and lifecycle state are checked by the server. Use the market interface for supported trades.

Source: [item commands](../../plugins/rpg-items/src/main/resources/plugin.yml), [enhancement commands](../../plugins/rpg-enhancement/src/main/resources/plugin.yml), and [item lifecycle design](../LONG_TERM_GAME_ECONOMY_PLAN.md).

## Dungeons and parties

Use `/dungeon party create`, invite a player with `/dungeon party invite <player>`, and have them accept using the command help. `/dungeon party info` shows the party. Entry checks apply to the party's members; a leader cannot substitute their own eligibility for everyone else's.

The checked-in default catalog contains these six instances:

| Dungeon ID | Variant | Minimum effective level | Energy cost |
|---|---|---:|---:|
| `ancient-ruins` | Normal | 5 | 20 |
| `ancient-ruins-hidden` | Hidden | 15 | 30 |
| `sulfur-depths` | Normal | 12 | 24 |
| `sulfur-depths-hidden` | Hidden | 20 | 32 |
| `storm-vault` | Normal | 22 | 28 |
| `storm-vault-hidden` | Hidden | 28 | 36 |

All six are configured for one to four players. Hidden variants also require the applicable ticket. These values come from [the dungeon configuration](../../plugins/rpg-dungeons/src/main/resources/config.yml); check the live menu for changes or disabled content.

Enter with `/dungeon enter <id>` and leave with `/dungeon leave`. Entry and rewards use persistence and recovery mechanisms. If a reward is delayed, follow the status message and check `/market claim`; repeatedly clicking or starting a new run is not a recovery procedure. Report a failed return or unresolved delivery to the operator with the approximate time and displayed message.

## Gold, Merit, Starlight, and trading

| Resource | Role |
|---|---|
| **Gold** | Tradable gameplay wealth used by the market and many crafting or progression costs |
| **Merit** | Account-bound gameplay currency used for specified progression and rewards |
| **Starlight** | Account-bound gameplay currency with configured earning, exchange, and spending routes |
| **Support Credit** | Separately accounted paid support; project rules prohibit conversion into gameplay wealth, materials, or power |

Use `/economy status` to see limits and `/economy exchange` to inspect current conversion recipes. Exchanges are not a free profit loop: recipes, quotas, and losses matter. `/economy commissions` shows available resource commissions and the labor-evidence requirements that prevent unlimited material-to-money sales. `/economy shop`, `/economy merchant`, and `/store` cover their respective catalogs.

The market holds items in **escrow**: the server retains an item while its listing is active and settles payment and delivery through the market system. Under the checked-in defaults, normal listings have a 5% fee, custom equipment a 10% fee, and listings expire after 168 hours. See [market defaults](../../plugins/rpg-market/src/main/resources/config.yml); the confirmation page is the place to verify the actual transaction.

Use `/market` to browse, `/market mine` for your listings, and `/market claim` for durable item deliveries. The same mailbox is used for several gameplay rewards, not only purchases. A full inventory can postpone a claim; it should not require dropping your other items on the ground.

## Travel, communities, and comfort

| Command | Purpose |
|---|---|
| `/hub` | Return to the protected hub |
| `/wild` | Start wilderness travel from the hub |
| `/sethome`, `/home` | Use the supported vanilla Overworld bed-home flow |
| `/hubfind` | Find a hub service NPC |
| `/warehouse` | Open the personal warehouse from the permitted hub context |
| `/community` | Open community features and their current permissions |
| `/companion` | Inspect the MyPet-backed companion system where deployed |
| `/aura`, `/trail` | Configure supported cosmetic auras and flight trails |
| `/story status`, `/story next` | Inspect and continue the optional journey |
| `/rpghelp <topic>` | Read the contextual in-game explanation |

Travel has combat, location, timing, and validity restrictions. `/home` is tied to the supported bed workflow, not an unlimited list of freely placed warp points. Feature availability also depends on the operator's runtime plugins and switches.

English coverage is broad but still under review. Shared map labels, some world-space names, and third-party or operator-supplied interfaces may remain Chinese. Report the exact screen, command, selected language, and expected meaning so the translation can be fixed at its source.
