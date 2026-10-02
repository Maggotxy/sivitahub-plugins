package cn.originalrpg.guide;

import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.rules.PlayerJourneyFacts;
import cn.originalrpg.api.service.MessageService;
import fr.xephi.authme.api.v3.AuthMeApi;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** One bound, non-economic first-forge package granted after class selection. */
final class StarterKitService {
    private final Plugin plugin;
    private final MessageService messages;
    private final NamespacedKey grantedVersion;
    private final NamespacedKey claimToolVersion;
    private final NamespacedKey starterBaseOwner;
    private final NamespacedKey starterCoreOwner;
    private final NamespacedKey starterArchetype;
    private final NamespacedKey materialOwner = NamespacedKey.fromString("rpgitems:material_owner");
    private final NamespacedKey forgeEssence = NamespacedKey.fromString("rpgitems:forge_essence");
    private final Map<UUID, Long> inventoryWarnings = new ConcurrentHashMap<>();

    StarterKitService(Plugin plugin, MessageService messages) {
        this.plugin = plugin;
        this.messages = messages;
        grantedVersion = new NamespacedKey(plugin, "starter_kit_version");
        claimToolVersion = new NamespacedKey(plugin, "claim_tool_version");
        starterBaseOwner = new NamespacedKey(plugin, "starter_base_owner");
        starterCoreOwner = new NamespacedKey(plugin, "starter_core_owner");
        starterArchetype = new NamespacedKey(plugin, "starter_archetype");
    }

    void tryGrant(Player player, Archetype archetype) {
        if (!plugin.getConfig().getBoolean("onboarding.starter-kit.enabled", true)
                || !currentSession(player) || archetype == null || archetype == Archetype.UNCHOSEN
                || !AuthMeApi.getInstance().isAuthenticated(player)) return;
        int version = Math.max(1, plugin.getConfig().getInt("onboarding.starter-kit.version", 1));
        int granted = player.getPersistentDataContainer().getOrDefault(
                grantedVersion, PersistentDataType.INTEGER, 0);
        if (granted >= version) return;

        List<ItemStack> packageItems = packageFor(player, archetype);
        long freeSlots = java.util.Arrays.stream(player.getInventory().getStorageContents())
                .filter(item -> item == null || item.getType().isAir()).count();
        if (freeSlots < packageItems.size()) {
            long now = System.currentTimeMillis();
            Long previous = inventoryWarnings.put(player.getUniqueId(), now);
            if (previous == null || now - previous >= 30_000L) {
                player.sendMessage(Component.text(messages.text(player,
                        "guide.starter-kit.need-space", Map.of("slots", packageItems.size())), NamedTextColor.YELLOW));
            }
            return;
        }

        for (ItemStack item : packageItems) {
            if (!player.getInventory().addItem(item).isEmpty()) {
                plugin.getLogger().severe("Starter kit capacity changed during synchronous grant for " + player.getUniqueId());
                return;
            }
        }
        player.getPersistentDataContainer().set(grantedVersion, PersistentDataType.INTEGER, version);
        player.saveData();
        inventoryWarnings.remove(player.getUniqueId());
        showGrant(player, packageItems.getFirst(), archetype);
    }

    boolean needsReconciliation(Player player) {
        if (!plugin.getConfig().getBoolean("onboarding.starter-kit.enabled", true)) return false;
        int expected = Math.max(1, plugin.getConfig().getInt("onboarding.starter-kit.version", 1));
        int granted = player.getPersistentDataContainer().getOrDefault(
                grantedVersion, PersistentDataType.INTEGER, 0);
        int expectedClaimTool = Math.max(1, plugin.getConfig().getInt(
                "onboarding.starter-kit.claim-tool-version", 1));
        int grantedClaimTool = player.getPersistentDataContainer().getOrDefault(
                claimToolVersion, PersistentDataType.INTEGER, 0);
        return granted < expected || (PlayerJourneyFacts.hasWildernessArrival(player)
                && grantedClaimTool < expectedClaimTool);
    }

