package cn.originalrpg.story;

import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.model.JourneyStep;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

final class StoryCommand implements CommandExecutor, TabCompleter {
    private static final List<String> PLAYER_ACTIONS = List.of("status", "next", "start", "claim", "cancel", "help");
    private final Plugin plugin;
    private final EscortManager escorts;
    private final StoryActorManager actor;
    private final MessageService messages;
    private final StoryIntegrationBridge bridge;

    StoryCommand(
            Plugin plugin,
            EscortManager escorts,
            StoryActorManager actor,
            MessageService messages,
            StoryIntegrationBridge bridge
    ) {
        this.plugin = plugin;
        this.escorts = escorts;
        this.actor = actor;
        this.messages = messages;
        this.bridge = bridge;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("storyadmin")) return admin(sender, args);
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.text(sender, "common.player-only"));
            return true;
        }
        if (!escorts.isAuthenticated(player)) return true;
        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "status" -> showStatus(player);
            case "next" -> bridge.openJourney(player);
            case "start" -> escorts.start(player, StoryQuest.PROLOGUE_ESCORT);
            case "claim" -> escorts.claim(player, StoryQuest.PROLOGUE_ESCORT);
            case "cancel" -> escorts.cancel(player, true);
            case "help" -> messages.lines(player, "story.help").forEach(line ->
                    player.sendMessage(Component.text(line, NamedTextColor.GRAY)));
            default -> player.sendMessage(Component.text(messages.text(player, "story.usage"), NamedTextColor.RED));
        }
        return true;
    }

    private void showStatus(Player player) {
        java.util.UUID sessionToken = escorts.sessionToken(player);
        if (sessionToken == null) return;
        StoryOverview overview = escorts.overview(player);
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text(
                text(player, "story.status-panel.explainer"), NamedTextColor.GRAY)));
        String stateId = overview.prologue().name().toLowerCase(Locale.ROOT).replace('_', '-');
        body.add(DialogBody.plainMessage(Component.text(
                text(player, "story.status-panel.states." + stateId + ".now"), NamedTextColor.GOLD)));
        body.add(DialogBody.plainMessage(Component.text(
                text(player, "story.status-panel.states." + stateId + ".where"), NamedTextColor.AQUA)));
        body.add(DialogBody.plainMessage(Component.text(
                text(player, "story.status-panel.states." + stateId + ".why"), NamedTextColor.GRAY)));

        JourneyStep next = overview.journey().nextStep().orElse(null);
        if (overview.prologue() == StoryStatusPolicy.State.COMPLETED) {
            if (next == null) {
                body.add(DialogBody.plainMessage(Component.text(
                        text(player, "story.status-panel.journey-complete"), NamedTextColor.GREEN)));
            } else {
                body.add(DialogBody.plainMessage(Component.text(text(player,
                        "story.status-panel.journey-next", "step", text(player,
                                "guide.onboarding.steps." + next.id() + ".name")), NamedTextColor.LIGHT_PURPLE)));
                body.add(DialogBody.plainMessage(Component.text(text(player,
                        "guide.onboarding.steps." + next.id() + ".where"), NamedTextColor.AQUA)));
            }
        }

        List<ActionButton> actions = new ArrayList<>();
        actions.add(primaryButton(player, sessionToken, overview));
        if (overview.prologue() != StoryStatusPolicy.State.COMPLETED) {
            actions.add(actionButton(player, "story.status-panel.actions.open-journey",
                    "story.status-panel.actions.open-journey-tooltip", sessionToken, bridge::openJourney));
        }
        actions.add(ActionButton.create(Component.text(text(player, "story.status-panel.actions.close")),
                Component.text(text(player, "story.status-panel.actions.close-tooltip")), 120, null));
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text(
                                text(player, "story.status-panel.title"), NamedTextColor.GOLD))
                        .externalTitle(Component.text(text(player, "story.status-panel.title")))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(body)
                        .build())
                .type(DialogType.multiAction(actions).columns(2).build()));
        player.showDialog(dialog);
    }

    private ActionButton primaryButton(Player player, java.util.UUID sessionToken, StoryOverview overview) {
        StoryStatusPolicy.State state = overview.prologue();
        String id = state.name().toLowerCase(Locale.ROOT).replace('_', '-');
        Consumer<Player> action = switch (state) {
            case NEEDS_CLASS -> bridge::openClass;
            case NEEDS_HUB, AVAILABLE, RESUMABLE ->
                    responder -> escorts.start(responder, StoryQuest.PROLOGUE_ESCORT);
            case ACTIVE -> ignored -> { };
            case CLAIMABLE -> responder -> escorts.claim(responder, StoryQuest.PROLOGUE_ESCORT);
            case COMPLETED -> overview.journey().complete() ? bridge::openTasks : bridge::openJourney;
        };
        return actionButton(player, "story.status-panel.actions." + id,
                "story.status-panel.actions." + id + "-tooltip", sessionToken, action);
    }

    private ActionButton actionButton(
            Player player, String labelKey, String tooltipKey,
            java.util.UUID sessionToken, Consumer<Player> action
    ) {
        return ActionButton.builder(Component.text(text(player, labelKey), NamedTextColor.GREEN))
                .tooltip(Component.text(text(player, tooltipKey)))
                .width(180)
                .action(DialogAction.customClick((response, audience) -> {
                    if (!(audience instanceof Player responder)) return;
                    escorts.runAuthenticated(responder, sessionToken, action);
                }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build()))
                .build();
    }

    private String text(Player player, String key, Object... placeholders) {
        if (placeholders.length == 0) return messages.text(player, key);
        java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();
        for (int index = 0; index + 1 < placeholders.length; index += 2) {
            values.put(String.valueOf(placeholders[index]), placeholders[index + 1]);
        }
        return messages.text(player, key, values);
    }

    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("rpg.story.admin")) return true;
        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "status" -> sender.sendMessage("RpgStory actor " + actor.status() + ", active escorts=" + escorts.activeCount());
            case "repair" -> sender.sendMessage("RpgStory actor repaired=" + actor.spawnAtHub() + " " + actor.status());
            default -> sender.sendMessage("/storyadmin <status|repair>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> values = command.getName().equalsIgnoreCase("storyadmin")
                ? List.of("status", "repair") : PLAYER_ACTIONS;
        return values.stream().filter(value -> value.startsWith(prefix)).toList();
    }
}
