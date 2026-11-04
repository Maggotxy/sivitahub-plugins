# Sivita hub orientation — BetonQuest 3.1

This installable, bilingual conversation connects the reserved **Qizhi Citizens NPC** to Sivita's existing hub tour and journey panel. English and Simplified Chinese are in the same package. It adds an NPC interaction route to the working `/story` commands; it does not introduce a second quest state or reward system.

## Install

1. Install the repository's required RpgStory, RpgHub, RpgCore, RpgApi, Citizens and **BetonQuest 3.1.0** versions. RpgStory registers the custom actions during its load phase.
2. Copy the **`sivita_story` directory**, including `package.yml`, from `QuestPackages/` here into your server's `plugins/BetonQuest/QuestPackages/`. Keep an existing customized package if you already use that name; compare it before replacement.
3. Keep the package's `npcs.qizhi` numeric ID aligned with `plugins/RpgStory/config.yml` → `actor.citizens-id` (shipped value: `9101`). RpgStory creates that actor and refuses an occupied ID belonging to a different UUID. Do not create a second actor with the same ID.
4. Restart the server after installing/updating the plugin JARs. For subsequent **package-only** edits, use BetonQuest's `/bq reload` and inspect its parse diagnostics. `/storyadmin status` confirms whether the actor is spawned; `/storyadmin repair` restores the reserved actor to the hub when needed.
5. Authenticate, choose a class, and right-click Qizhi near the hub arrival platform. The chat conversation offers the tour, reward recovery, and the real journey panel. `/story status`, `/story start`, `/story next`, `/story claim` and `/story cancel` remain available independently.

For a default English deployment, set `RpgCore/config.yml` → `language.default: en_us`. Set the corresponding existing section in BetonQuest's own `config.yml` as follows; merge these fields into that file instead of replacing the entire third-party configuration:

```yaml
language:
  default: en-US
  questlang_whitelist: [en-US, zh-CN]
```

BetonQuest ships both language files. If you removed them, restore the matching plugin distribution's `lang/en-US.yml` and `lang/zh-CN.yml`. RpgStory synchronizes the resolved RPG language after authentication and after a saved `/language` change through the supported `/questlang` command. The command still honors BetonQuest permissions, loaded languages, and the configured whitelist. Existing conversations should be closed and reopened after switching. `/questlang` changes BetonQuest's language only; `/language en_us` or `/language zh_cn` keeps the RPG plugins in sync.

## What the actions actually do

| Conversation choice | Existing component | Result |
| --- | --- | --- |
| Start/resume tour | `sivita_story_begin prologue_escort` | Runs `EscortManager.start`; keeps authentication, class, safe hub return, load/capacity and follow-distance checks. |
| Check completed tour reward | `sivita_story_claim prologue_escort` | Runs `EscortManager.claim`; requires the completed-route tag and uses the existing `story:<quest>:<player UUID>` ledger key. |
| Next journey goal | `sivita_story_open_journey` | Opens the real onboarding panel through `JourneyService` and `GuideMenuService`. |

Finishing the route already requests the reward automatically. The claim choice is a recovery path. The package contains **no money action, tag-writing action, command execution, XP award, item award or copied reward amount**. Reward configuration remains solely in `RpgStory/config.yml`; gameplay progression is unchanged.

The package intentionally uses the `simple` chat conversation interface so its dialogue needs no resource pack, special font, or packet plugin. Shared actor nameplates follow the server language; private dialogue follows the player's language.

## Validation and limits

From the repository root:

```bash
python3 scripts/check-story-package.py
python3 -m unittest discover -s scripts/tests -p 'test_story_package.py' -v
```

The checker safely parses YAML with duplicate-key rejection, checks the documented 3.1 structure, bilingual nonempty texts, pointers, conditions, actions, reachability, and the reserved actor binding. It rejects reward/state-writing actions and unresolved conversation routes. This is static validation, not a running BetonQuest or Citizens server test.

On a real deployment, verify both languages, an unchosen-class player, start/resume, cancellation, route completion, repeated reward recovery, relogging, and a language change during an active tour. Existing Java tests cover the RPG authority, idempotency and lifecycle rules; this package reuses those handlers. The repository's evidence policy still requires Paper/PostgreSQL runtime and human play testing before an operational-readiness claim.

Syntax checked against the versioned upstream references:

- [BetonQuest 3.1 package structure](https://betonquest.org/3.1/Documentation/Basics/Packages-%26-Templates/)
- [BetonQuest 3.1 conversations and translations](https://betonquest.org/3.1/Documentation/Advanced/Conversations/)
- [BetonQuest 3.1 NPC selectors and conversation bindings](https://betonquest.org/3.1/Documentation/Reference/Integrations-List/NPCs/)
- [BetonQuest 3.1 language configuration](https://betonquest.org/3.1/Documentation/Reference/Plugin-Config/)
