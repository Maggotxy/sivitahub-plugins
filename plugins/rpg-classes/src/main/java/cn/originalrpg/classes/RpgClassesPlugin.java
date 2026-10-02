package cn.originalrpg.classes;

import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.model.CoreAttribute;
import cn.originalrpg.api.model.CosmeticMoment;
import cn.originalrpg.api.model.ChapterState;
import cn.originalrpg.api.model.EquipmentSnapshot;
import cn.originalrpg.api.service.CosmeticEffectService;
import cn.originalrpg.api.service.OptionalServices;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.ProfileService;
import cn.originalrpg.api.service.JourneyService;
import cn.originalrpg.api.service.ClassBranchService;
import cn.originalrpg.api.service.ChapterService;
import cn.originalrpg.api.model.ClassBranchResult;
import cn.originalrpg.api.model.AscensionEvidence;
import cn.originalrpg.api.model.AscensionResult;
import cn.originalrpg.api.rules.ProgressionRules;
import cn.originalrpg.api.rules.HiddenPathRules;
import cn.originalrpg.api.rules.AscensionRules;
import cn.originalrpg.api.service.AscensionService;
import cn.originalrpg.api.service.HiddenPathService;
import cn.originalrpg.api.service.EconomyService;
import cn.originalrpg.api.model.HiddenPath;
import cn.originalrpg.api.model.HiddenPathActivationResult;
import cn.originalrpg.api.model.HiddenPathEligibility;
import cn.originalrpg.api.service.EquipmentService;
import cn.originalrpg.api.ui.MenuLoreLayout;
import fr.xephi.authme.api.v3.AuthMeApi;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RpgClassesPlugin extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {
    private ProfileService profiles;
    private MessageService messages;
    private ClassBranchService branches;
    private ChapterService chapters;
    private AscensionService ascensions;
    private HiddenPathService hiddenPaths;
    private HiddenPathEffects hiddenPathEffects;
    private final Map<UUID, AuthenticatedSession> authenticatedPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, OperationLease> profileMutations = new ConcurrentHashMap<>();
    private final Map<UUID, OperationLease> ascensionMutations = new ConcurrentHashMap<>();
    private final Map<UUID, OperationLease> reopenAfterClassChoice = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        validateBranchGraph();
        validateHiddenRoutes();
        profiles = Objects.requireNonNull(getServer().getServicesManager().load(ProfileService.class), "ProfileService");
        messages = Objects.requireNonNull(getServer().getServicesManager().load(MessageService.class), "MessageService");
        branches = Objects.requireNonNull(
                getServer().getServicesManager().load(ClassBranchService.class), "ClassBranchService");
        chapters = Objects.requireNonNull(
                getServer().getServicesManager().load(ChapterService.class), "ChapterService");
        ascensions = Objects.requireNonNull(
                getServer().getServicesManager().load(AscensionService.class), "AscensionService");
        hiddenPaths = Objects.requireNonNull(
                getServer().getServicesManager().load(HiddenPathService.class), "HiddenPathService");
        EconomyService economy = Objects.requireNonNull(
                getServer().getServicesManager().load(EconomyService.class), "EconomyService");
        PluginCommand command = Objects.requireNonNull(getCommand("class"), "class command");
        command.setExecutor(this);
        command.setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);
        hiddenPathEffects = new HiddenPathEffects(this, hiddenPaths, profiles, economy, messages);
        getServer().getPluginManager().registerEvents(hiddenPathEffects, this);
        hiddenPathEffects.initialize();
    }

    @Override
    public void onDisable() {
        authenticatedPlayers.clear();
        profileMutations.clear();
        ascensionMutations.clear();
        reopenAfterClassChoice.clear();
    }

    private void validateBranchGraph() {
        var root = Objects.requireNonNull(
                getConfig().getConfigurationSection("branches"), "branches configuration");
        Map<String, Map<String, ClassBranchGraph.Node>> graph = new java.util.HashMap<>();
        for (String owner : root.getKeys(false)) {
            var ownerSection = Objects.requireNonNull(
                    root.getConfigurationSection(owner), "branch owner " + owner);
            Map<String, ClassBranchGraph.Node> nodes = new java.util.HashMap<>();
            for (String id : ownerSection.getKeys(false)) {
                int stage = ownerSection.getInt(id + ".stage", 1);
                boolean enabled = ownerSection.getBoolean(id + ".enabled", stage == 1);
                nodes.put(id, new ClassBranchGraph.Node(
                        owner, stage, enabled, Set.copyOf(ownerSection.getStringList(id + ".requires-any"))));
            }
            graph.put(owner, Map.copyOf(nodes));
        }
        ClassBranchGraph.validate(Map.copyOf(graph));
    }

    /**
     * Hidden routes are only reachable when every gate they name already exists
     * at the route's own ascension. The route facts live in three plugins, so
     * this reads them off the live configurations; a contradiction fails
     * startup instead of shipping a route no player can ever accept.
     *
     * <p>The documented build stays untouched: the check does not weaken the
     * required skill, it refuses a route whose prerequisite opens later than
     * the route itself.
     */
    private void validateHiddenRoutes() {
        Plugin skillsPlugin = getServer().getPluginManager().getPlugin("RpgSkills");
        Plugin dungeonsPlugin = getServer().getPluginManager().getPlugin("RpgDungeons");
        if (!(skillsPlugin instanceof JavaPlugin skills) || !(dungeonsPlugin instanceof JavaPlugin dungeons)) {
            getLogger().warning("Hidden-route reachability check skipped: RpgSkills/RpgDungeons are unavailable");
            return;
        }
        ConfigurationSection skillRoot = skills.getConfig().getConfigurationSection("skills");
        ConfigurationSection bookRoot = skills.getConfig().getConfigurationSection("skill-books");
        ConfigurationSection dungeonRoot = dungeons.getConfig().getConfigurationSection("dungeons");
        if (skillRoot == null || bookRoot == null || dungeonRoot == null) {
            throw new IllegalStateException("Hidden-route reachability check needs skills, skill-books and dungeons");
        }
        java.util.Map<HiddenPath, java.util.Set<String>> unattainable =
                HiddenRouteReachabilityGate.unattainableRoutes(skillRoot, bookRoot, dungeonRoot);
        if (!unattainable.isEmpty()) {
            java.util.Map.Entry<HiddenPath, java.util.Set<String>> first = unattainable.entrySet().iterator().next();
            throw new IllegalStateException(HiddenRouteReachabilityGate.describe(first.getKey(), first.getValue()));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.text(sender, "common.player-only"));
            return true;
        }
        if (!isReady(player)) return true;
        if (args.length == 0) {
            UUID sessionToken = sessionToken(player);
            if (sessionToken == null) return true;
            profiles.get(player.getUniqueId())
                    .thenCombine(branches.branches(player.getUniqueId()), ProfileBranches::new)
                    .whenComplete((data, error) -> sync(() -> {
                if (!isReady(player, sessionToken)) return;
                if (error != null) {
                    player.sendMessage(Component.text(messages.text(player, "class-command.load-error"), NamedTextColor.RED));
                    return;
                }
                loadAscensionAndOpen(player, data.profile(), data.branches(), chapters.current());
            }));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("hidden")) {
            showHiddenPaths(player);
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("hidden") && args[1].equalsIgnoreCase("talisman")) {
            toggleTalisman(player);
            return true;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("hidden") && args[1].equalsIgnoreCase("activate")) {
            HiddenPath path = HiddenPath.parse(args[2]).orElse(null);
            if (path == null) {
                player.sendMessage(Component.text(messages.text(player, "hidden-path.invalid"), NamedTextColor.RED));
                return true;
            }
            activateHiddenPath(player, path);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("ascend")) {
            ascend(player);
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("branch")) {
            chooseBranch(player, args[1].toLowerCase(Locale.ROOT));
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("attribute")) {
            CoreAttribute attribute;
            try {
                attribute = CoreAttribute.valueOf(args[1].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                player.sendMessage(Component.text(messages.text(player, "class-command.attribute-help"), NamedTextColor.YELLOW));
                return true;
            }
            allocateAttribute(player, attribute, null, -1);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("starter")) {
            prepareStarterBuild(player);
            return true;
        }
        if (args.length != 2 || !args[0].equalsIgnoreCase("choose")) {
            return false;
        }
        Archetype archetype = Archetype.parse(args[1]).filter(value -> value != Archetype.UNCHOSEN).orElse(null);
        if (archetype == null) {
            player.sendMessage(Component.text(messages.text(player, "class-command.class-help"), NamedTextColor.YELLOW));
            return true;
        }
        OperationLease lease = startOperation(profileMutations, player);
        if (lease == null) {
            UUID currentToken = sessionToken(player);
            if (currentToken != null) {
                removeLease(reopenAfterClassChoice, player.getUniqueId(), currentToken, player);
            }
            return true;
        }
        profiles.chooseArchetype(player.getUniqueId(), archetype).whenComplete((profile, error) -> sync(() -> {
            releaseOperation(profileMutations, player.getUniqueId(), lease);
            if (!isReady(player, lease.sessionToken())) return;
            if (error != null) {
                removeLease(reopenAfterClassChoice, player.getUniqueId(), lease.sessionToken(), player);
                player.sendMessage(Component.text(messages.text(player, "class-command.operation-failed"), NamedTextColor.RED));
                return;
            }
            player.sendMessage(Component.text(messages.text(player, "class-command.chosen", Map.of(
                    "class", className(player, profile.archetype()))), NamedTextColor.GREEN));
            refreshJourney(player);
            // A permanent choice closes the old inventory to prevent double
            // submission, then returns to a freshly loaded page so the first
            // hour flow can continue straight into repeatable attribute
            // allocation without asking the player to discover /class again.
            if (removeLease(reopenAfterClassChoice, player.getUniqueId(), lease.sessionToken(), player)) {
                player.performCommand("class");
            }
        }));
        return true;
    }

    private void showHiddenPaths(Player player) {
        UUID playerId = player.getUniqueId();
        UUID sessionToken = sessionToken(player);
        if (sessionToken == null) return;
        hiddenPaths.path(playerId).thenCompose(active -> {
            if (active.isPresent()) return java.util.concurrent.CompletableFuture.completedFuture(
                    new HiddenPathView(active.get().path(), null, true));
            return hiddenPaths.eligibility(playerId, HiddenPath.BLOODPACT)
                    .thenCombine(hiddenPaths.eligibility(playerId, HiddenPath.TALISMAN),
                            (bloodpact, talisman) -> new HiddenPathView(null, List.of(bloodpact, talisman), false));
        }).whenComplete((view, error) -> sync(() -> {
            if (!isReady(player, sessionToken)) return;
            if (error != null) {
                player.sendMessage(Component.text(messages.text(player, "hidden-path.load-failed"), NamedTextColor.RED));
                return;
            }
            if (view.active()) {
                player.sendMessage(Component.text(messages.text(player, "hidden-path.active", Map.of(
                        "path", hiddenPathName(player, view.path()))), NamedTextColor.LIGHT_PURPLE));
                return;
            }
            player.sendMessage(Component.text(messages.text(player, "hidden-path.title"), NamedTextColor.LIGHT_PURPLE));
            for (HiddenPathEligibility eligibility : view.eligibilities()) {
                String statusText = eligibility.missingRequirements().isEmpty()
                        ? messages.text(player, "hidden-path.ready")
                        : messages.text(player, "hidden-path.missing", Map.of(
                                "requirements", eligibility.missingRequirements().stream()
                                        .map(code -> messages.text(player, "hidden-path.requirement." + code))
                                        .collect(java.util.stream.Collectors.joining("、"))));
                player.sendMessage(Component.text(messages.text(player, "hidden-path.line", Map.of(
                        "path", hiddenPathName(player, eligibility.path()),
                        "holders", eligibility.holders(), "capacity", eligibility.capacity(),
                        "status", statusText)), eligibility.eligible() ? NamedTextColor.GREEN : NamedTextColor.GRAY));
            }
            player.sendMessage(Component.text(messages.text(player, "hidden-path.activate-help"), NamedTextColor.DARK_GRAY));
        }));
    }

    private void activateHiddenPath(Player player, HiddenPath path) {
        UUID playerId = player.getUniqueId();
        OperationLease lease = startOperation(profileMutations, player);
        if (lease == null) return;
        hiddenPaths.activate(playerId, path).whenComplete((result, error) -> sync(() -> {
            releaseOperation(profileMutations, playerId, lease);
            if (!isReady(player, lease.sessionToken())) return;
            if (error != null) {
                player.sendMessage(Component.text(messages.text(player, "hidden-path.load-failed"), NamedTextColor.RED));
                return;
            }
            if (result.status() == HiddenPathActivationResult.Status.ACTIVATED) {
                hiddenPathEffects.refresh(player);
                player.sendMessage(Component.text(messages.text(player, "hidden-path.activated", Map.of(
                        "path", hiddenPathName(player, path))), NamedTextColor.LIGHT_PURPLE));
                return;
            }
            if (result.status() == HiddenPathActivationResult.Status.ALREADY_ACTIVE) {
                player.sendMessage(Component.text(messages.text(player, "hidden-path.already-active"), NamedTextColor.YELLOW));
                return;
            }
            HiddenPathEligibility eligibility = result.eligibility().orElse(null);
            if (eligibility != null) {
                String requirements = eligibility.missingRequirements().stream()
                        .map(requirement -> messages.text(player, "hidden-path.requirement." + requirement))
                        .collect(java.util.stream.Collectors.joining("、"));
                player.sendMessage(Component.text(messages.text(player, "hidden-path.not-ready", Map.of(
                        "requirements", requirements)), NamedTextColor.RED));
            } else {
                player.sendMessage(Component.text(messages.text(player, "hidden-path.invalid"), NamedTextColor.RED));
            }
        }));
    }

    private void toggleTalisman(Player player) {
        HiddenPathEffects.ToggleResult result = hiddenPathEffects.toggleTalisman(player);
        String key = switch (result) {
            case ENABLED -> "hidden-path.talisman-enabled";
            case DISABLED -> "hidden-path.talisman-disabled";
            case NOT_TALISMAN -> "hidden-path.talisman-unavailable";
        };
        player.sendMessage(Component.text(messages.text(player, key),
                result == HiddenPathEffects.ToggleResult.ENABLED ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.YELLOW));
    }

    private String hiddenPathName(Player player, HiddenPath path) {
        return messages.text(player, "hidden-path.name." + path.name().toLowerCase(Locale.ROOT));
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ClassMenu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!isReady(player)) {
            player.closeInventory();
            return;
        }
        String action = menu.actionAt(event.getRawSlot());
        if (action == null) return;
        if (ClassMenuPolicy.keepsMenuOpen(action)) {
            try {
                CoreAttribute attribute = CoreAttribute.valueOf(
                        action.substring("attribute ".length()).toUpperCase(Locale.ROOT));
                allocateAttribute(player, attribute, menu, event.getRawSlot());
            } catch (IllegalArgumentException exception) {
                player.sendActionBar(Component.text(
                        messages.text(player, "class-command.operation-failed"), NamedTextColor.RED));
            }
            return;
        }
        if (action.startsWith("command:")) {
            player.closeInventory();
            String target = action.substring("command:".length());
            UUID sessionToken = sessionToken(player);
            Bukkit.getScheduler().runTask(this, () -> {
                if (isReady(player, sessionToken)) player.performCommand(target);
            });
            return;
        }
        UUID sessionToken = sessionToken(player);
        if (sessionToken == null) return;
        if (action.startsWith("choose ")) {
            reopenAfterClassChoice.put(player.getUniqueId(), new OperationLease(player, sessionToken));
        }
        player.closeInventory();
        Bukkit.getScheduler().runTask(this, () -> {
            if (isReady(player, sessionToken)) player.performCommand("class " + action);
        });
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ClassMenu) event.setCancelled(true);
    }

    @EventHandler
    public void onAuthenticated(AuthenticatedPlayerReadyEvent event) {
        Player player = event.player();
        UUID playerId = player.getUniqueId();
        AuthenticatedSession previous = authenticatedPlayers.put(playerId,
                new AuthenticatedSession(player, UUID.randomUUID()));
        if (previous == null) return;
        // Durable mutations keep their exact lease until their callback
        // settles. This prevents a fresh authentication generation from
        // submitting a second write while the previous transaction is still
        // authoritative; the old callback can only remove its own lease.
        removeLease(reopenAfterClassChoice, playerId, previous.token(), previous.player());
    }

    @EventHandler
    public void onAuthenticationRevoked(AuthenticatedPlayerRevokedEvent event) {
        clearSession(event.player());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clearSession(event.getPlayer());
    }

    private void clearSession(Player player) {
        UUID playerId = player.getUniqueId();
        AuthenticatedSession session = authenticatedPlayers.get(playerId);
        if (session == null || session.player() != player
                || !authenticatedPlayers.remove(playerId, session)) return;
        // Submitted database mutations continue to their authoritative result;
        // only the connection-local reopen intent and visible menu are revoked.
        removeLease(reopenAfterClassChoice, playerId, session.token(), player);
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof ClassMenu) {
            player.closeInventory();
        }
    }

    private void ascend(Player player) {
        UUID playerId = player.getUniqueId();
        OperationLease lease = startOperation(ascensionMutations, player);
        if (lease == null) return;
        profiles.get(player.getUniqueId()).whenComplete((current, loadError) -> sync(() -> {
            if (!isReady(player, lease.sessionToken())) {
                releaseOperation(ascensionMutations, playerId, lease);
                return;
            }
            if (loadError != null) {
                releaseOperation(ascensionMutations, playerId, lease);
                player.sendMessage(Component.text(messages.text(player, "class-command.load-error"), NamedTextColor.RED));
                return;
            }
            if (current.ascension() >= ProgressionRules.MAX_ASCENSION) {
                releaseOperation(ascensionMutations, playerId, lease);
                player.sendMessage(Component.text(messages.text(
                        player, "class-command.ascend-status.maximum_reached"), NamedTextColor.YELLOW));
                return;
            }
            AscensionEvidence evidence;
            try {
                evidence = evidence(player, current.ascension() + 1);
            } catch (IllegalStateException exception) {
                releaseOperation(ascensionMutations, playerId, lease);
                getLogger().warning("Ascension evidence is unavailable for " + playerId + ": "
                        + exception.getMessage());
                player.sendMessage(Component.text(
                        messages.text(player, "class-command.ascend-status.data_unavailable"), NamedTextColor.RED));
                return;
            }
            ascensions.ascend(playerId, evidence).whenComplete((result, error) -> sync(() -> {
                releaseOperation(ascensionMutations, playerId, lease);
                if (!isReady(player, lease.sessionToken())) return;
                if (error != null) {
                    player.sendMessage(Component.text(
                            messages.text(player, "class-command.operation-failed"), NamedTextColor.RED));
                    return;
                }
                if (result.status() != AscensionResult.Status.SUCCESS) {
                    player.sendMessage(Component.text(ascensionStatus(player, result), NamedTextColor.YELLOW));
                    player.performCommand("class");
                    return;
                }
                var profile = result.profile();
                player.sendMessage(Component.text(messages.text(player, "class-command.ascended", Map.of(
                        "ascension", profile.ascension(), "level", profile.level())), NamedTextColor.GOLD));
                CosmeticEffectService cosmetics = OptionalServices.enabled(this, CosmeticEffectService.class);
                if (cosmetics != null) cosmetics.playMoment(player, CosmeticMoment.ASCENSION);
            }));
        }));
    }

    private void loadAscensionAndOpen(
            Player player, cn.originalrpg.api.model.PlayerProfile profile,
            Map<Integer, String> selected, ChapterState chapter
    ) {
        if (!isReady(player)) return;
        UUID sessionToken = sessionToken(player);
        if (sessionToken == null) return;
        if (profile.archetype() == Archetype.UNCHOSEN
                || profile.ascension() >= ProgressionRules.MAX_ASCENSION) {
            openMenu(player, profile, selected, chapter, null);
            return;
        }
        AscensionEvidence evidence;
        try {
            evidence = evidence(player, profile.ascension() + 1);
        } catch (IllegalStateException exception) {
            getLogger().warning("Ascension evidence is unavailable for " + player.getUniqueId() + ": "
                    + exception.getMessage());
            player.sendMessage(Component.text(
                    messages.text(player, "class-command.ascend-status.data_unavailable"), NamedTextColor.RED));
            openMenu(player, profile, selected, chapter, null);
            return;
        }
        ascensions.eligibility(player.getUniqueId(), evidence).whenComplete((eligibility, error) -> sync(() -> {
            if (!isReady(player, sessionToken)) return;
            if (error != null) {
                player.sendMessage(Component.text(
                        messages.text(player, "class-command.load-error"), NamedTextColor.RED));
                openMenu(player, profile, selected, chapter, null);
                return;
            }
            openMenu(player, eligibility.profile(), selected, chapters.current(), eligibility);
        }));
    }

    private void openMenu(
            Player player, cn.originalrpg.api.model.PlayerProfile profile,
            Map<Integer, String> selected, ChapterState chapter, AscensionResult eligibility
    ) {
        ClassMenu menu = new ClassMenu(messages.text(player, "class-command.gui-title"));
        renderMenu(player, menu, profile, selected, chapter, eligibility);
        player.openInventory(menu.getInventory());
    }

    private void renderMenu(
            Player player, ClassMenu menu, cn.originalrpg.api.model.PlayerProfile profile,
            Map<Integer, String> selected, ChapterState chapter, AscensionResult eligibility
    ) {
        menu.clear();
        menu.selectedBranches(selected);
        menu.ascension(eligibility);
        menu.set(4, item(Material.NETHER_STAR, messages.text(player, "class-command.gui-current", Map.of(
                        "class", className(player, profile.archetype()), "ascension", profile.ascension(),
                        "level", profile.level())), NamedTextColor.GOLD,
                messages.text(player, "class-command.gui-points", Map.of(
                        "attributes", profile.unspentAttributePoints(), "skills", profile.skillPoints())),
                messages.text(player, "class-command.gui-chapter", Map.of(
                        "chapter", chapterName(player, chapter),
                        "maximum", chapter.maximumOpenAscension()))), null);

        int classSlot = 10;
        for (Archetype archetype : Archetype.values()) {
            if (archetype == Archetype.UNCHOSEN) continue;
            boolean current = profile.archetype() == archetype;
            boolean selectable = profile.archetype() == Archetype.UNCHOSEN;
            List<String> classLore = new ArrayList<>(messages.lines(player,
                    "class-command.gui-class-lines." + archetype.name().toLowerCase(Locale.ROOT)));
            classLore.add(messages.text(player, current ? "class-command.gui-selected"
                    : selectable ? "class-command.gui-select" : "class-command.gui-other-locked"));
            menu.set(classSlot++, item(classIcon(archetype), className(player, archetype), classColor(archetype),
                    classLore.toArray(String[]::new)),
                    selectable ? "choose " + archetype.name().toLowerCase(Locale.ROOT) : null);
        }

        if (profile.archetype() == Archetype.UNCHOSEN) {
            menu.set(22, item(Material.KNOWLEDGE_BOOK,
                    messages.text(player, "class-command.gui-starter-unselected"), NamedTextColor.YELLOW,
                    messages.text(player, "class-command.gui-starter-unselected-lore")), null);
        } else {
            String archetypeId = profile.archetype().name().toLowerCase(Locale.ROOT);
            String starter = ClassMenuPolicy.starterSkill(profile.archetype());
            boolean starterReady = ClassMenuPolicy.starterReady(profile);
            menu.set(22, item(Material.KNOWLEDGE_BOOK,
                    messages.text(player, "class-command.gui-starter-title"), NamedTextColor.LIGHT_PURPLE,
                    messages.text(player, "class-command.gui-starter-skill", Map.of(
                            "skill", messages.text(player, "skills.names." + starter))),
                    messages.text(player, "class-command.gui-starter-allocations." + archetypeId),
                    messages.text(player, starterReady
                            ? "class-command.gui-starter-click" : "class-command.gui-starter-fix")),
                    starterReady ? "command:skills" : "starter");
        }
        if (ClassMenuPolicy.showsHiddenPath(profile.archetype())) {
            menu.set(ClassMenuPolicy.HIDDEN_PATH_SLOT, item(Material.ECHO_SHARD,
                    localMenuText(player, "hidden-path.name"), NamedTextColor.DARK_PURPLE,
                    localMenuText(player, "hidden-path.summary"),
                    localMenuText(player, "hidden-path.rules"),
                    localMenuText(player, "hidden-path.open")), "command:class hidden");
        }

        int attributeSlot = 28;
        for (CoreAttribute attribute : CoreAttribute.values()) {
            boolean allocatable = profile.unspentAttributePoints() > 0
                    && ClassMenuPolicy.mayAllocateStarter(profile, attribute);
            menu.set(attributeSlot++, item(attributeIcon(attribute), attributeName(player, attribute), NamedTextColor.AQUA,
                    messages.text(player, "class-command.gui-attribute-value", Map.of(
                            "value", profile.attribute(attribute))),
                    messages.text(player, "class-command.gui-attribute-effects."
                            + attribute.name().toLowerCase(Locale.ROOT)),
                    allocatable
                            ? messages.text(player, "class-command.gui-attribute-spend")
                            : profile.unspentAttributePoints() <= 0
                            ? messages.text(player, "class-command.gui-no-attribute-points")
                            : messages.text(player, "class-command.gui-starter-locked")),
                    allocatable
                            ? "attribute " + attribute.name().toLowerCase(Locale.ROOT) : null);
        }

        int currentCap = ProgressionRules.levelCap(profile.ascension());
        boolean chapterOpen = profile.ascension() + 1 <= chapter.maximumOpenAscension();
        boolean levelReady = profile.level() >= currentCap;
        List<String> ascendLore = new ArrayList<>();
        ascendLore.add(messages.text(player, "class-command.gui-ascend-stage", Map.of(
                "current", profile.ascension(), "next", Math.min(3, profile.ascension() + 1))));
        if (profile.ascension() >= ProgressionRules.MAX_ASCENSION) {
            ascendLore.add(messages.text(player, "class-command.ascend-status.maximum_reached"));
        } else {
            ascendLore.add(chapterOpen
                    ? messages.text(player, "class-command.gui-ascend-level", Map.of(
                            "level", profile.level(), "cap", currentCap))
                    : messages.text(player, "class-command.gui-ascend-chapter-locked", Map.of(
                            "chapter", chapterName(player, chapter),
                            "maximum", chapter.maximumOpenAscension())));
            if (eligibility == null) {
                ascendLore.add(messages.text(player, "class-command.gui-ascend-unavailable"));
            } else {
                var requirement = eligibility.requirement();
                if (requirement.journeyRequired()) ascendLore.add(messages.text(
                        player, "class-command.gui-ascend-journey", Map.of(
                                "state", requirementState(player, eligibility.evidence().journeyComplete()))));
                if (requirement.previousBranchRequired()) ascendLore.add(messages.text(
                        player, "class-command.gui-ascend-branch", Map.of(
                                "state", requirementState(player, eligibility.previousBranchSelected()))));
                ascendLore.add(messages.text(player, "class-command.gui-ascend-skills", Map.of(
                        "actual", eligibility.learnedSkills(), "required", requirement.learnedSkills(),
                        "state", requirementState(player,
                                eligibility.learnedSkills() >= requirement.learnedSkills()))));
                ascendLore.add(messages.text(player, "class-command.gui-ascend-dungeons", Map.of(
                        "actual", eligibility.dungeonClears(), "required", requirement.dungeonClears(),
                        "state", requirementState(player,
                                eligibility.dungeonClears() >= requirement.dungeonClears()
                                        && eligibility.distinctDungeons() >= requirement.distinctDungeons()))));
                ascendLore.add(messages.text(player, "class-command.gui-ascend-dungeon-variety", Map.of(
                        "actual", eligibility.distinctDungeons(), "required", requirement.distinctDungeons(),
                        "state", requirementState(player,
                                eligibility.distinctDungeons() >= requirement.distinctDungeons()))));
                ascendLore.add(messages.text(player, "class-command.gui-ascend-equipment", Map.of(
                        "actual", eligibility.evidence().equipmentScore(),
                        "required", requirement.equipmentScore(), "state", requirementState(player,
                                !eligibility.evidence().equipmentIds().isEmpty()
                                        && eligibility.evidence().equipmentScore() >= requirement.equipmentScore()))));
                ascendLore.add(messages.text(player, "class-command.gui-ascend-merit", Map.of(
                        "actual", eligibility.merit(), "required", requirement.meritCost(),
                        "state", requirementState(player, eligibility.merit() >= requirement.meritCost()))));
                ascendLore.add(ascensionStatus(player, eligibility));
            }
        }
        menu.set(ClassMenuPolicy.ASCEND_SLOT, item(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE,
                        messages.text(player, "class-command.gui-ascend"), NamedTextColor.GOLD,
                        ascendLore.toArray(String[]::new)),
                eligibility != null && eligibility.eligible() && levelReady ? "ascend" : null);

        menu.set(45, item(Material.COMPASS,
                messages.text(player, "class-command.gui-main-menu"), NamedTextColor.AQUA,
                messages.text(player, "class-command.gui-main-menu-lore")), "command:menu");
        menu.set(49, item(Material.ENCHANTED_BOOK,
                messages.text(player, "class-command.gui-skills"), NamedTextColor.LIGHT_PURPLE,
                messages.text(player, "class-command.gui-skills-lore")), "command:skills");
        menu.set(53, item(Material.RECOVERY_COMPASS,
                messages.text(player, "class-command.gui-journey"), NamedTextColor.GOLD,
                messages.text(player, "class-command.gui-journey-lore")), "command:rpghelp current");

        if (profile.archetype() != Archetype.UNCHOSEN) {
            var section = getConfig().getConfigurationSection(
                    "branches." + profile.archetype().name().toLowerCase(Locale.ROOT));
            if (section != null) {
                int[] stageIndexes = {0, 0, 0, 0};
                for (String id : section.getKeys(false)) {
                    int stage = Math.clamp(section.getInt(id + ".stage", 1), 1, 3);
                    int branchSlot = ClassMenuPolicy.branchSlot(stage, stageIndexes[stage]++);
                    if (branchSlot < 0) throw new IllegalStateException(
                            "Class branch layout exceeded stage capacity for " + id);
                    String chosen = selected.get(stage);
                    boolean isChosen = id.equals(chosen);
                    boolean stageTaken = chosen != null;
                    boolean enabled = section.getBoolean(id + ".enabled", stage == 1);
                    Set<String> requiredPrevious = Set.copyOf(section.getStringList(id + ".requires-any"));
                    boolean prerequisiteSatisfied = ClassMenuPolicy.prerequisiteSatisfied(
                            stage, requiredPrevious, selected.get(stage - 1));
                    boolean available = enabled && !stageTaken && profile.ascension() >= stage
                            && prerequisiteSatisfied;
                    List<String> lore = new ArrayList<>();
                    lore.addAll(messages.lines(player, "class-command.gui-branch-lines." + id));
                    lore.add(messages.text(player, "class-command.gui-branch-stage", Map.of("stage", stage)));
                    lore.add(messages.text(player, !enabled ? "class-command.gui-branch-unreleased"
                            : isChosen ? "class-command.gui-branch-selected"
                            : stageTaken ? "class-command.gui-branch-taken"
                            : !prerequisiteSatisfied ? "class-command.gui-branch-prerequisite"
                            : available ? "class-command.gui-branch-select" : "class-command.gui-branch-locked"));
                    menu.set(branchSlot, item(branchIcon(stage), branchName(player, id),
                                    isChosen ? NamedTextColor.GREEN : available ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.GRAY,
                                    lore.toArray(String[]::new)),
                            available ? "branch " + id : null);
                }
            }
        }
    }

    private void allocateAttribute(
            Player player, CoreAttribute attribute, ClassMenu sourceMenu, int sourceSlot
    ) {
        cn.originalrpg.api.model.PlayerProfile cached = profiles.cached(player.getUniqueId()).orElse(null);
        if (cached == null || !ClassMenuPolicy.mayAllocateStarter(cached, attribute)) {
            player.sendActionBar(Component.text(messages.text(player,
                    cached != null && cached.archetype() == Archetype.UNCHOSEN
                            ? "class-command.choose-before-attributes"
                            : "class-command.starter-allocation-locked"), NamedTextColor.YELLOW));
            return;
        }
        UUID playerId = player.getUniqueId();
        OperationLease lease = startOperation(profileMutations, player);
        if (lease == null) return;
        if (sourceMenu != null && sourceSlot >= 0 && isViewing(player, sourceMenu)) {
            sourceMenu.set(sourceSlot, item(Material.CLOCK,
                    messages.text(player, "class-command.gui-attribute-pending", Map.of(
                            "attribute", attributeName(player, attribute))), NamedTextColor.YELLOW,
                    messages.text(player, "class-command.gui-attribute-pending-lore")), null);
            player.updateInventory();
        }
        profiles.allocateAttribute(playerId, attribute).whenComplete((profile, error) -> sync(() -> {
            releaseOperation(profileMutations, playerId, lease);
            if (!isReady(player, lease.sessionToken())) return;
            if (error != null) {
                player.sendActionBar(Component.text(
                        messages.text(player, "class-command.operation-failed"), NamedTextColor.RED));
                if (sourceMenu != null && isViewing(player, sourceMenu)) {
                    profiles.cached(playerId).ifPresent(current -> {
                        renderMenu(player, sourceMenu, current, sourceMenu.selectedBranches(), chapters.current(),
                                sourceMenu.ascension());
                        player.updateInventory();
                    });
                }
                return;
            }
            Component success = Component.text(messages.text(player, "class-command.attribute-raised", Map.of(
                    "attribute", attributeName(player, attribute), "value", profile.attribute(attribute),
                    "remaining", profile.unspentAttributePoints())), NamedTextColor.GREEN);
            if (sourceMenu == null) player.sendMessage(success);
            else player.sendActionBar(success);
            refreshJourney(player);
            if (sourceMenu != null && isViewing(player, sourceMenu)) {
                renderMenu(player, sourceMenu, profile, sourceMenu.selectedBranches(), chapters.current(),
                        sourceMenu.ascension());
                player.updateInventory();
            }
        }));
    }

    private void prepareStarterBuild(Player player) {
        UUID playerId = player.getUniqueId();
        OperationLease lease = startOperation(profileMutations, player);
        if (lease == null) return;
        profiles.prepareStarterBuild(playerId).whenComplete((profile, error) -> sync(() -> {
            releaseOperation(profileMutations, playerId, lease);
            if (!isReady(player, lease.sessionToken())) return;
            if (error != null) {
                player.sendMessage(Component.text(
                        messages.text(player, "class-command.starter-fix-unavailable"), NamedTextColor.RED));
                return;
            }
            player.sendMessage(Component.text(
                    messages.text(player, "class-command.starter-fixed"), NamedTextColor.GREEN));
            refreshJourney(player);
            player.performCommand("class");
        }));
    }

    private boolean isViewing(Player player, ClassMenu menu) {
        return player.getOpenInventory().getTopInventory().getHolder() == menu;
    }

    private boolean isReady(Player player) {
        AuthenticatedSession session = player == null ? null : authenticatedPlayers.get(player.getUniqueId());
        return player != null && player.isOnline() && Bukkit.getPlayer(player.getUniqueId()) == player
                && AuthMeApi.getInstance().isAuthenticated(player)
                && session != null && session.player() == player
                && profiles.cached(player.getUniqueId()).isPresent();
    }

    private boolean isReady(Player player, UUID sessionToken) {
        if (sessionToken == null || !isReady(player)) return false;
        AuthenticatedSession session = authenticatedPlayers.get(player.getUniqueId());
        return session != null && session.player() == player && sessionToken.equals(session.token());
    }

    private UUID sessionToken(Player player) {
        if (!isReady(player)) return null;
        AuthenticatedSession session = authenticatedPlayers.get(player.getUniqueId());
        return session == null ? null : session.token();
    }

    private OperationLease startOperation(Map<UUID, OperationLease> operations, Player player) {
        UUID sessionToken = sessionToken(player);
        if (sessionToken == null) return null;
        OperationLease lease = new OperationLease(player, sessionToken);
        if (operations.putIfAbsent(player.getUniqueId(), lease) != null) {
            player.sendActionBar(Component.text(
                    messages.text(player, "class-command.operation-pending"), NamedTextColor.YELLOW));
            return null;
        }
        return lease;
    }

    private void releaseOperation(Map<UUID, OperationLease> operations, UUID playerId, OperationLease lease) {
        operations.remove(playerId, lease);
    }

    private boolean removeLease(
            Map<UUID, OperationLease> operations, UUID playerId, UUID sessionToken, Player player
    ) {
        OperationLease lease = operations.get(playerId);
        return lease != null && lease.player() == player && lease.sessionToken().equals(sessionToken)
                && operations.remove(playerId, lease);
    }

    private String localMenuText(Player player, String key) {
        String locale = messages.locale(player).toLowerCase(Locale.ROOT);
        String value = getConfig().getString("menu-text." + locale + "." + key);
        if (value == null) value = getConfig().getString("menu-text.zh_cn." + key);
        return value == null ? key : value;
    }

    /**
     * Builds a main-thread-only equipment proof. Inventory contents are not
     * accepted: only the two hands and worn armor count, every item must pass
     * the RpgItems signature, be bound to this player, and have a unique item
     * UUID. This prevents borrowing, market previews, and duplicated stacks
     * from satisfying an ascension gate.
     */
    private AscensionEvidence evidence(Player player, int targetAscension) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("equipment proof requested off main thread");
        EquipmentService equipment = getServer().getServicesManager().load(EquipmentService.class);
        JourneyService journey = getServer().getServicesManager().load(JourneyService.class);
        if (equipment == null || journey == null) {
            throw new IllegalStateException(equipment == null
                    ? "EquipmentService is not registered" : "JourneyService is not registered");
        }

        Map<UUID, EquipmentSnapshot> unique = new LinkedHashMap<>();
        List<ItemStack> equipped = new ArrayList<>(6);
        equipped.add(player.getInventory().getItemInMainHand());
        equipped.add(player.getInventory().getItemInOffHand());
        equipped.addAll(Arrays.asList(player.getInventory().getArmorContents()));
        for (ItemStack item : equipped) {
            if (item == null || item.getType().isAir() || !equipment.isAuthentic(item)) continue;
            equipment.inspect(item).filter(snapshot -> snapshot.boundOwner()
                            .filter(player.getUniqueId()::equals).isPresent())
                    .ifPresent(snapshot -> unique.putIfAbsent(snapshot.itemId(), snapshot));
        }
        int score = unique.values().stream().mapToInt(AscensionRules::equipmentScore).sum();
        boolean journeyComplete = journey.progress(player.getUniqueId()).complete();
        return new AscensionEvidence(targetAscension, journeyComplete, score, unique.keySet());
    }

    private String requirementState(CommandSender audience, boolean satisfied) {
        return messages.text(audience, satisfied
                ? "class-command.gui-ascend-met" : "class-command.gui-ascend-missing");
    }

    private String ascensionStatus(CommandSender audience, AscensionResult result) {
        return messages.text(audience,
                "class-command.ascend-status." + result.status().name().toLowerCase(Locale.ROOT));
    }

    /**
     * RpgGuide loads after the class plugin, so the journey service must be
     * resolved when an authoritative mutation succeeds instead of being
     * captured once during onEnable. The call is main-thread and read-only
     * with respect to class state; the guide owns its own reward transaction.
     */
    private void refreshJourney(Player player) {
        JourneyService journey = getServer().getServicesManager().load(JourneyService.class);
        if (journey != null) journey.refresh(player);
    }

    private String chapterName(Player player, ChapterState chapter) {
        return messages.text(player, "chapters." + chapter.chapterId() + ".name");
    }

    private ItemStack item(Material material, String name, NamedTextColor color, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
        meta.lore(Arrays.stream(loreLines).filter(value -> value != null && !value.isBlank())
                .flatMap(value -> MenuLoreLayout.wrap(value).stream())
                .map(value -> Component.text(value, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
                .toList());
        item.setItemMeta(meta);
        return item;
    }

    private Material classIcon(Archetype archetype) {
        return switch (archetype) {
            case HEALER -> Material.GHAST_TEAR;
            case WARRIOR -> Material.IRON_AXE;
            case ASSASSIN -> Material.IRON_SWORD;
            case MAGE -> Material.BLAZE_ROD;
            case RANGER -> Material.BOW;
            case FREEFORM -> Material.BUNDLE;
            case UNCHOSEN -> Material.BARRIER;
        };
    }

    private NamedTextColor classColor(Archetype archetype) {
        return switch (archetype) {
            case HEALER -> NamedTextColor.GREEN;
            case WARRIOR -> NamedTextColor.RED;
            case ASSASSIN -> NamedTextColor.DARK_PURPLE;
            case MAGE -> NamedTextColor.LIGHT_PURPLE;
            case RANGER -> NamedTextColor.AQUA;
            case FREEFORM, UNCHOSEN -> NamedTextColor.YELLOW;
        };
    }

    private Material attributeIcon(CoreAttribute attribute) {
        return switch (attribute) {
            case DEFENSE -> Material.SHIELD;
            case STRENGTH -> Material.IRON_AXE;
            case AGILITY -> Material.FEATHER;
            case INTELLIGENCE -> Material.BOOK;
            case STAMINA -> Material.COOKED_BEEF;
        };
    }

    private Material branchIcon(int stage) {
        return switch (stage) {
            case 1 -> Material.AMETHYST_SHARD;
            case 2 -> Material.ECHO_SHARD;
            default -> Material.NETHER_STAR;
        };
    }

    private record ProfileBranches(cn.originalrpg.api.model.PlayerProfile profile, Map<Integer, String> branches) {
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("choose", "attribute", "starter", "ascend", "branch", "hidden");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("hidden")) return List.of("activate", "talisman");
        if (args.length == 3 && args[0].equalsIgnoreCase("hidden") && args[1].equalsIgnoreCase("activate")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return Arrays.stream(HiddenPath.values()).map(value -> value.name().toLowerCase(Locale.ROOT))
                    .filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("choose")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Arrays.stream(Archetype.values())
                    .filter(value -> value != Archetype.UNCHOSEN)
                    .map(value -> value.name().toLowerCase(Locale.ROOT))
                    .filter(value -> value.startsWith(prefix))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("attribute")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Arrays.stream(CoreAttribute.values()).map(value -> value.name().toLowerCase(Locale.ROOT))
                    .filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("branch")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            Archetype archetype = sender instanceof Player player
                    ? profiles.cached(player.getUniqueId()).map(cn.originalrpg.api.model.PlayerProfile::archetype)
                    .orElse(Archetype.UNCHOSEN) : Archetype.UNCHOSEN;
            var section = getConfig().getConfigurationSection(
                    "branches." + archetype.name().toLowerCase(Locale.ROOT));
            return section == null ? List.of() : section.getKeys(false).stream()
                    .filter(value -> value.startsWith(prefix)).sorted().toList();
        }
        return List.of();
    }

    private void chooseBranch(Player player, String id) {
        OperationLease lease = startOperation(profileMutations, player);
        if (lease == null) return;
        profiles.get(player.getUniqueId()).whenComplete((profile, profileError) -> sync(() -> {
            if (!isReady(player, lease.sessionToken())) {
                releaseOperation(profileMutations, player.getUniqueId(), lease);
                return;
            }
            if (profileError != null) {
                releaseOperation(profileMutations, player.getUniqueId(), lease);
                player.sendMessage(Component.text(
                        messages.text(player, "class-command.load-error"), NamedTextColor.RED));
                return;
            }
            String path = "branches." + profile.archetype().name().toLowerCase(Locale.ROOT) + "." + id;
            var section = getConfig().getConfigurationSection(path);
            if (section == null) {
                releaseOperation(profileMutations, player.getUniqueId(), lease);
                player.sendMessage(Component.text(
                        messages.text(player, "class-command.branch-invalid"), NamedTextColor.RED));
                return;
            }
            int stage = Math.clamp(section.getInt("stage", 1), 1, 3);
            if (!section.getBoolean("enabled", stage == 1)) {
                releaseOperation(profileMutations, player.getUniqueId(), lease);
                player.sendMessage(Component.text(
                        messages.text(player, "class-command.branch-unreleased"), NamedTextColor.YELLOW));
                return;
            }
            Set<String> requiredPrevious = Set.copyOf(section.getStringList("requires-any"));
            branches.choose(player.getUniqueId(), profile.archetype(), stage, id, requiredPrevious)
                    .whenComplete((result, error) -> sync(() -> {
                releaseOperation(profileMutations, player.getUniqueId(), lease);
                if (!isReady(player, lease.sessionToken())) return;
                if (error != null) {
                    player.sendMessage(Component.text(messages.text(player, "class-command.operation-failed"), NamedTextColor.RED));
                    return;
                }
                switch (result.status()) {
                    case SUCCESS -> player.sendMessage(Component.text(messages.text(player, "class-command.branch-chosen", Map.of(
                            "stage", stage, "branch", branchName(player, id))), NamedTextColor.GREEN));
                    case ALREADY_CHOSEN -> player.sendMessage(Component.text(messages.text(player, "class-command.branch-existing", Map.of(
                            "stage", stage, "branch", branchName(player, result.branchId().orElse(id)))), NamedTextColor.YELLOW));
                    case STAGE_LOCKED -> player.sendMessage(Component.text(messages.text(player, "class-command.branch-locked", Map.of(
                            "stage", stage)), NamedTextColor.YELLOW));
                    case PREREQUISITE_MISMATCH -> player.sendMessage(Component.text(
                            messages.text(player, "class-command.branch-prerequisite"), NamedTextColor.YELLOW));
                    case CLASS_MISMATCH, INVALID -> player.sendMessage(Component.text(
                            messages.text(player, "class-command.branch-invalid"), NamedTextColor.RED));
                }
            }));
        }));
    }

    private void sync(Runnable action) {
        if (!isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(this, () -> {
                if (isEnabled()) action.run();
            });
        } catch (IllegalStateException ignored) {
            // Durable profile/class operations remain authoritative and are
            // reflected by the next authenticated menu load after shutdown.
        }
    }

    private String className(CommandSender audience, Archetype archetype) {
        return messages.text(audience, "classes." + archetype.name().toLowerCase(Locale.ROOT));
    }

    private String attributeName(CommandSender audience, CoreAttribute attribute) {
        return messages.text(audience, "attributes." + attribute.name().toLowerCase(Locale.ROOT));
    }

    private String branchName(CommandSender audience, String branch) {
        return messages.text(audience, "class-branches." + branch);
    }

    private record HiddenPathView(HiddenPath path, List<HiddenPathEligibility> eligibilities, boolean active) {
    }

    private record AuthenticatedSession(Player player, UUID token) {
    }

    private record OperationLease(Player player, UUID sessionToken) {
    }
}
