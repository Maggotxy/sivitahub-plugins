package cn.originalrpg.items.forge;

import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.model.EquipmentSnapshot;
import cn.originalrpg.api.model.EquipmentTraitCatalog;
import cn.originalrpg.api.model.ForgeImprint;
import cn.originalrpg.api.model.EquipmentOperationEnvelope;
import cn.originalrpg.api.model.EquipmentOperationKind;
import cn.originalrpg.api.model.EquipmentOperationRequest;
import cn.originalrpg.api.model.EquipmentOperationResult;
import cn.originalrpg.api.rules.AuthoritativeItemPolicy;
import cn.originalrpg.api.service.EquipmentProgressService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.ForgeImprintService;
import cn.originalrpg.items.security.EquipmentSecurity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.Item;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

public final class ForgeListener implements Listener, CommandExecutor, EquipmentProgressService {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final Set<Material> WEAPONS = Set.of(
            Material.WOODEN_SWORD, Material.STONE_SWORD, Material.IRON_SWORD, Material.GOLDEN_SWORD,
            Material.DIAMOND_SWORD, Material.NETHERITE_SWORD, Material.WOODEN_AXE, Material.STONE_AXE,
            Material.IRON_AXE, Material.GOLDEN_AXE, Material.DIAMOND_AXE, Material.NETHERITE_AXE,
            Material.WOODEN_HOE, Material.STONE_HOE, Material.IRON_HOE, Material.GOLDEN_HOE,
            Material.DIAMOND_HOE, Material.NETHERITE_HOE, Material.BOW, Material.CROSSBOW,
            Material.TRIDENT, Material.MACE, Material.BLAZE_ROD, Material.BREEZE_ROD
    );
    private static final Set<Material> ARMOR = Set.of(
            Material.LEATHER_HELMET, Material.LEATHER_CHESTPLATE, Material.LEATHER_LEGGINGS, Material.LEATHER_BOOTS,
            Material.CHAINMAIL_HELMET, Material.CHAINMAIL_CHESTPLATE, Material.CHAINMAIL_LEGGINGS, Material.CHAINMAIL_BOOTS,
            Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS,
            Material.GOLDEN_HELMET, Material.GOLDEN_CHESTPLATE, Material.GOLDEN_LEGGINGS, Material.GOLDEN_BOOTS,
            Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS,
            Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS,
            Material.TURTLE_HELMET, Material.SHIELD
    );

    private final Plugin plugin;
    private final EquipmentSecurity security;
    private final MessageService messages;
    private final ForgeImprintService imprints;
    private final LegacyBlueprintService blueprints;
    private final ForgeVisuals visuals;
    private final EquipmentOperationCoordinator operations;
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private final NamespacedKey itemId;
    private final NamespacedKey crafter;
    private final NamespacedKey owner;
    private final NamespacedKey tradeUnlocked;
    private final NamespacedKey rarity;
    private final NamespacedKey totalSlots;
    private final NamespacedKey revealedSlots;
    private final NamespacedKey itemExperience;
    private final NamespacedKey rareTrait;
    private final NamespacedKey equipmentType;
    private final NamespacedKey weaponArchetype;
    private final NamespacedKey forgeImprintId;
    private final NamespacedKey forgeBias;
    private final NamespacedKey forgeSource;
    private final NamespacedKey forgeStrength;
    private final NamespacedKey dungeonCoreSource;
    private final NamespacedKey limitLevel = NamespacedKey.fromString("rpgenhancement:limit_level");
    private final NamespacedKey[] refinedSlots = refinedSlotKeys();
    private final NamespacedKey adaptiveMaterial = NamespacedKey.fromString("rpgmobs:adaptive_material");
    private final NamespacedKey huntMaterial = NamespacedKey.fromString("rpgworld:hunt_material");
    private final NamespacedKey bossMaterial = NamespacedKey.fromString("rpgdungeons:boss_material");
    private final NamespacedKey forgeEssence = NamespacedKey.fromString("rpgitems:forge_essence");
    private final NamespacedKey materialOwner = NamespacedKey.fromString("rpgitems:material_owner");
    private final NamespacedKey starterBaseOwner = NamespacedKey.fromString("rpgguide:starter_base_owner");
    private final NamespacedKey starterCoreOwner = NamespacedKey.fromString("rpgguide:starter_core_owner");
    private final NamespacedKey starterArchetype = NamespacedKey.fromString("rpgguide:starter_archetype");

