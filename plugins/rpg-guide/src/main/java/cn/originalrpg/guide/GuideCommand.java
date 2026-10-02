package cn.originalrpg.guide;

import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.model.CommerceStatus;
import cn.originalrpg.api.model.EconomyProgress;
import cn.originalrpg.api.model.JourneyStep;
import cn.originalrpg.api.model.PlayerProfile;
import cn.originalrpg.api.model.VisualMode;
import cn.originalrpg.api.service.ActivityEconomyService;
import cn.originalrpg.api.service.CommerceService;
import cn.originalrpg.api.service.ChapterService;
import cn.originalrpg.api.service.GuideMenuService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.OptionalServices;
import cn.originalrpg.api.service.ProfileService;
import cn.originalrpg.api.service.SkillHudService;
import cn.originalrpg.api.model.SkillHudSnapshot;
import cn.originalrpg.api.rules.ProgressionRules;
import cn.originalrpg.api.ui.MenuLoreLayout;
import cn.originalrpg.api.ui.HudGlyphs;
import cn.originalrpg.api.ui.JourneyGlyphs;
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
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

final class GuideCommand implements CommandExecutor, TabCompleter, Listener {
    private static final List<Integer> HELP_SLOTS = List.of(
            10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 30, 31, 32);
    private static final List<Integer> NPC_SLOTS = List.of(
            10, 11, 12, 13, 14, 15, 16, 20, 21, 22, 23);
    private static final NamespacedKey ITEM_ID = NamespacedKey.fromString("rpgitems:item_id");
    private static final NamespacedKey TOTAL_SLOTS = NamespacedKey.fromString("rpgitems:total_slots");
    private static final NamespacedKey REVEALED_SLOTS = NamespacedKey.fromString("rpgitems:revealed_slots");
    private static final NamespacedKey BLANK_IMPRINT = NamespacedKey.fromString("rpgsulfur:blank_imprint");
    private static final NamespacedKey SULFUR_IMPRINT = NamespacedKey.fromString("rpgsulfur:imprint");
    private static final String HELP_LINES_LEAF = "lines";
    private static final String JOURNEY_HOW_LEAF = "how";
    private static final String JOURNEY_RULES_LEAF = "rules";
    private static final int HELP_BODY_WIDTH = 320;

    private final Plugin plugin;
    private final ProfileService profiles;
    private final ActivityEconomyService economy;
    private final CommerceService commerce;
    private final ChapterService chapters;
    private final MessageService messages;
    private final GuideTracker tracker;
    private final AtomicLong mainSequence = new AtomicLong();
    private final AtomicLong helpSequence = new AtomicLong();
    private final AtomicLong journeySequence = new AtomicLong();
    private final AtomicLong freeplaySequence = new AtomicLong();
    private final Map<UUID, Long> pendingMain = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pendingHelp = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pendingJourney = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pendingFreeplay = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> authenticatedPlayers = ConcurrentHashMap.newKeySet();

