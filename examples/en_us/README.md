# English onboarding configuration preset

This optional preset gives a source-default deployment English menus, shared hub NPC names and Dialog titles, map NPC tooltips, the story guide's name, and the load-protection join message. It uses the English messages already shipped by the plugins.

The four YAML files here are **small overlays**, not complete plugin configurations. They contain only 25 approved language/text fields; gameplay values are inherited from the canonical source configuration when generating a preview.

## Generate a review copy

From the repository root:

```bash
python3 -m pip install PyYAML==6.0.3
python3 scripts/generate-english-configs.py --output build/en_us-preview
```

Choose a new directory each time. The script refuses an existing directory, file or symlink, including an empty directory. The output path is mandatory; there is no default server destination.

The result contains:

```text
en_us-preview/
  RpgCore/config.yml
  RpgHub/config.yml
  RpgStory/config.yml
  RpgPerformance/config.yml
  manifest.json
```

The generator reads source configuration under `plugins/*/src/main/resources/` and the four overlays in this directory. It writes review copies and a manifest of source SHA-256 hashes and exact text changes. It does not install files, connect to a server, merge into an existing deployment, or modify the source configurations. Every input is validated before creating the output directory. Generated YAML preserves parsed values and types, while comments and formatting may differ from the source.

## What changes

| Overlay | Exact fields | Effect |
|---|---|---|
| [RpgCore.yml](RpgCore.yml) | `language.default` | Players without a saved choice receive English. Player selection remains enabled in the source defaults. |
| [RpgHub.yml](RpgHub.yml) | `npcs.<role>.name` and `npcs.<role>.subtitle` for the 11 existing roles | English shared world labels and Dialog titles; English NPC tooltips on squaremap. |
| [RpgStory.yml](RpgStory.yml) | `actor.name` | The shared Citizens story actor becomes **Hub Guide Qizhi**. |
| [RpgPerformance.yml](RpgPerformance.yml) | `overload-protection.join-message` | An English explanation when load protection rejects a join. |

The hub role IDs are `guide`, `quests`, `ai`, `class`, `skills`, `forge`, `enhancement`, `dungeon`, `market`, `economy`, and `wilderness`. Names retain the established English proper names from the hub language bundle. **Officer Suichuan** abbreviates the longer title “Expedition Officer Suichuan” to fit the existing 24-codepoint world-label limit.

No gameplay number, currency, price, drop rate, database setting, world coordinate, feature switch, actor ID or UUID is changed. Timezone-dependent activity resets retain their source configuration; this preset does not silently move them to a different timezone.

## Use it in an authorized deployment

For a new staging deployment, review the generated configuration alongside the [English setup guide](../../docs/en/GETTING_STARTED.md), including its database, signing-key, resource-pack and third-party requirements. A language preset does not provide the missing server deployment bundle.

For an existing deployment, back up its configuration and apply only the listed overlay fields to your reviewed staging configuration. **Do not replace production configuration with these generated full copies:** they contain source defaults, including the development database configuration. This generator deliberately does not read or rewrite installed server files.

After your normal controlled restart, check `/language`, `/menu`, NPC titles, `/story`, and map NPC tooltips. Existing players keep their saved language preference; use `/language en_us` and reopen menus to select English. A server default change does not erase saved player choices.

The NPC and story names are shared by all viewers. Players who choose Chinese retain Chinese menu/help text but still see the shared English names configured by this preset. For a bilingual community, decide on shared labels as a deployment choice.

## Remaining English release work

This preset covers configurable onboarding text. The following surfaces remain separate:

- The squaremap **layer name and hub boundary title/description** are hardcoded Chinese in `SquaremapHubIntegration`; this preset translates NPC markers only.
- The disabled-by-default session-lease rejection reasons and a hidden wolf-spirit entity name still have Chinese strings in Java.
- Third-party login, chat, quest, web and HUD configuration is not supplied by this repository and needs its own language review.
- Existing operator language overrides, generated equipment text, font coverage and long English text at different GUI scales require runtime checks.

See the [localization audit](../../docs/audits/LOCALIZATION_REVIEW.md) for source evidence and the [English player guide](../../docs/en/PLAYER_GUIDE.md) for terminology and controls.

## Validation

```bash
python3 -m unittest discover -s scripts/tests -p 'test_english_presets.py' -v
python3 scripts/check-locales.py
```

The preset tests generate actual copies from the canonical configurations, compare every value and its type, and assert that exactly the 25 approved string fields change. They also check refusal of existing output and symlinks, unauthorized fields, duplicate YAML keys, invalid value types, source schema drift and excessive NPC name length.

This is configuration-generation evidence, not a Paper/client runtime acceptance result. The repository's [internal-use terms](../../INTERNAL_USE.md) still apply.
