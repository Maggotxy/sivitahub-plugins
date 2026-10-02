package cn.originalrpg.api.model;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.Locale;
import java.util.Optional;

/** Player-owned accessibility preference shared by every visual gameplay module. */
public enum VisualMode {
    FULL(1.0, 1.0),
    REDUCED(0.45, 0.70),
    MINIMAL(0.0, 0.35),
    OFF(0.0, 0.0);

    private final double particleMultiplier;
    private final double soundMultiplier;

    VisualMode(double particleMultiplier, double soundMultiplier) {
        this.particleMultiplier = particleMultiplier;
        this.soundMultiplier = soundMultiplier;
    }

    public int particles(int configuredCount) {
        if (configuredCount <= 0 || particleMultiplier <= 0.0) return 0;
        return Math.max(1, (int) Math.round(configuredCount * particleMultiplier));
    }

    public float volume(double configuredVolume) {
        return (float) Math.clamp(configuredVolume * soundMultiplier, 0.0, 2.0);
    }

    public void save(Player player) {
        player.getPersistentDataContainer().set(Storage.KEY, PersistentDataType.STRING, name());
        player.saveData();
    }

    public static VisualMode forPlayer(Player player) {
        String stored = player.getPersistentDataContainer().get(Storage.KEY, PersistentDataType.STRING);
        return parse(stored).orElse(FULL);
    }

    public static Optional<VisualMode> parse(String input) {
        if (input == null || input.isBlank()) return Optional.empty();
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "full", "完整", "完整效果" -> Optional.of(FULL);
            case "reduced", "reduced-motion", "精简", "减少" -> Optional.of(REDUCED);
            case "minimal", "最低", "极简" -> Optional.of(MINIMAL);
            case "off", "关闭", "无" -> Optional.of(OFF);
            default -> Optional.empty();
        };
    }

    /** Keeps Bukkit-only key creation out of pure model/test initialization. */
    private static final class Storage {
        private static final NamespacedKey KEY = new NamespacedKey("rpgapi", "visual_mode");
    }
}
