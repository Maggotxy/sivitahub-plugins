package cn.originalrpg.mobs;

import cn.originalrpg.api.model.ServerLoadState;
import cn.originalrpg.api.model.VisualMode;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.OptionalServices;
import cn.originalrpg.api.service.PerformanceService;
import cn.originalrpg.api.ui.HudGlyphs;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** One-shot elite and personal rare-drop feedback; ordinary mobs remain vanilla. */
public final class MobVisuals {
    public enum DropKind { SHARD, CORE, SKILL_BOOK }

    private final Plugin plugin;
    private final MessageService messages;

    public MobVisuals(Plugin plugin, MessageService messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    public void eliteSpawn(LivingEntity monster) {
        if (!plugin.getConfig().getBoolean("visual-effects.enabled", true)) return;
        ServerLoadState load = load();
        int configured = switch (load) {
            case NORMAL -> Math.clamp(plugin.getConfig().getInt("visual-effects.elite-spawn-particles", 10), 0, 24);
            case PRESSURE -> 4;
            case CRITICAL -> 0;
        };
        Location center = monster.getLocation().add(0.0, Math.max(0.5, monster.getHeight() * 0.55), 0.0);
        double radius = Math.clamp(plugin.getConfig().getDouble("visual-effects.receiver-radius", 40.0), 8.0, 64.0);
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(160, 65, 215), 1.15F);
        for (Player receiver : monster.getWorld().getNearbyPlayers(center, radius)) {
            VisualMode mode = VisualMode.forPlayer(receiver);
            int count = mode.particles(configured);
            if (count > 0) receiver.spawnParticle(Particle.DUST, center, count, 0.55, 0.8, 0.55, 0.02, dust);
            float volume = mode.volume(plugin.getConfig().getDouble("visual-effects.sound-volume", 0.45));
            if (volume > 0.0F && load != ServerLoadState.CRITICAL) {
                receiver.playSound(center, Sound.ENTITY_ELDER_GUARDIAN_CURSE,
                        SoundCategory.HOSTILE, volume, 1.55F);
            }
        }
    }

    public void rareDrop(Player player, DropKind kind) {
        String itemKey = switch (kind) {
            case SHARD -> "mobs.items.mutation-shard";
            case CORE -> "mobs.items.mutation-core";
            case SKILL_BOOK -> "mobs.items.common-insight-book";
        };
        Component prefix = HudGlyphs.available(player)
                ? Component.empty().append(HudGlyphs.icon(HudGlyphs.RARE_DROP)).append(Component.space())
                : Component.empty();
        player.sendActionBar(prefix
                .append(Component.text(messages.text(player, "mobs.rare-drop", java.util.Map.of(
                        "item", messages.text(player, itemKey))), NamedTextColor.LIGHT_PURPLE)));
        if (!plugin.getConfig().getBoolean("visual-effects.enabled", true)) return;
        VisualMode mode = VisualMode.forPlayer(player);
        ServerLoadState load = load();
        int configured = switch (load) {
            case NORMAL -> plugin.getConfig().getInt("visual-effects.rare-drop-particles", 14);
            case PRESSURE -> 6;
            case CRITICAL -> 2;
        };
        int count = mode.particles(Math.clamp(configured, 0, 28));
        Location center = player.getLocation().add(0.0, 1.0, 0.0);
        if (count > 0) player.spawnParticle(kind == DropKind.SKILL_BOOK ? Particle.ENCHANT : Particle.END_ROD,
                center, count, 0.45, 0.65, 0.45, kind == DropKind.SKILL_BOOK ? 0.4 : 0.025);
        float volume = mode.volume(plugin.getConfig().getDouble("visual-effects.sound-volume", 0.55));
        if (volume > 0.0F) player.playSound(center, kind == DropKind.SKILL_BOOK
                        ? Sound.ITEM_BOOK_PAGE_TURN : Sound.ENTITY_EXPERIENCE_ORB_PICKUP,
                SoundCategory.PLAYERS, volume, kind == DropKind.SKILL_BOOK ? 1.35F : 1.65F);
    }

    public void affixTrigger(LivingEntity monster, Player receiver, String affix) {
        if (!plugin.getConfig().getBoolean("visual-effects.enabled", true)) return;
        VisualMode mode = VisualMode.forPlayer(receiver);
        ServerLoadState load = load();
        int base = load == ServerLoadState.NORMAL ? 8 : load == ServerLoadState.PRESSURE ? 3 : 0;
        int count = mode.particles(base);
        Location center = monster.getLocation().add(0.0, Math.max(0.45, monster.getHeight() * 0.5), 0.0);
        if (count > 0) {
            Particle particle = switch (affix) {
                case "swift" -> Particle.CLOUD;
                case "berserk" -> Particle.ANGRY_VILLAGER;
                case "armored" -> Particle.ENCHANTED_HIT;
                default -> Particle.SMOKE;
            };
            receiver.spawnParticle(particle, center, count, 0.45, 0.55, 0.45, 0.02);
        }
        float volume = mode.volume(load == ServerLoadState.CRITICAL ? 0.0 : 0.35);
        if (volume > 0.0F) receiver.playSound(center, Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR,
                SoundCategory.HOSTILE, volume, affix.equals("swift") ? 1.45F : 0.85F);
    }

    private ServerLoadState load() {
        PerformanceService performance = OptionalServices.enabled(plugin, PerformanceService.class);
        return performance == null ? ServerLoadState.NORMAL : performance.state();
    }
}