    GuideCommand(
            Plugin plugin,
            ProfileService profiles,
            ActivityEconomyService economy,
            CommerceService commerce,
            ChapterService chapters,
            MessageService messages,
            GuideTracker tracker
    ) {
        this.plugin = plugin;
        this.profiles = profiles;
        this.economy = economy;
        this.commerce = commerce;
        this.chapters = chapters;
        this.messages = messages;
        this.tracker = tracker;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.text(sender, "common.player-only"));
            return true;
        }
        cancelPendingViews(player);
        if (command.getName().equalsIgnoreCase("menu")) {
            openMain(player);
            return true;
        }
        if (command.getName().equalsIgnoreCase("tasks")) {
            openQuests(player);
            return true;
        }
        HelpCommandRoute route = HelpCommandRoute.parse(args);
        switch (route.target()) {
            case CONTEXTUAL -> openContextualHelp(player);
            case CURRENT -> openCurrentObjective(player);
            case HOME -> openHelp(player);
            case NPCS -> openNpcDirectory(player);
            case TOPICS -> openHelpTopics(player);
            case TOPIC -> showTopic(player, route.topic());
        }
        return true;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ActionMenu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String action = menu.actionAt(event.getRawSlot(), event.isRightClick() || event.isShiftClick());
        if (action == null) return;
        execute(player, action);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ActionMenu) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOtherInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player
                && !(event.getInventory().getHolder() instanceof ActionMenu)) {
            cancelPendingViews(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnyCommand(PlayerCommandPreprocessEvent event) {
        cancelPendingViews(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        authenticatedPlayers.remove(event.getPlayer().getUniqueId());
        cancelPendingViews(event.getPlayer());
    }

    @EventHandler
    public void onAuthenticationRevoked(AuthenticatedPlayerRevokedEvent event) {
        Player player = event.player();
        authenticatedPlayers.remove(player.getUniqueId());
        cancelPendingViews(player);
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof ActionMenu) {
            player.closeInventory();
        }
        // Paper dialogs are not inventories. Close any menu/help dialog that
        // was created while the connection was trusted before AuthMe shows its
        // own unauthenticated flow.
        player.closeDialog();
    }

    @EventHandler
    public void onAuthenticated(cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent event) {
        authenticatedPlayers.add(event.player().getUniqueId());
    }

    void openMain(Player player) {
        long request = mainSequence.incrementAndGet();
        pendingMain.put(player.getUniqueId(), request);
        profiles.get(player.getUniqueId())
                .thenCombine(economy.progress(player.getUniqueId()), ProfileEconomy::new)
                .thenCombine(commerce.status(player.getUniqueId()), MenuData::new)
                .whenComplete((data, error) -> sync(() -> {
                    if (!pendingMain.remove(player.getUniqueId(), request) || !ready(player)) return;
                    if (error != null) {
                        player.sendMessage(Component.text(t(player, "guide.menu.load-error"), NamedTextColor.RED));
                        return;
                    }
                    tracker.evaluate(player);
                    ActionMenu menu = new ActionMenu(GuideMenuLayout.SIZE, t(player, "guide.menu.main-title"));
                    PlayerProfile profile = data.profileEconomy().profile();
                    EconomyProgress progress = data.profileEconomy().economy();
                    CommerceStatus status = data.commerce();
                    SkillHudService skillHud = OptionalServices.enabled(plugin, SkillHudService.class);
                    SkillHudSnapshot skillSnapshot = skillHud == null ? null
                            : skillHud.snapshot(player.getUniqueId()).orElse(null);
                    long readySkills = skillSnapshot == null ? 0L : skillSnapshot.learnedSkills().stream()
                            .filter(entry -> entry.ready(System.currentTimeMillis())).count();
                    int learnedSkills = skillSnapshot == null ? 0 : skillSnapshot.learnedSkills().size();
                    String skillSummary = skillSnapshot == null
                            ? t(player, "guide.menu.profile-lines.skills-loading")
                            : t(player, "guide.menu.profile-lines.skills", "ready", readySkills,
                                    "learned", learnedSkills);
                    String experienceSummary = profile.level() >= ProgressionRules.levelCap(profile.ascension())
                            ? t(player, "guide.menu.profile-lines.experience-cap")
                            : t(player, "guide.menu.profile-lines.experience", "current", profile.experience(),
                                    "needed", ProgressionRules.experienceToNextLevel(
                                            profile.ascension(), profile.level()));
                    menu.set(GuideMenuLayout.PROFILE, item(Material.PLAYER_HEAD, player.getName(), NamedTextColor.GOLD,
                            t(player, "guide.menu.profile-lines.class", "class", className(player, profile.archetype())),
                            t(player, "guide.menu.profile-lines.stage", "ascension", profile.ascension(), "level", profile.level()),
                            experienceSummary,
                            t(player, "guide.menu.profile-lines.energy", "energy", profile.energy(), "cap", profile.energyCap()),
                            skillSummary,
                            t(player, "guide.menu.profile-lines.gold-merit", "gold", progress.gold().toPlainString(), "merit", progress.merit()),
                            t(player, "guide.menu.profile-lines.starlight", "starlight", status.credits())), "command:rpg");
                    JourneyStep next = tracker.nextStep(player);
                    menu.set(GuideMenuLayout.JOURNEY, item(Material.COMPASS, t(player, "guide.menu.onboarding-title"), NamedTextColor.AQUA,
                            t(player, "guide.menu.onboarding-progress", "done", tracker.completedCount(player),
                                    "total", GuideTracker.STEPS.size()),
                            next == null ? t(player, "guide.menu.onboarding-complete")
                                    : t(player, "guide.menu.onboarding-next", "step", tracker.stepName(player, next)),
                            next == null ? t(player, "guide.menu.onboarding-freeplay")
                                    : t(player, "guide.onboarding.steps." + next.id() + ".where"),
                            t(player, "guide.menu.open-current-goal")), next == null
                            ? GuideFreeplayMenuPolicy.completedJourneyAction() : "open:onboarding");
                    menu.set(GuideMenuLayout.GROWTH, item(Material.NETHER_STAR, t(player, "guide.menu.growth-title"), NamedTextColor.GOLD,
                            lines(player, "guide.menu.growth-lore")), "open:growth");
                    menu.set(GuideMenuLayout.ADVENTURE, item(Material.RECOVERY_COMPASS, t(player, "guide.menu.adventure-title"), NamedTextColor.AQUA,
                            lines(player, "guide.menu.adventure-lore")), "open:adventure");
                    menu.set(GuideMenuLayout.ECONOMY, item(Material.GOLD_INGOT, t(player, "guide.menu.economy-title"), NamedTextColor.YELLOW,
                            lines(player, "guide.menu.economy-category-lore")), "open:economy");
                    menu.set(GuideMenuLayout.SOCIAL, item(Material.LODESTONE, t(player, "guide.menu.social-title"), NamedTextColor.GREEN,
                            lines(player, "guide.menu.social-lore")), "open:social");
                    menu.set(GuideMenuLayout.VISUALS, item(Material.GLOW_INK_SAC, t(player, "guide.menu.visuals-title"), NamedTextColor.AQUA,
                            t(player, "guide.menu.visuals-lore", "mode", visualModeName(player))), "open:visuals");
                    menu.set(GuideMenuLayout.HELP, item(Material.WRITABLE_BOOK, t(player, "guide.menu.help-title"), NamedTextColor.WHITE,
                            t(player, "guide.menu.help-lore")), "open:help");
                    menu.set(GuideMenuLayout.MAILBOX, item(Material.CHEST, t(player, "guide.menu.mailbox-title"), NamedTextColor.GREEN,
                            t(player, "guide.menu.mailbox-lore")), "command:market claim");
                    player.openInventory(menu.getInventory());
                }));
    }

    private void openGrowth(Player player) {
        ActionMenu menu = submenu(player, "guide.submenus.growth.title");
        menu.set(10, item(Material.IRON_SWORD, t(player, "guide.menu.class-title"), NamedTextColor.RED,
                t(player, "guide.menu.class-lore")), "open:class");
        menu.set(12, item(Material.ENCHANTED_BOOK, t(player, "guide.menu.skills-title"),
                NamedTextColor.LIGHT_PURPLE, lines(player, "guide.menu.skills-lore-lines")), "command:skills");
        menu.set(14, item(Material.ANVIL, t(player, "guide.menu.forge-title"), NamedTextColor.AQUA,
                t(player, "guide.menu.forge-lore")), "command:forge");
        menu.set(16, item(Material.SMITHING_TABLE, t(player, "guide.menu.enhancement-title"),
                NamedTextColor.YELLOW, t(player, "guide.menu.enhancement-lore")), "open:enhancement");
        menu.set(22, item(Material.SULFUR, t(player, "guide.menu.sulfur-title"), NamedTextColor.YELLOW,
                t(player, "guide.menu.sulfur-lore")), "command:imprint");
        menu.set(30, item(Material.NETHER_STAR, t(player, "guide.submenus.growth.skill-hud"),
                NamedTextColor.AQUA, t(player, "guide.submenus.growth.skill-hud-lore")), "command:skills hud");
        menu.set(32, item(Material.WRITABLE_BOOK, t(player, "guide.submenus.growth.rules"),
                NamedTextColor.WHITE, t(player, "guide.submenus.growth.rules-lore")), "help:skills");
        player.openInventory(menu.getInventory());
    }

    private void openAdventure(Player player) {
        ActionMenu menu = submenu(player, "guide.submenus.adventure.title");
        menu.set(10, item(Material.COMPASS, t(player, "guide.menu.onboarding-title"), NamedTextColor.AQUA,
                t(player, "guide.menu.onboarding-progress", "done", tracker.completedCount(player),
                        "total", GuideTracker.STEPS.size())), "open:onboarding");
        menu.set(12, item(Material.WRITABLE_BOOK, t(player, "guide.menu.quests-title"), NamedTextColor.GREEN,
                t(player, "guide.menu.quests-lore")), "open:quests");
        menu.set(14, item(Material.RECOVERY_COMPASS, t(player, "guide.menu.travel-title"), NamedTextColor.AQUA,
                t(player, "guide.menu.travel-lore")), "open:travel");
        menu.set(16, item(Material.TRIAL_KEY, t(player, "guide.menu.dungeon-title"), NamedTextColor.GOLD,
                t(player, "guide.menu.dungeon-lore")), "open:dungeon");
        menu.set(GuideMenuLayout.ADVENTURE_SEASON, modeled(player, item(Material.RECOVERY_COMPASS,
                t(player, "guide.submenus.adventure.season"), NamedTextColor.LIGHT_PURPLE,
                t(player, "guide.submenus.adventure.season-lore")), "dungeon_core"),
                "command:dungeon season");
        menu.set(22, item(Material.REDSTONE_TORCH, t(player, "guide.menu.world-title"), NamedTextColor.RED,
                t(player, "guide.menu.world-lore")), "command:worldstatus");
        menu.set(24, item(Material.ZOMBIE_HEAD, t(player, "guide.submenus.adventure.leveling"), NamedTextColor.GREEN,
                t(player, "guide.submenus.adventure.leveling-lore")), "command:mobguide");
        menu.set(30, item(Material.RECOVERY_COMPASS, t(player, "guide.submenus.adventure.story"),
                NamedTextColor.LIGHT_PURPLE, t(player, "guide.submenus.adventure.story-lore")),
                "command:story status");
        menu.set(32, item(Material.WRITABLE_BOOK, t(player, "guide.submenus.adventure.rules"),
                NamedTextColor.WHITE, t(player, "guide.submenus.adventure.rules-lore")), "help:start");
        player.openInventory(menu.getInventory());
    }

    private void openSocial(Player player) {
        ActionMenu menu = submenu(player, "guide.submenus.social.title");
        menu.set(11, item(Material.LODESTONE, t(player, "guide.menu.community-title"), NamedTextColor.GREEN,
                t(player, "guide.menu.community-lore")), "command:community");
        menu.set(13, item(Material.ECHO_SHARD, t(player, "guide.menu.ai-title"), NamedTextColor.AQUA,
                t(player, "guide.menu.ai-lore")), "command:ainpc");
        menu.set(15, item(Material.WRITABLE_BOOK, t(player, "guide.menu.quests-title"), NamedTextColor.GREEN,
                t(player, "guide.menu.quests-lore")), "open:quests");
        menu.set(22, item(Material.LEAD, t(player, "guide.menu.companions-title"), NamedTextColor.GOLD,
                lines(player, "guide.menu.companions-lore")), "command:companion");
        menu.set(29, item(Material.GLOW_INK_SAC, t(player, "guide.menu.visuals-title"), NamedTextColor.AQUA,
                t(player, "guide.menu.visuals-lore", "mode", visualModeName(player))), "open:visuals");
        menu.set(31, item(Material.WRITABLE_BOOK, t(player, "guide.menu.help-title"), NamedTextColor.WHITE,
                t(player, "guide.menu.help-lore")), "open:help");
        menu.set(33, item(Material.CHEST, t(player, "guide.menu.mailbox-title"), NamedTextColor.GREEN,
                t(player, "guide.menu.mailbox-lore")), "command:market claim");
        player.openInventory(menu.getInventory());
    }

    private void openEnhancement(Player player) {
        ActionMenu menu = submenu(player, "guide.submenus.enhancement.title");
        menu.set(11, modeled(player, item(Material.GRINDSTONE, t(player, "guide.submenus.enhancement.refine"), NamedTextColor.AQUA,
                lines(player, "guide.submenus.enhancement.refine-lore")), "mutation_shard"), "command:refine");
        menu.set(13, modeled(player, item(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE,
                t(player, "guide.submenus.enhancement.limitbreak"), NamedTextColor.GOLD,
                t(player, "guide.submenus.enhancement.limitbreak-lore")), "mutation_core"), "command:limitbreak");
        menu.set(15, item(Material.ENCHANTED_BOOK, t(player, "guide.submenus.enhancement.mutate"), NamedTextColor.LIGHT_PURPLE,
                lines(player, "guide.submenus.enhancement.mutate-lore")), "help:enhancement");
        menu.set(30, modeled(player, item(Material.AMETHYST_SHARD, t(player, "guide.submenus.enhancement.merit-shop"), NamedTextColor.GREEN,
                t(player, "guide.submenus.enhancement.merit-shop-lore")), "protection_stone"), "command:economy shop");
        menu.set(32, modeled(player, item(Material.ECHO_SHARD, t(player, "guide.submenus.enhancement.starlight-shop"), NamedTextColor.LIGHT_PURPLE,
                t(player, "guide.submenus.enhancement.starlight-shop-lore")), "lucky_charm"), "command:store");
        menu.set(22, item(Material.FLINT, t(player, "guide.submenus.enhancement.salvage"), NamedTextColor.RED,
                t(player, "guide.submenus.enhancement.salvage-lore")), "command:salvage");
        menu.set(20, modeled(player, item(Material.PAPER, t(player, "guide.submenus.enhancement.legacy"), NamedTextColor.LIGHT_PURPLE,
                t(player, "guide.submenus.enhancement.legacy-lore")), "legacy_blueprint"), "command:legacy");
        menu.set(24, item(Material.TRIPWIRE_HOOK, t(player, "guide.submenus.enhancement.unbind"), NamedTextColor.GOLD,
                t(player, "guide.submenus.enhancement.unbind-lore")), "command:unbind");
        player.openInventory(menu.getInventory());
    }

    private void openDungeons(Player player) {
        ActionMenu menu = submenu(player, "guide.submenus.dungeon.title");
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("dungeons");
        if (section != null) {
            List<Integer> dungeonSlots = List.of(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25);
            int slotIndex = 0;
            for (String id : section.getKeys(false)) {
                ConfigurationSection dungeon = section.getConfigurationSection(id);
                if (dungeon == null || slotIndex >= dungeonSlots.size()) continue;
                int slot = dungeonSlots.get(slotIndex++);
                Material icon = Material.matchMaterial(dungeon.getString("icon", "TRIAL_KEY"));
                if (icon == null) icon = Material.TRIAL_KEY;
                menu.set(slot, modeled(player, item(icon, t(player, "guide.dungeons." + id), NamedTextColor.GOLD,
                        t(player, "guide.submenus.dungeon.minimum-level", "level", dungeon.getInt("minimum-level", 1)),
                        t(player, "guide.submenus.dungeon.energy", "energy", dungeon.getInt("energy", 20)),
                        t(player, "guide.submenus.dungeon.party", "party", dungeon.getString("party", "1-4")),
                        t(player, "guide.submenus.dungeon.enter")), id.endsWith("-hidden")
                        ? "hidden_ticket" : "dungeon_core"), "command:dungeon enter " + id);
            }
        }
        menu.set(28, item(Material.LIME_DYE, t(player, "guide.submenus.dungeon.party-create"), NamedTextColor.GREEN,
                t(player, "guide.submenus.dungeon.party-create-lore")), "command:dungeon party create");
        menu.set(GuideMenuLayout.DUNGEON_SEASON, modeled(player, item(Material.RECOVERY_COMPASS,
                t(player, "guide.submenus.dungeon.season"), NamedTextColor.LIGHT_PURPLE,
                t(player, "guide.submenus.dungeon.season-lore")), "dungeon_core"),
                "command:dungeon season");
        menu.set(30, item(Material.SPYGLASS, t(player, "guide.submenus.dungeon.party-info"), NamedTextColor.AQUA,
                t(player, "guide.submenus.dungeon.party-info-lore")), "command:dungeon party info");
        menu.set(32, item(Material.ENDER_PEARL, t(player, "guide.submenus.dungeon.leave"), NamedTextColor.YELLOW,
                t(player, "guide.submenus.dungeon.leave-lore")), "command:dungeon leave");
        menu.set(34, item(Material.WRITABLE_BOOK, t(player, "guide.submenus.dungeon.rules"), NamedTextColor.WHITE,
                t(player, "guide.submenus.dungeon.rules-lore")), "help:dungeons");
        player.openInventory(menu.getInventory());
    }

    private void openMarket(Player player) {
        ActionMenu menu = submenu(player, "guide.submenus.market.title");
        menu.set(11, item(Material.SPYGLASS, t(player, "guide.submenus.market.list"), NamedTextColor.AQUA,
                t(player, "guide.submenus.market.list-lore")), "command:market list");
        menu.set(13, item(Material.EMERALD, t(player, "guide.submenus.market.sell"), NamedTextColor.GREEN,
                lines(player, "guide.submenus.market.sell-lore")), "command:market sell");
        menu.set(15, item(Material.CHEST, t(player, "guide.submenus.market.claim"), NamedTextColor.GOLD,
                t(player, "guide.submenus.market.claim-lore")), "command:market claim");
        menu.set(31, item(Material.BARRIER, t(player, "guide.submenus.market.cancel"), NamedTextColor.RED,
                t(player, "guide.submenus.market.cancel-lore")), "command:market mine");
        menu.set(33, item(Material.WRITABLE_BOOK, t(player, "guide.submenus.market.rules"), NamedTextColor.WHITE,
                t(player, "guide.submenus.market.rules-lore")), "help:market");
        player.openInventory(menu.getInventory());
    }

    private void openEconomy(Player player) {
        ActionMenu menu = submenu(player, "guide.submenus.economy.title");
        menu.set(11, item(Material.GOLD_INGOT, t(player, "guide.submenus.economy.status"), NamedTextColor.GOLD,
                t(player, "guide.submenus.economy.status-lore")), "command:economy status");
        menu.set(13, item(Material.BUNDLE, t(player, "guide.submenus.economy.commissions"), NamedTextColor.GREEN,
                t(player, "guide.submenus.economy.commissions-lore")), "command:economy commissions");
        menu.set(15, item(Material.AMETHYST_SHARD, t(player, "guide.submenus.economy.shop"), NamedTextColor.LIGHT_PURPLE,
                t(player, "guide.submenus.economy.shop-lore")), "command:economy shop");
        menu.set(20, item(Material.ENDER_CHEST, t(player, "guide.submenus.economy.warehouse"), NamedTextColor.DARK_PURPLE,
                lines(player, "guide.submenus.economy.warehouse-lore")), "command:warehouse");
        menu.set(22, item(Material.NETHER_STAR, t(player, "guide.submenus.economy.exchange"), NamedTextColor.YELLOW,
                t(player, "guide.submenus.economy.exchange-lore")), "command:economy exchange");
        menu.set(24, item(Material.CRAFTING_TABLE, t(player, "guide.submenus.economy.vanilla"), NamedTextColor.AQUA,
                t(player, "guide.submenus.economy.vanilla-lore")), "command:economy vanilla");
        menu.set(29, item(Material.SUSPICIOUS_STEW, t(player, "guide.submenus.economy.merchant"), NamedTextColor.RED,
                t(player, "guide.submenus.economy.merchant-lore")), "command:economy merchant");
        menu.set(31, item(Material.EMERALD, t(player, "guide.submenus.economy.market"), NamedTextColor.AQUA,
                t(player, "guide.submenus.economy.market-lore")), "open:market");
        menu.set(33, item(Material.WRITABLE_BOOK, t(player, "guide.submenus.economy.rules"), NamedTextColor.WHITE,
                t(player, "guide.submenus.economy.rules-lore")), "help:economy");
        player.openInventory(menu.getInventory());
    }

    private void openQuests(Player player) {
        tracker.evaluate(player);
        ActionMenu menu = submenu(player, "guide.submenus.quests.title");
        JourneyStep next = tracker.nextStep(player);
        if (next == null) {
            menu.set(GuideMenuLayout.QUESTS_CURRENT, item(Material.NETHER_STAR,
                    t(player, "guide.submenus.quests.current-complete"), NamedTextColor.GREEN,
                    t(player, "guide.submenus.quests.current-progress", "done", tracker.completedCount(player),
                            "total", GuideTracker.STEPS.size()),
                    t(player, "guide.submenus.quests.current-complete-lore"),
                    t(player, "guide.submenus.quests.current-review")),
                    GuideFreeplayMenuPolicy.completedJourneyAction());
        } else {
            menu.set(GuideMenuLayout.QUESTS_CURRENT, journeyItem(player, next,
                    t(player, "guide.submenus.quests.current", "step", tracker.stepName(player, next)),
                    NamedTextColor.GOLD,
                    t(player, "guide.submenus.quests.current-progress", "done", tracker.completedCount(player),
                            "total", GuideTracker.STEPS.size()),
                    stepHint(player, next),
                    t(player, "guide.submenus.quests.current-where", "where",
                            t(player, "guide.onboarding.steps." + next.id() + ".where")),
                    t(player, "guide.submenus.quests.current-open")), "brief:" + next.id());
        }
        menu.set(10, item(Material.COMPASS, t(player, "guide.submenus.quests.journey"), NamedTextColor.AQUA,
                t(player, "guide.menu.onboarding-progress", "done", tracker.completedCount(player),
                        "total", GuideTracker.STEPS.size()),
                t(player, "guide.submenus.quests.journey-lore")),
                GuideFreeplayMenuPolicy.reviewJourneyAction());
        menu.set(12, item(Material.BUNDLE, t(player, "guide.submenus.quests.commissions"), NamedTextColor.GREEN,
                t(player, "guide.submenus.quests.commissions-lore")), "command:economy commissions");
        menu.set(14, modeled(player, item(Material.TRIAL_KEY, t(player, "guide.submenus.quests.dungeons"), NamedTextColor.GOLD,
                t(player, "guide.submenus.quests.dungeons-lore")), "dungeon_core"), "open:dungeon");
        menu.set(16, modeled(player, item(Material.REDSTONE_TORCH, t(player, "guide.submenus.quests.events"), NamedTextColor.RED,
                t(player, "guide.submenus.quests.events-lore")), "blood_moon_essence"), "command:worldstatus");
        menu.set(20, item(Material.CRAFTING_TABLE, t(player, "guide.submenus.quests.vanilla"), NamedTextColor.YELLOW,
                t(player, "guide.submenus.quests.vanilla-lore")), "command:economy vanilla");
        menu.set(22, item(Material.ENCHANTED_BOOK, t(player, "guide.submenus.quests.hidden"), NamedTextColor.LIGHT_PURPLE,
                t(player, "guide.submenus.quests.hidden-lore")), "help:skills");
        menu.set(24, item(Material.CHEST, t(player, "guide.submenus.quests.claim"), NamedTextColor.GREEN,
                t(player, "guide.submenus.quests.claim-lore")), "command:market claim");
        menu.set(28, item(Material.RECOVERY_COMPASS, t(player, "guide.submenus.quests.story"), NamedTextColor.AQUA,
                t(player, "guide.submenus.quests.story-lore")), "command:story status");
        menu.set(30, item(Material.LODESTONE, t(player, "guide.submenus.quests.community"), NamedTextColor.GREEN,
                t(player, "guide.submenus.quests.community-lore")), "command:community");
        menu.set(32, item(Material.ECHO_SHARD, t(player, "guide.submenus.quests.ask-ai"), NamedTextColor.AQUA,
                t(player, "guide.submenus.quests.ask-ai-lore")), "command:ainpc");
        player.openInventory(menu.getInventory());
    }

    void openOnboarding(Player player) {
        tracker.evaluate(player);
        ActionMenu menu = submenu(player, "guide.submenus.onboarding.title");
        JourneyStep next = tracker.nextStep(player);
        if (next == null) {
            menu.set(GuideMenuLayout.ONBOARDING_CURRENT, item(Material.NETHER_STAR,
                    t(player, "guide.onboarding.current-complete"), NamedTextColor.GREEN,
                    t(player, "guide.onboarding.current-complete-lore"),
                    t(player, "guide.menu.onboarding-progress", "done", tracker.completedCount(player),
                            "total", GuideTracker.STEPS.size())),
                    GuideFreeplayMenuPolicy.completedJourneyAction());
        } else {
            menu.set(GuideMenuLayout.ONBOARDING_CURRENT, journeyItem(player, next,
                    t(player, "guide.onboarding.current-title", "step", tracker.stepName(player, next)),
                    NamedTextColor.GOLD,
                    stepHint(player, next),
                    t(player, "guide.onboarding.where-prefix")
                            + t(player, "guide.onboarding.steps." + next.id() + ".where"),
                    t(player, "guide.onboarding.why-prefix")
                            + t(player, "guide.onboarding.steps." + next.id() + ".why"),
                    t(player, "guide.onboarding.click-brief")), "brief:" + next.id());
        }
        int stepIndex = 0;
        for (JourneyStep step : GuideTracker.STEPS) {
            boolean complete = tracker.completed(player, step);
            JourneyUiPolicy.State state = JourneyUiPolicy.state(step, complete, next);
            String prefix = switch (state) {
                case COMPLETED -> t(player, "guide.onboarding.completed-prefix");
                case CURRENT -> t(player, "guide.onboarding.current-prefix");
                case LATER -> t(player, "guide.onboarding.later-prefix");
            };
            NamedTextColor color = switch (state) {
                case COMPLETED -> NamedTextColor.GREEN;
                case CURRENT -> NamedTextColor.GOLD;
                case LATER -> NamedTextColor.GRAY;
            };
            int stepSlot = GuideMenuLayout.ONBOARDING_STEP_SLOTS.get(stepIndex++);
            menu.set(stepSlot, journeyItem(player, step, prefix + tracker.stepName(player, step), color,
                    stepHint(player, step),
                    t(player, "guide.onboarding.where-prefix")
                            + t(player, "guide.onboarding.steps." + step.id() + ".where"),
                    t(player, "guide.onboarding.unlock-prefix")
                            + t(player, "guide.onboarding.steps." + step.id() + ".unlock"),
                    complete ? t(player, "guide.onboarding.revisit")
                            : t(player, "guide.onboarding.step-reward", "merit",
                                    plugin.getConfig().getLong("onboarding.step-merit", 2L))), "brief:" + step.id());
        }
        menu.set(GuideMenuLayout.ONBOARDING_STORY, item(Material.RECOVERY_COMPASS,
                t(player, "guide.submenus.onboarding.story"), NamedTextColor.AQUA,
                lines(player, "guide.submenus.onboarding.story-lore")), "command:story status");
        menu.set(GuideMenuLayout.ONBOARDING_SKILLS, item(Material.ENCHANTED_BOOK,
                t(player, "guide.submenus.onboarding.skills"), NamedTextColor.LIGHT_PURPLE,
                lines(player, "guide.submenus.onboarding.skills-lore")), "command:skills");
        menu.set(GuideMenuLayout.ONBOARDING_FULL_GUIDE, item(Material.WRITABLE_BOOK,
                t(player, "guide.submenus.onboarding.full-guide"), NamedTextColor.WHITE,
                t(player, "guide.submenus.onboarding.full-guide-lore")), "help:start");
        player.openInventory(menu.getInventory());
    }

    void openCurrentObjective(Player player) {
        cancelPendingViews(player);
        tracker.evaluate(player);
        JourneyStep next = tracker.nextStep(player);
        if (next == null) {
            openFreeplayGuide(player);
            return;
        }
        showJourneyBriefing(player, next);
    }

    private void openContextualHelp(Player player) {
        // During onboarding, help answers the immediate question instead of
        // asking a new player to classify their own confusion. Once all eight
        // goals are complete, the problem-based help library becomes home.
        tracker.evaluate(player);
        JourneyStep next = tracker.nextStep(player);
        if (next == null) openFreeplayGuide(player);
        else showJourneyBriefing(player, next);
    }

    /**
     * The finite tutorial must not end in another directory. Once all eight
     * checks are complete, derive one executable recommendation from live
     * character state and keep the player's other intents one click away.
     */
    private void openFreeplayGuide(Player player) {
        PlayerProfile cached = profiles.cached(player.getUniqueId()).orElse(null);
        if (cached != null) {
            pendingFreeplay.remove(player.getUniqueId());
            showFreeplayGuide(player, cached);
            return;
        }
        long request = freeplaySequence.incrementAndGet();
        pendingFreeplay.put(player.getUniqueId(), request);
        player.sendActionBar(Component.text(t(player, "guide.freeplay.loading"), NamedTextColor.AQUA));
        profiles.get(player.getUniqueId()).whenComplete((profile, error) -> sync(() -> {
            if (!pendingFreeplay.remove(player.getUniqueId(), request) || !ready(player)) return;
            if (error != null || profile == null) {
                player.sendMessage(Component.text(t(player, "guide.freeplay.load-error"), NamedTextColor.RED));
                openHelp(player);
                return;
            }
            showFreeplayGuide(player, profile);
        }));
    }

    private void showFreeplayGuide(Player player, PlayerProfile profile) {
        EquipmentSummary equipment = equipmentSummary(player);
        SkillHudService skillHud = OptionalServices.enabled(plugin, SkillHudService.class);
        SkillHudSnapshot skills = skillHud == null
                ? null : skillHud.snapshot(player.getUniqueId()).orElse(null);
        // Completing the starter-build step already proves at least one skill.
        // A temporarily cold HUD cache must not send a graduate back to the
        // beginner learning route on a false zero.
        int learned = skills == null ? 1 : skills.learnedSkills().size();
        DungeonReadiness dungeons = dungeonReadiness(profile);
        boolean canAscendNow = profile.level() >= ProgressionRules.levelCap(profile.ascension())
                && profile.ascension() < ProgressionRules.MAX_ASCENSION
                && profile.ascension() + 1 <= chapters.current().maximumOpenAscension();
        int revealedForPolicy = equipment.items() == 0 || equipment.requiresReveal()
                ? 0 : Math.max(1, equipment.revealedSlots());
        GuideFreeplayPolicy.Recommendation recommendation = GuideFreeplayPolicy.recommend(
                new GuideFreeplayPolicy.PlayerState(
                        canAscendNow,
                        profile.skillPoints(), learned, equipment.items(),
                        revealedForPolicy,
                        dungeons.ready() > 0));
        String route = recommendation.route().name().toLowerCase(Locale.ROOT);
        Material fallback = Material.matchMaterial(recommendation.vanillaIcon());
        if (fallback == null) fallback = Material.COMPASS;
        ItemStack preview = modeled(player, item(fallback,
                t(player, "guide.freeplay.routes." + route + ".name"), NamedTextColor.GOLD,
                t(player, "guide.freeplay.routes." + route + ".reason")), recommendation.itemModel());

        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.item(preview).showDecorations(false).showTooltip(true)
                .width(56).height(56).build());
        body.add(helpSection(player, "guide.freeplay.labels.recommendation",
                t(player, "guide.freeplay.routes." + route + ".name"), NamedTextColor.GREEN));
        body.add(helpSection(player, "guide.freeplay.labels.reason",
                t(player, "guide.freeplay.routes." + route + ".reason"), NamedTextColor.WHITE));
        body.add(helpSection(player, "guide.freeplay.labels.status",
                t(player, "guide.freeplay.status",
                        "class", className(player, profile.archetype()),
                        "ascension", profile.ascension(), "level", profile.level(),
                        "energy", profile.energy(), "cap", profile.energyCap(),
                        "skills", learned, "equipment", equipment.items(),
                        "revealed", equipment.revealedSlots()), NamedTextColor.AQUA));
        body.add(helpSection(player, "guide.freeplay.labels.choice",
                t(player, "guide.freeplay.choice"), NamedTextColor.GRAY));

        List<ActionButton> actions = List.of(
                helpButton(player,
                        t(player, "guide.freeplay.actions.start", "route",
                        t(player, "guide.freeplay.routes." + route + ".name")),
                        t(player, "guide.freeplay.routes." + route + ".action-tooltip"),
                        recommendation.action(), NamedTextColor.GREEN, 160),
                helpButton(player, t(player, "guide.freeplay.actions.explain"),
                        t(player, "guide.freeplay.actions.explain-tooltip"),
                        "help:" + recommendation.helpTopic(), NamedTextColor.LIGHT_PURPLE, 160),
                helpButton(player, t(player, "guide.freeplay.actions.change"),
                        t(player, "guide.freeplay.actions.change-tooltip"),
                        GuideFreeplayMenuPolicy.openAlternativesAction(), NamedTextColor.GOLD, 160),
                helpButton(player, t(player, "guide.freeplay.actions.npcs"),
                        t(player, "guide.freeplay.actions.npcs-tooltip"),
                        "open:npcs", NamedTextColor.WHITE, 160));
        ActionButton close = ActionButton.create(
                Component.text(t(player, "guide.help-panel.actions.close"), NamedTextColor.GRAY),
                Component.text(t(player, "guide.help-panel.actions.close-tooltip")), 150, null);
        Component title = Component.text(t(player, "guide.freeplay.title"), NamedTextColor.GOLD);
        if (ResourcePackUiPolicy.customVisuals(true, player)) {
            title = HudGlyphs.withIcon(HudGlyphs.OBJECTIVE, title);
        }
        Component dialogTitle = title;
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(dialogTitle)
                        .externalTitle(Component.text(t(player, "guide.freeplay.title")))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(body)
                        .build())
                .type(DialogType.multiAction(actions).columns(2).exitAction(close).build()));
        player.closeInventory();
        player.showDialog(dialog);
    }

    private void showFreeplayAlternatives(Player player) {
        List<DialogBody> body = List.of(helpSection(player, "guide.freeplay.labels.choice",
                t(player, "guide.freeplay.alternatives.intro"), NamedTextColor.GRAY));
        List<ActionButton> actions = new ArrayList<>();
        for (GuideFreeplayMenuPolicy.Alternative alternative : GuideFreeplayMenuPolicy.alternatives()) {
            if (!GuideFreeplayMenuPolicy.isTrustedAlternativeAction(alternative.action())) continue;
            NamedTextColor color = switch (alternative) {
                case TASKS -> NamedTextColor.GOLD;
                case LEVEL -> NamedTextColor.GREEN;
                case BUILD, EQUIPMENT -> NamedTextColor.AQUA;
                case ECONOMY -> NamedTextColor.YELLOW;
            };
            actions.add(helpButton(player,
                    t(player, "guide.freeplay.actions." + alternative.id()),
                    t(player, "guide.freeplay.actions." + alternative.id() + "-tooltip"),
                    alternative.action(), color, 160));
        }
        actions.add(helpButton(player, t(player, "guide.freeplay.actions.review"),
                t(player, "guide.freeplay.actions.review-tooltip"),
                GuideFreeplayMenuPolicy.reviewJourneyAction(), NamedTextColor.WHITE, 160));
        ActionButton back = helpButton(player, t(player, "guide.freeplay.alternatives.back"),
                t(player, "guide.freeplay.alternatives.back-tooltip"),
                GuideFreeplayMenuPolicy.completedJourneyAction(), NamedTextColor.GRAY, 160);
        Component title = Component.text(t(player, "guide.freeplay.alternatives.title"), NamedTextColor.GOLD);
        if (ResourcePackUiPolicy.customVisuals(true, player)) {
            title = HudGlyphs.withIcon(HudGlyphs.OBJECTIVE, title);
        }
        Component dialogTitle = title;
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(dialogTitle)
                        .externalTitle(Component.text(t(player, "guide.freeplay.alternatives.title")))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(body)
                        .build())
                .type(DialogType.multiAction(actions).columns(2).exitAction(back).build()));
        player.closeInventory();
        player.showDialog(dialog);
    }

    private void showJourneyBriefing(Player player, JourneyStep step) {
        if (step != JourneyStep.DUNGEON) {
            pendingJourney.remove(player.getUniqueId());
            showJourneyBriefing(player, step, null);
            return;
        }
        PlayerProfile cached = profiles.cached(player.getUniqueId()).orElse(null);
        if (cached != null) {
            pendingJourney.remove(player.getUniqueId());
            showJourneyBriefing(player, step, cached);
            return;
        }
        long request = journeySequence.incrementAndGet();
        pendingJourney.put(player.getUniqueId(), request);
        player.sendActionBar(Component.text(t(player, "guide.journey-brief.dungeon-loading"),
                NamedTextColor.AQUA));
        profiles.get(player.getUniqueId()).whenComplete((profile, error) -> sync(() -> {
            if (!pendingJourney.remove(player.getUniqueId(), request) || !ready(player)) return;
            if (error != null || profile == null) {
                player.sendMessage(Component.text(t(player, "guide.journey-brief.dungeon-load-error"),
                        NamedTextColor.RED));
                return;
            }
            showJourneyBriefing(player, step, profile);
        }));
    }

    private void showJourneyBriefing(Player player, JourneyStep step, PlayerProfile dungeonProfile) {
        String base = "guide.onboarding.steps." + step.id() + ".";
        String primaryAction = GuideJourneyPolicy.primaryAction(step);
        DungeonBriefing dungeon = step == JourneyStep.DUNGEON
                ? dungeonBriefing(java.util.Objects.requireNonNull(dungeonProfile, "dungeonProfile")) : null;
        int position = Math.max(1, GuideTracker.STEPS.indexOf(step) + 1);
        List<DialogBody> body = new ArrayList<>();
        ItemStack preview = journeyItem(player, step, tracker.stepName(player, step), NamedTextColor.GOLD,
                stepHint(player, step));
        body.add(DialogBody.item(preview).showDecorations(false).showTooltip(true)
                .width(48).height(48).build());
        // The first screen is an action card, not a progress dashboard. The
        // title already says "step N/8"; putting the eight-step strip above
        // the destination previously pushed the useful instructions below
        // the fold on 720p clients and recreated the old "A/8 means what?"
        // problem in a prettier form.
        body.add(helpSection(player, "guide.journey-brief.labels.goal", stepHint(player, step),
                NamedTextColor.WHITE));
        body.add(helpSection(player, "guide.journey-brief.labels.where", t(player, base + "where"),
                NamedTextColor.AQUA));
        if (dungeon != null) {
            NamedTextColor readinessColor = switch (dungeon.route()) {
                case READY -> NamedTextColor.GREEN;
                case NEEDS_LEVEL -> NamedTextColor.YELLOW;
                case NEEDS_ENERGY -> NamedTextColor.AQUA;
            };
            body.add(helpSection(player, "guide.journey-brief.labels.requirements",
                    dungeonStatus(player, dungeon), readinessColor));
        }
        body.add(journeySteps(player, journeyInstructionLines(player, base, dungeon)));
        body.add(helpSection(player, "guide.journey-brief.labels.completion", t(player, base + "completion"),
                NamedTextColor.GREEN));

        List<ActionButton> actions = new ArrayList<>();
        String npcRole = GuideJourneyPolicy.npcRole(step).orElse(null);
        if (dungeon != null) {
            String route = dungeon.route().id();
            actions.add(helpButton(player,
                    t(player, "guide.journey-brief.dungeon-actions." + route),
                    t(player, "guide.journey-brief.dungeon-actions." + route + "-tooltip",
                            "seconds", dungeon.regeneration().seconds(),
                            "amount", dungeon.regeneration().amount()),
                    dungeon.route().action(), NamedTextColor.GREEN, 280));
            String npcName = t(player, "guide.npc-directory.roles.dungeon.name");
            actions.add(helpButton(player,
                    t(player, "guide.journey-brief.actions.visit", "npc", npcName),
                    t(player, "guide.journey-brief.actions.visit-tooltip", "npc", npcName),
                    primaryAction, NamedTextColor.GOLD, 280));
        } else if (npcRole == null) {
            actions.add(helpButton(player, "▶ " + t(player, base + "action"),
                    t(player, base + "action-tooltip"), primaryAction, NamedTextColor.GREEN, 280));
        } else {
            String npcName = t(player, "guide.npc-directory.roles." + npcRole + ".name");
            actions.add(helpButton(player,
                    t(player, "guide.journey-brief.actions.start-at", "npc", npcName),
                    t(player, "guide.journey-brief.actions.start-at-tooltip", "npc", npcName),
                    primaryAction, NamedTextColor.GREEN, 280));
        }
        if (dungeon == null) {
            GuideJourneyPolicy.systemAction(step).ifPresent(action -> actions.add(helpButton(player,
                    t(player, base + "action"), t(player, base + "action-tooltip"),
                    action, NamedTextColor.AQUA, 280)));
        }
        actions.add(helpButton(player, t(player, "guide.journey-brief.actions.more"),
                t(player, "guide.journey-brief.actions.more-tooltip"),
                "journey-details:" + step.id(), NamedTextColor.LIGHT_PURPLE, 280));
        ActionButton close = ActionButton.create(
                Component.text(t(player, "guide.help-panel.actions.close"), NamedTextColor.GRAY),
                Component.text(t(player, "guide.help-panel.actions.close-tooltip")), 150, null);
        Component title = Component.text(t(player, "guide.journey-brief.title",
                "step", tracker.stepName(player, step), "current", position,
                "total", GuideTracker.STEPS.size()), NamedTextColor.GOLD);
        if (ResourcePackUiPolicy.customVisuals(true, player)) {
            title = JourneyGlyphs.withIcon(step, title);
        }
        Component dialogTitle = title;
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(dialogTitle)
                        .externalTitle(Component.text(tracker.stepName(player, step)))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(body)
                        .build())
                .type(DialogType.multiAction(actions).columns(1).exitAction(close).build()));
        player.closeInventory();
        player.showDialog(dialog);
    }

    /** Secondary page for reasoning and optional routes; never the first screen a newcomer sees. */
    private void showJourneyDetails(Player player, JourneyStep step) {
        String base = "guide.onboarding.steps." + step.id() + ".";
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.item(journeyItem(player, step, tracker.stepName(player, step),
                        NamedTextColor.GOLD, stepHint(player, step)))
                .showDecorations(false).showTooltip(true).width(48).height(48).build());
        JourneyStep current = tracker.nextStep(player);
        int position = Math.max(1, GuideTracker.STEPS.indexOf(step) + 1);
        body.add(journeyProgress(player, step, current, tracker.completed(player, step), position));
        body.add(helpSection(player, "guide.journey-brief.labels.completion", t(player, base + "completion"),
                NamedTextColor.GREEN));
        body.add(helpSection(player, "guide.journey-brief.labels.why", t(player, base + "why"),
                NamedTextColor.YELLOW));
        body.add(helpSection(player, "guide.journey-brief.labels.unlock", t(player, base + "unlock"),
                NamedTextColor.LIGHT_PURPLE));
        EnergyRegeneration regeneration = energyRegeneration();
        List<String> rules = step == JourneyStep.DUNGEON
                ? messages.lines(player, base + JOURNEY_RULES_LEAF, Map.of(
                        "seconds", regeneration.seconds(), "amount", regeneration.amount()))
                : messages.lines(player, base + JOURNEY_RULES_LEAF);
        body.add(journeyRules(player, rules));

        List<ActionButton> actions = new ArrayList<>();
        actions.add(helpButton(player, t(player, "guide.journey-brief.actions.back-objective"),
                t(player, "guide.journey-brief.actions.back-objective-tooltip"),
                "brief:" + step.id(), NamedTextColor.GREEN));
        GuideJourneyPolicy.detailsAlternative(step).ifPresent(alternative -> actions.add(helpButton(player,
                t(player, "guide.journey-brief.actions.alternative." + alternative.id()),
                t(player, "guide.journey-brief.actions.alternative." + alternative.id() + "-tooltip"),
                alternative.action(), NamedTextColor.AQUA)));
        actions.add(helpButton(player, t(player, "guide.journey-brief.actions.all-steps"),
                t(player, "guide.journey-brief.actions.all-steps-tooltip"),
                "open:onboarding", NamedTextColor.WHITE));
        actions.add(helpButton(player, t(player, "guide.journey-brief.actions.explain"),
                t(player, "guide.journey-brief.actions.explain-tooltip"),
                "help:" + GuideJourneyPolicy.helpTopic(step), NamedTextColor.LIGHT_PURPLE));
        ActionButton close = ActionButton.create(
                Component.text(t(player, "guide.help-panel.actions.close"), NamedTextColor.GRAY),
                Component.text(t(player, "guide.help-panel.actions.close-tooltip")), 150, null);
        Component title = Component.text(t(player, "guide.journey-brief.details-title",
                "step", tracker.stepName(player, step)), NamedTextColor.GOLD);
        if (ResourcePackUiPolicy.customVisuals(true, player)) {
            title = JourneyGlyphs.withIcon(step, title);
        }
        Component dialogTitle = title;
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(dialogTitle)
                        .externalTitle(Component.text(tracker.stepName(player, step)))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(body)
                        .build())
                .type(DialogType.multiAction(actions).columns(2).exitAction(close).build()));
        player.closeInventory();
        player.showDialog(dialog);
    }

    private void openVisuals(Player player) {
        ActionMenu menu = submenu(player, "guide.submenus.visuals.title");
        VisualMode current = VisualMode.forPlayer(player);
        setVisualModeItem(menu, player, 10, Material.FIREWORK_STAR, VisualMode.FULL, current);
        setVisualModeItem(menu, player, 12, Material.GLOWSTONE_DUST, VisualMode.REDUCED, current);
        setVisualModeItem(menu, player, 14, Material.ECHO_SHARD, VisualMode.MINIMAL, current);
        setVisualModeItem(menu, player, 16, Material.INK_SAC, VisualMode.OFF, current);
        menu.set(22, item(Material.AMETHYST_CLUSTER, t(player, "guide.submenus.visuals.aura"),
                NamedTextColor.LIGHT_PURPLE, t(player, "guide.submenus.visuals.aura-lore")), "command:aura");
        menu.set(24, item(Material.ELYTRA, t(player, "guide.submenus.visuals.trail"),
                NamedTextColor.AQUA, t(player, "guide.submenus.visuals.trail-lore")), "command:trail");
        setGameplayHudItem(menu, player, 29, Material.GLOW_ITEM_FRAME, true);
        setGameplayHudItem(menu, player, 31, Material.ITEM_FRAME, false);
        menu.set(34, item(Material.WRITABLE_BOOK, t(player, "guide.submenus.visuals.note"), NamedTextColor.GRAY,
                t(player, "guide.submenus.visuals.note-lore")), null);
        player.openInventory(menu.getInventory());
    }

    private void openTravel(Player player) {
        ActionMenu menu = submenu(player, "guide.submenus.travel.title");
        menu.set(11, item(Material.LODESTONE, t(player, "guide.submenus.travel.hub"), NamedTextColor.GOLD,
                t(player, "guide.submenus.travel.hub-lore")), "command:hub");
        menu.set(13, item(Material.COMPASS, t(player, "guide.submenus.travel.wild"), NamedTextColor.GREEN,
                t(player, "guide.submenus.travel.wild-lore")), "command:wild");
        menu.set(15, item(Material.RED_BED, t(player, "guide.submenus.travel.set-home"), NamedTextColor.YELLOW,
                t(player, "guide.submenus.travel.set-home-lore")), "command:sethome");
        menu.set(22, item(Material.RECOVERY_COMPASS, t(player, "guide.submenus.travel.home"), NamedTextColor.AQUA,
                t(player, "guide.submenus.travel.home-lore")), "command:home");
        menu.set(31, item(Material.WRITABLE_BOOK, t(player, "guide.submenus.travel.rules"), NamedTextColor.WHITE,
                t(player, "guide.submenus.travel.rules-lore")), "help:start");
        player.openInventory(menu.getInventory());
    }

    void openSection(Player player, GuideMenuService.Section section) {
        cancelPendingViews(player);
        switch (section) {
            case MAIN -> openMain(player);
            // The classes plugin owns the live, refresh-in-place menu. Keeping
            // a second copy here previously closed the GUI after every point.
            case CLASS -> player.performCommand("class");
            case ENHANCEMENT -> openEnhancement(player);
            case DUNGEON -> openDungeons(player);
            case MARKET -> openMarket(player);
            case ECONOMY -> openEconomy(player);
            case QUESTS -> openQuests(player);
            case ONBOARDING -> openOnboarding(player);
            case CURRENT -> openCurrentObjective(player);
            case NPCS -> openNpcDirectory(player);
            case HELP -> openHelp(player);
            case VISUALS -> openVisuals(player);
        }
    }

    private void setVisualModeItem(
            ActionMenu menu, Player player, int slot, Material material, VisualMode mode, VisualMode current
    ) {
        String id = mode.name().toLowerCase(Locale.ROOT);
        menu.set(slot, item(material, t(player, "visuals.modes." + id),
                current == mode ? NamedTextColor.GREEN : NamedTextColor.AQUA,
                t(player, "guide.submenus.visuals." + id + "-lore"),
                t(player, current == mode ? "guide.submenus.visuals.selected" : "guide.submenus.visuals.select")),
                "command:visuals " + id);
    }

    private void setGameplayHudItem(
            ActionMenu menu, Player player, int slot, Material material, boolean enabled
    ) {
        cn.originalrpg.api.service.GameplayHudService service = OptionalServices.enabled(
                plugin, cn.originalrpg.api.service.GameplayHudService.class);
        Boolean current = service == null ? null : service.enabled(player).orElse(null);
        String id = enabled ? "on" : "off";
        menu.set(slot, item(material, t(player, "guide.submenus.visuals.gameplay-hud-" + id),
                current != null && current == enabled ? NamedTextColor.GREEN : NamedTextColor.AQUA,
                t(player, "guide.submenus.visuals.gameplay-hud-" + id + "-lore"),
                t(player, current != null && current == enabled ? "guide.submenus.visuals.selected"
                        : "guide.submenus.visuals.select")), "command:guidehud " + id);
    }

    private void openHelp(Player player) {
        tracker.evaluate(player);
        ActionMenu menu = submenu(player, "guide.help-home.title");
        JourneyStep next = tracker.nextStep(player);
        if (next == null) {
            menu.set(GuideMenuLayout.HELP_CURRENT, item(Material.NETHER_STAR,
                    t(player, "guide.help-home.current-complete"), NamedTextColor.GREEN,
                    t(player, "guide.help-home.current-complete-lore")),
                    GuideFreeplayMenuPolicy.completedJourneyAction());
        } else {
            menu.set(GuideMenuLayout.HELP_CURRENT, journeyItem(player, next,
                    t(player, "guide.help-home.current", "step", tracker.stepName(player, next)),
                    NamedTextColor.GOLD,
                    stepHint(player, next),
                    t(player, "guide.help-home.current-where", "where",
                            t(player, "guide.onboarding.steps." + next.id() + ".where")),
                    t(player, "guide.help-home.current-open")), "brief:" + next.id());
        }
        menu.set(GuideMenuLayout.HELP_GROWTH, modeled(player, item(Material.ENCHANTED_BOOK,
                t(player, "guide.help-home.growth"), NamedTextColor.LIGHT_PURPLE,
                lines(player, "guide.help-home.growth-lore")), "common_insight_skill_book"), "open:growth");
        menu.set(GuideMenuLayout.HELP_EQUIPMENT, modeled(player, item(Material.ANVIL,
                t(player, "guide.help-home.equipment"), NamedTextColor.AQUA,
                lines(player, "guide.help-home.equipment-lore")), "forged_chestplate"), "help:equipment");
        menu.set(GuideMenuLayout.HELP_ADVENTURE, modeled(player, item(Material.RECOVERY_COMPASS,
                t(player, "guide.help-home.adventure"), NamedTextColor.GREEN,
                lines(player, "guide.help-home.adventure-lore")), "hunt_crystal"), "help:tasks");
        menu.set(GuideMenuLayout.HELP_ECONOMY, item(Material.GOLD_INGOT,
                t(player, "guide.help-home.economy"), NamedTextColor.YELLOW,
                lines(player, "guide.help-home.economy-lore")), "open:economy");
        menu.set(GuideMenuLayout.HELP_NPCS, item(Material.VILLAGER_SPAWN_EGG,
                t(player, "guide.help-home.npcs"), NamedTextColor.GOLD,
                lines(player, "guide.help-home.npcs-lore")), "open:npcs");
        menu.set(GuideMenuLayout.HELP_ALL_TOPICS, item(Material.BOOKSHELF,
                t(player, "guide.help-home.all-topics"), NamedTextColor.WHITE,
                t(player, "guide.help-home.all-topics-lore")), "open:help-topics");
        menu.set(GuideMenuLayout.HELP_AI, item(Material.ECHO_SHARD,
                t(player, "guide.help-home.ask-ai"), NamedTextColor.AQUA,
                lines(player, "guide.help-home.ask-ai-lore")), "command:ainpc");
        player.openInventory(menu.getInventory());
    }

    private void openHelpTopics(Player player) {
        ActionMenu menu = submenu(player, "guide.submenus.help.title");
        menu.set(GuideMenuLayout.BACK, back(player), "open:help");
        int index = 0;
        for (HelpTopic topic : HelpTopic.ordered()) {
            String base = "guide.help." + topic.id() + ".";
            ItemStack icon = item(topicMaterial(topic), t(player, base + "title"), NamedTextColor.AQUA,
                    t(player, base + "summary"),
                    t(player, "guide.help-panel.menu-where", "where", t(player, base + "where")),
                    t(player, "guide.help-panel.menu-open"));
            if (topic.itemModel() != null) icon = modeled(player, icon, topic.itemModel());
            menu.set(HELP_SLOTS.get(index++), icon, "help:" + topic.id());
        }
        player.openInventory(menu.getInventory());
    }

    private void openNpcDirectory(Player player) {
        tracker.evaluate(player);
        ActionMenu menu = submenu(player, "guide.npc-directory.title");
        menu.set(GuideMenuLayout.BACK, back(player), "open:help");
        int index = 0;
        for (GuideNpcTarget target : GuideNpcTarget.ordered()) {
            String base = "guide.npc-directory.roles." + target.id() + ".";
            menu.set(NPC_SLOTS.get(index++), item(target.icon(), t(player, base + "name"),
                    NamedTextColor.AQUA,
                    t(player, base + "summary"),
                    t(player, "guide.npc-directory.visit"),
                    t(player, "guide.npc-directory.walk", "command", target.navigationCommand())),
                    target.visitAction(), "command:hubfind " + target.id());
        }
        JourneyStep next = tracker.nextStep(player);
        if (next != null && GuideJourneyPolicy.npcRole(next).isEmpty()) {
            menu.set(31, journeyItem(player, next,
                    t(player, "guide.npc-directory.current-no-npc"), NamedTextColor.GOLD,
                    t(player, "guide.npc-directory.current-no-npc-lore",
                            "step", tracker.stepName(player, next))), "brief:" + next.id());
        } else if (next == null) {
            menu.set(31, item(Material.WRITABLE_BOOK,
                    t(player, "guide.npc-directory.current-complete"), NamedTextColor.GREEN,
                    t(player, "guide.npc-directory.current-complete-lore")),
                    "command:hubfind quests");
        } else {
            String role = GuideJourneyPolicy.npcRole(next).orElseThrow();
            String npc = t(player, "guide.npc-directory.roles." + role + ".name");
            menu.set(31, item(Material.COMPASS,
                    t(player, "guide.npc-directory.current", "npc", npc), NamedTextColor.GOLD,
                    t(player, "guide.npc-directory.current-lore")),
                    "command:hubfind " + role);
        }
        menu.set(33, item(Material.MAP, t(player, "guide.npc-directory.map"),
                NamedTextColor.GREEN, t(player, "guide.npc-directory.map-lore")), "link:map");
        player.openInventory(menu.getInventory());
    }

    private void showTopic(Player player, String id) {
        HelpTopic topic = HelpTopic.find(id).orElse(null);
        if (topic == null) {
            player.sendMessage(Component.text(t(player, "guide.help.invalid"), NamedTextColor.RED));
            return;
        }
        tracker.evaluate(player);
        long request = helpSequence.incrementAndGet();
        pendingHelp.put(player.getUniqueId(), request);
        player.sendActionBar(Component.text(t(player, "guide.help-panel.loading"), NamedTextColor.AQUA));
        profiles.get(player.getUniqueId())
                .thenCombine(economy.progress(player.getUniqueId()), ProfileEconomy::new)
                .thenCombine(commerce.status(player.getUniqueId()), MenuData::new)
                .whenComplete((data, error) -> sync(() -> {
                    if (!pendingHelp.remove(player.getUniqueId(), request) || !ready(player)) return;
                    showHelpDialog(player, topic, error == null ? data : null);
                }));
    }

    private void showHelpDialog(Player player, HelpTopic topic, MenuData data) {
        String base = "guide.help." + topic.id() + ".";
        ItemStack preview = item(topicMaterial(topic), t(player, base + "title"), NamedTextColor.GOLD,
                t(player, base + "summary"));
        if (topic.itemModel() != null) preview = modeled(player, preview, topic.itemModel());

        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.item(preview).showDecorations(false).showTooltip(true)
                .width(48).height(48).build());
        body.add(helpSection(player, "guide.help-panel.labels.what", t(player, base + "summary"),
                NamedTextColor.WHITE));
        body.add(helpSection(player, "guide.help-panel.labels.status", topicStatus(player, topic, data),
                data == null ? NamedTextColor.RED : NamedTextColor.GREEN));
        body.add(helpSection(player, "guide.help-panel.labels.now", t(player, base + "primary-tooltip"),
                NamedTextColor.GREEN));
        body.add(helpSection(player, "guide.help-panel.labels.where", t(player, base + "where"),
                NamedTextColor.AQUA));

        String currentJourneyRole = GuideJourneyPolicy.npcRole(tracker.nextStep(player)).orElse(null);
        String visitAction = topic.visitAction(currentJourneyRole);
        String npcShort = topic.usesCurrentJourneyNpc() && currentJourneyRole != null
                ? t(player, "guide.npc-directory.roles." + currentJourneyRole + ".name")
                : t(player, base + "npc-short");
        List<ActionButton> actions = new ArrayList<>();
        actions.add(helpButton(player, t(player, base + "primary"), t(player, base + "primary-tooltip"),
                topic.primaryAction(), NamedTextColor.GREEN));
        actions.add(helpButton(player, t(player, base + "secondary"), t(player, base + "secondary-tooltip"),
                topic.secondaryAction(), NamedTextColor.AQUA));
        if (visitAction != null) {
            actions.add(helpButton(player,
                    t(player, "guide.help-panel.actions.visit", "npc", npcShort),
                    t(player, "guide.help-panel.actions.visit-tooltip", "npc", npcShort),
                    visitAction, NamedTextColor.GOLD));
        }
        actions.add(helpButton(player, t(player, "guide.help-panel.actions.details"),
                t(player, "guide.help-panel.actions.details-tooltip"),
                "help-details:" + topic.id(), NamedTextColor.LIGHT_PURPLE));
        ActionButton close = ActionButton.create(
                Component.text(t(player, "guide.help-panel.actions.close"), NamedTextColor.GRAY),
                Component.text(t(player, "guide.help-panel.actions.close-tooltip")), 150, null);
        Component title = Component.text(t(player, "guide.help-panel.title",
                "topic", t(player, base + "title")), NamedTextColor.GOLD);
        if (ResourcePackUiPolicy.customVisuals(true, player)) {
            title = HudGlyphs.withIcon(HudGlyphs.OBJECTIVE, title);
        }
        Component dialogTitle = title;
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(dialogTitle)
                        .externalTitle(Component.text(t(player, base + "title")))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(body)
                        .build())
                .type(DialogType.multiAction(actions).columns(2).exitAction(close).build()));
        player.closeInventory();
        player.showDialog(dialog);
    }

    /** Full preparation and edge rules, deliberately one click behind the action card. */
    private void showHelpDetailsDialog(Player player, HelpTopic topic) {
        String base = "guide.help." + topic.id() + ".";
        ItemStack preview = item(topicMaterial(topic), t(player, base + "title"), NamedTextColor.GOLD,
                t(player, base + "summary"));
        if (topic.itemModel() != null) preview = modeled(player, preview, topic.itemModel());

        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.item(preview).showDecorations(false).showTooltip(true)
                .width(48).height(48).build());
        body.add(helpSection(player, "guide.help-panel.labels.prepare", t(player, base + "preparation"),
                NamedTextColor.YELLOW));
        body.add(helpSection(player, "guide.help-panel.labels.where", t(player, base + "where"),
                NamedTextColor.AQUA));
        body.add(helpSection(player, "guide.help-panel.labels.npc", t(player, base + "npc"),
                NamedTextColor.LIGHT_PURPLE));
        body.add(helpDetails(player, messages.lines(player, helpKey(topic, HELP_LINES_LEAF))));

        String currentJourneyRole = GuideJourneyPolicy.npcRole(tracker.nextStep(player)).orElse(null);
        String visitAction = topic.visitAction(currentJourneyRole);
        String locatorAction = topic.locatorAction(currentJourneyRole);
        String npcShort = topic.usesCurrentJourneyNpc() && currentJourneyRole != null
                ? t(player, "guide.npc-directory.roles." + currentJourneyRole + ".name")
                : t(player, base + "npc-short");
        List<ActionButton> actions = new ArrayList<>();
        actions.add(helpButton(player, t(player, base + "secondary"), t(player, base + "secondary-tooltip"),
                topic.secondaryAction(), NamedTextColor.AQUA));
        if (visitAction != null) {
            actions.add(helpButton(player,
                    t(player, "guide.help-panel.actions.visit", "npc", npcShort),
                    t(player, "guide.help-panel.actions.visit-tooltip", "npc", npcShort),
                    visitAction, NamedTextColor.GOLD));
        }
        if (locatorAction != null) {
            actions.add(helpButton(player,
                    t(player, "guide.help-panel.actions.locate", "npc", npcShort),
                    t(player, "guide.help-panel.actions.locate-tooltip", "npc", npcShort),
                    locatorAction, NamedTextColor.GOLD));
        }
        actions.add(helpButton(player, t(player, "guide.help-panel.actions.hub"),
                t(player, "guide.help-panel.actions.hub-tooltip"), "command:hub", NamedTextColor.YELLOW));
        actions.add(helpButton(player, t(player, "guide.help-panel.actions.back-card"),
                t(player, "guide.help-panel.actions.back-card-tooltip"),
                "help:" + topic.id(), NamedTextColor.GREEN));
        actions.add(helpButton(player, t(player, "guide.help-panel.actions.back"),
                t(player, "guide.help-panel.actions.back-tooltip"), "open:help-topics", NamedTextColor.WHITE));
        ActionButton close = ActionButton.create(
                Component.text(t(player, "guide.help-panel.actions.close"), NamedTextColor.GRAY),
                Component.text(t(player, "guide.help-panel.actions.close-tooltip")), 150, null);
        Component title = Component.text(t(player, "guide.help-panel.details-title",
                "topic", t(player, base + "title")), NamedTextColor.GOLD);
        if (ResourcePackUiPolicy.customVisuals(true, player)) {
            title = HudGlyphs.withIcon(HudGlyphs.RARE_DROP, title);
        }
        Component dialogTitle = title;
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(dialogTitle)
                        .externalTitle(Component.text(t(player, base + "title")))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(body)
                        .build())
                .type(DialogType.multiAction(actions).columns(2).exitAction(close).build()));
        player.closeInventory();
        player.showDialog(dialog);
    }

    private DialogBody helpSection(
            Player player, String labelKey, String value, NamedTextColor valueColor
    ) {
        Component label = Component.text(t(player, labelKey), NamedTextColor.GOLD)
                .decorate(TextDecoration.BOLD);
        Character glyph = sectionGlyph(labelKey);
        if (glyph != null && ResourcePackUiPolicy.customVisuals(true, player)) {
            label = HudGlyphs.withIcon(glyph, label);
        }
        Component content = label
                .append(Component.newline())
                .append(Component.text(value, valueColor).decoration(TextDecoration.BOLD, false));
        return DialogBody.plainMessage(content, HELP_BODY_WIDTH);
    }

    private DialogBody helpDetails(Player player, List<String> lines) {
        Component content = Component.text(t(player, "guide.help-panel.labels.details"), NamedTextColor.GOLD)
                .decorate(TextDecoration.BOLD);
        if (lines.isEmpty()) {
            content = content.append(Component.newline()).append(Component.text(
                    t(player, "guide.help-panel.no-details"), NamedTextColor.GRAY)
                    .decoration(TextDecoration.BOLD, false));
        } else {
            for (String line : lines) {
                content = content.append(Component.newline())
                        .append(Component.text("• " + line, NamedTextColor.GRAY)
                                .decoration(TextDecoration.BOLD, false));
            }
        }
        return DialogBody.plainMessage(content, HELP_BODY_WIDTH);
    }

    private DialogBody journeySteps(Player player, List<String> lines) {
        Component label = Component.text(t(player, "guide.journey-brief.labels.steps"), NamedTextColor.GOLD)
                .decorate(TextDecoration.BOLD);
        if (ResourcePackUiPolicy.customVisuals(true, player)) {
            label = HudGlyphs.withIcon(HudGlyphs.SKILL, label);
        }
        Component content = label;
        if (lines.isEmpty()) {
            content = content.append(Component.newline()).append(Component.text(
                    t(player, "guide.help-panel.no-details"), NamedTextColor.GRAY)
                    .decoration(TextDecoration.BOLD, false));
        } else {
            for (int index = 0; index < lines.size(); index++) {
                content = content.append(Component.newline())
                        .append(Component.text((index + 1) + ". ", NamedTextColor.AQUA)
                                .decoration(TextDecoration.BOLD, false))
                        .append(Component.text(lines.get(index), NamedTextColor.WHITE)
                                .decoration(TextDecoration.BOLD, false));
            }
        }
        return DialogBody.plainMessage(content, HELP_BODY_WIDTH);
    }

    /**
     * One compact progress strip replaces a paragraph-only status block. The
     * eight original step textures are reused as font glyphs after the client
     * confirms the pack; vanilla symbols provide a readable fallback.
     */
    private DialogBody journeyProgress(
            Player player, JourneyStep selected, JourneyStep current, boolean complete, int position
    ) {
        Component label = Component.text(t(player, "guide.journey-brief.labels.progress"),
                NamedTextColor.GOLD).decorate(TextDecoration.BOLD);
        String stateKey = complete ? "complete" : selected == current ? "current" : "later";
        Component content = label.append(Component.newline()).append(Component.text(
                t(player, "guide.journey-brief.states." + stateKey,
                        "current", position, "total", GuideTracker.STEPS.size()),
                complete ? NamedTextColor.GREEN : selected == current
                        ? NamedTextColor.GOLD : NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false));

        content = content.append(Component.newline());
        boolean custom = ResourcePackUiPolicy.customVisuals(true, player);
        JourneyStep actualCurrent = tracker.nextStep(player);
        for (int index = 0; index < GuideTracker.STEPS.size(); index++) {
            JourneyStep step = GuideTracker.STEPS.get(index);
            JourneyUiPolicy.State state = JourneyUiPolicy.state(
                    step, tracker.completed(player, step), actualCurrent);
            NamedTextColor color = switch (state) {
                case COMPLETED -> NamedTextColor.GREEN;
                case CURRENT -> NamedTextColor.GOLD;
                case LATER -> NamedTextColor.DARK_GRAY;
            };
            Component marker = custom
                    ? JourneyGlyphs.icon(step).color(color)
                    : Component.text(switch (state) {
                        case COMPLETED -> "✓";
                        case CURRENT -> "◆";
                        case LATER -> "·";
                    }, color);
            marker = marker.hoverEvent(Component.text((index + 1) + ". " + tracker.stepName(player, step), color));
            if (index > 0) content = content.append(Component.space());
            content = content.append(marker);
        }
        content = content.append(Component.text("  " + tracker.completedCount(player)
                + "/" + GuideTracker.STEPS.size(), NamedTextColor.GRAY));
        return DialogBody.plainMessage(content, HELP_BODY_WIDTH);
    }

    private DialogBody journeyRules(Player player, List<String> lines) {
        Component label = Component.text(t(player, "guide.journey-brief.labels.rules"), NamedTextColor.GOLD)
                .decorate(TextDecoration.BOLD);
        if (ResourcePackUiPolicy.customVisuals(true, player)) {
            label = HudGlyphs.withIcon(HudGlyphs.RARE_DROP, label);
        }
        Component content = label;
        if (lines.isEmpty()) {
            content = content.append(Component.newline()).append(Component.text(
                    t(player, "guide.help-panel.no-details"), NamedTextColor.GRAY)
                    .decoration(TextDecoration.BOLD, false));
        } else {
            for (String line : lines) {
                content = content.append(Component.newline())
                        .append(Component.text("• " + line, NamedTextColor.WHITE)
                                .decoration(TextDecoration.BOLD, false));
            }
        }
        return DialogBody.plainMessage(content, HELP_BODY_WIDTH);
    }

    private ActionButton helpButton(
            Player player, String label, String tooltip, String action, NamedTextColor color
    ) {
        return helpButton(player, label, tooltip, action, color, 150);
    }

    private ActionButton helpButton(
            Player player, String label, String tooltip, String action, NamedTextColor color, int width
    ) {
        return ActionButton.builder(Component.text(label, color))
                .tooltip(Component.text(tooltip))
                .width(Math.clamp(width, 100, HELP_BODY_WIDTH))
                .action(DialogAction.customClick((response, audience) -> {
                    if (!(audience instanceof Player responder)) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (ready(responder)) execute(responder, action);
                    });
                }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build()))
                .build();
    }

    private Material topicMaterial(HelpTopic topic) {
        Material material = Material.matchMaterial(plugin.getConfig().getString(
                "help." + topic.id() + ".icon", "BOOK"));
        return material == null ? Material.BOOK : material;
    }

    private String helpKey(HelpTopic topic, String leaf) {
        return "guide.help." + topic.id() + "." + leaf;
    }

    private String topicStatus(Player player, HelpTopic topic, MenuData data) {
        if (data == null) return t(player, "guide.help-panel.status.unavailable");
        PlayerProfile profile = data.profileEconomy().profile();
        EconomyProgress progress = data.profileEconomy().economy();
        CommerceStatus commerceStatus = data.commerce();
        int effectiveLevel = profile.effectiveLevel();
        return switch (topic) {
            case START -> {
                JourneyStep next = tracker.nextStep(player);
                if (next == null) {
                    yield t(player, "guide.help-panel.status.start-complete",
                            "done", tracker.completedCount(player), "total", GuideTracker.STEPS.size());
                }
                yield t(player, "guide.help-panel.status.start-current",
                        "done", tracker.completedCount(player), "total", GuideTracker.STEPS.size(),
                        "step", tracker.stepName(player, next),
                        "where", t(player, "guide.onboarding.steps." + next.id() + ".where"));
            }
            case TASKS -> {
                DungeonReadiness readiness = dungeonReadiness(profile);
                JourneyStep next = tracker.nextStep(player);
                String nextTask = next == null
                        ? t(player, "guide.help-panel.status.tasks-free")
                        : tracker.stepName(player, next);
                yield t(player, "guide.help-panel.status.tasks",
                        "done", tracker.completedCount(player), "total", GuideTracker.STEPS.size(),
                        "next", nextTask, "ready", readiness.ready(), "dungeons", readiness.total());
            }
            case PROFILE -> {
                if (profile.level() >= ProgressionRules.levelCap(profile.ascension())) {
                    String capKey;
                    if (profile.ascension() >= ProgressionRules.MAX_ASCENSION) {
                        capKey = "guide.help-panel.status.profile-cap-terminal";
                    } else if (profile.ascension() + 1 <= chapters.current().maximumOpenAscension()) {
                        capKey = "guide.help-panel.status.profile-cap-ready";
                    } else {
                        capKey = "guide.help-panel.status.profile-cap-locked";
                    }
                    yield t(player, capKey,
                            "class", className(player, profile.archetype()), "ascension", profile.ascension(),
                            "level", profile.level(), "energy", profile.energy(), "cap", profile.energyCap(),
                            "maximum", chapters.current().maximumOpenAscension());
                }
                long needed = ProgressionRules.experienceToNextLevel(profile.ascension(), profile.level());
                yield t(player, "guide.help-panel.status.profile",
                        "class", className(player, profile.archetype()), "ascension", profile.ascension(),
                        "level", profile.level(), "experience", profile.experience(), "needed", needed,
                        "energy", profile.energy(), "cap", profile.energyCap());
            }
            case CLASSES -> profile.archetype() == Archetype.UNCHOSEN
                    ? t(player, "guide.help-panel.status.classes-unchosen",
                            "attributes", profile.unspentAttributePoints())
                    : t(player, "guide.help-panel.status.classes-chosen",
                            "class", className(player, profile.archetype()),
                            "attributes", profile.unspentAttributePoints(), "skills", profile.skillPoints());
            case EQUIPMENT -> {
                EquipmentSummary equipment = equipmentSummary(player);
                yield t(player, "guide.help-panel.status.equipment",
                        "items", equipment.items(), "revealed", equipment.revealedSlots(),
                        "held", t(player, equipment.heldCustom()
                                ? "guide.help-panel.states.held-custom" : "guide.help-panel.states.held-none"));
            }
            case MOBS -> t(player, "guide.help-panel.status.mobs",
                    "level", effectiveLevel, "minimum", Math.max(1, effectiveLevel - 3),
                    "maximum", effectiveLevel + 6,
                    "world", worldScope(player));
            case DUNGEONS -> {
                DungeonReadiness readiness = dungeonReadiness(profile);
                yield t(player, "guide.help-panel.status.dungeons",
                        "level", effectiveLevel, "energy", profile.energy(), "cap", profile.energyCap(),
                        "ready", readiness.ready(), "total", readiness.total());
            }
            case ENHANCEMENT -> {
                EquipmentSummary equipment = equipmentSummary(player);
                yield t(player, "guide.help-panel.status.enhancement",
                        "items", equipment.items(), "revealed", equipment.revealedSlots(),
                        "gold", progress.gold().toPlainString());
            }
            case SKILLS -> {
                SkillHudService skillHud = OptionalServices.enabled(plugin, SkillHudService.class);
                SkillHudSnapshot snapshot = skillHud == null
                        ? null : skillHud.snapshot(player.getUniqueId()).orElse(null);
                if (snapshot == null) {
                    yield t(player, "guide.help-panel.status.skills-loading",
                            "class", className(player, profile.archetype()), "points", profile.skillPoints());
                }
                long ready = snapshot.learnedSkills().stream()
                        .filter(skill -> skill.ready(System.currentTimeMillis())).count();
                yield t(player, "guide.help-panel.status.skills",
                        "class", className(player, profile.archetype()), "points", profile.skillPoints(),
                        "learned", snapshot.learnedSkills().size(), "ready", ready,
                        "mana", snapshot.mana(), "cap", snapshot.manaCap());
            }
            case MARKET -> {
                ItemStack held = player.getInventory().getItemInMainHand();
                String key = held.getType().isAir()
                        ? "guide.help-panel.status.market-empty" : "guide.help-panel.status.market-held";
                yield t(player, key, "gold", progress.gold().toPlainString(), "amount", held.getAmount());
            }
            case ECONOMY -> t(player, "guide.help-panel.status.economy",
                    "gold", progress.gold().toPlainString(), "merit", progress.merit(),
                    "starlight", commerceStatus.credits());
            case WORLD -> t(player, "guide.help-panel.status.world", "world", worldScope(player));
            case STORE -> t(player, "guide.help-panel.status.store",
                    "starlight", commerceStatus.credits(),
                    "member", t(player, commerceStatus.memberActive(Instant.now())
                            ? "guide.help-panel.states.member-active" : "guide.help-panel.states.member-inactive"));
            case SULFUR -> {
                SulfurSummary sulfur = sulfurSummary(player);
                yield t(player, "guide.help-panel.status.sulfur",
                        "blank", sulfur.blank(), "finished", sulfur.finished());
            }
            case CLAIMS -> t(player, "guide.help-panel.status.claims",
                    "world", worldScope(player));
            case NPCS -> {
                JourneyStep next = tracker.nextStep(player);
                if (next == null) yield t(player, "guide.help-panel.status.npcs-free");
                String role = GuideJourneyPolicy.npcRole(next).orElse(null);
                if (role == null) yield t(player, "guide.help-panel.status.npcs-combat",
                        "step", tracker.stepName(player, next));
                yield t(player, "guide.help-panel.status.npcs-current",
                        "step", tracker.stepName(player, next),
                        "npc", t(player, "guide.help-panel.npc-names." + role));
            }
            case COMMUNITY -> t(player, "guide.help-panel.status.community",
                    "level", effectiveLevel, "gold", progress.gold().toPlainString(),
                    "level-state", readiness(player, effectiveLevel >= 15),
                    "gold-state", readiness(player, progress.gold().compareTo(new BigDecimal("10000.00")) >= 0));
            case COMPANIONS -> t(player, "guide.help-panel.status.companions");
        };
    }

    private String worldScope(Player player) {
        Plugin hub = plugin.getServer().getPluginManager().getPlugin("RpgHub");
        String survivalWorld = hub == null ? "world"
                : hub.getConfig().getString("hub.wilderness.world", "world");
        boolean overworld = player.getWorld().getEnvironment() == World.Environment.NORMAL
                && player.getWorld().getName().equals(survivalWorld);
        return t(player, overworld
                ? "guide.help-panel.states.overworld" : "guide.help-panel.states.other-world");
    }

    private String readiness(Player player, boolean ready) {
        return t(player, ready ? "guide.help-panel.states.ready" : "guide.help-panel.states.not-ready");
    }

    private EquipmentSummary equipmentSummary(Player player) {
        int items = 0;
        int revealed = 0;
        boolean requiresReveal = false;
        for (ItemStack stack : player.getInventory().getContents()) {
            ItemMeta meta = stack == null ? null : stack.getItemMeta();
            if (meta == null || ITEM_ID == null
                    || !meta.getPersistentDataContainer().has(ITEM_ID, PersistentDataType.STRING)) continue;
            items += stack.getAmount();
            int visible = REVEALED_SLOTS == null ? 0 : meta.getPersistentDataContainer().getOrDefault(
                    REVEALED_SLOTS, PersistentDataType.INTEGER, 0);
            revealed += visible;
            Integer total = TOTAL_SLOTS == null ? null
                    : meta.getPersistentDataContainer().get(TOTAL_SLOTS, PersistentDataType.INTEGER);
            if (total != null && total > 0 && visible == 0) requiresReveal = true;
        }
        ItemMeta heldMeta = player.getInventory().getItemInMainHand().getItemMeta();
        boolean heldCustom = heldMeta != null && ITEM_ID != null
                && heldMeta.getPersistentDataContainer().has(ITEM_ID, PersistentDataType.STRING);
        return new EquipmentSummary(items, revealed, heldCustom, requiresReveal);
    }

    private SulfurSummary sulfurSummary(Player player) {
        int blank = 0;
        int finished = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            ItemMeta meta = stack == null ? null : stack.getItemMeta();
            if (meta == null) continue;
            if (BLANK_IMPRINT != null && meta.getPersistentDataContainer().getOrDefault(
                    BLANK_IMPRINT, PersistentDataType.BYTE, (byte) 0) == (byte) 1) blank += stack.getAmount();
            if (SULFUR_IMPRINT != null && meta.getPersistentDataContainer().getOrDefault(
                    SULFUR_IMPRINT, PersistentDataType.BYTE, (byte) 0) == (byte) 1) finished += stack.getAmount();
        }
        return new SulfurSummary(blank, finished);
    }

    private DungeonReadiness dungeonReadiness(PlayerProfile profile) {
        Plugin dungeonPlugin = Bukkit.getPluginManager().getPlugin("RpgDungeons");
        ConfigurationSection live = dungeonPlugin == null ? null
                : dungeonPlugin.getConfig().getConfigurationSection("dungeons");
        if (live != null) {
            return dungeonReadiness(profile, live, "minimum-effective-level", "energy-cost");
        }
        ConfigurationSection fallback = plugin.getConfig().getConfigurationSection("dungeons");
        return dungeonReadiness(profile, fallback, "minimum-level", "energy");
    }

    private DungeonReadiness dungeonReadiness(
            PlayerProfile profile, ConfigurationSection section, String levelKey, String energyKey
    ) {
        if (section == null) return new DungeonReadiness(0, 0);
        int ready = 0;
        int total = 0;
        for (String id : section.getKeys(false)) {
            ConfigurationSection dungeon = section.getConfigurationSection(id);
            if (dungeon == null || id.endsWith("-hidden")
                    || !dungeon.getBoolean("enabled", true)) continue;
            total++;
            if (profile.effectiveLevel() >= dungeon.getInt(levelKey, 1)
                    && profile.energy() >= dungeon.getInt(energyKey, 20)) ready++;
        }
        return new DungeonReadiness(ready, total);
    }

    private DungeonBriefing dungeonBriefing(PlayerProfile profile) {
        DungeonGate gate = firstNormalDungeonGate();
        GuideJourneyPolicy.DungeonRoute route = GuideJourneyPolicy.dungeonRoute(
                profile.effectiveLevel(), profile.energy(), gate.minimumLevel(), gate.energyCost());
        return new DungeonBriefing(gate, profile.effectiveLevel(), profile.energy(), profile.energyCap(),
                energyRegeneration(), route);
    }

    private String dungeonStatus(Player player, DungeonBriefing briefing) {
        DungeonGate gate = briefing.gate();
        return t(player, "guide.journey-brief.dungeon-status." + briefing.route().id(),
                "level", briefing.effectiveLevel(),
                "energy", briefing.energy(),
                "cap", briefing.energyCap(),
                "dungeon", t(player, "guide.dungeons." + gate.id()),
                "minimum", gate.minimumLevel(),
                "cost", gate.energyCost(),
                "missing-level", Math.max(0, gate.minimumLevel() - briefing.effectiveLevel()),
                "missing-energy", Math.max(0, gate.energyCost() - briefing.energy()),
                "seconds", briefing.regeneration().seconds(),
                "amount", briefing.regeneration().amount());
    }

    private List<String> journeyInstructionLines(
            Player player, String base, DungeonBriefing dungeon
    ) {
        if (dungeon == null || dungeon.route() == GuideJourneyPolicy.DungeonRoute.READY) {
            return messages.lines(player, base + JOURNEY_HOW_LEAF);
        }
        DungeonGate gate = dungeon.gate();
        return messages.lines(player, base + JOURNEY_HOW_LEAF + "-" + dungeon.route().id(), Map.of(
                "level", dungeon.effectiveLevel(),
                "energy", dungeon.energy(),
                "minimum", gate.minimumLevel(),
                "cost", gate.energyCost(),
                "seconds", dungeon.regeneration().seconds(),
                "amount", dungeon.regeneration().amount()));
    }

    /** Read live RpgDungeons gates first; the guide config is an offline fallback only. */
    private DungeonGate firstNormalDungeonGate() {
        Plugin dungeonPlugin = Bukkit.getPluginManager().getPlugin("RpgDungeons");
        if (dungeonPlugin != null) {
            ConfigurationSection live = dungeonPlugin.getConfig().getConfigurationSection("dungeons");
            DungeonGate gate = firstNormalDungeonGate(live,
                    "minimum-effective-level", "energy-cost");
            if (gate != null) return gate;
        }
        ConfigurationSection fallback = plugin.getConfig().getConfigurationSection("dungeons");
        DungeonGate gate = firstNormalDungeonGate(fallback, "minimum-level", "energy");
        return gate == null ? new DungeonGate("ancient-ruins", 5, 20) : gate;
    }

    private DungeonGate firstNormalDungeonGate(
            ConfigurationSection definitions, String levelKey, String energyKey
    ) {
        if (definitions == null) return null;
        DungeonGate best = null;
        for (String id : definitions.getKeys(false)) {
            ConfigurationSection dungeon = definitions.getConfigurationSection(id);
            if (dungeon == null || id.endsWith("-hidden")
                    || !dungeon.getBoolean("enabled", true)) continue;
            int minimumLevel = dungeon.getInt(levelKey, -1);
            int energyCost = dungeon.getInt(energyKey, -1);
            if (minimumLevel < 1 || energyCost < 1) continue;
            DungeonGate candidate = new DungeonGate(id, minimumLevel, energyCost);
            if (best == null || candidate.minimumLevel() < best.minimumLevel()
                    || candidate.minimumLevel() == best.minimumLevel()
                    && (candidate.energyCost() < best.energyCost()
                    || candidate.energyCost() == best.energyCost()
                    && candidate.id().compareTo(best.id()) < 0)) {
                best = candidate;
            }
        }
        return best;
    }

    private EnergyRegeneration energyRegeneration() {
        Plugin core = Bukkit.getPluginManager().getPlugin("RpgCore");
        ConfigurationSection section = core == null ? null
                : core.getConfig().getConfigurationSection("progression.energy-regen");
        long seconds = section == null ? 300L : Math.max(30L, section.getLong("interval-seconds", 300L));
        int amount = section == null ? 2 : Math.max(1, section.getInt("amount", 2));
        return new EnergyRegeneration(seconds, amount);
    }

    private void execute(Player player, String action) {
        cancelPendingViews(player);
        if (action.startsWith("help-details:")) {
            HelpTopic.find(action.substring("help-details:".length()))
                    .ifPresent(topic -> showHelpDetailsDialog(player, topic));
            return;
        }
        if (action.startsWith("journey-details:")) {
            JourneyStep.parse(action.substring("journey-details:".length()))
                    .ifPresent(step -> showJourneyDetails(player, step));
            return;
        }
        if (action.startsWith("brief:")) {
            JourneyStep.parse(action.substring("brief:".length()))
                    .ifPresent(step -> showJourneyBriefing(player, step));
            return;
        }
        if (action.equals("link:map")) {
            player.closeInventory();
            sendMapLinks(player);
            return;
        }
        if (action.startsWith("command:")) {
            player.closeInventory();
            String command = action.substring("command:".length());
            Bukkit.getScheduler().runTask(plugin, () -> player.performCommand(command));
            return;
        }
        if (action.startsWith("help:")) {
            showTopic(player, action.substring("help:".length()));
            return;
        }
        switch (action) {
            case "open:main" -> openMain(player);
            case "open:growth" -> openGrowth(player);
            case "open:adventure" -> openAdventure(player);
            case "open:social" -> openSocial(player);
            case "open:class" -> {
                player.closeInventory();
                Bukkit.getScheduler().runTask(plugin, () -> player.performCommand("class"));
            }
            case "open:enhancement" -> openEnhancement(player);
            case "open:dungeon" -> openDungeons(player);
            case "open:market" -> openMarket(player);
            case "open:economy" -> openEconomy(player);
            case "open:quests" -> openQuests(player);
            case "open:onboarding" -> openOnboarding(player);
            case "open:current" -> openCurrentObjective(player);
            case "open:freeplay-routes" -> showFreeplayAlternatives(player);
            case "open:npcs" -> openNpcDirectory(player);
            case "open:travel" -> openTravel(player);
            case "open:visuals" -> openVisuals(player);
            case "open:help" -> openHelp(player);
            case "open:help-topics" -> openHelpTopics(player);
            default -> { }
        }
    }

    private void cancelPendingViews(Player player) {
        UUID playerId = player.getUniqueId();
        pendingMain.remove(playerId);
        pendingHelp.remove(playerId);
        pendingJourney.remove(playerId);
        pendingFreeplay.remove(playerId);
    }

    private boolean ready(Player player) {
        return player != null && player.isOnline()
                && Bukkit.getPlayer(player.getUniqueId()) == player
                && authenticatedPlayers.contains(player.getUniqueId());
    }

    private void sendMapLinks(Player player) {
        String publicCandidate = plugin.getConfig().getString(
                "links.map-public-url", "https://mc.sivitahub.com/map/");
        String lanCandidate = plugin.getConfig().getString("links.map-lan-url", "");
        List<String> urls = TrustedLinkPolicy.uniqueHttpUrls(publicCandidate, lanCandidate);
        if (urls.isEmpty()) {
            player.sendMessage(Component.text(t(player, "guide.npc-directory.map-unavailable"),
                    NamedTextColor.RED));
            return;
        }
        String publicUrl = TrustedLinkPolicy.httpUrl(publicCandidate).orElse(null);
        String lanUrl = TrustedLinkPolicy.httpUrl(lanCandidate).orElse(null);
        player.sendMessage(Component.text(t(player, "guide.npc-directory.map-links-title"),
                NamedTextColor.GREEN));
        for (String url : urls) {
            boolean publicLink = url.equals(publicUrl);
            String labelKey = publicLink || lanUrl == null
                    ? "guide.npc-directory.map-link-public" : "guide.npc-directory.map-link-lan";
            Component link = Component.text("[" + t(player, labelKey) + "]", NamedTextColor.AQUA)
                    .decorate(TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.openUrl(url))
                    .hoverEvent(Component.text(t(player, "guide.npc-directory.map-link-hover"),
                            NamedTextColor.GRAY));
            player.sendMessage(link);
        }
    }

    private ActionMenu submenu(Player player, String titleKey) {
        ActionMenu menu = new ActionMenu(GuideMenuLayout.SIZE, t(player, titleKey));
        menu.set(GuideMenuLayout.BACK, back(player), "open:main");
        return menu;
    }

    private ItemStack back(Player player) {
        return item(Material.ARROW, t(player, "common.back"), NamedTextColor.YELLOW,
                t(player, "common.click-back"));
    }

    private ItemStack item(Material material, String name, NamedTextColor color, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        Arrays.stream(loreLines).filter(line -> line != null && !line.isBlank())
                .flatMap(line -> MenuLoreLayout.wrap(line).stream())
                .forEach(line -> lore.add(Component.text(line, NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack modeled(Player player, ItemStack item, String model) {
        // A menu may open while the required pack is still applying. Custom
        // models are therefore a per-player enhancement, never a global bet.
        if (!ResourcePackUiPolicy.customVisuals(
                plugin.getConfig().getBoolean("menu.use-custom-item-models", false), player)) return item;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setItemModel(new org.bukkit.NamespacedKey("originalrpg", model));
        item.setItemMeta(meta);
        return item;
    }

    private Material stepIcon(JourneyStep step) {
        return switch (step) {
            case HUB -> Material.COMPASS;
            case CLASS -> Material.NETHER_STAR;
            case ATTRIBUTE -> Material.AMETHYST_SHARD;
            case EXPLORE -> Material.SPYGLASS;
            case HUNT -> Material.IRON_SWORD;
            case FORGE -> Material.ANVIL;
            case REVEAL -> Material.EXPERIENCE_BOTTLE;
            case DUNGEON -> Material.TRIAL_KEY;
        };
    }

    private ItemStack journeyItem(
            Player player, JourneyStep step, String name, NamedTextColor color, String... loreLines
    ) {
        return modeled(player, item(stepIcon(step), name, color, loreLines),
                GuideJourneyPolicy.itemModel(step));
    }

    private Character sectionGlyph(String labelKey) {
        return switch (labelKey) {
            case "guide.journey-brief.labels.goal", "guide.journey-brief.labels.completion",
                    "guide.help-panel.labels.what", "guide.help-panel.labels.now" -> HudGlyphs.OBJECTIVE;
            case "guide.journey-brief.labels.where", "guide.help-panel.labels.where",
                    "guide.help-panel.labels.npc" -> HudGlyphs.NPC;
            case "guide.journey-brief.labels.unlock", "guide.help-panel.labels.details" -> HudGlyphs.RARE_DROP;
            default -> null;
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("rpghelp")) return List.of();
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return java.util.stream.Stream.concat(
                        HelpTopic.ordered().stream().map(HelpTopic::id),
                        java.util.stream.Stream.of("current", "home", "menu", "npc", "all", "topics"))
                .filter(id -> id.startsWith(prefix)).sorted().toList();
    }

    private String stepHint(Player player, JourneyStep step) {
        return t(player, "guide.onboarding.steps." + step.id() + ".hint");
    }

    private String className(Player player, Archetype archetype) {
        return t(player, "classes." + archetype.name().toLowerCase(Locale.ROOT));
    }

    private String visualModeName(Player player) {
        return t(player, "visuals.modes." + VisualMode.forPlayer(player).name().toLowerCase(Locale.ROOT));
    }

    private String[] lines(Player player, String key) {
        return messages.lines(player, key).toArray(String[]::new);
    }

    private String t(Player player, String key, Object... placeholders) {
        if (placeholders.length == 0) return messages.text(player, key);
        if (placeholders.length % 2 != 0) throw new IllegalArgumentException("Placeholder pairs required");
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        for (int index = 0; index < placeholders.length; index += 2) {
            values.put(String.valueOf(placeholders[index]), placeholders[index + 1]);
        }
        return messages.text(player, key, values);
    }

    private void sync(Runnable action) {
        if (!plugin.isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (plugin.isEnabled()) action.run();
            });
        } catch (IllegalStateException ignored) {
            // Pending view generations are connection-local and are rebuilt by
            // the next explicit menu request after the plugin is enabled.
        }
    }

    private record ProfileEconomy(PlayerProfile profile, EconomyProgress economy) {
    }

    private record MenuData(ProfileEconomy profileEconomy, CommerceStatus commerce) {
    }

    private record EquipmentSummary(
            int items, int revealedSlots, boolean heldCustom, boolean requiresReveal
    ) {
    }

    private record SulfurSummary(int blank, int finished) {
    }

    private record DungeonReadiness(int ready, int total) {
    }

    private record DungeonGate(String id, int minimumLevel, int energyCost) {
    }

    private record EnergyRegeneration(long seconds, int amount) {
    }

    private record DungeonBriefing(
            DungeonGate gate,
            int effectiveLevel,
            int energy,
            int energyCap,
            EnergyRegeneration regeneration,
            GuideJourneyPolicy.DungeonRoute route
    ) {
    }
}
