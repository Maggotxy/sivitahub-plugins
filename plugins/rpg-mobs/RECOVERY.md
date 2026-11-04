# Natural-mob reward recovery

This module now uses one durable recovery path for every custom natural-mob reward, including kills whose player profile is already cached. The local journal stores immutable instructions. **Core profile receipts remain the XP authority, and the existing market mailbox remains the item authority.** The journal is not another balance, inventory, currency or pity-counter ledger.

## Gameplay and upgrade behavior

Vanilla death drops and vanilla XP are unchanged. Custom mutation materials and common Insight Skill Books now go to the existing mailbox; players collect them with `/market claim`. A kill creates a mailbox entry only when its original custom drop roll succeeds, with a maximum of two entries: one material and one book. Growth XP never creates mail. Drop probabilities, guarantee thresholds and XP formulas are unchanged.

This changes the previous cache-hit path, which spawned custom ground items. Ground entities cannot be replayed safely after a crash because a player may already have picked them up. All custom drops therefore use the same existing idempotent mailbox path. Rare-drop effects and guarantee feedback occur after authority acknowledgement; mailbox reminders are rate-limited. Growth XP feedback remains merged in the action bar, and level-up feedback is immediate after the profile result.

`MarketService.enqueueDelivery` currently has no aggregation operation or per-player pending-mail capacity limit. The claim command processes up to 50 entries per invocation and stops if the inventory cannot accept the next item. This change does not invent a second claim flow or auto-claim items. Monitor mailbox row growth for high-volume farms; the tests verify submission bounds, not sustained production TPS or retention sizing.

The old in-memory XP batching keys under `experience-write-buffer` are no longer used, except `feedback-ticks`. Existing configurations may retain those old keys harmlessly, but they no longer control reward persistence. The `delayed-rewards` section now governs both cached and uncached kills. Old pending memory from a previous plugin process cannot be reconstructed during upgrade. Deploy through a normal shutdown; this feature cannot retroactively recover observations that the old version lost.

## The acceptance boundary

1. On the server thread, capture the kill identity, cached eligibility if available, XP amount, exact random inputs/results and before/after pity counters. Build and serialize at most two custom items on that same thread.
2. Reserve bounded volatile queue capacity. `QUEUED_NOT_DURABLE` means precisely that: **the observation can still be lost if the process exits before the journal acknowledgement**. Rejected observations do not advance pity counters and do not issue a custom reward. The player receives a rejection message; vanilla drops continue normally.
3. One filesystem worker writes a bounded, versioned, checksummed staging file, forces its contents, atomically renames it to `<kill-id>.mob-reward`, and forces the containing directory. Only a successful acknowledgement permits an authority call. Unsupported atomic rename or directory sync is a failure; there is no weaker fallback.
4. If eligibility was unknown at death, obtain the first later authoritative profile snapshot, resolve the level-gap gate and persist that decision through the same boundary **before** issuing XP or items.
5. Submit the fixed XP receipt through `ProfileService.grantExperienceOnce`. Submit each original serialized item through `MarketService.enqueueDelivery` with its fixed delivery ID and source. A partial success or lost response retries the same immutable content.
6. After every required authority acknowledges the reward, delete the intent and force the directory. A failed cleanup is retried; an old surviving intent is safe to replay against the same authoritative receipts.

The kill ID is derived once from the player UUID, entity UUID and observed death timestamp. The XP receipt and item delivery IDs are derived from that frozen kill ID and operation kind. Retry, shutdown and recovery never mint replacement IDs or deserialize and regenerate item bytes.

### Eligibility and pity are explicit boundaries

A cached profile supplies the qualification snapshot at death. If no cached profile exists, the persisted record is a **conditional candidate**, not a claim that the player was eligible at death. The first later successful authoritative lookup supplies that snapshot. Unknown candidates in the same pending player queue share its first loaded level, matching the existing delayed queue behavior. This snapshot can differ from the player's historical death-time level if profile loading was unavailable. Once a candidate's resolution is durably saved, recovery never recomputes it, even if the player has since leveled up.

The existing player PDC remains the pity-counter authority. Its counter changes once after volatile admission, and the original random result is frozen in the intent. Recovery never rerolls and never writes an old counter value over the player's current PDC. Player-data saving and journal acceptance are **not an atomic transaction**. An abrupt exit between those persistence boundaries can save the counter without the reward, or the reward without the latest counter. A missing-profile candidate can also advance the original counter before a later ineligible result, as in the previous delayed path. Fixing that boundary requires a separate authoritative transactional pity design; this change does not hide a second counter ledger in the journal.

## Bounded work and overload

The defaults are in `src/main/resources/config.yml`:

| Control | Default | What it bounds |
| --- | ---: | --- |
| `maximum-pending` | 512 | Total queued or replaying kill intents |
| `maximum-per-player` | 32 | New observations admitted for one player |
| `maximum-payload-bytes` | 16 MiB | Conservative serialized-payload admission budget and journal file budget |
| `maximum-journal-operations` | 16 | Running plus queued filesystem operations on one worker |
| `maximum-profile-loads` | 16 | Concurrent missing-profile lookups |
| `maximum-experience-writes` | 16 | Concurrent core XP receipt calls |
| `maximum-mailbox-writes` | 32 | Concurrent mailbox calls |
| `maximum-settlements-per-tick` | 16 | Protocol steps started per server tick, not whole kills |
| `retry-ticks` / `retry-maximum-ticks` | 20 / 200 | Bounded exponential retry delay |
| `shutdown-timeout-ms` | 5,000 | Maximum normal shutdown wait for the final journal pass |

