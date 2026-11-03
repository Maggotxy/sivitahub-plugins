# International edition: delivery and verification

Date: **2026-11-03**. Reviewed source baseline: [`c83226ff87c8ecb868277d9ca5bce575b4684abc`](https://github.com/Maggotxy/sivitahub-plugins/tree/c83226ff87c8ecb868277d9ca5bce575b4684abc). The changes described here are the international-readiness branch, not a production deployment.

## Goal and delivered result

Make the repository understandable and reviewable for international players, developers and authorized operators, while correcting concrete defects found during the audit.

| Deliverable | Contents |
|---|---|
| [English entry point](../../README.en.md) and [documentation index](../en/README.md) | Nine guides covering setup, players, architecture, operations, contributions, terminology, roadmap, and the complete economy constitution, with an index |
| [Revised Chinese entry point](../../README.md) | Correct source-checkout build steps, language navigation and an explicit boundary between the plugin repository and private deployment |
| [Current source review](CURRENT_SOURCE_REVIEW.md) | Existing strengths, seven prioritized findings, source evidence, and acceptance criteria |
| [Progression audit refresh](PROGRESSION_SEMANTICS_AUDIT.md) | Historical draft retained and clearly superseded by a current call-site review |
| [Localization review](LOCALIZATION_REVIEW.md) | Six bilingual resource groups, language-selection behavior, confirmed gaps, and client acceptance steps |
| [English onboarding preset](../../examples/en_us/README.md) | Four text-only overlays and a generator for review copies; 25 approved string fields |
| [Locale checker](../../scripts/check-locales.py) | Duplicate keys, key/type parity, placeholders, required bundles and malformed data |
| Runtime presentation repairs | Bounded skill-slot placeholder parsing and a governance confirmation that renders without unresolved tokens |
| Dependency fetcher repair | Read-only `--check`, verified temporary downloads, atomic promotion, and failure cleanup |

This work changes presentation, documentation and developer tooling. It does not implement the remaining economy simulator or multi-node protocol. Those are separate phases with their own invariants and runtime acceptance requirements.

## Runtime and tooling fixes

### Skill HUD placeholders

The original pattern generated `skill_(1-4)_(...)`. That accepts the literal `1-4`, rather than the four valid slot numbers. The new [`SkillPlaceholderKey`](../../plugins/rpg-integrations/src/main/java/cn/originalrpg/integrations/SkillPlaceholderKey.java) parses a positive integer, checks the configured bound, and returns an unknown-key result for malformed or overflowing input. The actual PlaceholderAPI expansion now calls this parser.

The regression covers all four slots and seven fields, malformed ranges, zero/negative/out-of-range slots, leading zeros, overflowing numbers, unknown suffixes, and configurable multi-digit bounds. A Paper/BetterHud client rendering test is still required.

### Governance messages

The command handler used an event-announcement key without supplying the event's placeholders. It now uses the already-shipped, parameter-free `community.governance.effective-ok` confirmation. The English event announcement also retains the `{player}` token present in Chinese. The existing two-approval behavior test now renders both real language bundles and rejects unresolved placeholders.

Installed language overrides are preserved by the runtime loader. An existing server must merge the one updated English event sentence into its maintained language file; a new JAR alone does not overwrite an operator's old translation.

### Dependency verification

Previously, an invalid local file could be deleted by `fetch-deps.sh --check`, and a failed forced download could remove an installed file. The updated script makes check mode non-mutating, rejects `--check --force`, downloads beside the destination, verifies both pinned checksums, and promotes the file only after success. Failed promotion is also covered by fault injection and temporary-file cleanup. CLI diagnostics are now in English.

Vendor integrity remains the responsibility of the existing `check-smoke-lock.py` check. The fetcher reports vendor presence explicitly, without claiming it verified those hashes.

## Tests actually executed

The Java run used **Temurin JDK 25.0.5+8 and Gradle 9.1.0**. Both downloaded toolchain archives were checked against their published/pinned SHA-256 values. The sandbox's existing network proxy and system CA trust store were used to resolve Maven dependencies; TLS verification remained enabled. No repository build versions or dependencies were changed for the test.

The Gradle task arguments were:

```bash
./gradlew --no-daemon \
  :plugins:rpg-integrations:test --tests '*SkillPlaceholderKeyTest*' \
  :plugins:rpg-community:test --tests '*CommunityGovernanceHandlerTest*' \
  --tests '*CommunityGovernanceMessagesTest*' -PexcludeTags=env
```

The downloaded Gradle 9.1.0 launcher was used directly after the wrapper's initial network attempt failed. The command above is the equivalent repository entry point for an environment with configured Java/network access.

| Check | Actual result | Evidence limit |
|---|---|---|
| `SkillPlaceholderKeyTest` | **3 tests passed**, 0 failures/errors/skips | Real parser behavior; no live PlaceholderAPI client |
| `CommunityGovernanceHandlerTest` | **12 tests passed**, 0 failures/errors/skips | Handler behavior, including actual bilingual resource rendering |
| `CommunityGovernanceMessagesTest` | **2 tests passed**, 0 failures/errors/skips | Message-key resource contract |
| Python locale fixtures | **13 tests passed** | Positive and negative resource-parser cases |
| Python dependency-fetch fixtures | **7 tests passed** | Offline local downloads, corruption, read-only checks, replacement failure |
| Python English-preset fixtures | **9 tests passed** | Actual source configs, exact value/type diff, source immutability, refusal cases |
| `python3 scripts/check-locales.py` | **6 groups, 2,995 keys per locale passed** | Checked-in YAML; not a percentage of all visible game text |
| English preset generation | **4 config copies and manifest generated; 25 string overrides** | Preview only; no server configuration installed |
| `sh scripts/check-file-deps.sh` | **Passed** | Nine declared compilation file dependencies have manifest entries |
| `python3 scripts/check-smoke-lock.py` | **Passed: 15 entries, 5 local artifacts, 0 problems** | Local hashes and manifest structure; remote runtime downloads not executed |
| `sh scripts/check-repo-artifacts.sh` | **Passed** | Documented private deployment paths remain explicitly registered |
| Shell syntax and `git diff --check` | **Passed** | Syntax/whitespace only |

Java totals: **17 executed, 17 passed**. Python totals: **29 executed, 29 passed**. These are targeted checks for this change, not an all-project or production certification. The complete Python suite can be rerun with:

```bash
python3 -m pip install PyYAML==6.0.3
python3 -m unittest discover -s scripts/tests -p 'test_*.py' -v
python3 scripts/check-locales.py
```

## Build blocker and unproven items

The dependency fetcher successfully downloaded and verified four of the five public compile-time artifacts: AuthMe, both WorldEdit artifacts, and BetonQuest. The exact pinned Citizens URL returned **HTTP 403** in this execution environment:

```text
https://ci.citizensnpcs.co/job/Citizens2/4231/artifact/dist/target/Citizens-2.0.43-b4231.jar
```

Consequently, dependency preflight correctly remains unsuccessful for a full checkout build. This does not prove that the URL is unavailable from every network. The version/hash pins were not weakened or substituted. The two changed Java modules did not require that artifact and their selected tests completed.

The following remain unproven here:

- A clean, complete build of all 25 plugins and every configured test.
- `[M]` Economic behavior: the current simulator reports 14 `NEEDS_ENGINE` cases; parsing scenarios is not simulation.
- `[R]` Real Paper, PostgreSQL failures, multiplayer recovery, BetterHud rendering, and production deployment.
- `[H]` Human English/Chinese playthroughs, GUI-scale readability, and the roadmap's sustained multiplayer playtest.
- English coverage of external deployment components and the remaining shared/hardcoded surfaces listed in the localization review.

## Constitution, data migration, failure modes and rollback

**Constitution:** C-001 through C-015 remain unchanged. The branch adds no currency, ascension, market, ledger, permission, paid-power path, or economic value adjustment. Translation preserves persistent identifiers and command syntax.

**Data migration:** none. Production Java changes concern message selection and placeholder parsing only. No schema, SQL migration or source gameplay configuration is modified. English defaults are opt-in overlays generated into a new review directory.

**Failure modes:** locale/schema drift fails validation; an existing output directory is refused; corrupt downloads never replace the previous destination; malformed skill placeholders return the normal unknown-key result. Existing deployed translation overrides may need a targeted merge. Shared English NPC names are visible to all viewers, regardless of their personal menu language.

**Rollback:** revert the branch changes to restore the previous code/documentation/tooling. No database rollback is required. Generated preview directories are independent artifacts. If an operator later applies text overlays, restore those fields from the configuration backup taken before that separate deployment action.

## Official sources checked for this review

Checked on 2026-11-03; these sources inform compatibility and design guidance, not claims of local gameplay success.

| Primary source | Relevance |
|---|---|
| [Paper requirements](https://docs.papermc.io/paper/getting-started/) | Paper 26.1+ uses Java 25; matches this repository's target |
| [Gradle Java compatibility](https://docs.gradle.org/current/userguide/compatibility.html) | Java 25 support starts at Gradle 9.1.0; the pinned wrapper is appropriate |
| [Paper 26.2 announcement](https://papermc.io/news/26-2/) | Documents the Adventure 5 transition and version-specific API changes |
| [Paper scheduling](https://docs.papermc.io/paper/dev/scheduler/) | Database work should avoid the tick thread; most world-state operations belong on it |
| [Adventure localization](https://docs.papermc.io/adventure/localization/) | Translation mechanisms differ by surface; global translation does not automatically cover Paper item text |

The evidence supports maintaining the current pinned platform while improving reproducibility and recipient-aware presentation. It does not justify an unrelated server/API upgrade during an internationalization change.