    /**
     * Delivers only the standard GriefPrevention selection tool. The version is
     * deliberately independent from the class kit and is not land-claim state.
     */
    void tryGrantClaimTool(Player player) {
        if (!plugin.getConfig().getBoolean("onboarding.starter-kit.enabled", true)
                || !currentSession(player) || !AuthMeApi.getInstance().isAuthenticated(player)) return;
        int expected = Math.max(1, plugin.getConfig().getInt(
                "onboarding.starter-kit.claim-tool-version", 1));
        int granted = player.getPersistentDataContainer().getOrDefault(
                claimToolVersion, PersistentDataType.INTEGER, 0);
        if (granted >= expected) return;

        if (!hasClaimTool(player)) {
            ItemStack shovel = named(Material.GOLDEN_SHOVEL,
                    messages.text(player, "guide.starter-kit.claim-tool-name"));
            ItemMeta meta = shovel.getItemMeta();
            meta.lore(lore(messages.text(player,
                    "guide.starter-kit.claim-tool-lore"), NamedTextColor.GRAY));
            shovel.setItemMeta(meta);
            Map<Integer, ItemStack> remainder = player.getInventory().addItem(shovel);
            if (!remainder.isEmpty()) {
                warnClaimToolSpace(player);
                return;
            }
        }

        player.getPersistentDataContainer().set(
                claimToolVersion, PersistentDataType.INTEGER, expected);
        player.saveData();
        inventoryWarnings.remove(player.getUniqueId());
    }

