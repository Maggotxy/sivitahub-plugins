# Support Credit: a developer integration contract

This implementation provides a **signed settlement receipt adapter and a cosmetic-only store**. A trusted external bridge verifies the actual payment provider's settled order, signs a bounded statement, and imports it through the service API or server console. The plugin contains no payment-provider credentials, card collection, checkout gateway, fiat exchange rate, or private signing key.

The shipped configuration disables the feature. Setting up an external payment provider remains an operator integration task. The automated tests establish source and transaction behavior; they do not prove a live provider integration or long-term operation.

## Authority and purchase flow

`JdbcSupportCreditService` extends the existing `rpg_support_credit_accounts` and `rpg_support_credit_ledger` authority. It never updates Gold, Merit, Starlight, gameplay membership, equipment, materials, progression, or mailbox item payloads.

A successful import commits all of the following in one database transaction:

1. The globally unique provider-prefixed `orderId` and exact authenticated payload.
2. A `GRANT` audit ledger row and the recipient's Support Credit balance.
3. The recipient's UTC-week issuance counter and the global UTC-week issuance counter.

A cosmetic checkout commits a unique purchase UUID, a `SPEND` ledger row, the balance debit, and an entitlement together. The entitlement primary key `(player_uuid, cosmetic_id)` and account lock prevent multiple charges for the same appearance, including concurrent checkouts with different operation IDs.

The finite catalog maps to real renderers in `rpg-cosmetics`:

| API enum | Stable purchase ID | Renderer | Selection command |
| --- | --- | --- | --- |
| `PRISMATIC_AURA` | `aura-prismatic` | Prismatic resonance aura | `/aura select prismatic` |
| `ELYTRA_WINGS` | `trail-wings` | Elytra wing trail | `/trail select wings` |
| `ELYTRA_COMET` | `trail-comet` | Elytra comet trail | `/trail select comet` |

Operators may configure positive integer prices for these IDs. Unknown IDs fail configuration validation. There is no generic item, command, material, membership, or arbitrary target purchase API. The old unsigned `grant` and generic `spend` methods remain binary/source compatibility entry points that fail closed.

`/support` displays the balance and catalog. `/support buy <id>` creates one preview tied to the current authenticated connection. `/support confirm <preview-uuid>` confirms the displayed price within 60 seconds. A preview is consumed once; reconnect or reauthentication invalidates it. A submitted transaction retains its single-flight slot until its own completion, even when its connection leaves.

## Trusted bridge responsibilities

The bridge must independently authenticate the provider notification, establish that the provider order is settled, bind it to the intended canonical player UUID, and determine the credit amount from its own reviewed product table. Its durable provider-order record must reuse the same `orderId` and signed payload on retry. Prefix order IDs with the provider/account namespace so that two providers cannot accidentally reuse the same identifier.

The plugin trusts the configured Ed25519 public keys to authorize issuance. Possession of a matching private key therefore authorizes issuance within the configured caps. Keep signing keys in the external bridge's secret management. The game server receives **only** X.509 SubjectPublicKeyInfo-encoded public keys, Base64-encoded without PEM headers. Key IDs using letters, digits, hyphens or underscores are portable in Bukkit configuration paths.

Use a different `audience` for each independent live economy and each test environment. A development receipt cannot be accepted by a deployment with a different audience. Sharing one database means sharing its order namespace and quota authority.

## Version 1 signing format

Sign the exact ASCII bytes below using pure **Ed25519**. Use LF (`\n`), the exact field order, and one final LF. Do not sign JSON, normalize whitespace, reorder fields, add a BOM, or use platform line endings.

```text
SIVITA_SUPPORT_RECEIPT
schema=1
keyId=bridge-2026
audience=sivita-production
orderId=provider:order-17
playerUUID=12345678-1234-5678-9abc-123456789abc
creditUnits=500
issuedAt=1804075200
expiresAt=1804075800
currency=SUPPORT_CREDIT
```

The values above are an interoperability fixture, not a live order. `SupportReceiptCodecTest` independently asserts these exact bytes.

