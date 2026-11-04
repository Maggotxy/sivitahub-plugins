# Funded buy/crafting orders: migration and recovery

This additive Phase 4 change closes the player fulfillment path and removes raw-payload settlement. It preserves the single market, core gold ledger, signed item lifecycle and mailbox. The implementation is `JdbcMarketBuyOrderService`; behavioral evidence comes from `JdbcMarketBuyOrderServiceTest`, which runs against the actual runtime schema.

## Schema changes

`rpg_market_listings` gains nullable `item_matcher`, `crafter_uuid` and default-zero `item_quantity`. These are immutable facts captured from the actual item before PREPARED escrow. Existing listings retain their ordinary purchase/cancel/expire behavior; no metadata is guessed from opaque item bytes. They must be cancelled, claimed and re-listed to join order matching.

`rpg_market_buy_orders` gains `order_kind` (BUY by default), `original_quantity`, `placement_key` and `escrow_key`. Two indexes cover active-order expiry and per-buyer open-order limits. There is no new wallet, item ledger or delivery table. Newly placed BUY and CRAFT orders share the combined limit of twelve open orders per buyer, enforced under the account lock.

CRAFT means a funded request for equipment made by the delivering player, with materials supplied by that maker. It does not hold buyer-provided crafting materials. Matching is exact on equipment type and rarity, and the server checks the original crafter and item identity. Random stats are visible in the existing listing; no extra random result is rolled during settlement.

## Funded-hold invariant

At every externally visible commit, an ACTIVE order's current unfilled quantity times unit price equals a genuine, unreversed debit for that buyer. Settlement checks the debit's player, amount, zero fee, direction and order-specific reason. It cannot refund from a submitted amount alone.

A partial fill performs the following inside one transaction:

1. Lock the order and the existing ACTIVE item listing; recheck matcher, quantity, seller and maker.
2. Lock buyer/seller accounts in stable UUID order and verify the current hold.
3. Reverse the entire current hold with `reversal_of` pointing to the equal-amount debit.
4. Record the consumed purchase, pay the seller and burn 5% or 10% according to item kind.
5. Debit a new hold for any remaining quantity and store its key on the same order.
6. Mark the item listing SOLD, release its uniqueness guard, advance signed identity ownership, and create its mailbox delivery.

The buyer's spendable balance has zero net movement during a fill: its reserved budget pays both purchase and the next hold. Cancellation/expiry reverses the remaining hold and credits the buyer. `rpg_ledger.reversal_of` remains unique. Internal hold/release rows are accounting movements, not new reward faucets; economic reporting must not classify every receiver-only row as newly minted money.

## Dry-run on an existing installation

Stop new order placement while reviewing old ACTIVE rows. Compare each order with its original `rpg_ledger` debit by `reason = 'buy-order-escrow:' || order_id`, matching buyer, null receiver, zero fee and `price * quantity`. Check for an existing reversal. Investigate duplicate candidates, missing receipts, old partial fills and out-of-band ledger edits.

An old fully funded order with one exact unreversed debit can be safely adopted on its first cancellation/expiry/fill. An old partial order whose original debit is greater than its remaining budget returns `ESCROW_UNVERIFIED`: neither a guessed partial refund nor an unbacked payout is created. Preserve the row, original trades and mailbox evidence for an operator reconciliation. No automatic guess converts an ambiguous legacy hold into money.

## Failure and recovery

| Failure | Result |
|---|---|
| Repeat exact fill identity | DUPLICATE; no second item or payout |
| Reuse key for a different listing/player | IDEMPOTENCY_CONFLICT |
| Another seller fills first | State/quantity recheck rejects the losing operation |
| Ordinary purchase wins the same listing | The fill cannot consume that listing |
| Delivery insert/identity update fails | The whole transaction rolls back |
| Buyer disconnects/full inventory | Settled goods remain in the existing mailbox |
| Seller disconnects after submitting | Persisted settlement remains authoritative; inspect/replay the same request |
| Refund would exceed balance cap | BALANCE_LIMIT; ACTIVE hold remains recoverable after space is available |
| Legacy hold cannot be proven | ESCROW_UNVERIFIED; preserve evidence for reconciliation |
| Market kill switch disabled | New placement/fulfillment blocked; cancellation/expiry remain available |

The periodic market expiry task now scans both sell listings and funded orders with the existing 168-hour policy. A clock change or a repeated scan cannot make a terminal order refund twice. Operators should investigate unverified or balance-capped orders instead of deleting them.

## Rollback

Freeze placement/fulfillment, let current transactions finish, then drain ACTIVE orders with receipt-backed cancellation. Preserve all hold/reversal/trade rows. Keep the additive schema columns and indexes. Do not downgrade to the old raw-payload fill implementation while funded orders remain; that implementation cannot maintain the new receipt invariant. Restore the previous binaries only after reviewing the drained state and their compatibility with additive fields.

## Runtime acceptance

Automated JDBC tests cover funds, exact replay/conflicts, partial refund receipts, maker validation, the equipment fee/lifecycle, competing purchase paths, injected delivery failure, expiry, balance caps, quota and legacy holds. The conditional PostgreSQL run uses a disposable random schema. A Paper/client acceptance pass must still exercise `/market sell`, order placement, confirmation, fulfillment, mailbox claiming and reconnect with real serialized items. H2 tests alone do not prove player-data file recovery or cross-node inventory transfer.