    public ForgeListener(
            Plugin plugin,
            EquipmentSecurity security,
            MessageService messages,
            ForgeImprintService imprints,
            LegacyBlueprintService blueprints,
            EquipmentOperationCoordinator operations
    ) {
        this.plugin = plugin;
        this.security = security;
        this.messages = messages;
        this.imprints = imprints;
        this.blueprints = blueprints;
        this.operations = operations;
        this.visuals = new ForgeVisuals(plugin, messages);
        itemId = new NamespacedKey(plugin, "item_id");
        crafter = new NamespacedKey(plugin, "crafter");
        owner = new NamespacedKey(plugin, "owner");
        tradeUnlocked = new NamespacedKey(plugin, "trade_unlocked");
        rarity = new NamespacedKey(plugin, "rarity");
        totalSlots = new NamespacedKey(plugin, "total_slots");
        revealedSlots = new NamespacedKey(plugin, "revealed_slots");
        itemExperience = new NamespacedKey(plugin, "item_xp");
        rareTrait = new NamespacedKey(plugin, "rare_trait");
        equipmentType = new NamespacedKey(plugin, "equipment_type");
        weaponArchetype = new NamespacedKey(plugin, "weapon_archetype");
        forgeImprintId = new NamespacedKey(plugin, "forge_imprint_id");
        forgeBias = new NamespacedKey(plugin, "forge_bias");
        forgeSource = new NamespacedKey(plugin, "forge_source");
        forgeStrength = new NamespacedKey(plugin, "forge_strength");
        dungeonCoreSource = new NamespacedKey(plugin, "dungeon_core_source");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.text(sender, "common.player-only"));
            return true;
        }
        player.openInventory(new ForgeMenu(player.getUniqueId(), messages.text(player, "items.forge-title"),
                messages.text(player, "items.forge-instruction")).getInventory());
        return true;
    }

    @EventHandler
    public void onAuthenticated(AuthenticatedPlayerReadyEvent event) {
        Player player = event.player();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!plugin.isEnabled() || !currentSession(player)) return;
            refreshInventory(player);
            operations.recover(player);
        });
    }

    private static boolean currentSession(Player player) {
        return player != null && player.isOnline()
                && Bukkit.getPlayer(player.getUniqueId()) == player;
    }

    @EventHandler
    public void onAuthenticationRevoked(AuthenticatedPlayerRevokedEvent event) {
        Player player = event.player();
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof ForgeMenu) player.closeInventory();
        // Submitted durable operations keep their duplicate-click guard until completion.
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ForgeMenu menu)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            event.setCancelled(true);
            return;
        }
        if (!menu.ownerId().equals(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (event.isShiftClick()) {
            event.setCancelled(true);
            return;
        }
        int rawSlot = event.getRawSlot();
        if (pending.contains(player.getUniqueId()) && rawSlot < menu.getInventory().getSize()) {
            event.setCancelled(true);
            return;
        }
        if (rawSlot >= menu.getInventory().getSize()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> updatePreview(menu));
            return;
        }
        if (rawSlot == ForgeMenu.RESULT_SLOT) {
            event.setCancelled(true);
            attemptForge(player, menu);
            return;
        }
        if (!ForgeMenu.INPUT_SLOTS.contains(rawSlot)) {
            event.setCancelled(true);
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> updatePreview(menu));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ForgeMenu menu)) {
            return;
        }
        if (!menu.ownerId().equals(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (pending.contains(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (event.getRawSlots().stream().anyMatch(slot -> slot < menu.getInventory().getSize() && !ForgeMenu.INPUT_SLOTS.contains(slot))) {
            event.setCancelled(true);
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> updatePreview(menu));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof ForgeMenu menu)) {
            return;
        }
        Player player = (Player) event.getPlayer();
        if (!menu.ownerId().equals(player.getUniqueId())) return;
        for (int slot : ForgeMenu.INPUT_SLOTS) {
            ItemStack item = menu.getInventory().getItem(slot);
            if (item != null && !item.getType().isAir()) {
                player.getInventory().addItem(item).values().forEach(leftover -> dropOwned(player, leftover));
                menu.getInventory().setItem(slot, null);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFirstUse(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        ItemStack weapon = player.getInventory().getItem(EquipmentSlot.HAND);
        ItemMeta meta = weapon.getItemMeta();
        if (meta == null || !meta.getPersistentDataContainer().has(itemId)) {
            return;
        }
        if (!security.isAuthentic(weapon)) {
            event.setCancelled(true);
            player.sendMessage(Component.text(messages.text(player, "items.signature-use-blocked"), NamedTextColor.RED));
            return;
        }
        bind(player, weapon, meta);
    }

    /** Bind ranged weapons at launch so the immutable combat snapshot cannot
     * activate an unbound hidden trait or miss ownership until impact. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onProjectileUse(ProjectileLaunchEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player player)) return;
        ItemStack weapon = projectileWeapon(event.getEntity(), player);
        ItemMeta meta = weapon.getItemMeta();
        if (meta == null || !meta.getPersistentDataContainer().has(itemId)) return;
        if (!security.isAuthentic(weapon)) {
            event.setCancelled(true);
            player.sendMessage(Component.text(messages.text(player, "items.signature-use-blocked"), NamedTextColor.RED));
            return;
        }
        Optional<EquipmentSnapshot> snapshot = security.inspect(weapon);
        if (snapshot.flatMap(EquipmentSnapshot::boundOwner)
                .filter(ownerId -> !ownerId.equals(player.getUniqueId())).isPresent()) {
            event.setCancelled(true);
            player.sendMessage(Component.text(messages.text(player, "combat.owner-mismatch"), NamedTextColor.RED));
            return;
        }
        bind(player, weapon, meta);
        freezeProjectileWeapon(event.getEntity(), weapon);
    }

    private ItemStack projectileWeapon(org.bukkit.entity.Projectile projectile, Player player) {
        if (projectile instanceof org.bukkit.entity.AbstractArrow arrow) {
            ItemStack frozen = arrow.getWeapon();
            if (security.isCustomEquipment(frozen)) {
                Optional<UUID> id = security.itemId(frozen);
                if (id.isPresent()) {
                    for (ItemStack hand : List.of(player.getInventory().getItemInMainHand(),
                            player.getInventory().getItemInOffHand())) {
                        if (security.itemId(hand).equals(id)) return hand;
                    }
                }
                // Thrown tridents may already have left both hands; their
                // projectile-owned weapon copy is then the persistent item.
                return frozen;
            }
        }
        return player.getInventory().getItemInMainHand();
    }

    private void freezeProjectileWeapon(org.bukkit.entity.Projectile projectile, ItemStack weapon) {
        if (!(projectile instanceof org.bukkit.entity.AbstractArrow arrow)
                || !security.isCustomEquipment(weapon)) return;
        ItemStack frozen = weapon.clone();
        arrow.setWeapon(frozen);
        if (projectile instanceof org.bukkit.entity.Trident trident) trident.setItem(frozen.clone());
    }

    @EventHandler
    public void onKill(EntityDeathEvent event) {
        Player player = event.getEntity().getKiller();
        if (player == null) {
            return;
        }
        int gained = Math.max(1, event.getDroppedExp());
        if (event.getDamageSource().getDirectEntity() instanceof AbstractArrow arrow) {
            grantProjectileKillExperience(player, arrow, gained);
            return;
        }
        ItemStack weapon = player.getInventory().getItemInMainHand();
        ItemMeta meta = weapon.getItemMeta();
        if (meta == null || !meta.getPersistentDataContainer().has(itemId)) {
            return;
        }
        if (!security.isAuthentic(weapon)) {
            player.sendMessage(Component.text(messages.text(player, "items.signature-xp-blocked"), NamedTextColor.RED));
            return;
        }
        grantUseExperience(player, weapon, gained);
    }

    /**
     * Attribute a ranged kill to the immutable weapon captured at launch, not
     * whatever happens to be in the main hand when the projectile lands.
     * The live inventory item remains the authority; a thrown trident instead
     * persists its updated copy back onto the projectile entity.
     */
    private void grantProjectileKillExperience(Player player, AbstractArrow arrow, int gained) {
        ItemStack launched = arrow.getWeapon();
        if (!security.isAuthentic(launched)) return;
        Optional<UUID> launchedId = security.itemId(launched);
        if (launchedId.isEmpty()) return;
        for (ItemStack candidate : player.getInventory().getContents()) {
            if (!security.itemId(candidate).equals(launchedId)) continue;
            grantUseExperience(player, candidate, gained);
            return;
        }
        if (!(arrow instanceof Trident trident)) return;
        ItemStack thrown = trident.getItem();
        if (!security.itemId(thrown).equals(launchedId)
                || !grantUseExperience(player, thrown, gained)) return;
        trident.setItem(thrown.clone());
        arrow.setWeapon(thrown.clone());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArmorUse(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.getFinalDamage() <= 0.0) return;
        int gained = Math.max(1, (int) Math.ceil(event.getFinalDamage()));
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (ItemStack armor : player.getInventory().getArmorContents()) {
                bindAndGain(player, armor, gained);
            }
            ItemStack offHand = player.getInventory().getItemInOffHand();
            if (offHand.getType() == Material.SHIELD) bindAndGain(player, offHand, gained);
        });
    }

    @EventHandler
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        ItemStack input = event.getInventory().getInputEquipment();
        if (input == null || !security.isCustomEquipment(input)) return;
        Optional<EquipmentSnapshot> inspected = security.inspect(input);
        if (inspected.isEmpty()) {
            event.setResult(null);
            return;
        }
        if (event.getView().getPlayer() instanceof Player player
                && inspected.get().boundOwner().isPresent()
                && !inspected.get().boundOwner().orElseThrow().equals(player.getUniqueId())) {
            event.setResult(null);
            return;
        }
        ItemStack result = event.getResult();
        if (result == null || !security.itemId(result).equals(security.itemId(input))) return;
        ItemMeta resultMeta = result.getItemMeta();
        if (resultMeta == null) return;
        resultMeta.setItemModel(itemModelFor(result.getType()));
        result.setItemMeta(resultMeta);
        security.sign(result);
        event.setResult(result);
    }

    private void updatePreview(ForgeMenu menu) {
        Player audience = plugin.getServer().getPlayer(menu.ownerId());
        ItemStack base = menu.getInventory().getItem(ForgeMenu.BASE_SLOT);
        ItemStack core = menu.getInventory().getItem(ForgeMenu.CORE_SLOT);
        ItemStack catalyst = menu.getInventory().getItem(ForgeMenu.CATALYST_SLOT);
        ItemStack imprintItem = menu.getInventory().getItem(ForgeMenu.IMPRINT_SLOT);
        ItemStack blueprintItem = menu.getInventory().getItem(ForgeMenu.BLUEPRINT_SLOT);
        int coreCost = plugin.getConfig().getInt("forge.core-cost", 8);
        if (!validForgeBase(base) || !isCoreMaterial(plugin.getServer().getPlayer(menu.ownerId()), core)
                || core.getAmount() < coreCost) {
            menu.getInventory().setItem(ForgeMenu.RESULT_SLOT, named(Material.ANVIL,
                    messages.text(audience, "items.preview-materials", Map.of("count", coreCost))));
            return;
        }
        StarterForgePolicy.Assessment starter = starterAssessment(audience, base, core);
        if (starter.containsStarterComponent() && !starter.valid()) {
            menu.getInventory().setItem(ForgeMenu.RESULT_SLOT, named(Material.BARRIER,
                    messages.text(audience, "items.starter-pair-invalid")));
            return;
        }
        if (!isCatalyst(catalyst)) {
            menu.getInventory().setItem(ForgeMenu.RESULT_SLOT, named(Material.BARRIER,
                    messages.text(audience, "items.preview-catalyst")));
            return;
        }
        Optional<ForgeImprint> imprint = inspectImprint(audience, imprintItem);
        if (present(imprintItem) && imprint.isEmpty()) {
            menu.getInventory().setItem(ForgeMenu.RESULT_SLOT, named(Material.BARRIER,
                    messages.text(audience, "items.preview-imprint-invalid")));
            return;
        }
        Optional<LegacyBlueprint> blueprint = inspectBlueprint(audience, blueprintItem, base.getType());
        if (present(blueprintItem) && blueprint.isEmpty()) {
            menu.getInventory().setItem(ForgeMenu.RESULT_SLOT, named(Material.BARRIER,
                    messages.text(audience, "items.preview-blueprint-invalid")));
            return;
        }
        boolean starterForge = starter.valid();
        Optional<DungeonCoreResonanceRules.Resonance> coreResonance = inspectDungeonCore(core);
        double chance = starterForge ? 1.0
                : successChance(base.getType(), catalystUsed(catalyst), coreResonance);
        String displayedCost = starterForge ? "0.00"
                : plugin.getConfig().getString("forge.gold-cost", "250.00");
        ItemStack preview = base.clone();
        preview.setAmount(1);
        ItemMeta meta = preview.getItemMeta();
        meta.displayName(Component.text(messages.text(audience, "items.preview-title"), NamedTextColor.GOLD));
        List<Component> previewLore = new ArrayList<>(List.of(
                Component.text(messages.text(audience, "items.preview-chance", Map.of(
                        "chance", Math.round(chance * 100))), NamedTextColor.GREEN),
                Component.text(messages.text(audience, "items.preview-cost", Map.of(
                        "cost", displayedCost)), NamedTextColor.GOLD),
                Component.text(messages.text(audience, "items.preview-quality", Map.of(
                        "quality", ForgeRules.baseQuality(base.getType().name()),
                        "maximum", ForgeRules.slotUpperBound(coreResonance
                                .map(value -> DungeonCoreResonanceRules.enforceMinimumRarity("COMMON", value))
                                .orElse("COMMON"), base.getType().name()))), NamedTextColor.AQUA),
                Component.text(messages.text(audience, "items.preview-slots"), NamedTextColor.GRAY),
                Component.text(messages.text(audience, "items.preview-rare-trait", Map.of(
                        "chance", String.format(Locale.ROOT, "%.1f", rareTraitChance(coreResonance) * 100.0),
                        "count", EquipmentTraitCatalog.implemented().size())), NamedTextColor.LIGHT_PURPLE),
                Component.text(messages.text(audience, starterForge
                        ? "items.preview-starter-guarantee" : "items.preview-risk"),
                        starterForge ? NamedTextColor.GREEN : NamedTextColor.RED)
        ));
        coreResonance.ifPresent(value -> {
            previewLore.add(Component.text(messages.text(audience, "items.preview-core-source", Map.of(
                    "source", messages.text(audience, "items.core-resonance-names." + value.id()))),
                    value.hidden() ? NamedTextColor.GOLD : NamedTextColor.AQUA));
            previewLore.add(Component.text(coreResonancePreview(
                    audience, value, base.getType(), catalystUsed(catalyst)), NamedTextColor.GRAY));
        });
        imprint.ifPresent(value -> previewLore.add(Component.text(messages.text(audience,
                "items.preview-imprint", Map.of(
                        "stat", messages.text(audience, "item-stats." + value.bias()),
                        "chance", String.format(Locale.ROOT, "%.1f", value.selectedStatChance() * 100.0))),
                NamedTextColor.LIGHT_PURPLE)));
        blueprint.ifPresent(value -> previewLore.add(Component.text(messages.text(audience,
                "items.preview-blueprint", Map.of(
                        "trait", messages.text(audience, "item-traits." + value.rareTrait()),
                        "chance", String.format(Locale.ROOT, "%.1f",
                                inheritanceChance(coreResonance) * 100.0))), NamedTextColor.LIGHT_PURPLE)));
        meta.lore(previewLore);
        preview.setItemMeta(meta);
        menu.getInventory().setItem(ForgeMenu.RESULT_SLOT, preview);
    }

    private void attemptForge(Player player, ForgeMenu menu) {
        if (operations.hasPending(player)) {
            operations.recover(player);
            player.sendMessage(Component.text(messages.text(player, "items.forge-pending"), NamedTextColor.YELLOW));
            return;
        }
        if (!pending.add(player.getUniqueId())) {
            player.sendMessage(Component.text(messages.text(player, "items.forge-pending"), NamedTextColor.YELLOW));
            return;
        }
        ItemStack base = menu.getInventory().getItem(ForgeMenu.BASE_SLOT);
        ItemStack core = menu.getInventory().getItem(ForgeMenu.CORE_SLOT);
        ItemStack imprintItem = menu.getInventory().getItem(ForgeMenu.IMPRINT_SLOT);
        ItemStack blueprintItem = menu.getInventory().getItem(ForgeMenu.BLUEPRINT_SLOT);
        int coreCost = plugin.getConfig().getInt("forge.core-cost", 8);
        if (!validForgeBase(base) || !isCoreMaterial(player, core)
                || core.getAmount() < coreCost || !isCatalyst(menu.getInventory().getItem(ForgeMenu.CATALYST_SLOT))
                || present(imprintItem) && inspectImprint(player, imprintItem).isEmpty()
                || present(blueprintItem) && inspectBlueprint(player, blueprintItem, base.getType()).isEmpty()) {
            pending.remove(player.getUniqueId());
            player.sendMessage(Component.text(messages.text(player, "items.materials-insufficient"), NamedTextColor.RED));
            return;
        }
        StarterForgePolicy.Assessment starter = starterAssessment(player, base, core);
        if (starter.containsStarterComponent() && !starter.valid()) {
            pending.remove(player.getUniqueId());
            player.sendMessage(Component.text(messages.text(player,
                    "items.starter-pair-invalid"), NamedTextColor.RED));
            return;
        }
        boolean starterForge = starter.valid();
        BigDecimal price = starterForge ? BigDecimal.ZERO
                : new BigDecimal(plugin.getConfig().getString("forge.gold-cost", "250.00"));
        completeForge(player, menu, price, UUID.randomUUID());
    }

    private void completeForge(Player player, ForgeMenu menu, BigDecimal price, UUID operationId) {
        ItemStack base = menu.getInventory().getItem(ForgeMenu.BASE_SLOT);
        ItemStack core = menu.getInventory().getItem(ForgeMenu.CORE_SLOT);
        ItemStack catalyst = menu.getInventory().getItem(ForgeMenu.CATALYST_SLOT);
        ItemStack imprintItem = menu.getInventory().getItem(ForgeMenu.IMPRINT_SLOT);
        ItemStack blueprintItem = menu.getInventory().getItem(ForgeMenu.BLUEPRINT_SLOT);
        int coreCost = plugin.getConfig().getInt("forge.core-cost", 8);
        int catalystCost = plugin.getConfig().getInt("forge.catalyst-cost", 1);
        if (!validForgeBase(base) || !isCoreMaterial(player, core)
                || core.getAmount() < coreCost || !isCatalyst(catalyst)
                || present(imprintItem) && inspectImprint(player, imprintItem).isEmpty()
                || present(blueprintItem) && inspectBlueprint(player, blueprintItem, base.getType()).isEmpty()) {
            pending.remove(player.getUniqueId());
            player.sendMessage(Component.text(messages.text(player, "items.materials-changed"), NamedTextColor.RED));
            return;
        }
        StarterForgePolicy.Assessment starter = starterAssessment(player, base, core);
        if (starter.containsStarterComponent() && !starter.valid()) {
            pending.remove(player.getUniqueId());
            player.sendMessage(Component.text(messages.text(player,
                    "items.starter-pair-invalid"), NamedTextColor.RED));
            return;
        }
        int catalystLuck = catalystUsed(catalyst);
        Optional<ForgeImprint> imprint = inspectImprint(player, imprintItem);
        Optional<LegacyBlueprint> blueprint = inspectBlueprint(player, blueprintItem, base.getType());
        Optional<DungeonCoreResonanceRules.Resonance> coreResonance = inspectDungeonCore(core);
        boolean starterForge = starter.valid() && price.signum() == 0;
        double successChance = starterForge ? 1.0
                : successChance(base.getType(), catalystLuck, coreResonance);
        int catalystConsumed = catalyst == null ? 0 : Math.max(catalystCost, catalystLuck);
        List<ItemStack> escrow = new ArrayList<>();
        escrow.add(amountCopy(base, 1));
        escrow.add(amountCopy(core, coreCost));
        if (catalystConsumed > 0) escrow.add(amountCopy(catalyst, catalystConsumed));
        if (imprint.isPresent()) escrow.add(amountCopy(imprintItem, 1));
        if (blueprint.isPresent()) escrow.add(amountCopy(blueprintItem, 1));
        List<String> rejected = escrow.stream().map(EquipmentOperationCoordinator::payload).toList();
        RandomGenerator operationRandom = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom")
                .create(EquipmentOperationEnvelope.deterministicSeed(operationId, "forge"));
        boolean success = operationRandom.nextDouble() <= successChance;
        List<String> committed;
        ItemStack result = null;
        if (success) {
            result = createForgedItem(base, player, catalystLuck, imprint, blueprint,
                    coreResonance, starterForge, operationRandom, operationId);
            committed = List.of(EquipmentOperationCoordinator.payload(result));
        } else {
            committed = List.of();
        }
        String requestPayload = "chance=" + successChance + ";starter=" + starterForge
                + ";catalyst=" + catalystLuck + ";core=" + coreResonance.map(
                DungeonCoreResonanceRules.Resonance::id).orElse("") + ";outcome=" + success;
        EquipmentOperationRequest request = new EquipmentOperationRequest(
                operationId, player.getUniqueId(), EquipmentOperationKind.FORGE, Optional.empty(),
                EquipmentOperationEnvelope.fingerprint(requestPayload, rejected), requestPayload,
                price, "forge:" + operationId, committed, rejected);
        try {
            operations.stage(player, request);
        } catch (RuntimeException exception) {
            operations.clearStaged(player, operationId);
            pending.remove(player.getUniqueId());
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Could not escrow forge inputs", exception);
            player.sendMessage(Component.text(messages.text(player, "items.settlement-failed"), NamedTextColor.RED));
            return;
        }
        consume(core, coreCost);
        if (catalystConsumed > 0) consume(catalyst, catalystConsumed);
        if (imprint.isPresent()) consume(imprintItem, 1);
        if (blueprint.isPresent()) consume(blueprintItem, 1);
        consume(base, 1);
        player.saveData();
        ItemStack visualResult = result;
        operations.submit(player, request, settlement -> {
            pending.remove(player.getUniqueId());
            if (!player.isOnline()) return;
            if (settlement.status() == EquipmentOperationResult.Status.COMMITTED) {
                if (success) {
                    player.sendMessage(Component.text(messages.text(player, "items.forge-success"), NamedTextColor.GREEN));
                    ItemMeta resultMeta = visualResult.getItemMeta();
                    PersistentDataContainer resultData = resultMeta.getPersistentDataContainer();
                    visuals.success(player,
                            resultData.getOrDefault(rarity, PersistentDataType.STRING, "COMMON"),
                            resultData.getOrDefault(totalSlots, PersistentDataType.INTEGER, 0),
                            resultData.has(rareTrait, PersistentDataType.STRING));
                } else {
                    player.sendMessage(Component.text(messages.text(player, "items.forge-failed"), NamedTextColor.RED));
                    visuals.failure(player);
                }
            }
            if (player.getOpenInventory().getTopInventory().getHolder() == menu) updatePreview(menu);
        });
    }

    private ItemStack createForgedItem(
            ItemStack base,
            Player player,
            int catalystAmount,
            Optional<ForgeImprint> imprint,
            Optional<LegacyBlueprint> blueprint,
            Optional<DungeonCoreResonanceRules.Resonance> coreResonance,
            boolean starterForge,
            RandomGenerator operationRandom,
            UUID operationId
    ) {
        ItemStack result = base.clone();
        result.setAmount(1);
        String rolledRarity = rollRarity(base.getType(), catalystAmount, coreResonance, operationRandom);
        int slots = rollSlots(rolledRarity, base.getType(), coreResonance, operationRandom);
        ItemMeta meta = result.getItemMeta();
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(itemId, PersistentDataType.STRING, EquipmentOperationEnvelope.derivedId(
                operationId, "forged-item", 0).toString());
        data.set(crafter, PersistentDataType.STRING, player.getUniqueId().toString());
        data.set(security.schemaVersionKey(), PersistentDataType.INTEGER, 5);
        data.set(rarity, PersistentDataType.STRING, rolledRarity);
        data.set(equipmentType, PersistentDataType.STRING, ARMOR.contains(base.getType()) ? "ARMOR" : "WEAPON");
        String direction = starterForge ? starterDirection(base, player).orElse(archetypeFor(base.getType()))
                : archetypeFor(base.getType());
        data.set(weaponArchetype, PersistentDataType.STRING, direction);
        data.set(totalSlots, PersistentDataType.INTEGER, slots);
        data.set(revealedSlots, PersistentDataType.INTEGER, 0);
        data.set(itemExperience, PersistentDataType.INTEGER, 0);
        if (starterBaseOwner != null) data.remove(starterBaseOwner);
        if (starterArchetype != null) data.remove(starterArchetype);
        if (materialOwner != null) data.remove(materialOwner);
        if (starterForge) data.set(owner, PersistentDataType.STRING, player.getUniqueId().toString());
        imprint.ifPresent(value -> {
            data.set(forgeImprintId, PersistentDataType.STRING, value.imprintId().toString());
            data.set(forgeBias, PersistentDataType.STRING, value.bias());
            data.set(forgeSource, PersistentDataType.STRING, value.sourceMaterial());
            data.set(forgeStrength, PersistentDataType.INTEGER, value.strength());
        });
        coreResonance.ifPresent(value -> data.set(dungeonCoreSource, PersistentDataType.STRING, value.id()));
        if (slots > 0) {
            if (blueprint.isPresent() && operationRandom.nextDouble() < inheritanceChance(coreResonance)) {
                data.set(rareTrait, PersistentDataType.STRING, blueprint.orElseThrow().rareTrait());
            } else if (operationRandom.nextDouble() < rareTraitChance(coreResonance)) {
                data.set(rareTrait, PersistentDataType.STRING, rollRareTrait(operationRandom));
            }
        }
        // A name entered on an ordinary base in a vanilla anvil is carried
        // into the finished item. The bound starter placeholder is replaced
        // by a readable class-specific default; the result can still be
        // renamed later in any vanilla anvil.
        if (starterForge || !meta.hasCustomName()) {
            meta.displayName(Component.text(messages.text(player,
                    "items.forged-names." + defaultNameId(base.getType(), direction)), rarityColor(rolledRarity)));
        }
        meta.setUnbreakable(true);
        if (meta instanceof Damageable damageable) damageable.setDamage(0);
        meta.setItemModel(itemModelFor(base.getType()));
        renderLore(player, meta, data, result.getType());
        result.setItemMeta(meta);
        security.sign(result);
        return result;
    }

    /** Creates a fresh signed equivalent when an operator has audited a loss. */
    public ItemStack createStarterReplacement(Player player, Archetype archetype) {
        Material material = switch (archetype) {
            case HEALER -> Material.BREEZE_ROD;
            case WARRIOR -> Material.STONE_AXE;
            case ASSASSIN -> Material.STONE_SWORD;
            case MAGE -> Material.BLAZE_ROD;
            case RANGER -> Material.BOW;
            case FREEFORM -> Material.WOODEN_SWORD;
            case UNCHOSEN -> throw new IllegalArgumentException("A class is required for equipment recovery");
        };
        ItemStack base = new ItemStack(material);
        ItemMeta meta = base.getItemMeta();
        if (starterBaseOwner == null || starterArchetype == null) {
            throw new IllegalStateException("Starter equipment keys are unavailable");
        }
        meta.getPersistentDataContainer().set(starterBaseOwner, PersistentDataType.STRING,
                player.getUniqueId().toString());
        meta.getPersistentDataContainer().set(starterArchetype, PersistentDataType.STRING,
                archetype.name());
        base.setItemMeta(meta);
        UUID operationId = UUID.randomUUID();
        RandomGenerator operationRandom = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom")
                .create(EquipmentOperationEnvelope.deterministicSeed(operationId, "starter-replacement"));
        return createForgedItem(base, player, 0, Optional.empty(), Optional.empty(), Optional.empty(), true,
                operationRandom, operationId);
    }

    private void renderLore(Player audience, ItemMeta meta, PersistentDataContainer data, Material material) {
        String rolledRarity = data.getOrDefault(rarity, PersistentDataType.STRING, "COMMON");
        String type = data.getOrDefault(equipmentType, PersistentDataType.STRING,
                ARMOR.contains(material) ? "ARMOR" : "WEAPON");
        EquipmentLoreProjection projection = EquipmentLoreProjection.project(
                data.getOrDefault(totalSlots, PersistentDataType.INTEGER, 0),
                data.getOrDefault(revealedSlots, PersistentDataType.INTEGER, 0),
                data.getOrDefault(itemExperience, PersistentDataType.INTEGER, 0),
                data.getOrDefault(limitLevel, PersistentDataType.INTEGER, 0),
                data.has(owner, PersistentDataType.STRING),
                data.getOrDefault(tradeUnlocked, PersistentDataType.BYTE, (byte) 0) == (byte) 1,
                java.util.Arrays.stream(refinedSlots)
                        .map(key -> data.get(key, PersistentDataType.STRING)).toList());
        int total = projection.totalSlots();
        int revealed = projection.revealedSlots();
        int experience = projection.experience();
        List<Component> lore = new ArrayList<>();
        int power = cn.originalrpg.api.rules.EquipmentBaseRules.power(material.name(), rolledRarity, type);
        int guard = cn.originalrpg.api.rules.EquipmentBaseRules.guard(material.name(), rolledRarity, type);
        lore.add(Component.text(messages.text(audience, power > 0
                ? "items.lore.base-power" : "items.lore.base-guard", Map.of(
                        "value", power > 0 ? power : guard)), NamedTextColor.GREEN));
        lore.add(Component.text(messages.text(audience, "items.lore.indestructible"), NamedTextColor.DARK_GREEN));
        lore.add(Component.text(messages.text(audience, "items.lore.rarity", Map.of(
                "rarity", rarityName(audience, rolledRarity))), rarityColor(rolledRarity)));
        lore.add(Component.text(messages.text(audience, "items.lore.limit-break", Map.of(
                "level", projection.limitBreakLevel())),
                NamedTextColor.GOLD));
        lore.add(Component.text(messages.text(audience, "items.lore.experience", Map.of(
                "experience", experience)), NamedTextColor.GRAY));
        lore.add(Component.text(messages.text(audience, "items.lore.slots", Map.of(
                "revealed", revealed, "total", total)), NamedTextColor.AQUA));
        for (int index = 0; index < total; index++) {
            if (index >= revealed) {
                lore.add(Component.text(messages.text(audience, "items.lore.unknown-slot"), NamedTextColor.DARK_GRAY));
                continue;
            }
            Optional<RefinedSlotValue> refined = projection.refinedSlots().get(index);
            if (refined.isEmpty()) {
                lore.add(Component.text(messages.text(audience, "items.lore.unrefined-slot", Map.of(
                        "slot", index + 1)), NamedTextColor.WHITE));
                continue;
            }
            RefinedSlotValue value = refined.orElseThrow();
            lore.add(Component.text(messages.text(audience, "items.lore.refined-slot", Map.of(
                            "slot", index + 1,
                            "stat", messages.text(audience, "item-stats." + value.stat()),
                            "value", value.signedValue())),
                    value.value() >= 0 ? NamedTextColor.GREEN : NamedTextColor.RED));
        }
        if (total > 0 && revealed < total) {
            int next = nextRevelationThreshold(revealed);
            lore.add(Component.text(messages.text(audience, "items.lore.next-reveal", Map.of(
                    "remaining", Math.max(0, next - experience))), NamedTextColor.YELLOW));
        } else if (total > 0) {
            lore.add(Component.text(messages.text(audience, "items.lore.all-revealed"), NamedTextColor.GREEN));
        }
        String trait = data.get(rareTrait, PersistentDataType.STRING);
        if (trait != null && revealed == total) {
            lore.add(Component.text(messages.text(audience, "items.lore.rare-trait", Map.of(
                    "trait", messages.text(audience, "item-traits." + trait))), NamedTextColor.LIGHT_PURPLE));
            EquipmentTraitCatalog.borrowedSkill(trait).ifPresent(skill -> lore.add(Component.text(
                    messages.text(audience, "items.lore.borrowed-skill-active", Map.of(
                            "skill", messages.text(audience, "skills.names." + skill))),
                    NamedTextColor.DARK_PURPLE)));
        }
        String creator = creatorName(audience, data.get(crafter, PersistentDataType.STRING));
        String direction = data.getOrDefault(weaponArchetype, PersistentDataType.STRING, "UNIVERSAL");
        lore.add(Component.text(messages.text(audience, "items.lore.direction", Map.of(
                "direction", directionName(audience, direction))), NamedTextColor.BLUE));
        String imprintBias = data.get(forgeBias, PersistentDataType.STRING);
        int imprintStrength = data.getOrDefault(forgeStrength, PersistentDataType.INTEGER, 0);
        if (imprintBias != null && imprintStrength > 0) {
            lore.add(Component.text(messages.text(audience, "items.lore.imprint", Map.of(
                    "stat", messages.text(audience, "item-stats." + imprintBias),
                    "chance", String.format(Locale.ROOT, "%.1f",
                            biasedStatChance(imprintStrength) * 100.0))), NamedTextColor.LIGHT_PURPLE));
        }
        String coreSource = data.get(dungeonCoreSource, PersistentDataType.STRING);
        if (coreSource != null && DungeonCoreResonanceRules.inspect(coreSource).isPresent()) {
            lore.add(Component.text(messages.text(audience, "items.lore.dungeon-core-source", Map.of(
                    "source", messages.text(audience, "items.core-resonance-names." + coreSource))),
                    NamedTextColor.AQUA));
        }
        lore.add(Component.text(messages.text(audience, "items.lore.crafter", Map.of(
                "crafter", creator)), NamedTextColor.DARK_GRAY));
        lore.add(Component.text(messages.text(audience, "items.lore.rename-hint"), NamedTextColor.GRAY));
        boolean unlocked = projection.ownership() == EquipmentLoreProjection.OwnershipState.TRADE_UNLOCKED;
        String stateKey = switch (projection.ownership()) {
            case BOUND -> "items.lore.bound";
            case TRADE_UNLOCKED -> "items.lore.trade-unlocked";
            case UNBOUND -> "items.lore.unbound";
        };
        lore.add(Component.text(messages.text(audience, stateKey),
                unlocked ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        meta.lore(lore);
    }

    private double successChance(Material baseMaterial, int catalystAmount,
                                 Optional<DungeonCoreResonanceRules.Resonance> coreResonance) {
        double baseChance = plugin.getConfig().getDouble("forge.base-success-rate", 0.65);
        double maximum = plugin.getConfig().getDouble("forge.maximum-success-rate", 0.95);
        double ordinary = ForgeRules.successChance(baseMaterial.name(), catalystAmount, baseChance, maximum);
        return coreResonance.map(value -> DungeonCoreResonanceRules.successChance(ordinary, maximum, value))
                .orElse(ordinary);
    }

    private String rollRarity(Material base, int catalystAmount,
                              Optional<DungeonCoreResonanceRules.Resonance> coreResonance,
                              RandomGenerator operationRandom) {
        double ordinaryLuck = ForgeRules.rarityLuck(base.name(), catalystAmount);
        double luck = coreResonance.map(value -> DungeonCoreResonanceRules.rarityLuck(ordinaryLuck, value))
                .orElse(ordinaryLuck);
        double roll = operationRandom.nextDouble();
        String rarityRoll;
        if (roll < 0.03 + luck * 0.20) rarityRoll = "LEGENDARY";
        else if (roll < 0.15 + luck * 0.55) rarityRoll = "EPIC";
        else if (roll < 0.45 + luck) rarityRoll = "RARE";
        else rarityRoll = "COMMON";
        return coreResonance.map(value -> DungeonCoreResonanceRules.enforceMinimumRarity(rarityRoll, value))
                .orElse(rarityRoll);
    }

    private int rollSlots(String rolledRarity, Material base,
                          Optional<DungeonCoreResonanceRules.Resonance> coreResonance,
                          RandomGenerator operationRandom) {
        int lower = coreResonance.map(value -> DungeonCoreResonanceRules.minimumSlots(rolledRarity, value))
                .orElseGet(() -> ForgeRules.slotLowerBound(rolledRarity));
        int upper = ForgeRules.slotUpperBound(rolledRarity, base.name());
        return operationRandom.nextInt(lower, upper + 1);
    }

    private String rollRareTrait(RandomGenerator operationRandom) {
        var section = java.util.Objects.requireNonNull(
                plugin.getConfig().getConfigurationSection("rare-traits.weights"));
        java.util.Map<String, Integer> weights = new java.util.HashMap<>();
        for (String id : section.getKeys(false)) weights.put(id, section.getInt(id));
        long total = weights.values().stream().mapToLong(value -> Math.min(10_000, value)).sum();
        return RareTraitRules.select(weights, operationRandom.nextLong(total));
    }

    private static ItemStack amountCopy(ItemStack item, int amount) {
        ItemStack copy = item.clone();
        copy.setAmount(amount);
        return copy;
    }

    private static void consume(ItemStack item, int amount) {
        item.setAmount(Math.max(0, item.getAmount() - amount));
    }

    private static void dropOwned(Player player, ItemStack item) {
        Item dropped = player.getWorld().dropItemNaturally(player.getLocation(), item);
        dropped.setOwner(player.getUniqueId());
    }

    private void bindAndGain(Player player, ItemStack item, int gained) {
        grantUseExperience(player, item, gained);
    }

    @Override
    public boolean grantUseExperience(Player player, ItemStack item, int amount) {
        if (amount <= 0 || item == null || item.getType().isAir() || !security.isCustomEquipment(item)) return false;
        Optional<EquipmentSnapshot> inspected = security.inspect(item);
        if (inspected.isEmpty() || inspected.get().boundOwner().isPresent()
                && !inspected.get().boundOwner().orElseThrow().equals(player.getUniqueId())) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        bind(player, item, meta);
        gainEquipmentExperience(player, item, amount);
        return true;
    }

    private void bind(Player player, ItemStack item, ItemMeta meta) {
        PersistentDataContainer data = meta.getPersistentDataContainer();
        if (data.has(owner)) return;
        data.set(owner, PersistentDataType.STRING, player.getUniqueId().toString());
        data.remove(tradeUnlocked);
        item.setItemMeta(meta);
        security.sign(item);
        player.sendMessage(Component.text(messages.text(player, "items.bound"), NamedTextColor.YELLOW));
    }

    private void gainEquipmentExperience(Player player, ItemStack item, int gained) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !security.isAuthentic(item)) return;
        PersistentDataContainer data = meta.getPersistentDataContainer();
        int oldExperience = data.getOrDefault(itemExperience, PersistentDataType.INTEGER, 0);
        int newExperience = (int) Math.min(Integer.MAX_VALUE, (long) oldExperience + gained);
        int total = data.getOrDefault(totalSlots, PersistentDataType.INTEGER, 0);
        int previous = data.getOrDefault(revealedSlots, PersistentDataType.INTEGER, 0);
        int revealed = EquipmentRevelationRules.afterExperienceGain(
                previous, newExperience, total, plugin.getConfig().getIntegerList("revelation-xp"));
        data.set(itemExperience, PersistentDataType.INTEGER, newExperience);
        data.set(revealedSlots, PersistentDataType.INTEGER, revealed);
        renderLore(player, meta, data, item.getType());
        item.setItemMeta(meta);
        security.sign(item);
        if (revealed > previous) {
            player.sendMessage(Component.text(messages.text(player, "items.slot-revealed"), NamedTextColor.LIGHT_PURPLE));
            visuals.slotRevealed(player, revealed, total,
                    revealed == total && data.has(rareTrait, PersistentDataType.STRING));
        }
    }

    private static boolean isForgeable(Material material) {
        return WEAPONS.contains(material) || ARMOR.contains(material);
    }

    private boolean validForgeBase(ItemStack base) {
        return base != null && isForgeable(base.getType()) && !security.isCustomEquipment(base);
    }

    private static NamespacedKey[] refinedSlotKeys() {
        NamespacedKey[] keys = new NamespacedKey[8];
        for (int index = 0; index < keys.length; index++) {
            keys[index] = java.util.Objects.requireNonNull(
                    NamespacedKey.fromString("rpgenhancement:slot_" + index));
        }
        return keys;
    }

    private boolean isCoreMaterial(Player player, ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer data = meta.getPersistentDataContainer();
        // G-112 命名空间门：核心材料的每个外部标记都必须落在本服权威命名空间内。
        // 注意不能用 AuthoritativeItemPolicy.isAuthoritative(item)（那是 anyMatch，
        // 只要有一个权威标记就返回 true），否则「第三方标记 + 一个权威标记」的
        // 伪造材料会被放行。这里用逐 key 的 allMatch（见 authoritativeNamespaceOnly）。
        if (!authoritativeNamespaceOnly(item)) return false;
        String dungeonSource = bossMaterial == null ? null
                : data.get(bossMaterial, PersistentDataType.STRING);
        boolean marked = adaptiveMaterial != null && data.has(adaptiveMaterial, PersistentDataType.BYTE)
                || huntMaterial != null && data.has(huntMaterial, PersistentDataType.INTEGER)
                || dungeonSource != null && DungeonCoreResonanceRules.inspect(dungeonSource).isPresent()
                || forgeEssence != null && data.has(forgeEssence, PersistentDataType.BYTE);
        if (!marked) return false;
        String ownerId = materialOwner == null ? null : data.get(materialOwner, PersistentDataType.STRING);
        return ownerId == null || player != null && player.getUniqueId().toString().equals(ownerId);
    }

    /**
     * 物品的每个 PDC key 都必须属于本服权威命名空间（allMatch，fail-closed）。
     * 空 PDC 也返回 true：无标记的普通物品在 {@link #isCoreMaterial} 里随后
     * 会因为「没有任何核心标记」被拒，命名空间门不负责这一层判定。
     */
    private static boolean authoritativeNamespaceOnly(ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().getKeys().stream()
                .allMatch(AuthoritativeItemPolicy::isAuthoritativeKey);
    }

    private Optional<DungeonCoreResonanceRules.Resonance> inspectDungeonCore(ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        if (meta == null || bossMaterial == null) return Optional.empty();
        return DungeonCoreResonanceRules.inspect(meta.getPersistentDataContainer().get(
                bossMaterial, PersistentDataType.STRING));
    }

    private StarterForgePolicy.Assessment starterAssessment(Player player, ItemStack base, ItemStack core) {
        if (player == null || base == null || core == null || starterBaseOwner == null
                || starterCoreOwner == null || starterArchetype == null) {
            return StarterForgePolicy.assess(new UUID(0L, 0L), null, null, null);
        }
        ItemMeta baseMeta = base.getItemMeta();
        ItemMeta coreMeta = core.getItemMeta();
        if (baseMeta == null || coreMeta == null) {
            return StarterForgePolicy.assess(player.getUniqueId(), null, null, null);
        }
        return StarterForgePolicy.assess(player.getUniqueId(),
                baseMeta.getPersistentDataContainer().get(starterBaseOwner, PersistentDataType.STRING),
                coreMeta.getPersistentDataContainer().get(starterCoreOwner, PersistentDataType.STRING),
                baseMeta.getPersistentDataContainer().get(starterArchetype, PersistentDataType.STRING));
    }

    private Optional<String> starterDirection(ItemStack base, Player player) {
        if (base == null || player == null || starterArchetype == null || starterBaseOwner == null) {
            return Optional.empty();
        }
        ItemMeta meta = base.getItemMeta();
        if (meta == null || !player.getUniqueId().toString().equals(meta.getPersistentDataContainer().get(
                starterBaseOwner, PersistentDataType.STRING))) return Optional.empty();
        return Optional.ofNullable(meta.getPersistentDataContainer().get(
                starterArchetype, PersistentDataType.STRING));
    }

    private boolean isCatalyst(ItemStack item) {
        if (item == null || item.getType().isAir()) return true;
        int minimum = Math.max(1, plugin.getConfig().getInt("forge.catalyst-cost", 1));
        return item.getAmount() >= minimum && plugin.getConfig().getStringList("forge.catalyst-materials")
                .stream().map(Material::matchMaterial).anyMatch(item.getType()::equals) && !item.hasItemMeta();
    }

    private Optional<ForgeImprint> inspectImprint(Player player, ItemStack item) {
        if (!present(item) || imprints == null || player == null) return Optional.empty();
        return imprints.inspect(item, player.getUniqueId());
    }

    private Optional<LegacyBlueprint> inspectBlueprint(Player player, ItemStack item, Material base) {
        if (!present(item) || player == null || base == null) return Optional.empty();
        String type = ARMOR.contains(base) ? "ARMOR" : "WEAPON";
        return blueprints.inspect(item, player.getUniqueId())
                .filter(value -> LegacyBlueprintRules.compatible(value, type));
    }

    private double inheritanceChance(Optional<DungeonCoreResonanceRules.Resonance> coreResonance) {
        double ordinary = Math.clamp(plugin.getConfig().getDouble(
                "legacy.inheritance-chance", 0.18), 0.01, 0.50);
        return coreResonance.map(value -> DungeonCoreResonanceRules.inheritanceChance(ordinary, value))
                .orElse(ordinary);
    }

    private double rareTraitChance(Optional<DungeonCoreResonanceRules.Resonance> coreResonance) {
        double ordinary = Math.clamp(plugin.getConfig().getDouble(
                "rare-traits.random-roll-chance", 0.015), 0.0, 0.10);
        return coreResonance.map(value -> DungeonCoreResonanceRules.rareTraitChance(ordinary, value))
                .orElse(ordinary);
    }

    private String coreResonancePreview(Player audience, DungeonCoreResonanceRules.Resonance resonance,
                                        Material base, int catalystAmount) {
        double luck = DungeonCoreResonanceRules.rarityLuck(
                ForgeRules.rarityLuck(base.name(), catalystAmount), resonance);
        String floorRarity = DungeonCoreResonanceRules.enforceMinimumRarity("COMMON", resonance);
        return messages.text(audience, "items.core-resonance-effects." + resonance.id(), Map.of(
                "success", Math.round(resonance.successBonus() * 100.0),
                "minimumRarity", rarityName(audience, floorRarity),
                "minimumSlots", DungeonCoreResonanceRules.minimumSlots(floorRarity, resonance),
                "trait", String.format(Locale.ROOT, "%.1f",
                        DungeonCoreResonanceRules.rareTraitChance(
                                Math.clamp(plugin.getConfig().getDouble(
                                        "rare-traits.random-roll-chance", 0.015), 0.0, 0.10), resonance) * 100.0),
                "inheritance", String.format(Locale.ROOT, "%.1f",
                        DungeonCoreResonanceRules.inheritanceChance(
                                Math.clamp(plugin.getConfig().getDouble(
                                        "legacy.inheritance-chance", 0.18), 0.01, 0.50), resonance) * 100.0),
                "epic", String.format(Locale.ROOT, "%.1f",
                        (floorRarity.equals("EPIC") || floorRarity.equals("LEGENDARY")
                                ? 1.0 : 0.15 + luck * 0.55) * 100.0),
                "legendary", String.format(Locale.ROOT, "%.1f", (0.03 + luck * 0.20) * 100.0)));
    }

    private static boolean present(ItemStack item) {
        return item != null && !item.getType().isAir();
    }

    private static double biasedStatChance(int strength) {
        int bonus = Math.clamp(strength, 1, 3) * 75;
        return (100.0 + bonus) / (800.0 + bonus);
    }

    private int catalystUsed(ItemStack catalyst) {
        if (catalyst == null || catalyst.getType().isAir()) return 0;
        return Math.min(catalyst.getAmount(), Math.max(1,
                plugin.getConfig().getInt("forge.maximum-catalyst-cost", 8)));
    }

    private static String archetypeFor(Material material) {
        if (ARMOR.contains(material)) return "UNIVERSAL";
        if (material == Material.BOW || material == Material.CROSSBOW || material == Material.TRIDENT) return "RANGER";
        if (material == Material.BLAZE_ROD || material.name().endsWith("_HOE")) return "MAGE";
        if (material == Material.BREEZE_ROD) return "HEALER";
        if (material == Material.MACE || material.name().endsWith("_AXE")) return "WARRIOR";
        return "ASSASSIN";
    }

    private static String modelFor(Material material) {
        if (material == Material.SHIELD) return "forged_shield";
        if (material == Material.BOW) return "forged_ranger_bow";
        if (material == Material.CROSSBOW) return "forged_ranger_crossbow";
        if (material == Material.TRIDENT) return "forged_ranger_trident";
        if (material.name().endsWith("_HELMET") || material == Material.TURTLE_HELMET) return "forged_helmet";
        if (material.name().endsWith("_CHESTPLATE")) return "forged_chestplate";
        if (material.name().endsWith("_LEGGINGS")) return "forged_leggings";
        if (material.name().endsWith("_BOOTS")) return "forged_boots";
        return "forged_" + archetypeFor(material).toLowerCase(Locale.ROOT) + "_weapon";
    }

    private static NamespacedKey itemModelFor(Material material) {
        return new NamespacedKey("originalrpg", modelFor(material));
    }

    private String directionName(Player audience, String direction) {
        return messages.text(audience, direction.equals("UNIVERSAL")
                ? "items.universal" : "classes." + direction.toLowerCase(Locale.ROOT));
    }

    private static ItemStack named(Material material, String text) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(text, NamedTextColor.GRAY));
        item.setItemMeta(meta);
        return item;
    }

    private static NamedTextColor rarityColor(String rarity) {
        return switch (rarity) {
            case "LEGENDARY" -> NamedTextColor.GOLD;
            case "EPIC" -> NamedTextColor.LIGHT_PURPLE;
            case "RARE" -> NamedTextColor.AQUA;
            default -> NamedTextColor.WHITE;
        };
    }

    private String rarityName(Player audience, String rarity) {
        return messages.text(audience, "item-rarities." + rarity.toLowerCase(Locale.ROOT));
    }

    private int nextRevelationThreshold(int revealed) {
        List<Integer> thresholds = plugin.getConfig().getIntegerList("revelation-xp");
        if (thresholds.isEmpty()) return 0;
        return thresholds.get(Math.clamp(revealed, 0, thresholds.size() - 1));
    }

    private String creatorName(Player audience, String value) {
        try {
            String name = value == null ? null : plugin.getServer().getOfflinePlayer(UUID.fromString(value)).getName();
            return name == null || name.isBlank()
                    ? messages.text(audience, "items.lore.crafter-unknown") : name;
        } catch (IllegalArgumentException exception) {
            return messages.text(audience, "items.lore.crafter-unknown");
        }
    }

    private void refreshInventory(Player player) {
        if (!player.isOnline()) return;
        for (ItemStack item : player.getInventory().getContents()) refreshDisplay(player, item);
        for (ItemStack item : player.getEnderChest().getContents()) refreshDisplay(player, item);
    }

    @Override
    public boolean refreshDisplay(Player audience, ItemStack item) {
        if (audience == null || item == null || item.getType().isAir() || !security.isAuthentic(item)) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer data = meta.getPersistentDataContainer();
        Component currentName = meta.customName();
        String plainName = currentName == null ? "" : PLAIN.serialize(currentName);
        if (currentName == null || plainName.endsWith("的自制装备")
                || plainName.endsWith("'s Forged Equipment")) {
            String direction = data.getOrDefault(weaponArchetype, PersistentDataType.STRING, "UNIVERSAL");
            String itemRarity = data.getOrDefault(rarity, PersistentDataType.STRING, "COMMON");
            meta.displayName(Component.text(messages.text(audience,
                    "items.forged-names." + defaultNameId(item.getType(), direction)), rarityColor(itemRarity)));
        }
        renderLore(audience, meta, data, item.getType());
        item.setItemMeta(meta);
        security.preserveDurability(item);
        return true;
    }

    private static String defaultNameId(Material material, String direction) {
        if (material == Material.SHIELD) return "shield";
        if (material.name().endsWith("_HELMET") || material == Material.TURTLE_HELMET) return "helmet";
        if (material.name().endsWith("_CHESTPLATE")) return "chestplate";
        if (material.name().endsWith("_LEGGINGS")) return "leggings";
        if (material.name().endsWith("_BOOTS")) return "boots";
        return switch (direction == null ? "" : direction.toUpperCase(Locale.ROOT)) {
            case "HEALER" -> "healer";
            case "WARRIOR" -> "warrior";
            case "ASSASSIN" -> "assassin";
            case "MAGE" -> "mage";
            case "RANGER" -> "ranger";
            case "FREEFORM" -> "freeform";
            default -> "weapon";
        };
    }
}