    private boolean hasClaimTool(Player player) {
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && item.getType() == Material.GOLDEN_SHOVEL) return true;
        }
        return false;
    }

    private void warnClaimToolSpace(Player player) {
        long now = System.currentTimeMillis();
        Long previous = inventoryWarnings.put(player.getUniqueId(), now);
        if (previous == null || now - previous >= 30_000L) {
            player.sendMessage(Component.text(messages.text(player,
                    "guide.starter-kit.claim-tool-need-space"), NamedTextColor.YELLOW));
        }
    }

    private List<ItemStack> packageFor(Player player, Archetype archetype) {
        String owner = player.getUniqueId().toString();
        List<ItemStack> items = new ArrayList<>();
        ItemStack base = named(baseMaterial(archetype), messages.text(player,
                "guide.starter-kit.base-name", Map.of("class", messages.text(player,
                        "classes." + archetype.name().toLowerCase(Locale.ROOT)))));
        ItemMeta baseMeta = base.getItemMeta();
        baseMeta.getPersistentDataContainer().set(starterBaseOwner, PersistentDataType.STRING, owner);
        baseMeta.getPersistentDataContainer().set(starterArchetype, PersistentDataType.STRING, archetype.name());
        baseMeta.setUnbreakable(true);
        bind(baseMeta, owner);
        baseMeta.lore(lore(messages.text(player, "guide.starter-kit.base-lore"), NamedTextColor.GRAY));
        base.setItemMeta(baseMeta);
        items.add(base);

        ItemStack core = named(Material.ECHO_SHARD, messages.text(player, "guide.starter-kit.core-name"));
        core.setAmount(8);
        ItemMeta coreMeta = core.getItemMeta();
        coreMeta.getPersistentDataContainer().set(starterCoreOwner, PersistentDataType.STRING, owner);
        if (forgeEssence != null) coreMeta.getPersistentDataContainer().set(
                forgeEssence, PersistentDataType.BYTE, (byte) 1);
        bind(coreMeta, owner);
        coreMeta.lore(lore(messages.text(player, "guide.starter-kit.core-lore"), NamedTextColor.AQUA));
        core.setItemMeta(coreMeta);
        items.add(core);

        items.add(boundUtility(player, Material.BREAD, 12, "food"));
        items.add(boundUtility(player, Material.WHITE_BED, 1, "bed"));
        if (archetype == Archetype.RANGER) items.add(boundUtility(player, Material.ARROW, 24, "arrows"));
        return List.copyOf(items);
    }

    private ItemStack boundUtility(Player player, Material material, int amount, String key) {
        ItemStack item = named(material, messages.text(player, "guide.starter-kit." + key + "-name"));
        item.setAmount(amount);
        ItemMeta meta = item.getItemMeta();
        bind(meta, player.getUniqueId().toString());
        meta.lore(lore(messages.text(player, "guide.starter-kit.utility-lore"), NamedTextColor.DARK_GRAY));
        item.setItemMeta(meta);
        return item;
    }

    private void bind(ItemMeta meta, String owner) {
        if (materialOwner != null) meta.getPersistentDataContainer().set(
                materialOwner, PersistentDataType.STRING, owner);
    }

    private void showGrant(Player player, ItemStack base, Archetype archetype) {
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP,
                SoundCategory.PLAYERS, 0.65F, 1.25F);
        Component quickOpen = Component.text("[" + messages.text(player,
                        "guide.starter-kit.open-button") + "]", NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/forge"));
        player.sendMessage(Component.text(messages.text(player, "guide.starter-kit.granted"), NamedTextColor.GOLD)
                .append(Component.space()).append(quickOpen));

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text(
                                messages.text(player, "guide.starter-kit.title"), NamedTextColor.GOLD))
                        .externalTitle(Component.text(messages.text(player, "guide.starter-kit.title")))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(List.of(
                                DialogBody.item(base.clone()).showDecorations(false).showTooltip(true)
                                        .width(56).height(56).build(),
                                DialogBody.plainMessage(Component.text(messages.text(player,
                                        "guide.starter-kit.identity", Map.of("class", messages.text(player,
                                                "classes." + archetype.name().toLowerCase(Locale.ROOT)))), NamedTextColor.GOLD)),
                                DialogBody.plainMessage(Component.text(messages.text(player,
                                        "guide.starter-kit.instruction"), NamedTextColor.WHITE)),
                                DialogBody.plainMessage(Component.text(messages.text(player,
                                        "guide.starter-kit.guarantee"), NamedTextColor.GREEN)),
                                DialogBody.plainMessage(Component.text(messages.text(player,
                                        "guide.starter-kit.bound"), NamedTextColor.GRAY))
                        )).build())
                .type(DialogType.multiAction(List.of(
                        ActionButton.builder(Component.text(messages.text(player,
                                        "guide.starter-kit.open-button"), NamedTextColor.GREEN))
                                .tooltip(Component.text(messages.text(player,
                                        "guide.starter-kit.open-tooltip")))
                                .width(180)
                                .action(DialogAction.customClick((response, audience) -> {
                                    if (!(audience instanceof Player responder)) return;
                                    sync(() -> {
                                        if (currentSession(responder)
                                                && AuthMeApi.getInstance().isAuthenticated(responder)) {
                                            responder.performCommand("forge");
                                        }
                                    });
                                }, ClickCallback.Options.builder().uses(1)
                                        .lifetime(Duration.ofMinutes(10)).build()))
                                .build(),
                        ActionButton.create(Component.text(messages.text(player,
                                        "guide.starter-kit.later-button"), NamedTextColor.GRAY),
                                Component.text(messages.text(player,
                                        "guide.starter-kit.later-tooltip")), 140, null)
                )).columns(2).build()));
        player.showDialog(dialog);
    }

    static Material baseMaterial(Archetype archetype) {
        return switch (archetype) {
            case HEALER -> Material.BREEZE_ROD;
            case WARRIOR -> Material.STONE_AXE;
            case ASSASSIN -> Material.STONE_SWORD;
            case MAGE -> Material.BLAZE_ROD;
            case RANGER -> Material.BOW;
            case FREEFORM -> Material.WOODEN_SWORD;
            case UNCHOSEN -> throw new IllegalArgumentException("A class is required for a starter kit");
        };
    }

    private static boolean currentSession(Player player) {
        return player != null && player.isOnline()
                && Bukkit.getPlayer(player.getUniqueId()) == player;
    }

    private void sync(Runnable action) {
        if (!plugin.isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (plugin.isEnabled()) action.run();
            });
        } catch (IllegalStateException ignored) {
            // The already-delivered package and PDC version are durable; only
            // this optional convenience button expires during shutdown.
        }
    }

    private static ItemStack named(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private static List<Component> lore(String text, NamedTextColor color) {
        return cn.originalrpg.api.ui.MenuLoreLayout.wrap(text).stream()
                .<Component>map(line -> Component.text(line, color)
                        .decoration(TextDecoration.ITALIC, false))
                .toList();
    }
}
