# Community governance and shared construction supplies

This guide describes the Phase 6 implementation in `RpgCore`, `RpgCommunity` and `RpgMarket`. Community membership remains in the existing Core tables. Shared materials use the existing market escrow and mailbox; each withdrawal records an exact recipient and requires two independent current governors. No community operation creates a currency, equipment state machine or combat bonus. The governing constraints are in the [constitution](GAME_ECONOMY_CONSTITUTION.md).

Source and automated tests establish specific transaction and adapter behavior. PostgreSQL test results, a Paper deployment, inventory crash recovery and human acceptance must be recorded separately for the revision being released. Enabling a configuration flag does not establish those forms of evidence.

## Configuration and rollout

The shipped defaults keep advanced governance and storage disabled. Enable the applicable gates together in a reviewed staging configuration:

```yaml
# plugins/RpgCore/config.yml
community:
  governance-enabled: true
  storage:
    enabled: true
    maximum-stacks: 128
  lifecycle:
    owner-inactivity-days: 30
    candidate-membership-days: 7
    succession-cooling-hours: 72
    dissolution-cooling-hours: 72
```

```yaml
# plugins/RpgCommunity/config.yml
community:
  governance:
    enabled: true
```

These examples explicitly opt in; the repository defaults remain `false`. `maximum-stacks` is clamped to 1–500. Capacity counts stored, prepared and quarantined stacks. A donor also has a limit of 128 unresolved or stored community stacks across communities. Deposits require the existing `market.escrow.enabled` mechanism to be enabled. Use the matching plugin build and restart the server to apply these service-construction settings. [Core configuration](../../plugins/rpg-core/src/main/resources/config.yml), [community configuration](../../plugins/rpg-community/src/main/resources/config.yml), [storage authority](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcCommunityStorageService.java).

Keep the existing market inventory protections and claim recovery enabled. `RpgMarket` supplies the physical deposit adapter. If the adapter is unavailable, the community command reports that condition before removing an item. Closing the new-action gates leaves accepted preparation recovery available through Core and the authenticated market recovery path.

## Roles and signed role changes

Persisted roles are `OWNER`, `OFFICER`, `TREASURER`, `BUILDER`, `EVENT_HOST`, `RECRUITER` and `MEMBER`. `LEADER` and `DEPUTY` are accepted aliases for the first two. `OWNER`, `OFFICER` and `TREASURER` can sign governance decisions. Domain roles can initiate their permitted domain actions; they do not acquire approval power. [Role definitions](../../plugins/rpg-api/src/main/java/cn/originalrpg/api/model/CommunityRole.java), [capability policy](../../plugins/rpg-api/src/main/java/cn/originalrpg/api/model/CommunityRolePower.java).

```text
/community roles
/community role TREASURER
/community assign <player> <role>
/community approvals community.role-grant
/community approve community.role-grant
/community deny community.role-grant
```

`assign` defaults to `RECRUITER` if its role argument is omitted. The first governor creates a request whose persisted subject is `targetUUID:ROLE`; the next governor approves that recorded intent. A second caller cannot replace the requested role. The target cannot sign their own role change. `OWNER` is excluded from this assignment path; ownership uses the explicit handover or succession workflow. Legacy pending rows that contain only a UUID must be denied and proposed again with an explicit role. [Intent format](../../plugins/rpg-api/src/main/java/cn/originalrpg/api/model/CommunityRoleIntent.java), [signed implementation](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcCommunityService.java).

The same person cannot supply two signatures. A community with fewer than two eligible governors cannot complete these decisions. The existing `/community transfer <player>` followed by its confirmation performs a real voluntary ownership handover and leaves the former owner as `OFFICER`. No implicit administrator approval or reduced threshold is provided for small communities.

## Succession and dissolution

`/community lifecycle` shows the centralized timing policy, incumbent activity and recent proposals. Only one lifecycle proposal can remain active for a community.

| Workflow | Who proposes | Conditions before the first signature | After an independent second signature |
|---|---|---|---|
| `/community succession propose` | A current eligible governor nominates themselves | The owner has not authenticated for 30 days; the candidate has belonged continuously for at least 7 days | The 72-hour succession cooling period begins |
| `/community dissolve propose` | The current owner | Active community and an eligible owner membership | The 72-hour dissolution cooling period begins |

