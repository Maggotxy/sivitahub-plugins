package cn.originalrpg.hub;

import cn.originalrpg.api.model.VisualMode;
import cn.originalrpg.api.model.PlayerProfile;
import cn.originalrpg.api.model.JourneyProgress;
import cn.originalrpg.api.model.JourneyStep;
import cn.originalrpg.api.service.GuideMenuService;
import cn.originalrpg.api.service.JourneyService;
import cn.originalrpg.api.service.ProfileService;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

final class HubNpcManager {
    private static final int ENTITY_SCHEMA = 3;
    private static final String KIND_MANNEQUIN = "mannequin";
    private static final String KIND_LABEL = "label";

    private final Plugin plugin;
    private final GuideMenuService guideMenus;
    private final ProfileService profiles;
    private final JourneyService journeys;
    private final HubMessages hubMessages;
    private final AuthenticationSession authentication;
    private final NamespacedKey managedKey;
    private final NamespacedKey roleKey;
    private final NamespacedKey kindKey;
    private final NamespacedKey schemaKey;
    private final NamespacedKey observedHubKey = NamespacedKey.fromString("rpgguide:observed_hub");
    private final Map<UUID, Long> interactionCooldown = new ConcurrentHashMap<>();
    private final Map<UUID, Long> dialogGenerations = new ConcurrentHashMap<>();
    private final Map<HubRole, ManagedNpcPair> entities = new EnumMap<>(HubRole.class);
    private final HubNpcDisplayPolicy displayPolicy;
    private HubSite activeSite;
    private org.bukkit.World ticketWorld;
    private BukkitTask motionTask;
    private BukkitTask integrityTask;

    HubNpcManager(
            Plugin plugin,
            GuideMenuService guideMenus,
            ProfileService profiles,
            JourneyService journeys,
            HubMessages messages, AuthenticationSession authentication
    ) {
        this.plugin = plugin;
        this.guideMenus = guideMenus;
        this.profiles = profiles;
        this.journeys = journeys;
        this.hubMessages = messages;
        this.authentication = authentication;
        managedKey = new NamespacedKey(plugin, "managed_npc");
        roleKey = new NamespacedKey(plugin, "npc_role");
        kindKey = new NamespacedKey(plugin, "npc_kind");
        schemaKey = new NamespacedKey(plugin, "npc_schema");
        displayPolicy = HubNpcDisplayPolicy.load(plugin.getConfig());
    }

    int ensure(HubSite site) {
        if (activeSite != null) removeManaged(activeSite);
        releaseChunkTickets();
        activeSite = site;
        if (!holdNpcChunks(site)) {
            plugin.getLogger().warning("Hub NPC chunks are not loaded yet; actor restoration will retry shortly.");
            return 0;
        }
        removeManaged(site);
        entities.clear();
        int spawned = 0;
        for (HubRole role : HubRole.values()) {
            try {
                entities.put(role, spawn(site, role));
                spawned++;
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Could not create hub NPC " + role.id() + ": " + exception.getMessage());
            }
        }
        startMotionTask();
        return spawned;
    }

    void close() {
        if (motionTask != null) motionTask.cancel();
        if (integrityTask != null) integrityTask.cancel();
        motionTask = null;
        integrityTask = null;
        if (activeSite != null) removeManaged(activeSite);
        releaseChunkTickets();
        activeSite = null;
        entities.clear();
        interactionCooldown.clear();
        dialogGenerations.clear();
    }

    void revoke(Player player) {
        if (player == null) return;
        interactionCooldown.remove(player.getUniqueId());
        dialogGenerations.remove(player.getUniqueId());
        player.closeDialog();
    }

    int count(HubSite site) {
        int complete = 0;
        for (HubRole role : HubRole.values()) {
            ManagedNpcPair pair = entities.get(role);
            if (pair != null && healthy(site, role, pair)) complete++;
        }
        return complete;
    }

    boolean managed(Entity entity) {
        return entity.getPersistentDataContainer().has(managedKey, PersistentDataType.BYTE);
    }

