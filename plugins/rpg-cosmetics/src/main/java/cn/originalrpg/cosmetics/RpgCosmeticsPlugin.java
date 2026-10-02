package cn.originalrpg.cosmetics;

import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.model.CosmeticMoment;
import cn.originalrpg.api.model.ServerLoadState;
import cn.originalrpg.api.model.VisualMode;
import cn.originalrpg.api.service.CosmeticEffectService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.OptionalServices;
import cn.originalrpg.api.service.PerformanceService;
import com.destroystokyo.paper.event.player.PlayerElytraBoostEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class RpgCosmeticsPlugin extends JavaPlugin
        implements Listener, CommandExecutor, TabCompleter, CosmeticEffectService {
    private static final String AURA_VOUCHER_MARKER = "founder_cosmetic";
    private static final Color[] PRISMATIC = {
            Color.fromRGB(245, 88, 88), Color.fromRGB(244, 174, 65), Color.fromRGB(100, 218, 125),
            Color.fromRGB(70, 202, 235), Color.fromRGB(133, 100, 234), Color.fromRGB(232, 105, 211)
    };
    private static final Particle.DustOptions VERDANT_DUST =
            new Particle.DustOptions(Color.fromRGB(82, 218, 142), 0.85F);
    private static final Particle.DustOptions ARCANE_DUST =
            new Particle.DustOptions(Color.fromRGB(164, 86, 235), 0.85F);
    private static final Particle.DustOptions TIDE_DUST =
            new Particle.DustOptions(Color.fromRGB(70, 205, 230), 0.85F);
    private static final Particle.DustTransition[] PRISMATIC_TRANSITIONS = createPrismaticTransitions();

    private final Map<UUID, Long> combatSuppression = new HashMap<>();
    private final Set<UUID> authenticatedPlayers = ConcurrentHashMap.newKeySet();
    private final EnumMap<TrailStyle, NamespacedKey> trailUnlockedKeys = new EnumMap<>(TrailStyle.class);
    private final Deque<MomentRequest> pendingMoments = new ArrayDeque<>();
    private final Map<String, Long> momentCooldowns = new HashMap<>();
    private MessageService messages;
    private NamespacedKey selectedAuraKey;
    private NamespacedKey prismaticUnlockedKey;
    private NamespacedKey selectedTrailKey;
    private NamespacedKey selectedTrailPaletteKey;
    private NamespacedKey rewardMarkerKey;
    private NamespacedKey rewardOwnerKey;
    private int auraCursor;
    private int trailCursor;
    private long renderPass;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        messages = Objects.requireNonNull(
                getServer().getServicesManager().load(MessageService.class), "MessageService");
        selectedAuraKey = new NamespacedKey(this, "selected_aura");
        prismaticUnlockedKey = new NamespacedKey(this, "prismatic_unlocked");
        selectedTrailKey = new NamespacedKey(this, "selected_trail");
        selectedTrailPaletteKey = new NamespacedKey(this, "selected_trail_palette");
        for (TrailStyle style : TrailStyle.values()) {
            if (style.voucherRequired()) {
                trailUnlockedKeys.put(style, new NamespacedKey(this, "trail_unlocked_" + style.id()));
            }
        }
        rewardMarkerKey = Objects.requireNonNull(NamespacedKey.fromString("rpgcommerce:reward_marker"));
        rewardOwnerKey = Objects.requireNonNull(NamespacedKey.fromString("rpgcommerce:owner"));
        registerCommand("aura");
        registerCommand("trail");
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getServicesManager().register(CosmeticEffectService.class, this, this, ServicePriority.Normal);
        long interval = Math.clamp(getConfig().getLong("render.interval-ticks", 5L), 2L, 40L);
        getServer().getScheduler().runTaskTimer(this, this::render, interval, interval);
    }

    @Override
    public void onDisable() {
        pendingMoments.clear();
        momentCooldowns.clear();
        combatSuppression.clear();
        getServer().getServicesManager().unregisterAll(this);
    }

    @Override
    public void playMoment(Player player, CosmeticMoment moment) {
        if (player == null || moment == null) return;
        UUID playerId = player.getUniqueId();
        if (Bukkit.isPrimaryThread()) queueMoment(playerId, moment);
        else getServer().getScheduler().runTask(this, () -> queueMoment(playerId, moment));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.text(sender, "common.player-only"));
            return true;
        }
        return command.getName().equalsIgnoreCase("trail")
                ? handleTrailCommand(player, args) : handleAuraCommand(player, args);
    }

    private boolean handleAuraCommand(Player player, String[] args) {
        if (args.length == 0) {
            openAura(player);
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "off" -> selectAura(player, AuraType.OFF);
            case "status" -> auraStatus(player);
            case "unlock" -> unlockAura(player);
            case "select" -> {
                if (args.length != 2) return false;
                AuraType type = AuraType.parse(args[1]).orElse(null);
                if (type == null) unknown(player, "cosmetics.unknown");
                else selectAura(player, type);
            }
            default -> {
                AuraType type = AuraType.parse(action).orElse(null);
                if (type == null) return false;
                selectAura(player, type);
            }
        }
        return true;
    }

    private boolean handleTrailCommand(Player player, String[] args) {
        if (args.length == 0) {
            openTrail(player);
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "off" -> selectTrail(player, TrailStyle.OFF);
            case "status" -> trailStatus(player);
            case "unlock" -> unlockHeldTrailVoucher(player);
            case "select" -> {
                if (args.length != 2) return false;
                TrailStyle style = TrailStyle.parse(args[1]).orElse(null);
                if (style == null) unknown(player, "cosmetics.trails.unknown");
                else selectTrail(player, style);
            }
            case "palette" -> {
                if (args.length != 2) return false;
                AuraType palette = AuraType.parse(args[1]).orElse(null);
                if (palette == null || palette == AuraType.OFF) unknown(player, "cosmetics.trails.palette-unknown");
                else selectTrailPalette(player, palette);
            }
            default -> {
                TrailStyle style = TrailStyle.parse(action).orElse(null);
                if (style == null) return false;
                selectTrail(player, style);
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("trail")) {
            if (args.length == 1) return filter(List.of("select", "palette", "off", "unlock", "status"), args[0]);
            if (args.length == 2 && args[0].equalsIgnoreCase("select")) {
                return filter(List.of("wake", "wings", "comet", "off"), args[1]);
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("palette")) {
                return filter(List.of("ember", "verdant", "arcane", "tide", "prismatic"), args[1]);
            }
            return List.of();
        }
        if (args.length == 1) return filter(List.of("select", "off", "unlock", "status"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("select")) {
            return filter(List.of("ember", "verdant", "arcane", "tide", "prismatic"), args[1]);
        }
        return List.of();
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof AuraMenu menu) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;
            handleAuraMenu(player, menu.actionAt(event.getRawSlot()));
            return;
        }
        if (event.getView().getTopInventory().getHolder() instanceof TrailMenu menu) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;
            handleTrailMenu(player, menu.actionAt(event.getRawSlot()));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        Object holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof AuraMenu || holder instanceof TrailMenu) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) suppress(player);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        Player attacker = playerSource(event.getDamager());
        if (attacker != null) suppress(attacker);
    }

    @EventHandler(ignoreCancelled = true)
    public void onElytraBoost(PlayerElytraBoostEvent event) {
        if (selectedTrail(event.getPlayer()) != TrailStyle.OFF) {
            playMoment(event.getPlayer(), CosmeticMoment.ELYTRA_BOOST);
        }
    }

    @EventHandler
    public void onAuthenticated(AuthenticatedPlayerReadyEvent event) {
        authenticatedPlayers.add(event.player().getUniqueId());
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
        authenticatedPlayers.remove(playerId);
        combatSuppression.remove(playerId);
        pendingMoments.removeIf(request -> request.playerId().equals(playerId));
        momentCooldowns.keySet().removeIf(key -> key.startsWith(playerId + ":"));
    }

    private void handleAuraMenu(Player player, String action) {
        if (action == null) return;
        if (action.equals("unlock")) unlockAura(player);
        else if (action.equals("open:trail")) {
            openTrail(player);
            return;
        } else AuraType.parse(action).ifPresent(type -> selectAura(player, type));
        if (player.isOnline()) openAura(player);
    }

    private void handleTrailMenu(Player player, String action) {
        if (action == null) return;
        if (action.equals("open:aura")) {
            openAura(player);
            return;
        }
        String[] split = action.split(":", 2);
        if (split.length != 2) return;
        switch (split[0]) {
            case "style" -> TrailStyle.parse(split[1]).ifPresent(style -> selectTrail(player, style));
            case "unlock" -> TrailStyle.parse(split[1]).ifPresent(style -> unlockTrail(player, style));
            case "palette" -> AuraType.parse(split[1]).ifPresent(palette -> selectTrailPalette(player, palette));
            default -> { return; }
        }
        if (player.isOnline()) openTrail(player);
    }

    private void openAura(Player player) {
        AuraType selected = selectedAura(player);
        AuraMenu menu = new AuraMenu(Component.text(messages.text(player, "cosmetics.menu-title")));
        List<AuraType> types = List.of(
                AuraType.EMBER, AuraType.VERDANT, AuraType.ARCANE, AuraType.TIDE, AuraType.PRISMATIC);
        int[] slots = {10, 11, 12, 13, 14};
        for (int index = 0; index < types.size(); index++) {
            AuraType type = types.get(index);
            boolean unlocked = unlockedAura(player, type);
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(messages.text(player, "cosmetics.auras." + type.id() + ".lore"),
                    NamedTextColor.GRAY));
            lore.add(Component.text(messages.text(player, unlocked
                    ? selected == type ? "cosmetics.selected" : "cosmetics.select"
                    : "cosmetics.locked"), unlocked ? NamedTextColor.AQUA : NamedTextColor.RED));
            ItemStack item = menuItem(icon(type), messages.text(player,
                    "cosmetics.auras." + type.id() + ".name"),
                    selected == type ? NamedTextColor.GREEN : NamedTextColor.LIGHT_PURPLE, lore);
            menu.set(slots[index], item, unlocked ? type.id() : type == AuraType.PRISMATIC ? "unlock" : null);
        }
        menu.set(18, menuItem(icon(AuraType.OFF), messages.text(player, "cosmetics.auras.off.name"),
                selected == AuraType.OFF ? NamedTextColor.GREEN : NamedTextColor.GRAY,
                List.of(Component.text(messages.text(player, "cosmetics.auras.off.lore"), NamedTextColor.GRAY))),
                AuraType.OFF.id());
        menu.set(20, menuItem(Material.ELYTRA, messages.text(player, "cosmetics.open-trails"),
                NamedTextColor.AQUA,
                List.of(Component.text(messages.text(player, "cosmetics.open-trails-lore"), NamedTextColor.GRAY))),
                "open:trail");
        menu.set(22, menuItem(Material.WRITABLE_BOOK, messages.text(player, "cosmetics.performance-title"),
                NamedTextColor.AQUA, List.of(Component.text(messages.text(player, "cosmetics.performance-lore"),
                        NamedTextColor.GRAY))), null);
        player.openInventory(menu.getInventory());
    }

    private void openTrail(Player player) {
        TrailStyle selectedStyle = selectedTrail(player);
        AuraType selectedPalette = selectedTrailPalette(player);
        TrailMenu menu = new TrailMenu(Component.text(messages.text(player, "cosmetics.trails.menu-title")));
        List<TrailStyle> styles = List.of(TrailStyle.OFF, TrailStyle.WAKE, TrailStyle.WINGS, TrailStyle.COMET);
        int[] styleSlots = {10, 12, 14, 16};
        for (int index = 0; index < styles.size(); index++) {
            TrailStyle style = styles.get(index);
            boolean unlocked = unlockedTrail(player, style);
            List<Component> lore = List.of(
                    Component.text(messages.text(player, "cosmetics.trails.styles." + style.id() + ".lore"),
                            NamedTextColor.GRAY),
                    Component.text(messages.text(player, unlocked
                                    ? selectedStyle == style ? "cosmetics.selected" : "cosmetics.select"
                                    : "cosmetics.trails.voucher-required"),
                            unlocked ? NamedTextColor.AQUA : NamedTextColor.RED));
            menu.set(styleSlots[index], menuItem(trailIcon(style), messages.text(player,
                            "cosmetics.trails.styles." + style.id() + ".name"),
                    selectedStyle == style ? NamedTextColor.GREEN : NamedTextColor.LIGHT_PURPLE, lore),
                    unlocked ? "style:" + style.id() : "unlock:" + style.id());
        }
        List<AuraType> palettes = List.of(
                AuraType.EMBER, AuraType.VERDANT, AuraType.ARCANE, AuraType.TIDE, AuraType.PRISMATIC);
        int[] paletteSlots = {28, 29, 30, 31, 32};
        for (int index = 0; index < palettes.size(); index++) {
            AuraType palette = palettes.get(index);
            boolean unlocked = unlockedAura(player, palette);
            List<Component> lore = List.of(
                    Component.text(messages.text(player, "cosmetics.trails.palette-lore"), NamedTextColor.GRAY),
                    Component.text(messages.text(player, unlocked
                                    ? selectedPalette == palette ? "cosmetics.selected" : "cosmetics.select"
                                    : "cosmetics.trails.prismatic-required"),
                            unlocked ? NamedTextColor.AQUA : NamedTextColor.RED));
            menu.set(paletteSlots[index], menuItem(icon(palette), messages.text(player,
                            "cosmetics.auras." + palette.id() + ".name"),
                    selectedPalette == palette ? NamedTextColor.GREEN : NamedTextColor.LIGHT_PURPLE, lore),
                    unlocked ? "palette:" + palette.id() : "open:aura");
        }
        menu.set(36, menuItem(Material.AMETHYST_CLUSTER, messages.text(player, "cosmetics.open-auras"),
                NamedTextColor.AQUA,
                List.of(Component.text(messages.text(player, "cosmetics.open-auras-lore"), NamedTextColor.GRAY))),
                "open:aura");
        menu.set(40, menuItem(Material.WRITABLE_BOOK, messages.text(player, "cosmetics.performance-title"),
                NamedTextColor.AQUA,
                List.of(Component.text(messages.text(player, "cosmetics.trails.performance-lore"),
                        NamedTextColor.GRAY))), null);
        player.openInventory(menu.getInventory());
    }

    private void selectAura(Player player, AuraType type) {
        if (!unlockedAura(player, type)) {
            player.sendMessage(Component.text(messages.text(player, "cosmetics.locked-message"), NamedTextColor.RED));
            return;
        }
        player.getPersistentDataContainer().set(selectedAuraKey, PersistentDataType.STRING, type.id());
        player.saveData();
        player.sendMessage(Component.text(messages.text(player, "cosmetics.selected-message", Map.of(
                "aura", messages.text(player, "cosmetics.auras." + type.id() + ".name"))),
                type == AuraType.OFF ? NamedTextColor.GRAY : NamedTextColor.GREEN));
    }

    private void selectTrail(Player player, TrailStyle style) {
        if (!unlockedTrail(player, style)) {
            player.sendMessage(Component.text(messages.text(player, "cosmetics.trails.locked-message"), NamedTextColor.RED));
            return;
        }
        player.getPersistentDataContainer().set(selectedTrailKey, PersistentDataType.STRING, style.id());
        player.saveData();
        player.sendMessage(Component.text(messages.text(player, "cosmetics.trails.selected-message", Map.of(
                "style", messages.text(player, "cosmetics.trails.styles." + style.id() + ".name"))),
                style == TrailStyle.OFF ? NamedTextColor.GRAY : NamedTextColor.GREEN));
    }

    private void selectTrailPalette(Player player, AuraType palette) {
        if (palette == AuraType.OFF || !unlockedAura(player, palette)) {
            player.sendMessage(Component.text(messages.text(player, "cosmetics.trails.prismatic-required"),
                    NamedTextColor.RED));
            return;
        }
        player.getPersistentDataContainer().set(selectedTrailPaletteKey, PersistentDataType.STRING, palette.id());
        player.saveData();
        player.sendMessage(Component.text(messages.text(player, "cosmetics.trails.palette-selected", Map.of(
                "palette", messages.text(player, "cosmetics.auras." + palette.id() + ".name"))),
                NamedTextColor.GREEN));
    }

    private void auraStatus(Player player) {
        AuraType type = selectedAura(player);
        player.sendMessage(Component.text(messages.text(player, "cosmetics.status", Map.of(
                "aura", messages.text(player, "cosmetics.auras." + type.id() + ".name"),
                "prismatic", messages.text(player, unlockedAura(player, AuraType.PRISMATIC)
                        ? "cosmetics.unlocked" : "cosmetics.not-unlocked"))), NamedTextColor.AQUA));
    }

    private void trailStatus(Player player) {
        TrailStyle style = selectedTrail(player);
        AuraType palette = selectedTrailPalette(player);
        player.sendMessage(Component.text(messages.text(player, "cosmetics.trails.status", Map.of(
                "style", messages.text(player, "cosmetics.trails.styles." + style.id() + ".name"),
                "palette", messages.text(player, "cosmetics.auras." + palette.id() + ".name"))),
                NamedTextColor.AQUA));
    }

    private void unlockAura(Player player) {
        if (unlockedAura(player, AuraType.PRISMATIC)) {
            player.sendMessage(Component.text(messages.text(player, "cosmetics.already-unlocked"), NamedTextColor.YELLOW));
            return;
        }
        if (!consumeHeldVoucher(player, AURA_VOUCHER_MARKER)) {
            player.sendMessage(Component.text(messages.text(player, "cosmetics.voucher-required"), NamedTextColor.RED));
            return;
        }
        player.getPersistentDataContainer().set(prismaticUnlockedKey, PersistentDataType.BYTE, (byte) 1);
        player.getPersistentDataContainer().set(selectedAuraKey, PersistentDataType.STRING, AuraType.PRISMATIC.id());
        player.getPersistentDataContainer().set(selectedTrailPaletteKey,
                PersistentDataType.STRING, AuraType.PRISMATIC.id());
        player.saveData();
        player.sendMessage(Component.text(messages.text(player, "cosmetics.unlock-success"), NamedTextColor.LIGHT_PURPLE));
    }

    private void unlockHeldTrailVoucher(Player player) {
        String marker = heldVoucherMarker(player);
        TrailStyle style = TrailStyle.forVoucher(marker).orElse(null);
        if (style == null) {
            player.sendMessage(Component.text(messages.text(player, "cosmetics.trails.voucher-required"),
                    NamedTextColor.RED));
            return;
        }
        unlockTrail(player, style);
    }

    private void unlockTrail(Player player, TrailStyle style) {
        if (!style.voucherRequired()) {
            selectTrail(player, style);
            return;
        }
        if (unlockedTrail(player, style)) {
            player.sendMessage(Component.text(messages.text(player, "cosmetics.trails.already-unlocked"),
                    NamedTextColor.YELLOW));
            return;
        }
        if (!consumeHeldVoucher(player, style.voucherMarker())) {
            player.sendMessage(Component.text(messages.text(player, "cosmetics.trails.voucher-required"),
                    NamedTextColor.RED));
            return;
        }
        player.getPersistentDataContainer().set(trailUnlockedKeys.get(style), PersistentDataType.BYTE, (byte) 1);
        player.getPersistentDataContainer().set(selectedTrailKey, PersistentDataType.STRING, style.id());
        player.saveData();
        player.sendMessage(Component.text(messages.text(player, "cosmetics.trails.unlock-success", Map.of(
                "style", messages.text(player, "cosmetics.trails.styles." + style.id() + ".name"))),
                NamedTextColor.LIGHT_PURPLE));
    }

    private boolean consumeHeldVoucher(Player player, String expectedMarker) {
        ItemStack item = player.getInventory().getItemInMainHand();
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer data = meta == null ? null : meta.getPersistentDataContainer();
        String marker = data == null ? null : data.get(rewardMarkerKey, PersistentDataType.STRING);
        String owner = data == null ? null : data.get(rewardOwnerKey, PersistentDataType.STRING);
        if (!expectedMarker.equals(marker) || !player.getUniqueId().toString().equals(owner)) return false;
        if (item.getAmount() <= 1) player.getInventory().setItemInMainHand(null);
        else item.setAmount(item.getAmount() - 1);
        return true;
    }

    private String heldVoucherMarker(Player player) {
        ItemMeta meta = player.getInventory().getItemInMainHand().getItemMeta();
        if (meta == null) return null;
        PersistentDataContainer data = meta.getPersistentDataContainer();
        String owner = data.get(rewardOwnerKey, PersistentDataType.STRING);
        if (!player.getUniqueId().toString().equals(owner)) return null;
        return data.get(rewardMarkerKey, PersistentDataType.STRING);
    }

    private void render() {
        if (!getConfig().getBoolean("render.enabled", true)) return;
        PerformanceService performance = OptionalServices.enabled(this, PerformanceService.class);
        // Cosmetics are never authoritative gameplay. If the load monitor is
        // unavailable during a reload, fail closed to the zero-packet budget
        // instead of retaining its disabled class loader.
        ServerLoadState state = performance == null ? ServerLoadState.CRITICAL : performance.state();
        String load = state.name().toLowerCase(Locale.ROOT);
        int packetLimit = Math.clamp(getConfig().getInt("render.max-packets-per-pass." + load,
                state == ServerLoadState.NORMAL ? 240 : state == ServerLoadState.PRESSURE ? 64 : 0), 0, 1024);
        long now = System.currentTimeMillis();
        cleanup(now);
        if (packetLimit == 0) {
            renderPass++;
            return;
        }
        RenderBudget budget = new RenderBudget(packetLimit);
        double phase = renderPass * 0.36;
        int momentPackets = categoryPacketLimit("render.moments.max-packets-per-pass", state, 48, 16);
        renderMoments(state, budget.child(momentPackets), phase, now);
        int trailPackets = categoryPacketLimit("render.trail.max-packets-per-pass", state, 120, 32);
        if (budget.hasRemaining()) renderTrails(state, budget.child(trailPackets), phase, now);
        int auraCadence = Math.clamp(getConfig().getInt("render.aura.cadence-passes", 2), 1, 20);
        if (budget.hasRemaining() && renderPass % auraCadence == 0) {
            renderAuras(state, budget, phase, now);
        }
        renderPass++;
    }

    private void renderMoments(
            ServerLoadState state, RenderBudget budget, double phase, long now
    ) {
        String load = state.name().toLowerCase(Locale.ROOT);
        int configuredPoints = Math.clamp(getConfig().getInt("render.moments.points-per-receiver." + load,
                state == ServerLoadState.NORMAL ? 10 : 4), 0, 16);
        int maxMoments = Math.clamp(getConfig().getInt("render.moments.max-per-pass", 4), 1, 16);
        int candidates = Math.min(maxMoments, pendingMoments.size());
        for (int index = 0; index < candidates && budget.hasRemaining(); index++) {
            MomentRequest request = pendingMoments.pollFirst();
            if (request == null || request.expiresAt() <= now) continue;
            Player player = Bukkit.getPlayer(request.playerId());
            if (player == null || !player.isOnline() || !baseEligible(player)) continue;
            AuraType palette = selectedTrailPalette(player);
            AuraType.Shape shape = switch (request.moment()) {
                case ASCENSION -> AuraType.Shape.DOUBLE_HELIX;
                case ELYTRA_BOOST -> AuraType.Shape.PULSE;
                case ACTIVITY_REWARD -> AuraType.Shape.ORBIT;
            };
            Location base = player.getLocation().add(0.0, request.moment() == CosmeticMoment.ASCENSION ? 0.1 : 0.65, 0.0);
            renderGeometry(player, palette, base, shape, configuredPoints, phase, budget);
        }
    }

    private void renderTrails(
            ServerLoadState state, RenderBudget budget, double phase, long now
    ) {
        String load = state.name().toLowerCase(Locale.ROOT);
        int maxEmitters = Math.clamp(getConfig().getInt("render.trail.max-emitters-per-pass." + load,
                state == ServerLoadState.NORMAL ? 12 : 4), 0, 48);
        int configuredPoints = Math.clamp(getConfig().getInt("render.trail.points-per-receiver." + load,
                state == ServerLoadState.NORMAL ? 4 : 2), 0, 8);
        if (maxEmitters == 0 || configuredPoints == 0) return;
        List<? extends Player> emitters = Bukkit.getOnlinePlayers().stream()
                .filter(player -> eligibleTrail(player, now)).toList();
        if (emitters.isEmpty()) {
            trailCursor = 0;
            return;
        }
        int count = Math.min(maxEmitters, emitters.size());
        int attempted = 0;
        for (int offset = 0; offset < count && budget.hasRemaining(); offset++) {
            Player emitter = emitters.get(Math.floorMod(trailCursor + offset, emitters.size()));
            attempted++;
            renderTrail(emitter, selectedTrail(emitter), selectedTrailPalette(emitter),
                    configuredPoints, phase + offset * 0.13, budget);
        }
        trailCursor = Math.floorMod(trailCursor + Math.max(1, attempted), emitters.size());
    }

    private void renderAuras(
            ServerLoadState state, RenderBudget budget, double phase, long now
    ) {
        String load = state.name().toLowerCase(Locale.ROOT);
        int maxEmitters = Math.clamp(getConfig().getInt("render.aura.max-emitters-per-pass." + load,
                state == ServerLoadState.NORMAL ? 20 : 6), 0, 48);
        int configuredPoints = Math.clamp(getConfig().getInt("render.aura.points-per-receiver." + load,
                state == ServerLoadState.NORMAL ? 5 : 2), 0, 10);
        if (maxEmitters == 0 || configuredPoints == 0) return;
        List<? extends Player> emitters = Bukkit.getOnlinePlayers().stream()
                .filter(player -> eligibleAura(player, now)).toList();
        if (emitters.isEmpty()) {
            auraCursor = 0;
            return;
        }
        int count = Math.min(maxEmitters, emitters.size());
        int attempted = 0;
        for (int offset = 0; offset < count && budget.hasRemaining(); offset++) {
            Player emitter = emitters.get(Math.floorMod(auraCursor + offset, emitters.size()));
            attempted++;
            renderGeometry(emitter, selectedAura(emitter), emitter.getLocation(), selectedAura(emitter).shape(),
                    configuredPoints, phase + offset * 0.17, budget);
        }
        auraCursor = Math.floorMod(auraCursor + Math.max(1, attempted), emitters.size());
    }

    private void renderTrail(
            Player emitter, TrailStyle style, AuraType palette, int configuredPoints,
            double phase, RenderBudget budget
    ) {
        Location base = emitter.getLocation().add(0.0, 0.72, 0.0);
        Vector direction = emitter.getVelocity();
        Map<Integer, List<AuraGeometry.Offset>> pointsByBudget = new HashMap<>();
        for (Player receiver : receivers(emitter, base)) {
            int points = VisualMode.forPlayer(receiver).particles(configuredPoints);
            if (points <= 0) continue;
            List<AuraGeometry.Offset> offsets = pointsByBudget.computeIfAbsent(points,
                    amount -> TrailGeometry.points(style.shape(), amount, phase,
                            direction.getX(), direction.getY(), direction.getZ()));
            if (!spawnOffsets(receiver, palette, base, offsets, phase, budget)) return;
        }
    }

    private void renderGeometry(
            Player emitter, AuraType palette, Location base, AuraType.Shape shape,
            int configuredPoints, double phase, RenderBudget budget
    ) {
        Map<Integer, List<AuraGeometry.Offset>> pointsByBudget = new HashMap<>();
        for (Player receiver : receivers(emitter, base)) {
            int points = VisualMode.forPlayer(receiver).particles(configuredPoints);
            if (points <= 0) continue;
            List<AuraGeometry.Offset> offsets = pointsByBudget.computeIfAbsent(points,
                    amount -> AuraGeometry.points(shape, amount, phase));
            if (!spawnOffsets(receiver, palette, base, offsets, phase, budget)) return;
        }
    }

    private boolean spawnOffsets(
            Player receiver, AuraType palette, Location base, List<AuraGeometry.Offset> offsets,
            double phase, RenderBudget budget
    ) {
        for (int index = 0; index < offsets.size(); index++) {
            if (!budget.take()) return false;
            AuraGeometry.Offset point = offsets.get(index);
            spawn(receiver, palette, base.clone().add(point.x(), point.y(), point.z()), index, phase);
        }
        return true;
    }

    private List<Player> receivers(Player emitter, Location base) {
        int receiverLimit = Math.clamp(getConfig().getInt("render.max-receivers-per-emitter", 6), 1, 12);
        double receiverRadius = Math.clamp(getConfig().getDouble("render.receiver-radius", 24.0), 8.0, 40.0);
        LinkedHashSet<Player> receivers = new LinkedHashSet<>();
        receivers.add(emitter);
        emitter.getWorld().getNearbyPlayers(base, receiverRadius).stream()
                .filter(receiver -> receiver != emitter && receiver.canSee(emitter))
                .sorted(Comparator.comparingDouble(receiver -> receiver.getLocation().distanceSquared(base)))
                .limit(receiverLimit - 1L).forEach(receivers::add);
        return List.copyOf(receivers);
    }

    private void spawn(Player receiver, AuraType type, Location location, int index, double phase) {
        if (type == AuraType.EMBER) {
            receiver.spawnParticle(Particle.FLAME, location, 1, 0.0, 0.0, 0.0, 0.0);
            return;
        }
        if (type == AuraType.PRISMATIC) {
            int palette = Math.floorMod(index + (int) Math.floor(phase), PRISMATIC.length);
            receiver.spawnParticle(Particle.DUST_COLOR_TRANSITION, location, 1,
                    0.0, 0.0, 0.0, 0.0, PRISMATIC_TRANSITIONS[palette]);
            return;
        }
        receiver.spawnParticle(Particle.DUST, location, 1, 0.0, 0.0, 0.0, 0.0, dust(type));
    }

    private boolean eligibleAura(Player player, long now) {
        return baseEligible(player) && !player.isGliding() && selectedAura(player) != AuraType.OFF
                && combatSuppression.getOrDefault(player.getUniqueId(), 0L) <= now;
    }

    private boolean eligibleTrail(Player player, long now) {
        double minimumSpeed = Math.max(0.01, getConfig().getDouble("render.trail.minimum-speed-squared", 0.06));
        return baseEligible(player) && player.isGliding() && selectedTrail(player) != TrailStyle.OFF
                && player.getVelocity().lengthSquared() >= minimumSpeed
                && combatSuppression.getOrDefault(player.getUniqueId(), 0L) <= now;
    }

    private boolean baseEligible(Player player) {
        if (!authenticatedPlayers.contains(player.getUniqueId())
                || player.getGameMode() == GameMode.SPECTATOR || player.isInvisible()) return false;
        String world = player.getWorld().getName();
        return getConfig().getStringList("render.disabled-world-prefixes").stream().noneMatch(world::startsWith);
    }

    private AuraType selectedAura(Player player) {
        String value = player.getPersistentDataContainer().get(selectedAuraKey, PersistentDataType.STRING);
        return AuraType.parse(value).orElse(AuraType.OFF);
    }

    private TrailStyle selectedTrail(Player player) {
        String value = player.getPersistentDataContainer().get(selectedTrailKey, PersistentDataType.STRING);
        TrailStyle style = TrailStyle.parse(value).orElse(TrailStyle.OFF);
        return unlockedTrail(player, style) ? style : TrailStyle.OFF;
    }

    private AuraType selectedTrailPalette(Player player) {
        String value = player.getPersistentDataContainer().get(selectedTrailPaletteKey, PersistentDataType.STRING);
        AuraType auraFallback = selectedAura(player);
        if (auraFallback == AuraType.OFF) auraFallback = AuraType.ARCANE;
        AuraType palette = AuraType.parse(value).orElse(auraFallback);
        if (palette == AuraType.OFF || !unlockedAura(player, palette)) return AuraType.ARCANE;
        return palette;
    }

    private boolean unlockedAura(Player player, AuraType type) {
        if (!type.voucherRequired()) return true;
        Byte unlocked = player.getPersistentDataContainer().get(prismaticUnlockedKey, PersistentDataType.BYTE);
        return unlocked != null && unlocked == (byte) 1;
    }

    private boolean unlockedTrail(Player player, TrailStyle style) {
        if (!style.voucherRequired()) return true;
        Byte unlocked = player.getPersistentDataContainer().get(trailUnlockedKeys.get(style), PersistentDataType.BYTE);
        return unlocked != null && unlocked == (byte) 1;
    }

    private void queueMoment(UUID playerId, CosmeticMoment moment) {
        long now = System.currentTimeMillis();
        String key = playerId + ":" + moment.name();
        long defaultCooldown = moment == CosmeticMoment.ELYTRA_BOOST ? 750L : 1500L;
        long cooldown = Math.clamp(getConfig().getLong("render.moments.cooldown-ms." +
                moment.name().toLowerCase(Locale.ROOT).replace('_', '-'), defaultCooldown), 250L, 30_000L);
        if (momentCooldowns.getOrDefault(key, 0L) > now) return;
        int maxQueued = Math.clamp(getConfig().getInt("render.moments.max-queued", 32), 1, 128);
        if (pendingMoments.size() >= maxQueued) return;
        momentCooldowns.put(key, now + cooldown);
        pendingMoments.addLast(new MomentRequest(playerId, moment, now + 3000L));
    }

    private void cleanup(long now) {
        combatSuppression.entrySet().removeIf(entry -> entry.getValue() <= now);
        momentCooldowns.entrySet().removeIf(entry -> entry.getValue() <= now);
        pendingMoments.removeIf(request -> request.expiresAt() <= now);
    }

    private int categoryPacketLimit(String path, ServerLoadState state, int normal, int pressure) {
        int fallback = state == ServerLoadState.NORMAL ? normal
                : state == ServerLoadState.PRESSURE ? pressure : 0;
        return Math.clamp(getConfig().getInt(path + "." + state.name().toLowerCase(Locale.ROOT), fallback),
                0, 512);
    }

    private void suppress(Player player) {
        long seconds = Math.clamp(getConfig().getLong("render.suppress-after-combat-seconds", 8L), 0L, 60L);
        if (seconds > 0) combatSuppression.put(player.getUniqueId(), System.currentTimeMillis() + seconds * 1000L);
    }

    private Player playerSource(Entity entity) {
        if (entity instanceof Player player) return player;
        if (entity instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();
            if (source instanceof Player player) return player;
        }
        return null;
    }

    private void registerCommand(String name) {
        PluginCommand command = Objects.requireNonNull(getCommand(name), name + " command");
        command.setExecutor(this);
        command.setTabCompleter(this);
    }

    private void migrateConfig() {
        int version = getConfig().contains("config-version", true)
                ? getConfig().getInt("config-version", 0) : 0;
        if (version >= 2) return;
        getConfig().set("config-version", 2);
        getConfig().set("render.interval-ticks", 5);
        getConfig().set("render.max-receivers-per-emitter", 6);
        getConfig().set("render.max-emitters-per-pass", null);
        getConfig().set("render.points-per-receiver", null);
    }

    private void unknown(Player player, String key) {
        player.sendMessage(Component.text(messages.text(player, key), NamedTextColor.RED));
    }

    private ItemStack menuItem(Material material, String name, NamedTextColor color, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private List<String> filter(List<String> values, String input) {
        String prefix = input.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.startsWith(prefix)).toList();
    }

    private Material icon(AuraType type) {
        return switch (type) {
            case OFF -> Material.INK_SAC;
            case EMBER -> Material.BLAZE_POWDER;
            case VERDANT -> Material.SPORE_BLOSSOM;
            case ARCANE -> Material.AMETHYST_SHARD;
            case TIDE -> Material.HEART_OF_THE_SEA;
            case PRISMATIC -> Material.NETHER_STAR;
        };
    }

    private Material trailIcon(TrailStyle style) {
        return switch (style) {
            case OFF -> Material.INK_SAC;
            case WAKE -> Material.FEATHER;
            case WINGS -> Material.ELYTRA;
            case COMET -> Material.FIREWORK_ROCKET;
        };
    }

    private Particle.DustOptions dust(AuraType type) {
        return switch (type) {
            case VERDANT -> VERDANT_DUST;
            case ARCANE -> ARCANE_DUST;
            case TIDE -> TIDE_DUST;
            default -> throw new IllegalArgumentException("No dust options for " + type);
        };
    }

    private static Particle.DustTransition[] createPrismaticTransitions() {
        Particle.DustTransition[] transitions = new Particle.DustTransition[PRISMATIC.length];
        for (int index = 0; index < PRISMATIC.length; index++) {
            transitions[index] = new Particle.DustTransition(
                    PRISMATIC[index], PRISMATIC[(index + 1) % PRISMATIC.length], 0.9F);
        }
        return transitions;
    }

    private record MomentRequest(UUID playerId, CosmeticMoment moment, long expiresAt) { }

}