| Field | Validation |
| --- | --- |
| `schema` | Exactly `1` |
| `keyId` | 1–48 ASCII letters, digits, `.`, `_`, `-`; must name a configured trusted public key |
| `audience` | 1–64 ASCII letters, digits, `.`, `_`, `:`, `-`; exact deployment match |
| `orderId` | 1–96 ASCII letters, digits, `.`, `_`, `:`, `-`; unique across the database |
| `playerUUID` | Canonical lowercase UUID with hyphens |
| `creditUnits` | Canonical decimal integer, 1–1,000,000,000, further restricted by deployment limits |
| `issuedAt`, `expiresAt` | Nonnegative Unix epoch seconds, no fractional seconds; expiry strictly after issuance |
| `currency` | Exactly `SUPPORT_CREDIT` |

The envelope is `SignedSupportReceipt(payload, signature)`:

- `payload`: unpadded Base64url of the canonical ASCII bytes, at most 2,048 characters.
- `signature`: unpadded Base64url of the 64-byte Ed25519 signature, exactly 86 characters.

The Java bridge can depend on the `rpg-api` artifact and use the same public types:

```java
SupportReceipt receipt = new SupportReceipt(
        1, trustedKeyId, deploymentAudience, providerPrefixedOrderId,
        canonicalPlayerUuid, reviewedCreditUnits, issuedAt, expiresAt,
        SupportReceipt.CURRENCY);
Signature signer = Signature.getInstance("Ed25519");
signer.initSign(privateKeyFromExternalSecretStore);
signer.update(SupportReceiptCodec.bytes(receipt));
SignedSupportReceipt envelope = new SignedSupportReceipt(
        SupportReceiptCodec.encode(receipt),
        Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()));
// Persist this exact envelope alongside the provider order before sending it.
```

An in-process trusted adapter calls `SupportCreditService.acceptReceipt(envelope)` and awaits its `CompletionStage`. The console transport is:

```text
rpgcommerce support-receipt <payload-base64url> <signature-base64url>
```

The command accepts only the server console and requires `rpg.commerce.support.import`; an operator player cannot use it. The public service independently verifies the signature, audience, receipt shape, clock bounds, quotas and kill switch. Neither transport bypasses the core checks. Do not call `join()` on a Paper event thread; cryptography and JDBC run on the bounded core executor.

## Deployment policy

Configure `support-credit` in `RpgCore/config.yml`. The shipped values have `enabled: false`, an empty audience, no trusted keys, zero issuance caps and no cosmetic prices. The database mechanism `commerce.support_credit_payment.enabled` also defaults to false. Both gates must permit issuance. After the deployment policy has been reviewed and loaded, the trusted server console can control the persistent gate without restarting:

```text
rpgcommerce support-payments status
rpgcommerce support-payments enable
rpgcommerce support-payments disable
```

These commands require `rpg.commerce.support.control`. The returned boolean is the **effective** state: enabling the database gate cannot override `support-credit.enabled: false`. The command performs JDBC work asynchronously. A failed or uncertain control response must be checked with `status` before further imports.

Each receipt transaction reads and locks the authoritative mechanism row before financial locks. Other nodes' cached switch values do not authorize or reject its acceptance. An already admitted transaction holds the gate row through commit; a disable waits for it. Once the disable commits, subsequent imports on all nodes reject until an explicit enable commits. A concurrent administrator may later re-enable the gate, so status represents its current state rather than a permanent promise.

| Configuration field | Meaning |
| --- | --- |
| `enabled` | Enables this deployment policy and its cosmetic catalog |
| `audience` | Exact signing audience |
| `trusted-keys` | Up to 16 named Ed25519 X.509 public keys; no signing key |
| `maximum-per-order` | Positive import cap, no greater than 1,000,000,000 |
| `player-weekly-limit` | Finite per-recipient cap, at least the per-order cap |
| `global-weekly-limit` | Finite cap for all recipients, at least the player cap |
| `maximum-receipt-lifetime-seconds` | 60–86,400 seconds; default 3,600 |
| `allowed-clock-skew-seconds` | 0–300 seconds for future `issuedAt`; default 30 |
| `cosmetic-prices` | Positive integer costs for only the reviewed catalog IDs above |

Issuance weeks begin Monday at 00:00 UTC. Quota belongs to the week of **acceptance**, not the provider's checkout time. Failed or duplicate orders do not consume new quota. The authoritative gate is held through commit and the final clock check occurs after all financial row locks; a request that crosses the UTC week while waiting returns `RETRY_REQUIRED` without issuance. Expired new receipts are rejected even if they were timely when queued.

