package cn.originalrpg.guide;

import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.service.GuideMenuService;
import cn.originalrpg.api.service.MessageService;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/** Shows onboarding only after AuthMe has released the player. */
final class WelcomeExperience implements Listener {
    private final Plugin plugin;
    private final MessageService messages;
    private final BiConsumer<Player, GuideMenuService.Section> menuOpener;
    private final NamespacedKey welcomeSeen;
    private final int welcomeVersion;
    private final Set<UUID> scheduled = ConcurrentHashMap.newKeySet();
    private final Set<UUID> authenticatedPlayers = ConcurrentHashMap.newKeySet();

    WelcomeExperience(
            Plugin plugin,
            MessageService messages,
            BiConsumer<Player, GuideMenuService.Section> menuOpener
    ) {
        this.plugin = plugin;
        this.messages = messages;
        this.menuOpener = menuOpener;
        this.welcomeSeen = new NamespacedKey(plugin, "welcome_seen");
        this.welcomeVersion = Math.max(1, plugin.getConfig().getInt("onboarding.welcome-version", 1));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAuthenticated(AuthenticatedPlayerReadyEvent event) {
        authenticatedPlayers.add(event.player().getUniqueId());
        schedule(event.player(), 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clearSession(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onAuthenticationRevoked(AuthenticatedPlayerRevokedEvent event) {
        clearSession(event.player().getUniqueId());
    }

    private void clearSession(UUID playerId) {
        scheduled.remove(playerId);
        authenticatedPlayers.remove(playerId);
    }

    private void schedule(Player player, long delay) {
        if (!plugin.getConfig().getBoolean("onboarding.open-menu-on-first-join", true)) return;
        if (hasSeenCurrentWelcome(player)) return;
        if (!scheduled.add(player.getUniqueId())) return;
        if (!plugin.isEnabled()) {
            scheduled.remove(player.getUniqueId());
            return;
        }
        try {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                scheduled.remove(player.getUniqueId());
                if (plugin.isEnabled()) showIfReady(player);
            }, delay);
        } catch (IllegalStateException ignored) {
            scheduled.remove(player.getUniqueId());
        }
    }

    private void showIfReady(Player player) {
        if (!ready(player)) return;
        if (hasSeenCurrentWelcome(player)) return;
        // Integer schema versions let a materially improved first-hour flow be
        // shown once to existing test accounts without repeatedly nagging them.
        player.getPersistentDataContainer().set(welcomeSeen, PersistentDataType.INTEGER, welcomeVersion);
        player.saveData();
        player.sendMessage(Component.text(messages.text(player, "guide.welcome"), NamedTextColor.GOLD));

        List<DialogBody> body = List.of(
                DialogBody.item(welcomeCompass(player)).showDecorations(false).showTooltip(true)
                        .width(56).height(56).build(),
                DialogBody.plainMessage(Component.text(
                        messages.text(player, "guide.welcome-dialog.identity"), NamedTextColor.GOLD)),
                DialogBody.plainMessage(Component.text(
                        messages.text(player, "guide.welcome-dialog.first-goal"), NamedTextColor.WHITE)),
                DialogBody.plainMessage(Component.text(
                        messages.text(player, "guide.welcome-dialog.first-where"), NamedTextColor.AQUA)),
                DialogBody.plainMessage(Component.text(
                        messages.text(player, "guide.welcome-dialog.first-why"), NamedTextColor.GRAY)),
                DialogBody.plainMessage(Component.text(
                        messages.text(player, "guide.welcome-dialog.controls-short"), NamedTextColor.DARK_GRAY))
        );
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text(
                                messages.text(player, "guide.welcome-dialog.title"), NamedTextColor.GOLD))
                        .externalTitle(Component.text(messages.text(player, "guide.welcome-dialog.title")))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(body)
                        .build())
                .type(DialogType.multiAction(List.of(
                        button(player, "start-button", GuideMenuService.Section.CURRENT),
                        button(player, "classes-button", GuideMenuService.Section.CLASS),
                        button(player, "menu-button", GuideMenuService.Section.MAIN),
                        ActionButton.create(Component.text(messages.text(player,
                                        "guide.welcome-dialog.later-button"), NamedTextColor.GRAY),
                                Component.text(messages.text(player,
                                        "guide.welcome-dialog.later-tooltip")), 140, null)
                )).columns(2).build()));
        player.showDialog(dialog);
    }

    private ItemStack welcomeCompass(Player player) {
        ItemStack compass = new ItemStack(Material.COMPASS);
        ItemMeta meta = compass.getItemMeta();
        meta.displayName(Component.text(messages.text(player, "guide.onboarding.handbook-name"),
                        NamedTextColor.GOLD)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        if (ResourcePackUiPolicy.customVisuals(true, player)) {
            meta.setItemModel(new NamespacedKey("originalrpg", "traveler_compass"));
        }
        compass.setItemMeta(meta);
        return compass;
    }

    private boolean hasSeenCurrentWelcome(Player player) {
        // Older test builds stored this marker as BYTE. Paper correctly rejects
        // reading a ByteTag through the INTEGER adapter, so probe the concrete
        // type before reading and discard the legacy marker. This both avoids a
        // join-event exception and lets the materially newer welcome flow appear.
        if (player.getPersistentDataContainer().has(welcomeSeen, PersistentDataType.INTEGER)) {
            Integer stored = player.getPersistentDataContainer().get(welcomeSeen, PersistentDataType.INTEGER);
            return stored != null && stored >= welcomeVersion;
        }
        if (player.getPersistentDataContainer().has(welcomeSeen, PersistentDataType.BYTE)) {
            player.getPersistentDataContainer().remove(welcomeSeen);
        }
        return false;
    }

    private ActionButton button(Player player, String key, GuideMenuService.Section section) {
        return ActionButton.builder(Component.text(
                        messages.text(player, "guide.welcome-dialog." + key), NamedTextColor.GREEN))
                .tooltip(Component.text(messages.text(player,
                        "guide.welcome-dialog." + key + "-tooltip")))
                .width(160)
                .action(DialogAction.customClick((response, audience) -> {
                    if (!(audience instanceof Player responder)) return;
                    sync(() -> {
                        if (ready(responder)) menuOpener.accept(responder, section);
                    });
                }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build()))
                .build();
    }

    private ActionButton commandButton(Player player, String key, String command) {
        return ActionButton.builder(Component.text(
                        messages.text(player, "guide.welcome-dialog." + key), NamedTextColor.LIGHT_PURPLE))
                .tooltip(Component.text(messages.text(player,
                        "guide.welcome-dialog." + key + "-tooltip")))
                .width(160)
                .action(DialogAction.customClick((response, audience) -> {
                    if (!(audience instanceof Player responder)) return;
                    sync(() -> {
                        if (ready(responder)) responder.performCommand(command);
                    });
                }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build()))
                .build();
    }

    private boolean ready(Player player) {
        return player != null && player.isOnline()
                && Bukkit.getPlayer(player.getUniqueId()) == player
                && authenticatedPlayers.contains(player.getUniqueId());
    }

    private void sync(Runnable action) {
        if (!plugin.isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (plugin.isEnabled()) action.run();
            });
        } catch (IllegalStateException ignored) {
            // A welcome dialog is connection-local and may safely expire while
            // the plugin is disabled; the version marker remains authoritative.
        }
    }
}
