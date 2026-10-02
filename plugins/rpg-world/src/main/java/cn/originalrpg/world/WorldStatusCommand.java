package cn.originalrpg.world;

import cn.originalrpg.api.service.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.Map;

/** One truthful readout for blood-moon timing and the player's live hunt chain. */
final class WorldStatusCommand implements CommandExecutor {
    private final MessageService messages;
    private final WorldEventListener events;

    WorldStatusCommand(MessageService messages, WorldEventListener events) {
        this.messages = messages;
        this.events = events;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.text(sender, "common.player-only"));
            return true;
        }
        WorldEventListener.WorldStatus status = events.status(player);
        WorldEventRules.BloodMoonTiming bloodMoon = status.bloodMoon();

        player.sendMessage(Component.text(messages.text(player, "world.status.title"), NamedTextColor.GOLD));
        if (bloodMoon.active()) {
            player.sendMessage(Component.text(messages.text(player, "world.status.blood-active", Map.of(
                    "seconds", bloodMoon.remainingSeconds())), NamedTextColor.RED));
        } else {
            String timingKey = bloodMoon.nightsUntil() == 0L
                    ? "world.status.blood-tonight" : "world.status.blood-next";
            player.sendMessage(Component.text(messages.text(player, timingKey, Map.of(
                    "nights", bloodMoon.nightsUntil())), NamedTextColor.YELLOW));
        }
        player.sendMessage(Component.text(messages.text(player, "world.status.blood-condition", Map.of(
                "interval", bloodMoon.intervalNights())), NamedTextColor.DARK_GRAY));
        player.sendMessage(Component.text(messages.text(player, "world.status.blood-pity", Map.of(
                "current", status.bloodMoonFailures(),
                "guarantee", status.bloodMoonGuarantee())), NamedTextColor.GRAY));

        WorldEventListener.HuntStatus hunt = status.hunt();
        if (hunt == null) {
            player.sendMessage(Component.text(messages.text(player, "world.status.hunt-none"), NamedTextColor.GRAY));
        } else {
            Component target = translatedEntity(hunt.entityType());
            player.sendMessage(Component.text(messages.text(player, "world.status.hunt-prefix"), NamedTextColor.AQUA)
                    .append(target)
                    .append(variant(player, hunt.variant()))
                    .append(Component.text(messages.text(player, "world.status.hunt-detail", Map.of(
                            "count", hunt.count(),
                            "bonus", hunt.bonusPercent(),
                            "seconds", hunt.remainingSeconds(),
                            "pity", hunt.materialFailures(),
                            "guarantee", hunt.materialGuarantee())), NamedTextColor.WHITE)));
            player.sendMessage(Component.text(messages.text(player, "world.status.hunt-reset", Map.of(
                    "distance", Math.round(hunt.resetDistance()),
                    "seconds", hunt.resetSeconds())), NamedTextColor.DARK_GRAY));
        }
        player.sendMessage(Component.text(messages.text(player, "world.status.hunt-rule"), NamedTextColor.GRAY));
        player.sendMessage(actions(player));
        return true;
    }

    private Component actions(Player player) {
        Component mobGuide = Component.text(messages.text(player, "world.status.mob-guide-button"),
                        NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand("/mobguide"))
                .hoverEvent(HoverEvent.showText(Component.text(
                        messages.text(player, "world.status.mob-guide-hover"))));
        Component wild = Component.text(messages.text(player, "world.status.wild-button"), NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/wild"))
                .hoverEvent(HoverEvent.showText(Component.text(
                        messages.text(player, "world.status.wild-hover"))));
        return mobGuide.append(Component.text("   ", NamedTextColor.DARK_GRAY)).append(wild);
    }

    private Component translatedEntity(String entityType) {
        try {
            return Component.translatable(EntityType.valueOf(entityType).translationKey(), NamedTextColor.WHITE);
        } catch (IllegalArgumentException ignored) {
            return Component.text(entityType, NamedTextColor.WHITE);
        }
    }

    private Component variant(Player player, String value) {
        if (value == null || value.isBlank()) return Component.empty();
        if (value.startsWith("size-")) {
            return Component.text(messages.text(player, "world.hunt-variants.size", Map.of(
                    "size", value.substring("size-".length()))), NamedTextColor.GRAY);
        }
        return Component.text(messages.text(player, "world.hunt-variants." + value), NamedTextColor.GRAY);
    }
}
