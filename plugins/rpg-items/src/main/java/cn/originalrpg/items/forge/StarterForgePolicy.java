package cn.originalrpg.items.forge;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Pure anti-laundering rules for the one-time class forge package. */
final class StarterForgePolicy {
    private StarterForgePolicy() {
    }

    static Assessment assess(UUID playerId, String baseOwner, String coreOwner, String archetype) {
        Objects.requireNonNull(playerId, "playerId");
        boolean containsStarterComponent = present(baseOwner) || present(coreOwner) || present(archetype);
        if (!containsStarterComponent) return new Assessment(false, false, Optional.empty());
        String expected = playerId.toString();
        boolean valid = expected.equals(baseOwner) && expected.equals(coreOwner) && present(archetype);
        return new Assessment(true, valid, valid ? Optional.of(archetype) : Optional.empty());
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    record Assessment(boolean containsStarterComponent, boolean valid, Optional<String> archetype) {
        Assessment {
            archetype = archetype == null ? Optional.empty() : archetype;
            if (valid && (!containsStarterComponent || archetype.isEmpty())) {
                throw new IllegalArgumentException("A valid starter forge needs both components and an archetype");
            }
            if (!valid && archetype.isPresent()) {
                throw new IllegalArgumentException("An invalid starter forge cannot expose an archetype");
            }
        }
    }
}
