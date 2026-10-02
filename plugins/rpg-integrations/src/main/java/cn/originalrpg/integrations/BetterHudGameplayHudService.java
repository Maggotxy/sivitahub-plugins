package cn.originalrpg.integrations;

import cn.originalrpg.api.service.GameplayHudService;
import kr.toxicity.hud.api.BetterHud;
import kr.toxicity.hud.api.bukkit.event.HudPlayerJoinEvent;
import kr.toxicity.hud.api.player.HudPlayer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.Optional;

/** Isolates all BetterHud API access behind the suite-owned service contract. */
final class BetterHudGameplayHudService implements GameplayHudService, Listener {
    private final JavaPlugin plugin;
    private final NamespacedKey preferenceKey;

    BetterHudGameplayHudService(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.preferenceKey = new NamespacedKey(plugin, "gameplay_hud_enabled");
    }

    @Override
    public boolean available() {
        return plugin.getServer().getPluginManager().isPluginEnabled("BetterHud");
    }

    @Override
    public Optional<Boolean> enabled(Player player) {
        Objects.requireNonNull(player, "player");
        if (!available()) return Optional.empty();
        HudPlayer hudPlayer = hudPlayer(player);
        if (hudPlayer != null) return Optional.of(hudPlayer.isHudEnabled());
        return Optional.of(stored(player));
    }

    @Override
    public ApplyResult setEnabled(Player player, boolean enabled) {
        Objects.requireNonNull(player, "player");
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Gameplay HUD preferences must be changed on the server thread");
        }
        if (!available()) return ApplyResult.UNAVAILABLE;
        player.getPersistentDataContainer().set(
                preferenceKey, PersistentDataType.BYTE, enabled ? (byte) 1 : (byte) 0);
        HudPlayer hudPlayer = hudPlayer(player);
        if (hudPlayer == null) return ApplyResult.PENDING;
        hudPlayer.setHudEnabled(enabled);
        return ApplyResult.APPLIED;
    }

    @EventHandler
    public void onHudPlayerReady(HudPlayerJoinEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        event.player().setHudEnabled(stored(player));
    }

    void reapply(Player player) {
        if (!available()) return;
        HudPlayer hudPlayer = hudPlayer(player);
        if (hudPlayer != null) hudPlayer.setHudEnabled(stored(player));
    }

    private boolean stored(Player player) {
        Byte value = player.getPersistentDataContainer().get(preferenceKey, PersistentDataType.BYTE);
        return value == null || value != 0;
    }

    private HudPlayer hudPlayer(Player player) {
        try {
            return BetterHud.getInstance().getPlayerManager().getHudPlayer(player.getUniqueId());
        } catch (RuntimeException | LinkageError exception) {
            return null;
        }
    }
}
