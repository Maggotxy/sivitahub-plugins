package cn.originalrpg.api.rules;

import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.model.CoreAttribute;

import java.util.Map;

/** Shared five-point contracts that make every class's first skill reachable. */
public final class StarterBuildRules {
    public static final int STARTING_ATTRIBUTE_POINTS = 5;

    private StarterBuildRules() {
    }

    public static Map<CoreAttribute, Integer> requirements(Archetype archetype) {
        if (archetype == null || archetype == Archetype.UNCHOSEN) return Map.of();
        return switch (archetype) {
            case HEALER -> Map.of(CoreAttribute.INTELLIGENCE, 3, CoreAttribute.STAMINA, 2);
            case WARRIOR -> Map.of(CoreAttribute.STRENGTH, 3, CoreAttribute.STAMINA, 2);
            case ASSASSIN -> Map.of(CoreAttribute.AGILITY, 4, CoreAttribute.STAMINA, 1);
            case MAGE -> Map.of(CoreAttribute.INTELLIGENCE, 4, CoreAttribute.STAMINA, 1);
            case RANGER -> Map.of(CoreAttribute.AGILITY, 3, CoreAttribute.STRENGTH, 2);
            case FREEFORM -> Map.of(
                    CoreAttribute.DEFENSE, 1,
                    CoreAttribute.STRENGTH, 1,
                    CoreAttribute.AGILITY, 1,
                    CoreAttribute.INTELLIGENCE, 1,
                    CoreAttribute.STAMINA, 1);
            case UNCHOSEN -> Map.of();
        };
    }

    public static boolean ready(Archetype archetype, Map<CoreAttribute, Integer> attributes) {
        if (attributes == null) return false;
        Map<CoreAttribute, Integer> requirements = requirements(archetype);
        return !requirements.isEmpty() && requirements.entrySet().stream()
                .allMatch(entry -> attributes.getOrDefault(entry.getKey(), 0) >= entry.getValue());
    }

    public static boolean mayAllocate(
            Archetype archetype, Map<CoreAttribute, Integer> attributes, CoreAttribute target
    ) {
        if (target == null || attributes == null || requirements(archetype).isEmpty()) return false;
        if (ready(archetype, attributes)) return true;
        return attributes.getOrDefault(target, 0) < requirements(archetype).getOrDefault(target, 0);
    }
}
