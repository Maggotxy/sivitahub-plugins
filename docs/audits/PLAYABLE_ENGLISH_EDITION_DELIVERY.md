# Playable English edition: implementation and acceptance record

Date: 2026-11-04. Base: `b78ff859ed14588d8d04d1faa9d0ddf44730ba6b`.

This change set implements in-game language behavior, repairs gameplay transactions and startup, and supplies reproducible client/server content inputs. It is not limited to translating repository documentation. The [25-plugin review](FULL_PLUGIN_GAMEPLAY_REVIEW.md) records every module's mechanics, presentation, dependencies, and remaining acceptance work.

**Release status: source review and offline validation complete; the full 25-plugin binary edition is blocked by the unavailable pinned Citizens dependency.** Twenty-four actual plugin JARs have been built and independently verified against the same current source fingerprint. No Paper/PostgreSQL or human/client acceptance is claimed.

中文说明：这一轮交付的是实际运行代码、双语剧情包、客户端资源包、怪物模型/HUD 服务端配置候选以及经济仿真器。25 个插件均完成源码与玩法入口核对；24 个已实际构建。Story 所需的固定 Citizens JAR 下载被返回 HTTP 403，因此完整发行打包器正确停止，没有把 24 个插件冒充完整 25 插件发行版。

## 1. In-game English behavior

The existing English and Simplified Chinese content is now connected more consistently to actual player and shared-world presentation:

- Locale changes are published only after a valid choice is saved. Installed overrides retain their identity; missing or invalid overrides fall back to packaged text in the selected language. Placeholder interpolation is single-pass, so player-provided braces, slashes, and dollar signs are not reinterpreted.
- Hub NPCs, map labels, and story actors use the server's default language. Personal guidance, escort presentation, messages, and knowledge answers use the player's language. Operator-customized actor names are preserved.
- An in-flight AI answer from the previous language cannot be shown after a language change; the current-language local answer supplies the fallback without a second paid request.
- Forged equipment uses the base item name separately from an anvil custom name. Recognizable old defaults can migrate; custom renames, identity, signatures, statistics, and quantities remain intact.
- Existing supported personal items refresh after authentication, language changes, receipt clearance, transfers, and container closure: mob materials/common skill books, dungeon materials/tickets/skill books, bound starter items, pet horns, hunt crystals, and Blood Moon essence. Refresh work runs on the main thread and coalesces per player.
- Pending market deliveries, listings, equipment operations, and dungeon entry escrow are excluded from generic presentation changes. This preserves the serialized payload used by recovery. Signed equipment uses its own guarded presentation path.
- Delayed adaptive-mob naming preserves Blood Moon rarity and player name tags. Skill spirits use localized names.
- HUD readiness requires an authenticated, current player state and a successfully loaded resource pack. Missing numeric placeholders return valid numbers; maximum mana is at least one. Empty identifiers and glyphs remain empty.

The six shipped bilingual groups contain **3,015 matching keys per locale**. This is a key/type/placeholder check, not a claim that every external plugin or every client layout has been accepted. See [Localization review](LOCALIZATION_REVIEW.md) for exact legacy-item, market-preview, and third-party boundaries.

## 2. Gameplay and persistence repairs

| Area | Corrected behavior | Evidence |
|---|---|---|
| Final progression cap | Ascension 3 / local level 30 may retain legitimate further XP; stage-experience summation saturates instead of overflowing. Invalid progression remains rejected. | Rules and database regressions `[S][T]` |
| Natural-mob XP | Frozen batches retain a stable grant ID. The profile transaction commits XP, skill milestones, and the receipt atomically; replay after a lost commit response does not grant XP twice. | Real H2 transaction, failure injection, and buffer regressions `[S][T]` |
| New database startup | The season-transition table precedes its index; the milestone grant schema matches its actual consumer. | Complete production migration tests `[S][T]` |
| Legacy skill audit | Empty recognized legacy tables may be rebuilt; nonempty legacy audits and unknown shapes stop with a review instruction, preserving existing rows and balances. | Migration regressions and [migration procedure](../operations/PROGRESSION_RECEIPT_MIGRATION.md) `[D][S][T]` |
| Mechanism switches | Explicit PostgreSQL/H2 dialect handling, correct conflict target, preserved disabled switches, atomic cache replacement, rollback on failure. | Nine persistence tests `[S][T]` |
| Governance | Notifications go to current authorized members of the relevant community after commit. Duplicate success notices and incorrect role-rejection events are removed. | Service and listener regressions `[S][T]` |
| Equipment startup | A failed exit-path or signing-key gate actually disables the plugin. | Source and existing startup/security contract regressions `[S][T]` |
| Cosmetic catalog | Validation reads the installed commerce plugin's effective configuration and declares its load ordering. | Three new catalog/descriptor tests `[S][T]` |

