package cn.originalrpg.api.service;

import cn.originalrpg.api.model.JourneyProgress;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Exposes the real, one-time onboarding journey to story and UI plugins.
 * The progress view is an immutable concurrent snapshot and may be read by
 * BetonQuest's asynchronous condition evaluator. Refresh calls touch Bukkit
 * player data and must run on the primary server thread.
 */
public interface JourneyService {
    JourneyProgress progress(UUID playerId);

    /** Re-evaluates authoritative profile and held-equipment state without inventing quest progress. */
    void refresh(Player player);
}
