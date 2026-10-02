package cn.originalrpg.api.service;

import cn.originalrpg.api.model.SkillHudSnapshot;
import cn.originalrpg.api.model.SkillCooldownSnapshot;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Low-cost bridge between the skill runtime and menus, TAB, or placeholders. */
public interface SkillHudService {
    /** Returns only cached state and must never block or query a database. */
    Optional<SkillHudSnapshot> snapshot(UUID playerId);

    /**
     * Returns the four player-configured combat slots in display order.
     * Implementations must keep this memory-only just like {@link #snapshot(UUID)}.
     */
    default List<SkillCooldownSnapshot> slots(UUID playerId) {
        return snapshot(playerId).map(value -> value.learnedSkills().stream().limit(4).toList())
                .orElseGet(List::of);
    }

    /** Zero-based selected combat slot. */
    default int selectedSlot(UUID playerId) {
        return 0;
    }

    /** Shows a short, player-requested ActionBar overview without enabling a permanent HUD. */
    void showOverview(Player player);
}
