# Runtime mechanism control

[Developer handbook](../en/DEVELOPER_HANDBOOK.md) · [Operations](../en/OPERATIONS.md)

`/rpgadmin mechanism` exposes the existing core mechanism registry to the authorized server console. It does not create a second switch authority, change prices, or rewrite drop rates. The command has a single pending-operation gate through both the database completion and scheduled console callback.

## Commands

```text
/rpgadmin mechanism list
/rpgadmin mechanism disable market.escrow.enabled CONFIRM incident investigation
/rpgadmin mechanism enable market.escrow.enabled CONFIRM recovery evidence reviewed
```

Run these through the server console with `rpg.admin`. Player and other sender types are rejected. `enable` and `disable` require the exact `CONFIRM` token and a nonblank reason of at most 56 characters. The persisted `updated_by` label is `console:` plus that reason. Unknown mechanism IDs, control characters and oversized labels are rejected before database dispatch.

The registry contains the hunt, dungeon and commission faucets, market escrow, season settlement, operational Starlight grants, support-payment collection and community projects. Use `list` for the exact current IDs. A raw database row for an unregistered mechanism cannot enable an undeclared runtime path.

## Timing and authority

The durable `rpg_mechanism_switches` row is authoritative. A successful change records the state, operator label and timestamp; the command also writes the committed decision and reason to the server log. This table retains the latest decision, not an immutable history of every toggle. Preserve server logs when an incident requires the full operation sequence.

Ordinary cached admission checks use an immutable snapshot. Core refreshes it asynchronously every two seconds through the bounded persistence executor, with at most one refresh in flight. The Paper tick thread and dedicated node-lease executor do not perform registry JDBC. A snapshot older than ten seconds fails closed, and a failed refresh/control write closes cached admission immediately until a complete read succeeds. This bounds stale admission during queue saturation or a failed refresh; it is not an instantaneous cancellation of already accepted work.

Support-payment acceptance is stricter: it locks the authoritative payment switch as the first business-transaction row and retains it through commit. A concurrent disable waits for transactions already holding that lock; transactions admitted after disable commits see it closed on every node. The payment path does not use an old local cache to override that check. Configuration-disabled payment collection cannot be reopened through the registry alone.

## Interpret results and counters

A successful command means the durable control write was confirmed. If the response says the operation was not confirmed, run `list` before retrying: the commit may have succeeded even though its reply was lost. Repeating an explicit desired state is safe; interpreting an error as rollback is not.

`allowedChecks` and `deniedChecks` are counts in this Paper process. They count gate checks, including retries, and are neither globally aggregated metrics nor a count of settled rewards. Economic settlement evidence remains in the corresponding transaction ledger.

Disabling new admission does not reverse committed trades, delete accepted rewards or cancel refunds. Use each mechanism's documented recovery path for accepted work. The market cancellation/expiry paths retain their proved-hold refund semantics. A switch also does not substitute for the separate configuration gates on advanced governance, season operations or multi-node integration.

## Verification

`MechanismRegistryPersistenceTest`, `MechanismKillSwitchContractTest`, `JdbcMechanismControlServiceTest` and `MechanismControlCommandTest` cover durable state, unknown mechanisms, snapshot expiry, interrupted refresh, failed and uncertain commits, console/permission/confirmation checks, bounded pending work and callbacks after closure. `SupportPaymentGateTest` covers payment's cross-node transactional admission boundary.

For an operator rehearsal, use a disposable server/database: record initial state, disable a named source, confirm rejected new admission and retained recovery, inspect the row/log, restore the reviewed prior state, and record the result. Do not run a rehearsal against production balances merely because the control command exists.