No new currency, progression balance, marketplace, or equipment state machine was added. Support Credit remains isolated. Maximum ascension, local level cap, economic values, and the default-disabled multi-server controls are unchanged.

The stable XP receipt solves uncertain-commit duplication. **Pending natural-mob rewards still held only in memory can be lost on a hard process termination before submission.** Governance events are also in-process notifications; post-commit notification delivery is not a durable Outbox guarantee.

## 3. Playable content and resource pipeline

### Client assets and navigation

The portable build works from the public source checkout without the private deployment tree:

```bash
bash resource-pack/build.sh
```

The actual client baseline ZIP contains **597 files / 343,021 bytes**. Its SHA-256 is:

```text
3e1f31a62d56dd9240eb08d47f4726155079727be9ac197213a6d7b7061bbbe0
```

It includes all checked-in namespaces and shaders. Validation covers 81 item definitions, 42 skill identities, seven class states, six dungeons, 15 model identities, 1,529 non-vanilla references, 252 PNG baselines, and 141 HUD layout checks. The reviewed PNG artwork was retained; no visual redesign or new raster-art authorship is claimed.

The G-key datapack now registers a native `minecraft:dialog` entry containing eight player-context command actions. It no longer relies on a function tag or slash-prefixed function commands. Client language selects the translated labels with English fallbacks. The target formats are resource pack **88.0** and datapack **107.1**, matching the project's Minecraft 26.2 target.

### Server model/HUD candidates

```bash
python3 resource-pack/tools/build_server_resources.py --output build/server-resources-review
```

The output must be a new directory. The reviewed candidate contains **15 `.bbmodel` files, 164 geometry elements, 105 bones, a four-slot BetterHud layout, 53 icon inputs, and 80 payload files plus its manifest**. Models use the actual mapped IDs and embed the checked-in texture bytes. Dungeon models include the animation names called by the plugins.

These are **server registration/compilation inputs**. The pinned BetterModel and BetterHud versions must compile them in an isolated deployment, then the resulting client model indices, font codepoints, and shader layout table must be synchronized into the same final resource pack. The portable baseline ZIP is not automatically asserted to match a newly compiled candidate layout.

The old private deployment workflow remains explicit: Gradle `buildDeploymentResourcePack` runs `build.sh --deployment`, and `stageRelease` depends on it. `buildResourcePack` is the portable source build. This prevents a private release from silently staging new plugin JARs against an unchanged live pack.

### Bilingual prologue

The [BetonQuest package](../../examples/betonquest/README.md) is real installable content: one conversation, 14 options, 15 bilingual text groups, three existing actions, and the reserved Qizhi actor ID 9101. It connects tour start/resume, reward recovery, and the authoritative journey menu. It adds no duplicated quest balance or independent reward commands.

This is the working prologue/orientation package, **not a claim of a complete campaign**. Citizens spawning, BetonQuest parsing, permissions, route completion, reconnect recovery, and both dialogue languages still require the locked plugins on a real server.

## 4. Economy simulation

The former runner stub is replaced with a deterministic cohort model. The [full recorded run](evidence/economy-simulation-2026-11-04.json) includes source/configuration hashes, the seed, explicit synthetic assumptions, extracted parameters, weekly flows, wealth distribution, market inventory age, and currency/equipment conservation checks.

```bash
python3 -m unittest discover -s tools/economy-sim -p 'test_*.py'
python3 tools/economy-sim/run.py --output build/economy-sim/simulation.json
```

All **15 scenarios** execute: S01–S14 are `SIMULATED_UNCALIBRATED`; S15 produces the required `EXPECTED_FAIL_FAST` rejection of Support Credit conversion. All **25 model behavior tests pass**.