    boolean interact(Player player, Entity entity, HubTravel travel) {
        String id = entity.getPersistentDataContainer().get(roleKey, PersistentDataType.STRING);
        if (id == null) return false;
        if (!authentication.authenticated(player.getUniqueId())) return true;
        long now = System.nanoTime();
        Long previous = interactionCooldown.put(player.getUniqueId(), now);
        if (previous != null && now - previous < 450_000_000L) return true;
        HubRole role;
        try {
            role = HubRole.valueOf(id.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            player.sendMessage(Component.text(hubMessages.text(player, "npc-unavailable"), NamedTextColor.RED));
            return true;
        }
        feedback(player, entity);
        openConversation(player, role, travel);
        return true;
    }

    void openConversation(Player player, HubRole role, HubTravel travel) {
        if (!authentication.authenticated(player.getUniqueId())) return;
        dialogGenerations.put(player.getUniqueId(), authentication.token(player.getUniqueId()));
        if (role == HubRole.GUIDE && observedHubKey != null
                && !player.getPersistentDataContainer().has(observedHubKey, PersistentDataType.BYTE)) {
            player.getPersistentDataContainer().set(observedHubKey, PersistentDataType.BYTE, (byte) 1);
            player.saveData();
            player.sendMessage(Component.text(hubMessages.text(player, "guide-observed"), NamedTextColor.GREEN));
        }
        journeys.refresh(player);
        showConversation(player, role, travel);
    }

    private ManagedNpcPair spawn(HubSite site, HubRole role) {
        Location location = npcLocation(site, role);
        Mannequin mannequin = null;
        TextDisplay label = null;
        try {
            mannequin = site.world().spawn(location, Mannequin.class, npc -> configureMannequin(npc, role));
            mannequin.setRotation(role.yaw(), 0.0F);

            String name = HubNpcLabelPolicy.label(npcName(role), role.id());
            Location labelLocation = labelLocation(site, role);
            label = site.world().spawn(labelLocation, TextDisplay.class,
                    textDisplay -> configureLabel(textDisplay, role, name));
            return new ManagedNpcPair(mannequin.getUniqueId(), label.getUniqueId());
        } catch (RuntimeException exception) {
            if (label != null) label.remove();
            if (mannequin != null) mannequin.remove();
            throw exception;
        }
    }

    private void configureMannequin(Mannequin npc, HubRole role) {
        mark(npc, role, KIND_MANNEQUIN);
        UUID profileId = UUID.nameUUIDFromBytes(("sivita-hub:" + role.id()).getBytes(StandardCharsets.UTF_8));
        npc.setProfile(ResolvableProfile.resolvableProfile(
                Bukkit.createProfileExact(profileId, "S_" + role.id())));
        // null hides the native 26.2 mannequin interaction description. An
        // empty component still creates a description render layer and was
        // colliding with the dedicated TextDisplay on clients.
        npc.setDescription(null);
        npc.customName(null);
        npc.setCustomNameVisible(false);
        npc.setImmovable(true);
        npc.setAI(false);
        npc.setGravity(false);
        npc.setInvulnerable(true);
        npc.setSilent(true);
        npc.setPersistent(true);
        npc.setRemoveWhenFarAway(false);
        npc.setCollidable(false);
        npc.setCanPickupItems(false);
        equip(npc, role);
    }

    private void configureLabel(TextDisplay label, HubRole role, String name) {
        mark(label, role, KIND_LABEL);
        // World-space text is an identity plate, not a help panel. Descriptions
        // remain available to squaremap and the interaction Dialog without
        // forcing every nearby client to render another line above the actor.
        label.text(Component.text(name, NamedTextColor.GOLD));
        label.setBillboard(org.bukkit.entity.Display.Billboard.VERTICAL);
        label.setAlignment(TextDisplay.TextAlignment.CENTER);
        label.setDefaultBackground(false);
        label.setBackgroundColor(org.bukkit.Color.fromARGB(56, 0, 0, 0));
        label.setLineWidth(displayPolicy.lineWidthPixels());
        label.setTextOpacity((byte) -1);
        label.setShadowed(true);
        label.setSeeThrough(false);
        label.setViewRange(displayPolicy.viewRangeMultiplier());
        label.setDisplayWidth(HubNpcDisplayPolicy.LABEL_DISPLAY_WIDTH);
        label.setDisplayHeight(HubNpcDisplayPolicy.SINGLE_LINE_DISPLAY_HEIGHT);
        label.setGravity(false);
        label.setInvulnerable(true);
        label.setSilent(true);
        label.setPersistent(true);
        Transformation transformation = label.getTransformation();
        label.setTransformation(new Transformation(
                transformation.getTranslation(),
                transformation.getLeftRotation(),
                new Vector3f(displayPolicy.scale()),
                transformation.getRightRotation()));
    }

    private void mark(Entity entity, HubRole role, String kind) {
        entity.getPersistentDataContainer().set(managedKey, PersistentDataType.BYTE, (byte) 1);
        entity.getPersistentDataContainer().set(roleKey, PersistentDataType.STRING, role.id());
        entity.getPersistentDataContainer().set(kindKey, PersistentDataType.STRING, kind);
        entity.getPersistentDataContainer().set(schemaKey, PersistentDataType.INTEGER, ENTITY_SCHEMA);
    }

    private void equip(Mannequin npc, HubRole role) {
        EntityEquipment equipment = npc.getEquipment();
        ItemStack chest = leather(Material.LEATHER_CHESTPLATE, role.color());
        ItemStack legs = leather(Material.LEATHER_LEGGINGS, role.color());
        ItemStack boots = leather(Material.LEATHER_BOOTS, org.bukkit.Color.fromRGB(48, 52, 45));
        equipment.setChestplate(chest, true);
        equipment.setLeggings(legs, true);
        equipment.setBoots(boots, true);
        equipment.setItemInMainHand(new ItemStack(role.heldItem()), true);
        // Mannequins are invulnerable non-Mob entities. Paper exposes an
        // equipment view for them but deliberately rejects Mob drop chances.
    }

    private ItemStack leather(Material material, org.bukkit.Color color) {
        ItemStack item = new ItemStack(material);
        if (item.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(color);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void feedback(Player player, Entity entity) {
        Location location = entity.getLocation();
        if (entity instanceof Mannequin mannequin) {
            mannequin.swingMainHand();
            face(mannequin, player);
        }
        VisualMode mode = VisualMode.forPlayer(player);
        int particles = mode.particles(8);
        if (particles > 0) player.spawnParticle(Particle.ENCHANT, location.clone().add(0.0, 1.2, 0.0),
                particles, 0.35, 0.55, 0.35, 0.02);
        float volume = mode.volume(0.45F);
        if (volume > 0.0F) player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING,
                SoundCategory.PLAYERS, volume, 1.35F);
    }

    private int removeManaged(HubSite site) {
        Set<UUID> removed = new HashSet<>();
        for (ManagedNpcPair pair : entities.values()) {
            removeEntity(pair.mannequinId(), removed);
            removeEntity(pair.labelId(), removed);
        }
        Location center = new Location(site.world(), site.centerX(), site.baseY(), site.centerZ());
        double radius = site.protectionRadius() + 8.0;
        for (Entity entity : site.world().getNearbyEntities(center, radius, 48.0, radius)) {
            if (managed(entity) && removed.add(entity.getUniqueId())) entity.remove();
        }
        return removed.size();
    }

    private void removeEntity(UUID id, Set<UUID> removed) {
        Entity entity = Bukkit.getEntity(id);
        if (entity != null && removed.add(id)) entity.remove();
    }

    private void showConversation(Player player, HubRole role, HubTravel travel) {
        if (role == HubRole.AI) {
            showAiConversation(player);
            return;
        }
        String name = npcName(role);
        JourneyProgress journey = journeys.progress(player.getUniqueId());
        JourneyStep next = journey.nextStep().orElse(null);
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text(
                hubMessages.text(player, "npc-dialog.roles." + role.id() + ".summary"), NamedTextColor.WHITE)));
        body.add(DialogBody.plainMessage(Component.text(progress(player), NamedTextColor.DARK_GRAY)));
        if (next == null) {
            body.add(DialogBody.plainMessage(Component.text(
                    hubMessages.text(player, "npc-dialog.journey.complete"), NamedTextColor.GREEN)));
            body.add(DialogBody.plainMessage(Component.text(
                    hubMessages.text(player, "npc-dialog.journey.complete-next"), NamedTextColor.GRAY)));
        } else {
            String prefix = "npc-dialog.journey.steps." + next.id() + ".";
            body.add(DialogBody.plainMessage(Component.text(
                    hubMessages.text(player, "npc-dialog.journey.current", "step", stepName(player, next)),
                    NamedTextColor.GOLD)));
            body.add(DialogBody.plainMessage(Component.text(
                    hubMessages.text(player, prefix + "goal"), NamedTextColor.WHITE)));
            body.add(DialogBody.plainMessage(Component.text(
                    hubMessages.text(player, prefix + "where"), NamedTextColor.AQUA)));
            body.add(DialogBody.plainMessage(Component.text(
                    hubMessages.text(player, prefix + "why"), NamedTextColor.GRAY)));
        }
        List<ActionButton> actions = new ArrayList<>();
        actions.add(recommendedButton(player, next, travel));
        if (next != null) {
            actions.add(ActionButton.builder(Component.text(
                            hubMessages.text(player, "npc-dialog.journey.open-details"), NamedTextColor.GOLD))
                    .tooltip(Component.text(hubMessages.text(player,
                            "npc-dialog.journey.open-details-tooltip")))
                    .width(150)
                    .action(callback(ignored -> guideMenus.open(player, GuideMenuService.Section.CURRENT)))
                    .build());
        }
        if (!HubJourneyPolicy.roleAlreadyServes(role, next)) {
            actions.add(roleServiceButton(player, role, travel));
        }
        actions.add(button(player, "npc-dialog.responsibility", ignored -> showNotice(player, role, false)));
        actions.add(ActionButton.create(Component.text(hubMessages.text(player, "npc-dialog.leave")),
                Component.text(hubMessages.text(player, "npc-dialog.leave-tooltip")), 110, null));
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text(name, NamedTextColor.GOLD))
                        .externalTitle(Component.text(name))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(body)
                        .build())
                .type(DialogType.multiAction(actions).columns(2).build()));
        player.showDialog(dialog);
    }

    private void showAiConversation(Player player) {
        String name = npcName(HubRole.AI);
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text(name, NamedTextColor.AQUA))
                        .externalTitle(Component.text(name))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(List.of(
                                DialogBody.plainMessage(Component.text(
                                        hubMessages.text(player, "npc-dialog.ai-intro"), NamedTextColor.GRAY)),
                                DialogBody.plainMessage(Component.text(
                                        hubMessages.text(player, "npc-dialog.ai-boundary"), NamedTextColor.DARK_GRAY))))
                        .inputs(List.of(DialogInput.text("question",
                                        Component.text(hubMessages.text(player, "npc-dialog.ai-question")))
                                .width(360)
                                .maxLength(240)
                                .build()))
                        .build())
                .type(DialogType.multiAction(List.of(
                        ActionButton.builder(Component.text(
                                        hubMessages.text(player, "npc-dialog.ai-analyze"), NamedTextColor.GREEN))
                                .tooltip(Component.text(hubMessages.text(player,
                                        "npc-dialog.ai-analyze-tooltip")))
                                .width(190)
                                .action(callback(ignored -> player.performCommand("ainpc analyze")))
                                .build(),
                        ActionButton.builder(Component.text(hubMessages.text(player, "npc-dialog.ai-submit")))
                                .tooltip(Component.text(hubMessages.text(player, "npc-dialog.ai-submit-tooltip")))
                                .width(150)
                                .action(DialogAction.customClick((response, audience) -> {
                                    if (!(audience instanceof Player responder)) return;
                                    UUID playerId = responder.getUniqueId();
                                    Long generation = dialogGenerations.get(playerId);
                                    String question = response.getText("question");
                                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                                        if (generation != null && responder.isOnline()
                                                && authentication.valid(playerId, generation)
                                                && question != null && !question.isBlank()) {
                                            responder.performCommand("ainpc ask " + question.strip());
                                        } else if (generation != null && responder.isOnline()
                                                && authentication.valid(playerId, generation)) {
                                            responder.sendMessage(Component.text(
                                                    hubMessages.text(responder, "npc-dialog.ai-empty"), NamedTextColor.YELLOW));
                                        }
                                    });
                                }, callbackOptions()))
                                .build(),
                        ActionButton.builder(Component.text(hubMessages.text(player, "npc-dialog.ai-chat-mode")))
                                .tooltip(Component.text(hubMessages.text(player, "npc-dialog.ai-chat-mode-tooltip")))
                                .width(150)
                                .action(callback(ignored -> player.performCommand("ainpc")))
                                .build(),
                        ActionButton.create(Component.text(hubMessages.text(player, "npc-dialog.leave")),
                                Component.text(hubMessages.text(player, "npc-dialog.leave-tooltip")), 110, null)
                )).columns(2).build()));
        player.showDialog(dialog);
    }

    private ActionButton button(Player player, String key, Consumer<Player> action) {
        return ActionButton.builder(Component.text(hubMessages.text(player, key)))
                .tooltip(Component.text(hubMessages.text(player, key + "-tooltip")))
                .width(150)
                .action(callback(action))
                .build();
    }

    private ActionButton recommendedButton(Player player, JourneyStep next, HubTravel travel) {
        String label = next == null
                ? hubMessages.text(player, "npc-dialog.journey.open-freeplay")
                : hubMessages.text(player, "npc-dialog.journey.continue", "step", stepName(player, next));
        String tooltip = next == null
                ? hubMessages.text(player, "npc-dialog.journey.open-freeplay-tooltip")
                : hubMessages.text(player, "npc-dialog.journey.steps." + next.id() + ".where");
        return ActionButton.builder(Component.text(label, NamedTextColor.GREEN))
                .tooltip(Component.text(tooltip))
                .width(190)
                .action(callback(ignored -> openJourneyStep(player, next, travel)))
                .build();
    }

    private ActionButton roleServiceButton(Player player, HubRole role, HubTravel travel) {
        String key = "npc-dialog.roles." + role.id() + ".service";
        return ActionButton.builder(Component.text(hubMessages.text(player, key), NamedTextColor.AQUA))
                .tooltip(Component.text(hubMessages.text(player, "npc-dialog.open-service-tooltip")))
                .width(150)
                .action(callback(ignored -> openService(player, role, travel)))
                .build();
    }

    private DialogAction callback(Consumer<Player> action) {
        return DialogAction.customClick((response, audience) -> {
            if (!(audience instanceof Player player)) return;
            UUID playerId = player.getUniqueId();
            Long generation = dialogGenerations.get(playerId);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (generation != null && player.isOnline()
                        && authentication.valid(playerId, generation)) action.accept(player);
            });
        }, callbackOptions());
    }

    private ClickCallback.Options callbackOptions() {
        return ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build();
    }

    private void openService(Player player, HubRole role, HubTravel travel) {
        if (role.section() != null) {
            guideMenus.open(player, role.section());
        } else if ("wild".equals(role.command())) {
            travel.toWilderness(player);
        } else if (role.command() != null && !player.performCommand(role.command())) {
            player.sendMessage(Component.text(hubMessages.text(player, "npc-unavailable"), NamedTextColor.RED));
        }
    }

    private void openJourneyStep(Player player, JourneyStep step, HubTravel travel) {
        switch (HubJourneyPolicy.target(step)) {
            case MAIN_MENU -> guideMenus.open(player, GuideMenuService.Section.MAIN);
            case CLASS_MENU -> guideMenus.open(player, GuideMenuService.Section.CLASS);
            case WILDERNESS -> travel.toWilderness(player);
            case FORGE -> player.performCommand("forge");
            case COMBAT_GUIDE -> player.performCommand("mobguide");
            case DUNGEON_MENU -> guideMenus.open(player, GuideMenuService.Section.DUNGEON);
            case QUEST_CENTER -> guideMenus.open(player, GuideMenuService.Section.QUESTS);
        }
    }

    private void showNotice(Player player, HubRole role, boolean recommendation) {
        String key = "npc-dialog.roles." + role.id() + (recommendation ? ".advice" : ".detail");
        Dialog notice = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text(npcName(role), NamedTextColor.GOLD))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .body(List.of(DialogBody.plainMessage(Component.text(
                                hubMessages.text(player, key), NamedTextColor.GRAY))))
                        .build())
                .type(DialogType.notice(ActionButton.create(
                        Component.text(hubMessages.text(player, "npc-dialog.understood")),
                        Component.empty(), 120, null))));
        player.showDialog(notice);
    }

    private String progress(Player player) {
        PlayerProfile profile = profiles.cached(player.getUniqueId()).orElse(null);
        if (profile == null) return hubMessages.text(player, "npc-dialog.progress-loading");
        return hubMessages.text(player, "npc-dialog.progress", "ascension", profile.ascension(),
                "level", profile.level(), "class", className(player, profile),
                "energy", profile.energy(), "cap", profile.energyCap());
    }

    private String className(Player player, PlayerProfile profile) {
        return hubMessages.text(player, "classes." + profile.archetype().name().toLowerCase(Locale.ROOT));
    }

    private String stepName(Player player, JourneyStep step) {
        return hubMessages.text(player, "npc-dialog.journey.steps." + step.id() + ".name");
    }

    private String npcName(HubRole role) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("npcs." + role.id());
        return section == null ? role.id() : section.getString("name", role.id());
    }

    private void startMotionTask() {
        if (motionTask == null) {
            motionTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
                HubSite site = activeSite;
                // There is no client that can observe mannequin rotation while
                // the hub world is empty. Avoid 22 forced rotation writes per
                // second on an idle server; the first player makes the next
                // pulse restore or track the correct facing immediately.
                if (site == null || site.world().getPlayers().isEmpty()) return;
                for (Map.Entry<HubRole, ManagedNpcPair> entry : entities.entrySet()) {
                    Entity entity = Bukkit.getEntity(entry.getValue().mannequinId());
                    if (!(entity instanceof Mannequin npc) || !npc.isValid()) continue;
                    Location origin = npc.getLocation();
                    Collection<Player> nearby = npc.getWorld().getNearbyPlayers(origin, 6.0,
                            candidate -> candidate.isValid() && !candidate.isInvisible());
                    Player nearest = null;
                    double nearestDistance = Double.POSITIVE_INFINITY;
                    for (Player candidate : nearby) {
                        double distance = candidate.getLocation().distanceSquared(origin);
                        if (distance >= nearestDistance) continue;
                        nearest = candidate;
                        nearestDistance = distance;
                    }
                    if (nearest == null) {
                        rotateIfChanged(npc, origin, entry.getKey().yaw(), 0.0F);
                    } else {
                        face(npc, origin, nearest);
                    }
                }
            }, 20L, 10L);
        }
        if (integrityTask == null) {
            integrityTask = plugin.getServer().getScheduler().runTaskTimer(plugin,
                    this::reconcile, displayPolicy.reconcileTicks(), displayPolicy.reconcileTicks());
        }
    }

    private void reconcile() {
        HubSite site = activeSite;
        if (site == null) return;
        Set<UUID> expected = new HashSet<>();
        for (ManagedNpcPair pair : entities.values()) {
            expected.add(pair.mannequinId());
            expected.add(pair.labelId());
        }
        int repaired = 0;
        Location center = new Location(site.world(), site.centerX(), site.baseY(), site.centerZ());
        double radius = site.protectionRadius() + 8.0;
        for (Entity entity : site.world().getNearbyEntities(center, radius, 48.0, radius)) {
            if (managed(entity) && !expected.contains(entity.getUniqueId())) {
                entity.remove();
                repaired++;
            }
        }
        for (HubRole role : HubRole.values()) {
            ManagedNpcPair pair = entities.get(role);
            if (pair != null && healthy(site, role, pair)) continue;
            if (pair != null && repaired == 0) {
                plugin.getLogger().warning("Hub NPC health diagnostic role=" + role.id() + " "
                        + healthIssue(site, role, pair));
            }
            if (pair != null) {
                Set<UUID> removed = new HashSet<>();
                removeEntity(pair.mannequinId(), removed);
                removeEntity(pair.labelId(), removed);
            }
            try {
                entities.put(role, spawn(site, role));
                repaired++;
            } catch (RuntimeException exception) {
                entities.remove(role);
                plugin.getLogger().warning("Could not repair hub NPC " + role.id() + ": " + exception.getMessage());
            }
        }
        if (repaired > 0) {
            plugin.getLogger().warning("Hub NPC integrity repaired entities=" + repaired);
        }
    }

    private boolean healthy(HubSite site, HubRole role, ManagedNpcPair pair) {
        Entity mannequinEntity = Bukkit.getEntity(pair.mannequinId());
        Entity labelEntity = Bukkit.getEntity(pair.labelId());
        if (!(mannequinEntity instanceof Mannequin npc) || !npc.isValid()
                || !(labelEntity instanceof TextDisplay label) || !label.isValid()) {
            return false;
        }
        if (!marked(npc, role, KIND_MANNEQUIN) || !marked(label, role, KIND_LABEL)) return false;
        if (!near(npc.getLocation(), npcLocation(site, role))
                || !near(label.getLocation(), labelLocation(site, role))) return false;
        // Heal metadata that another plugin or an old persisted entity may
        // have changed without respawning a healthy pair.
        if (npc.getDescription() != null) npc.setDescription(null);
        Component expectedLabel = Component.text(
                HubNpcLabelPolicy.label(npcName(role), role.id()), NamedTextColor.GOLD);
        if (!expectedLabel.equals(label.text())) label.text(expectedLabel);
        if (Math.abs(label.getViewRange() - displayPolicy.viewRangeMultiplier()) > 0.0001F) {
            label.setViewRange(displayPolicy.viewRangeMultiplier());
        }
        return true;
    }

    private String healthIssue(HubSite site, HubRole role, ManagedNpcPair pair) {
        Entity mannequinEntity = Bukkit.getEntity(pair.mannequinId());
        Entity labelEntity = Bukkit.getEntity(pair.labelId());
        if (mannequinEntity == null || labelEntity == null) {
            return "missing mannequin=" + describe(mannequinEntity) + " label=" + describe(labelEntity);
        }
        if (!(mannequinEntity instanceof Mannequin npc) || !(labelEntity instanceof TextDisplay label)) {
            return "wrong-type mannequin=" + describe(mannequinEntity) + " label=" + describe(labelEntity);
        }
        if (!npc.isValid() || !label.isValid()) {
            return "invalid mannequin=" + describe(npc) + " label=" + describe(label);
        }
        if (!marked(npc, role, KIND_MANNEQUIN) || !marked(label, role, KIND_LABEL)) {
            return "metadata mannequin=" + describe(npc) + " label=" + describe(label);
        }
        Location expectedNpc = npcLocation(site, role);
        Location expectedLabel = labelLocation(site, role);
        if (!near(npc.getLocation(), expectedNpc) || !near(label.getLocation(), expectedLabel)) {
            return "location mannequin=" + compact(npc.getLocation()) + " expected=" + compact(expectedNpc)
                    + " label=" + compact(label.getLocation()) + " expectedLabel=" + compact(expectedLabel);
        }
        return "unknown mannequin=" + describe(npc) + " label=" + describe(label);
    }

    private String describe(Entity entity) {
        if (entity == null) return "null";
        Integer schema = entity.getPersistentDataContainer().get(schemaKey, PersistentDataType.INTEGER);
        String role = entity.getPersistentDataContainer().get(roleKey, PersistentDataType.STRING);
        String kind = entity.getPersistentDataContainer().get(kindKey, PersistentDataType.STRING);
        return entity.getType() + "(" + entity.getUniqueId() + ",valid=" + entity.isValid()
                + ",schema=" + schema + ",role=" + role + ",kind=" + kind
                + ",at=" + compact(entity.getLocation()) + ")";
    }

    private String compact(Location location) {
        return String.format(Locale.ROOT, "%s:%.3f,%.3f,%.3f",
                location.getWorld() == null ? "null" : location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ());
    }

    private boolean marked(Entity entity, HubRole role, String kind) {
        Integer schema = entity.getPersistentDataContainer().get(schemaKey, PersistentDataType.INTEGER);
        String storedRole = entity.getPersistentDataContainer().get(roleKey, PersistentDataType.STRING);
        String storedKind = entity.getPersistentDataContainer().get(kindKey, PersistentDataType.STRING);
        return schema != null && schema == ENTITY_SCHEMA && role.id().equals(storedRole) && kind.equals(storedKind);
    }

    private boolean near(Location actual, Location expected) {
        return actual.getWorld() != null && expected.getWorld() != null
                && actual.getWorld().getUID().equals(expected.getWorld().getUID())
                && actual.distanceSquared(expected) <= 0.04;
    }

    private Location npcLocation(HubSite site, HubRole role) {
        return new Location(site.world(), site.centerX() + role.offsetX() + 0.5,
                site.baseY() + 1.05, site.centerZ() + role.offsetZ() + 0.5, role.yaw(), 0.0F);
    }

    private Location labelLocation(HubSite site, HubRole role) {
        return npcLocation(site, role).add(0.0, displayPolicy.labelHeight(), 0.0);
    }

    private boolean holdNpcChunks(HubSite site) {
        ticketWorld = site.world();
        Set<Long> held = new HashSet<>();
        for (HubRole role : HubRole.values()) {
            Location location = npcLocation(site, role);
            int chunkX = location.getBlockX() >> 4;
            int chunkZ = location.getBlockZ() >> 4;
            long key = Chunk.getChunkKey(chunkX, chunkZ);
            if (!held.add(key)) continue;
            // The completed hub owns these chunks. Do not synchronously load or
            // generate them while restoring NPCs; RpgHub prepares them through
            // Paper's async chunk API before calling ensure.
            if (!site.world().isChunkLoaded(chunkX, chunkZ)) return false;
            site.world().addPluginChunkTicket(chunkX, chunkZ, plugin);
        }
        return true;
    }

    private void releaseChunkTickets() {
        if (ticketWorld != null) ticketWorld.removePluginChunkTickets(plugin);
        ticketWorld = null;
    }

    private record ManagedNpcPair(UUID mannequinId, UUID labelId) { }

    private void face(Mannequin npc, Player player) {
        face(npc, npc.getLocation(), player);
    }

    private void face(Mannequin npc, Location origin, Player player) {
        Location facing = origin.clone();
        facing.setDirection(player.getEyeLocation().toVector().subtract(npc.getEyeLocation().toVector()));
        rotateIfChanged(npc, origin, facing.getYaw(), Math.clamp(facing.getPitch(), -30.0F, 30.0F));
    }

    private void rotateIfChanged(Mannequin npc, Location origin, float yaw, float pitch) {
        if (HubNpcFacingPolicy.shouldRotate(origin.getYaw(), origin.getPitch(), yaw, pitch)) {
            npc.setRotation(yaw, pitch);
        }
    }
}
