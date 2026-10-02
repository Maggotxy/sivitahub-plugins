package cn.originalrpg.mobs.ability;

import java.util.List;

/** Pure selection and scaling rules kept outside the entity scheduler. */
public final class MobAbilityRules {
    private MobAbilityRules() {
    }

    public static List<MobBehavior> candidates(String entityType) {
        return switch (entityType) {
            case "SKELETON", "STRAY", "BOGGED" -> List.of(MobBehavior.SKIRMISHER, MobBehavior.BOOMERANG);
            case "PILLAGER" -> List.of(MobBehavior.SKIRMISHER, MobBehavior.BOOMERANG,
                    MobBehavior.WARDEN, MobBehavior.TRAPPER);
            case "ZOMBIE", "HUSK", "DROWNED", "ZOMBIFIED_PIGLIN" ->
                    List.of(MobBehavior.CHARGER, MobBehavior.WARDEN, MobBehavior.BOOMERANG);
            case "SPIDER", "CAVE_SPIDER" ->
                    List.of(MobBehavior.SPITTER, MobBehavior.CHARGER, MobBehavior.TRAPPER);
            case "SLIME", "MAGMA_CUBE" -> List.of(MobBehavior.SPITTER, MobBehavior.CHARGER);
            case "WITCH" -> List.of(MobBehavior.SPITTER, MobBehavior.CONDUCTOR, MobBehavior.TRAPPER);
            case "VINDICATOR", "WITHER_SKELETON", "PIGLIN" ->
                    List.of(MobBehavior.WARDEN, MobBehavior.BOOMERANG, MobBehavior.CHARGER);
            case "GUARDIAN", "ELDER_GUARDIAN", "BREEZE", "WARDEN" -> List.of(MobBehavior.CONDUCTOR);
            case "BLAZE", "SHULKER", "PHANTOM" -> List.of(MobBehavior.ORBITER);
            case "ENDERMAN", "SILVERFISH", "ENDERMITE" -> List.of(MobBehavior.BURROWER);
            case "CREEPER", "HOGLIN", "RAVAGER" ->
                    List.of(MobBehavior.CHARGER);
            default -> List.of();
        };
    }

    public static MobBehavior choose(List<MobBehavior> candidates, int randomIndex) {
        if (candidates.isEmpty()) return null;
        return candidates.get(Math.floorMod(randomIndex, candidates.size()));
    }

    public static double scaledDamage(int level, double base, double perLevel, double maximum) {
        int safeLevel = Math.clamp(level, 1, 10_000);
        double safeBase = Math.max(0.0, base);
        double safePerLevel = Math.max(0.0, perLevel);
        double safeMaximum = Math.max(safeBase, maximum);
        return Math.min(safeMaximum, safeBase + (safeLevel - 1) * safePerLevel);
    }
}