One record is limited to 256 KiB, at most two deliveries and two pity snapshots. A serialized item payload is limited to 120,000 Base64 characters. The payload admission budget is a conservative serialized size, not a claim about JVM heap usage. An atomic rewrite can temporarily occupy one additional record-sized staging file. Startup scanning, file sizes, record counts and accepted payload totals are all bounded. Abandoned staging files count toward journal disk limits.

The per-player FIFO settles one kill at a time for a player, while later observations can still be journaled promptly. Recovery orders surviving files by captured timestamp and kill ID; kills observed within the same millisecond have a deterministic tie-break, not a persisted event sequence. Their item rolls, XP amounts and resolved eligibility remain frozen independently. Different players share the configured authority budgets. This trades the old volatile multi-kill XP batch for one authoritative XP receipt per accepted kill; it increases receipt/write volume while making each reward replayable. No TPS or database throughput claim is implied by the concurrency tests.

A disk failure pauses new custom reward admissions while existing observations retry. Database failures retain accepted files and back off; they do not expire a reward by age or attempt count. Capacity remains finite, so a prolonged outage eventually rejects new observations before acceptance. The player message and rate-limited operator log make that boundary visible. There is no silent promise that an unaccepted kill is recoverable.

## Restart and shutdown

The journal directory is `plugins/RpgMobs/mob-reward-journal` under the plugin's actual data folder. Do not relocate only the JAR while leaving accepted reward files behind.

Recovery completes on the filesystem worker before new observations are admitted. Final files are checksum-validated and synced again before replay. A surviving final file from an unacknowledged rename is reconciled safely; a `.staging` file is never treated as accepted. Corruption, unknown versions, conflicting IDs, unsupported filesystem guarantees or a configured limit below the recovered backlog fail closed and preserve evidence.

A local writer lock prevents two plugin instances from writing this directory simultaneously. It is **not** a distributed player lease or an authorization mechanism. Core services continue to own player/session and receipt policies. Accepted reward recovery calls the offline-capable XP and mailbox authority methods; it does not impersonate the old connection.

On normal shutdown, new work and continuations stop. The worker may make one final bounded persistence pass for observations whose durability was not yet acknowledged. It does not drain the database, change operation IDs or retry the disk forever. The worker is a daemon, so an unresponsive disk cannot keep JVM shutdown alive indefinitely. A timed-out write can still finish if the process remains alive; until confirmed, its status is **unconfirmed**, not definitely saved or definitely lost. The log records that distinction and a bounded sample of unconfirmed kill IDs.

A departed or authentication-revoked connection loses its UI token. Accepted rewards continue to settle offline, but late callbacks cannot notify a replacement session or recreate gameplay state on the old one.

## Operating and investigating recovery

- Preserve the journal and authoritative database as one deployment/backup unit. Restore a consistent backup, or allow the existing core receipts to deduplicate preserved intents. Restoring an old journal against a database with deleted receipts can defeat replay protection; do not prune receipts independently of the supported recovery horizon.
- Keep accepted `.mob-reward` files until the protocol confirms cleanup. Do not hand-edit IDs, amounts, payloads, checksums or resolved eligibility, and do not clear a backlog to silence a warning.
- Inspect an `UNCONFIRMED` shutdown report separately from a recovered accepted intent. A pre-rename `.staging` file does not establish durable acceptance and is retained for diagnostics. Its existence is not authorization to issue an ad hoc grant.
- If recovery is blocked by a deliberately reduced budget, restore the previous sufficient limit and restart. A lower per-player admission limit does not discard an already recovered player queue.
- If a checksum, identity or authority-content conflict persists, preserve the original files and database rows for investigation. The plugin fails closed instead of replacing authoritative content.
- Track recovery readiness, repeated operation failures, pre-acceptance rejection counts, mailbox growth and free disk space. Failure logs are exponentially rate-limited; absence of a new log line on every tick is not evidence that an outage has ended.

## Verification and scope

The runtime code is exercised by behavior tests, rather than source substring checks:

- `MobRewardJournalTest`: real temporary files, exact item/roll round trips, one-way eligibility, identity conflicts, corruption/truncation/version errors, filename mismatch, writer locking, file/count budgets, fsync fault points, cleanup retry and retained staging files.
- `MobRewardProcessRecoveryTest` with `MobRewardCrashFixture`: separate JVMs halt without shutdown hooks before rename, after rename and after directory sync. These tests prove process-exit recovery on the test filesystem; they do not simulate power loss, an unreliable storage controller or a network filesystem.
- `MobRewardSettlementTest`: no authority work before acknowledgement, qualification persistence before rewards, lost commit replies, partial mailbox completion, cleanup failure, restart with changed player level, ineligible candidates, long database outages, per-player ordering and bounded load/admission.
- `MobRewardAsyncLifecycleContractTest`: actual single-worker I/O, bounded queue rejection, daemon lifecycle, finite shutdown with explicit uncertainty, final-pass monotonicity and completion handoff to the owner thread. It replaces the old implementation-shaped entry in `scripts/source-contract-specs/all.json`.
- `ExperienceWriteBufferTest`: stable core receipt IDs and amounts, unresolved/ineligible gating, invalid authority result handling, revoked UI sessions and shutdown without an independent volatile replay path.

Run with the repository's pinned Java/Gradle toolchain: `./gradlew :plugins:rpg-mobs:test`. A Paper runtime pass is still required to verify real death events, item serialization/claim, player-data persistence, authentication handoff, visual timing and deployment disk/database latency. Unit and process tests do not substitute for those checks.

Implementation references: Java 25 [`FileChannel.force`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileChannel.html#force(boolean)) and [`Files.move` / `ATOMIC_MOVE`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Files.html#move(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption...)). End-to-end durability also depends on the configured database and storage guarantees; see PostgreSQL's [write-ahead logging documentation](https://www.postgresql.org/docs/current/wal.html).