A positive balance and existing cosmetic entitlements remain readable when issuance is disabled. Disabling the payment mechanism stops new receipts; the catalog policy separately governs cosmetic purchases. Disabling the whole `support-credit.enabled` policy also disables new cosmetic purchases. Previously committed cosmetic ownership is retained and may still render.

## Results, ambiguity and key rotation

Only `SUCCESS` adds credit. `DUPLICATE` confirms that the exact order was already accepted and adds zero. Reusing an order ID with a different beneficiary or payload returns `CONFLICT`. Other statuses identify disabled issuance, invalid signature, unknown key, wrong audience, expiry, future timestamp, quota, balance cap or a week-boundary retry.

An exact accepted receipt may be replayed after its expiry. **Verification and enablement still happen before duplicate lookup:** retain its old trusted public key and an enabled issuance gate if you want the importer to confirm that replay. A retired key produces `UNKNOWN_KEY`; a disabled gate produces `DISABLED`, even for an already accepted order. Neither response reverses earlier acceptance. Read the durable order audit when the old key must remain disabled.

If a transport, executor or database error leaves the outcome unknown, retry the **identical signed receipt**. The transaction may have committed before the acknowledgement was lost. Do not issue a replacement order ID or grant a manual balance adjustment. Preserve the canonical payload, signature and SHA-256 payload hash for reconciliation.

For purchases, retry the original operation UUID with the same player, cosmetic and expected price. The same receipt returns `DUPLICATE`; a changed request returns `CONFLICT`. A new checkout for an already-owned appearance returns `ALREADY_OWNED` without a new debit. No refund, revocation or chargeback protocol is implemented here; these require a separately reviewed reversal transaction that references the original debit.

## Rendering and historical ownership

Paid entitlements are read from the database after authentication, after the local purchase refresh event, and when an expiring rendering cache refreshes. A cache entry lasts at most 60 seconds; periodic refresh checks run every 10 seconds. Reads are single-flight per authenticated connection, and stale completions from a previous authentication generation cannot populate a new login. Failure clears paid rendering permission until a successful read.

`SupportEntitlementsChangedEvent` is only a refresh hint. It cannot grant an appearance by itself. New paid purchases **never** set a permanent PDC unlock flag. PDC may retain the selected appearance as a preference, but selection is checked against ownership before rendering.

The old voucher/PDC appearance unlocks remain compatible. Their historical source cannot be inferred: for example, `prismatic_unlocked` could originate from an earned Starlight voucher or the former cumulative-support cosmetic voucher. These old flags are not imported into signed orders or new paid entitlements, and they grant no new game currency or membership. The store avoids charging a player for an appearance already unlocked by one of those legacy flags.

## Verification and primary references

Behavioral tests cover signature tampering, cross-audience rejection, unique order replay, concurrent issuance, both UTC quotas, expiry after row-lock waiting, week rollover, before-commit rollback, lost commit acknowledgements, atomic entitlement creation, stale checkout confirmations and cache generations. Tests use the actual `Database.migrate()` schema.

For an isolated PostgreSQL run, set `SIVITA_SUPPORT_TEST_POSTGRES_URL`, `SIVITA_SUPPORT_TEST_POSTGRES_USER`, and `SIVITA_SUPPORT_TEST_POSTGRES_PASSWORD`. Each fixture creates and drops its own random `sivita_support_*` schema. Tests refuse `RPG_DATABASE_URL` so they cannot silently select the live runtime database. The selected test database account must be allowed to create and drop its own schema.

```bash
./gradlew :plugins:rpg-api:test :plugins:rpg-core:test \
  :plugins:rpg-commerce:test :plugins:rpg-cosmetics:test
```

The cryptography uses standard JDK facilities and an independently published Ed25519 test vector:

- [RFC 8032, EdDSA and Ed25519 test vectors](https://www.rfc-editor.org/rfc/rfc8032.html).
- [Java 25 JCA reference guide](https://docs.oracle.com/en/java/javase/25/security/java-cryptography-architecture-jca-reference-guide.html).
- [Java 25 providers and Ed25519 support](https://docs.oracle.com/en/java/javase/25/security/oracle-providers.html).

The trusted-console command `rpgcommerce legacy-payments <uuid> [limit]` reads up to 50 original payment audit rows for one exact player UUID. It creates no account and grants no new benefit.

See [the migration and rollback procedure](../../docs/operations/SUPPORT_CREDIT_MIGRATION.md) for historical paid membership, pending paid-item deliveries, audit queries and the explicit unproven runtime boundaries.