S07 imposes its completed-sales concentration using real modeled listings and buyer funds; the recorded run achieves a 50% largest-seller share over 12 sales. S12 redistributes expected weekly play hours; its 60-day run realizes about 68.22% weekend minutes because of sampled attendance and calendar boundaries. Zero-weekend inputs are supported; impossible daily hours are clamped and reported. Empty suites, invalid population weights, fractional ledger units, and malformed administrator amounts fail explicitly.

The results remain `[M]` evidence. They do not execute Paper, JDBC, concurrency, progression, all income/sink systems, or actual player behavior. The generated cash-stock alerts require calibration against observed data and review of omitted systems before changing live prices or drop rates. S10/S14's modeled identity/replay guards are not runtime crash-recovery proof.

## 5. Reproducible binary edition and verification

Every plugin JAR includes `META-INF/sivitahub-build.json`. Gradle and the edition packager independently compute a common fingerprint over all registered plugin main sources/resources, plugin build scripts, and root build settings. The final verified fingerprint covers **643 files**:

```text
bc587ab26bf7ee1730834b9edcafc5487cf49bc613a5d7cd0f003632851049f6
```

The packager rejects old Java-only builds, stale resources, missing stamps, wrong Java bytecode, corrupt archives, mixed source states, and changes during packaging. It requires a new destination and all 25 real plugin JARs. Fixture-JAR success tests are explicitly synthetic and are not counted as a built server edition.

### Executed evidence

| Check | Result |
|---|---|
| JDK / Gradle | Temurin 25.0.5+8 / Gradle 9.1.0 |
| Plugin tests excluding declared `env` tags | **1,138 passed, zero failures/errors/skips across 24 modules** |
| Database migration tool tests | **24 passed, two conditional PostgreSQL tests skipped** |
| Python content/localization/build/downloader fixtures | **79 passed**; all 12 edition-package tests repeated after the provenance root correction |
| Economy model behavior | **25 passed** |
| Full `test jar -PexcludeTags=env --continue` | Only `:plugins:rpg-story:compileJava` failed: missing pinned Citizens classes |
| Actual JAR validation | **24 verified**, matching descriptor/version, Java 25 main class, bundled resources and unified source fingerprint |
| Source contracts | **469 checks / 79 specs, green** |
| Gameplay specification | **140 entries, zero weak entries, green** |
| DDL consumers/registrations | **82 tables**, forward/reverse and registration checks green |
| Language/navigation/story/content/assets | Green; details above and [machine-readable evidence](evidence/edition-verification-2026-11-04.json) |
| Full English binary packager | Correctly rejected the missing Story JAR; **no partial edition was produced** |
| `[R]` and `[H]` | Not obtained |

### Exact build blocker

Required file:

```text
libs/Citizens-2.0.43-b4231.jar
SHA-256: 7f5bf10d581b0759779e9fc03570bef605065ffe1770430513232373a52a2e9a
```

The [locked upstream URL](https://ci.citizensnpcs.co/job/Citizens2/4231/artifact/dist/target/Citizens-2.0.43-b4231.jar) returned HTTP 403 in this environment. No newer Citizens build, fake class, or relaxed checksum was substituted. Once the exact artifact is available, run:

```bash
sh scripts/fetch-deps.sh --check
./gradlew test jar -PexcludeTags=env --continue
bash resource-pack/build.sh
python3 scripts/package-edition.py --output build/sivitahub-en-us-26.2
```

The final output command still requires a new directory. A full successful build then enables binary packaging; it does not replace server/client acceptance.

## 6. Required deployment acceptance

Before deploying, follow the migration procedure for any existing skill audit. Deploy the matching set of custom JARs together, since the API and dependent plugins changed. Keep signing keys, operator data, private worlds, and external-plugin configurations in the authorized deployment environment.

The next real acceptance session must cover a fresh English account: authentication, class choice, starter gear, forging, skill use, mob reward, dungeon entry/reward, mailbox, market delivery, pet horn, Qizhi's tour, reconnect, and switching both languages. Test pending ticket/delivery recovery and a lost database commit response. Compile model/HUD candidates with the pinned external plugins, then inspect fonts, all four slots, cooldowns, English label lengths, resource-load failure, model animations and multiple GUI scales.

The audit additionally preserves the unresolved natural-mob in-memory loss window, multi-node lease/Outbox work, third-party interface languages, legacy market previews, and the absence of observed economy calibration. These are specific remaining acceptance/implementation tasks, not claims hidden by green source tests.