The durations above are defaults. Review or act on the exact proposal UUID:

```text
/community succession approve <proposal-id>
/community succession execute <proposal-id>
/community succession cancel <proposal-id>
/community dissolve approve <proposal-id>
/community dissolve execute <proposal-id>
/community dissolve cancel <proposal-id>
/community history [community-id]
```

Execution is an explicit action after the deadline. It rechecks ownership, current roles, candidate membership incarnation and every signature. Departure, rejoining, demotion or a changed incumbent can invalidate an earlier proposal. An authenticated return by the incumbent cancels pending succession. Authenticated activity is recorded on the verified authentication event and in bounded online refreshes, including while advanced governance is disabled. Offline reward replay does not update this timestamp. Existing memberships receive a migration-time grace period rather than being treated as historically inactive. [Lifecycle authority](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcCommunityLifecycleService.java), [authentication and command adapter](../../plugins/rpg-community/src/main/java/cn/originalrpg/community/CommunityLifecycleController.java).

Succession promotes the candidate to `OWNER` and changes the former owner to `OFFICER`. Dissolution writes a `dissolved_at` tombstone, detaches memberships and invitations, and closes pending governance work. It retains the community name/tag, contributions, project history and personal honor. Browse, membership, invitation acceptance and contribution paths consistently exclude dissolved communities. Gold contributions are not refunded, and no world is deleted.

## Shared construction supplies

The community menu provides **Public construction supplies** and **Community projects** entries. `/community storage` opens the same supply UI as `/communitystorage list`.

| Command | Behavior |
|---|---|
| `/communitystorage list [page]` | Browse the community's stored and unresolved stacks |
| `/communitystorage materials` | Show the exact allowlist |
| `/communitystorage deposit` | Donate the pristine stack in the main hand |
| `/communitystorage withdraw <stack-id> [player]` | A governor requests that exact stack for the named member; recipient defaults to the proposer |
| `/communitystorage pending [page]` | Review exact recipients and current signatures |
| `/communitystorage approve <request-id>` | Another governor signs; the same transaction queues the item in the recipient's mailbox |
| `/communitystorage cancel <request-id>` | A governor cancels the request, leaving the stock stored |
| `/market claim` | The recipient collects through the existing delivery receipt protocol |

Each donation is one stack of 1–64 items. The 25 allowed matchers are stone, cobblestone, stone bricks, andesite, polished andesite, diorite, polished diorite, granite, polished granite, deepslate, cobbled deepslate, deepslate bricks, deepslate tiles, bricks, glass, terracotta, and oak, spruce, birch, jungle, acacia, dark oak, mangrove, cherry and bamboo planks. Any item metadata excludes the stack, including names, enchantments and PDC. Equipment, rare materials and containers have no storage route. Core independently checks the persisted matcher and quantity. [Shared allowlist](../../plugins/rpg-api/src/main/java/cn/originalrpg/api/rules/CommunityStorageRules.java), [main-thread item capture](../../plugins/rpg-market/src/main/java/cn/originalrpg/market/MarketGoodsCapture.java).

The physical deposit follows the existing market protocol: save an inventory marker, persist `PREPARED` custody, save a removal receipt with the physical removal, then activate the accepted listing. The original serialized payload and matching facts remain immutable. The community scope is identified by `rpg_market_listings.custody_community_id`. Ordinary market browsing, direct-ID preparation, activation, purchase, cancellation, expiry and buy-order filling exclude this scope. [Market adapter](../../plugins/rpg-market/src/main/java/cn/originalrpg/market/MarketCommand.java), [ordinary market filtering](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcMarketService.java), [buy-order isolation](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcMarketBuyOrderService.java).

A withdrawal fixes the listing and recipient before the first signature. Only one pending request can reserve a listing. The recipient must remain in the same membership incarnation; leaving and rejoining invalidates old consent. Signers must still be eligible when the second signature executes. In one transaction, Core records that signature, closes the original listing, inserts an idempotent mailbox delivery and appends audit records. The mailbox source is `community-storage:<request-id>`. Recipient identity and the original payload cannot be changed at claim time.

