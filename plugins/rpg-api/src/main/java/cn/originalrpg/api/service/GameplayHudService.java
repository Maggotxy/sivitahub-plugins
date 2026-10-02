package cn.originalrpg.api.service;

import org.bukkit.entity.Player;

import java.util.Optional;

/**
 * Optional bridge for the resource-pack gameplay HUD.
 *
 * <p>The implementation owns the third-party HUD API boundary. Consumers must
 * resolve this service dynamically because the HUD plugin can be disabled or
 * reloaded independently.</p>
 */
public interface GameplayHudService {
    enum ApplyResult {
        APPLIED,
        PENDING,
        UNAVAILABLE
    }

    /** Whether the backing HUD runtime is currently enabled. */
    boolean available();

    /**
     * Returns the live or persisted preference without database or filesystem
     * access. An empty value means that the backing HUD is unavailable.
     */
    Optional<Boolean> enabled(Player player);

    /**
     * Persists the player's preference and applies it to the current HUD
     * session when that session is ready. Must be called on the server thread.
     */
    ApplyResult setEnabled(Player player, boolean enabled);
}
