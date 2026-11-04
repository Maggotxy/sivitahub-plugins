package cn.originalrpg.mobs;

import cn.originalrpg.api.rules.CombatRules;
import cn.originalrpg.api.rules.PityRules;

import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable recovery instruction for one observed kill, not a second XP or item ledger.
 *
 * <p>All item serialization and random rolls happen on the server thread before this
 * value crosses the journal boundary. Recovery uses these exact bytes and identifiers;
 * it never spawns another ground item, rolls again, or writes an old pity counter.</p>
 *
 * <p>An {@link Eligibility#UNKNOWN} snapshot means no authoritative profile was
 * available at death. Its first later profile lookup must be frozen durably before
 * any reward is submitted. A resolved decision is never recomputed during replay.</p>
 */
public record MobRewardIntent(
        UUID killId, UUID playerId, long createdAtMillis, int mobLevel, long experience,
        Eligibility eligibility, int playerEffectiveLevel, List<Delivery> deliveries, List<PityRoll> pityRolls
) {
    public static final int MAX_PAYLOAD_CHARACTERS = 120_000;
    public static final int MAX_DELIVERIES = 2;

    public MobRewardIntent {
        Objects.requireNonNull(killId, "killId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(eligibility, "eligibility");
        deliveries = List.copyOf(deliveries);
        pityRolls = List.copyOf(pityRolls);
        if (createdAtMillis < 0 || mobLevel < 1 || experience < 0
                || (eligibility == Eligibility.UNKNOWN ? playerEffectiveLevel != 0 : playerEffectiveLevel < 1)
                || deliveries.size() > MAX_DELIVERIES || pityRolls.size() > 2) {
            throw new IllegalArgumentException("Invalid mob reward snapshot");
        }
        if (eligibility != Eligibility.UNKNOWN && (eligibility == Eligibility.ELIGIBLE)
                != CombatRules.allowsCustomDrops(mobLevel, playerEffectiveLevel)) {
            throw new IllegalArgumentException("Frozen eligibility does not match its level snapshot");
        }
        Set<String> kinds = new HashSet<>();
        for (Delivery delivery : deliveries) {
            if (!kinds.add(delivery.kind())
                    || !delivery.deliveryId().equals(MobRewardReliabilityRules.deliveryId(killId, delivery.kind()))) {
                throw new IllegalArgumentException("Delivery identity does not belong to this kill");
            }
        }
        Set<String> counters = new HashSet<>();
        for (PityRoll roll : pityRolls) {
            if (!counters.add(roll.counter())) throw new IllegalArgumentException("Duplicate pity counter");
        }
    }

    /** Stable core receipt: even a lost commit response must reuse this identifier. */
    public UUID experienceGrantId() {
        return MobRewardReliabilityRules.deliveryId(killId, "experience");
    }

    /** Resolve only a previously unknown decision using the first authoritative later lookup. */
    public MobRewardIntent resolve(int effectiveLevel) {
        if (eligibility != Eligibility.UNKNOWN) throw new IllegalStateException("Eligibility is already frozen");
        if (effectiveLevel < 1) throw new IllegalArgumentException("Invalid player level");
        return new MobRewardIntent(killId, playerId, createdAtMillis, mobLevel, experience,
                CombatRules.allowsCustomDrops(mobLevel, effectiveLevel) ? Eligibility.ELIGIBLE : Eligibility.INELIGIBLE,
                effectiveLevel, deliveries, pityRolls);
    }

    /** Compares the immutable observation, excluding the one permitted eligibility transition. */
    boolean sameObservation(MobRewardIntent other) {
        return killId.equals(other.killId) && playerId.equals(other.playerId)
                && createdAtMillis == other.createdAtMillis && mobLevel == other.mobLevel
                && experience == other.experience && deliveries.equals(other.deliveries) && pityRolls.equals(other.pityRolls);
    }

    /** Conservative serialized-payload admission budget; this is not a heap-size estimate. */
    long budgetBytes() {
        return 2048L + deliveries.stream().mapToLong(delivery -> delivery.itemPayload().length()).sum();
    }

    public enum Eligibility { UNKNOWN, ELIGIBLE, INELIGIBLE }

    /** The mailbox is the only delivery authority; no new delivery ID is minted during a retry. */
    public record Delivery(UUID deliveryId, String kind, String itemPayload,
                           String visualKind, String itemMessageKey, boolean pityGuaranteed) {
        public Delivery {
            Objects.requireNonNull(deliveryId, "deliveryId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(itemPayload, "itemPayload");
            Objects.requireNonNull(visualKind, "visualKind");
            Objects.requireNonNull(itemMessageKey, "itemMessageKey");
            if (!Set.of("material", "skill-book").contains(kind) || itemPayload.isBlank()
                    || itemPayload.length() > MAX_PAYLOAD_CHARACTERS
                    || !Set.of("CORE", "SHARD", "SKILL_BOOK").contains(visualKind)
                    || itemMessageKey.isBlank() || itemMessageKey.length() > 160) {
                throw new IllegalArgumentException("Invalid frozen mob delivery");
            }
            Base64.getDecoder().decode(itemPayload);
        }

        public String source(UUID killId) {
            return "mob-kill:" + killId + ':' + kind;
        }
    }

    /**
     * Audit snapshot of a single original PDC-based roll. The player PDC remains
     * the counter authority; this value is never applied back during recovery.
     * Player-data saving and journal fsync are explicitly not an atomic transaction.
     */
    public record PityRoll(String counter, int before, int after, int guarantee,
                          double chance, double randomUnit, boolean dropped, boolean guaranteed) {
        public PityRoll {
            Objects.requireNonNull(counter, "counter");
            if (!Set.of("elite_material_failures", "normal_material_failures", "skill_book_failures").contains(counter)) {
                throw new IllegalArgumentException("Unknown mob pity counter");
            }
            PityRules.Outcome expected = PityRules.attempt(before, guarantee, chance, randomUnit);
            if (expected.failures() != after || expected.dropped() != dropped || expected.guaranteed() != guaranteed) {
                throw new IllegalArgumentException("Pity snapshot does not match its original roll");
            }
        }
    }
}