Leaving a community does not reclaim a donation. A successful dissolution returns all remaining `ACTIVE` stocks to their original donors, including donors who have left, using `community-return:<listing-id>` deliveries. It voids pending withdrawals. Previously withdrawn goods are not reversed. All returns and the dissolution tombstone commit together; a failed return rolls the entire decision back. [Shared delivery and dissolution transaction](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/CommunityStorageTransactions.java).

## Recovery and audit

Authenticated market recovery reads both ordinary and community preparations before classifying any saved marker as orphaned. A failed or overlapping snapshot cannot clear markers. A saved removal receipt permits activation; an ambiguous preparation is quarantined. The current recovery implementation does not automatically release `QUARANTINED` items. A `PREPARED`, `QUARANTINED` or structurally invalid custody row blocks dissolution before any remaining stock is returned. Review the original donor data, marker/removal receipts, listing and audit together; preserve evidence rather than fabricating a replacement item. [Recovery snapshot](../../plugins/rpg-market/src/main/java/cn/originalrpg/market/PreparedEscrowBatch.java), [mailbox delivery contract](../../plugins/rpg-market/src/test/java/cn/originalrpg/market/MailboxDeliveryIntegrationContractTest.java).

`/community history <community-id>` remains available after dissolution. `rpg_community_lifecycle_audit` records role intent/application, lifecycle transitions, custody preparation/activation/quarantine, withdrawal requests and returns. Approval rows retain the identities that signed; the durable audit survives the cleanup of transient approval state. The relevant storage and lifecycle schema is appended by [Database migrations](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java) and enumerated in the [schema manifest](../../tools/db-migration/src/main/java/cn/originalrpg/tools/dbmigration/SchemaManifest.java).

Services execute SQL asynchronously and lock the authoritative community before mutating related membership, proposal or custody state. UI callbacks return to the Paper thread and verify the exact authenticated player connection. A committed transaction can outlive a disconnected UI; retrying its durable identity must not create a second outcome.

## Civilization history and construction scope

`/civilization projects [page]` displays completed weekly projects and their recorded contributors, verified recruits and honor. `/civilization seasons [page]` displays completed season settlements. These are read-only views over existing authoritative facts. A dissolved community remains represented in its completed history. The archive does not capture map files or claim that a structure was built. [Archive service](../../plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/JdbcCivilizationHistoryService.java).

The `community.project` and `community.event` approval actions represent authorization with role and signature checks. Shared materials support player-led construction. Automatic world construction, plot ownership and a scheduled event executor require their own reviewed implementation; receiving an approval event does not establish any of those outcomes.

## Reproducible verification

Run the transaction tests against H2 using the production migration set:

```sh
./gradlew :plugins:rpg-core:test \
  --tests '*JdbcCommunityStorageServiceTest' \
  --tests '*JdbcCommunityLifecycleServiceTest' \
  --tests '*JdbcCommunityGovernanceTest' \
  --tests '*JdbcCommunityApprovalPersistenceTest' \
  --rerun-tasks
./gradlew :plugins:rpg-community:test :plugins:rpg-market:test :plugins:rpg-api:test
```

For the same transaction tests on a dedicated PostgreSQL test database, set `SIVITA_COMMUNITY_TEST_POSTGRES_URL`, `SIVITA_COMMUNITY_TEST_POSTGRES_USER` and `SIVITA_COMMUNITY_TEST_POSTGRES_PASSWORD` in the test environment, then rerun the first command. The URL must use `jdbc:postgresql:` and omit `currentSchema`. Credentials stay outside version control. All nonempty `RPG_DATABASE_*` runtime overrides are rejected. Each fixture creates and verifies a random `sivita_community_test_<uuid>` schema, runs real migrations there and drops only that schema on cleanup. [Independent Phase 6 fixture](../../plugins/rpg-core/src/test/java/cn/originalrpg/core/persistence/CommunityDatabaseFixture.java).

The behavior tests cover concurrent requests, duplicate signatures, stale membership, changed roles, lost commit acknowledgements, rollback after mailbox insertion, blocked partial returns and atomic dissolution. Adapter tests cover authentication, exact connection handles, deferred callbacks and the immutable recipient. A release record should additionally demonstrate real Paper inventory interruption/reconnect recovery, GUI container isolation and PostgreSQL execution for its exact build.
