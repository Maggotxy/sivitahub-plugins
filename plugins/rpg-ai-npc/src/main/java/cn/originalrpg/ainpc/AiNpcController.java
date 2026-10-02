package cn.originalrpg.ainpc;

import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.model.PlayerProfile;
import cn.originalrpg.api.model.CoreAttribute;
import cn.originalrpg.api.model.EquipmentSnapshot;
import cn.originalrpg.api.model.JourneyProgress;
import cn.originalrpg.api.model.JourneyStep;
import cn.originalrpg.api.model.SkillCooldownSnapshot;
import cn.originalrpg.api.model.SkillHudSnapshot;
import cn.originalrpg.api.model.SkillProgress;
import cn.originalrpg.api.service.CombatStateService;
import cn.originalrpg.api.service.EquipmentService;
import cn.originalrpg.api.service.JourneyService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.OptionalServices;
import cn.originalrpg.api.service.ProfileService;
import cn.originalrpg.api.service.SkillHudService;
import cn.originalrpg.api.service.SkillService;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

final class AiNpcController implements CommandExecutor, TabCompleter, Listener {
    private final RpgAiNpcPlugin plugin;
    private final ProfileService profiles;
    private final MessageService messages;
    private final Map<UUID, Long> awaitingChat = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRequests = new ConcurrentHashMap<>();
    private final Map<UUID, RequestToken> inFlight = new ConcurrentHashMap<>();
    private final AiSessionRegistry sessions = new AiSessionRegistry();
    private final java.util.concurrent.atomic.AtomicLong requestSequence =
            new java.util.concurrent.atomic.AtomicLong();
    private final Map<UUID, ConversationHistory> histories = new ConcurrentHashMap<>();
    private volatile int listenSeconds;
    private volatile int cooldownSeconds;
    private volatile int maximumInputCharacters;
    private volatile int historyTurns;
    private volatile int historyTtlMinutes;

    AiNpcController(
            RpgAiNpcPlugin plugin, ProfileService profiles, MessageService messages
    ) {
        this.plugin = plugin;
        this.profiles = profiles;
        this.messages = messages;
        reloadSettings();
    }

    void reloadSettings() {
        listenSeconds = Math.clamp(plugin.getConfig().getInt("conversation.listen-seconds", 60), 15, 180);
        cooldownSeconds = Math.clamp(plugin.getConfig().getInt("conversation.cooldown-seconds", 8), 2, 60);
        maximumInputCharacters = Math.clamp(
                plugin.getConfig().getInt("conversation.maximum-input-characters", 240), 40, 600);
        historyTurns = Math.clamp(plugin.getConfig().getInt("conversation.history-turns", 3), 0, 6);
        historyTtlMinutes = Math.clamp(plugin.getConfig().getInt("conversation.history-ttl-minutes", 10), 1, 60);
        histories.clear();
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args
    ) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.text(sender, "common.player-only"));
            return true;
        }
        if (args.length == 0) {
            begin(player);
            return true;
        }
        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if (subcommand.equals("leave") || subcommand.equals("exit")) {
            boolean removed = awaitingChat.remove(player.getUniqueId()) != null;
            histories.remove(player.getUniqueId());
            player.sendMessage(text(player, removed ? "ai-npc.ended" : "ai-npc.not-listening"));
            return true;
        }
        if (subcommand.equals("status")) {
            status(player);
            return true;
        }
        if (subcommand.equals("analyze") || subcommand.equals("advice")) {
            analyze(player);
            return true;
        }
        if (subcommand.equals("reload")) {
            if (!player.hasPermission("rpg.ainpc.reload")) {
                player.sendMessage(text(player, "ai-npc.no-permission"));
                return true;
            }
            plugin.reloadRuntime();
            player.sendMessage(text(player, "ai-npc.reloaded"));
            return true;
        }
        int firstWord = subcommand.equals("ask") ? 1 : 0;
        if (firstWord >= args.length) {
            player.sendMessage(text(player, "ai-npc.help"));
            return true;
        }
        ask(player, String.join(" ", Arrays.copyOfRange(args, firstWord, args.length)));
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player playerSession = event.getPlayer();
        UUID playerId = playerSession.getUniqueId();
        long generation = sessions.token(playerId);
        if (!sessions.valid(playerId, generation)) return;
        Long expiry = awaitingChat.remove(playerId);
        if (expiry == null || expiry < System.nanoTime()) return;
        event.setCancelled(true);
        String question = PlainTextComponentSerializer.plainText().serialize(event.message());
        plugin.executeOnMain(() -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == playerSession && player.isOnline() && sessions.valid(playerId, generation)) {
                ask(player, question);
            }
        });
    }

    @EventHandler
    public void onAuthenticated(AuthenticatedPlayerReadyEvent event) {
        UUID playerId = event.player().getUniqueId();
        clearVolatileState(playerId);
        sessions.activate(playerId);
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
        sessions.revoke(playerId);
        clearVolatileState(playerId);
    }

    private void clearVolatileState(UUID playerId) {
        awaitingChat.remove(playerId);
        lastRequests.remove(playerId);
        inFlight.remove(playerId);
        histories.remove(playerId);
    }

    void shutdown() {
        sessions.clear();
        awaitingChat.clear();
        lastRequests.clear();
        inFlight.clear();
        histories.clear();
    }

    private void begin(Player player) {
        analyze(player);
        awaitingChat.put(player.getUniqueId(), System.nanoTime() + TimeUnit.SECONDS.toNanos(listenSeconds));
        player.sendMessage(Component.text(messages.text(player, "ai-npc.name"), NamedTextColor.AQUA)
                .append(Component.text("：", NamedTextColor.DARK_GRAY))
                .append(Component.text(messages.text(player, "ai-npc.greeting"), NamedTextColor.GRAY)));
        player.sendMessage(text(player, "ai-npc.listening", Map.of("seconds", listenSeconds)));
        sendSuggestions(player);
    }

    private void sendSuggestions(Player player) {
        Component row = Component.empty();
        Component analyze = Component.text("[" + messages.text(player, "ai-npc.suggestion-analyze") + "]",
                        NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/ainpc analyze"))
                .hoverEvent(HoverEvent.showText(Component.text(
                        messages.text(player, "ai-npc.suggestion-analyze-tooltip"), NamedTextColor.GRAY)));
        row = row.append(analyze).append(Component.space());
        for (String key : List.of("build", "forge", "dungeons")) {
            String question = messages.text(player, suggestionKey(key));
            Component button = Component.text("[" + question + "]", NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.runCommand("/ainpc ask " + question))
                    .hoverEvent(HoverEvent.showText(Component.text(question, NamedTextColor.GRAY)));
            row = row.append(button).append(Component.space());
        }
        player.sendMessage(row);
    }

    private static String suggestionKey(String id) {
        return switch (id) {
            case "build" -> "ai-npc.suggestion-build";
            case "forge" -> "ai-npc.suggestion-forge";
            case "dungeons" -> "ai-npc.suggestion-dungeons";
            default -> throw new IllegalArgumentException("Unknown suggestion: " + id);
        };
    }

    private void ask(Player player, String rawQuestion) {
        UUID playerId = player.getUniqueId();
        awaitingChat.remove(playerId);
        String question = sanitize(rawQuestion);
        if (question.isEmpty()) {
            player.sendMessage(text(player, "ai-npc.empty"));
            return;
        }
        int length = question.codePointCount(0, question.length());
        if (length > maximumInputCharacters) {
            player.sendMessage(text(player, "ai-npc.too-long", Map.of("max", maximumInputCharacters)));
            return;
        }
        long loginGeneration = sessions.token(playerId);
        if (!sessions.valid(playerId, loginGeneration)) return;
        long now = System.nanoTime();
        Long previous = lastRequests.get(playerId);
        if (previous != null) {
            long remainingNanos = TimeUnit.SECONDS.toNanos(cooldownSeconds) - (now - previous);
            if (remainingNanos > 0L) {
                long seconds = Math.max(1L, TimeUnit.NANOSECONDS.toSeconds(remainingNanos) + 1L);
                player.sendMessage(text(player, "ai-npc.cooldown", Map.of("seconds", seconds)));
                return;
            }
        }
        AiNpcRuntime runtime = plugin.runtime();
        RequestToken request = new RequestToken(requestSequence.incrementAndGet(), loginGeneration,
                runtime.generation(), player);
        if (inFlight.putIfAbsent(playerId, request) != null) {
            player.sendMessage(text(player, "ai-npc.busy"));
            return;
        }
        lastRequests.put(playerId, now);
        String locale = messages.locale(player);
        ConversationHistory history = histories.computeIfAbsent(playerId,
                ignored -> new ConversationHistory(historyTurns, Duration.ofMinutes(historyTtlMinutes)));
        AiRequestContext context = new AiRequestContext(locale,
                stateSnapshot(player), history.snapshot(), question);
        player.sendActionBar(text(player, "ai-npc.thinking"));
        long started = System.nanoTime();
        runtime.client().ask(context).whenComplete((answer, error) -> {
            boolean remote = error == null && answer != null && !answer.isBlank();
            String finalAnswer = remote ? answer : runtime.knowledge().answer(locale, question);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            plugin.getLogger().info("AI guidance player=" + playerId + " mode="
                    + (remote ? "remote" : "local") + " input_chars=" + length
                    + " output_chars=" + finalAnswer.codePointCount(0, finalAnswer.length())
                    + " latency_ms=" + elapsedMillis);
            boolean scheduled = plugin.executeOnMain(() -> {
                if (!current(request, runtime)) {
                    inFlight.remove(playerId, request);
                    return;
                }
                inFlight.remove(playerId, request);
                history.add(question, finalAnswer);
                Player online = Bukkit.getPlayer(playerId);
                if (online != request.playerSession() || !online.isOnline()) return;
                String prefix = messages.text(online, remote ? "ai-npc.response-prefix" : "ai-npc.fallback-prefix");
                online.sendMessage(Component.text(prefix + " ", NamedTextColor.AQUA)
                        .append(Component.text(finalAnswer, NamedTextColor.GRAY)));
                if (!remote && runtime.client().configured()) {
                    online.sendMessage(Component.text(messages.text(online, "ai-npc.fallback-note"), NamedTextColor.DARK_GRAY));
                }
            });
            if (!scheduled) inFlight.remove(playerId, request);
        });
    }

    private boolean current(RequestToken request, AiNpcRuntime runtime) {
        UUID playerId = request.playerSession().getUniqueId();
        return plugin.current(runtime) && request.runtimeGeneration() == runtime.generation()
                && sessions.valid(playerId, request.loginGeneration())
                && inFlight.get(playerId) == request;
    }

    private void status(Player player) {
        AiProviderClient client = plugin.runtime().client();
        String mode = messages.text(player, client.configured() ? "ai-npc.mode-remote" : "ai-npc.mode-static");
        String circuit = messages.text(player, client.circuitOpen()
                ? "ai-npc.circuit-open" : "ai-npc.circuit-closed");
        player.sendMessage(text(player, "ai-npc.status", Map.of(
                "mode", mode,
                "active", client.activeRequests(),
                "maximum", client.maximumConcurrent(),
                "circuit", circuit)));
    }

    private String stateSnapshot(Player player) {
        UUID playerId = player.getUniqueId();
        SkillHudService skillHud = OptionalServices.enabled(plugin, SkillHudService.class);
        PlayerProfile profile = profiles.cached(playerId).orElse(null);
        String profileText = profile == null ? "profile=loading" : "class="
                + profile.archetype().name().toLowerCase(Locale.ROOT)
                + ", ascension=" + profile.ascension()
                + ", level=" + profile.level()
                + ", energy=" + profile.energy() + "/" + profile.energyCap()
                + ", unspent_attributes=" + profile.unspentAttributePoints()
                + ", skill_points=" + profile.skillPoints()
                + ", attributes=" + profile.attributes();
        SkillHudSnapshot hud = skillHud == null ? null : skillHud.snapshot(playerId).orElse(null);
        long now = System.currentTimeMillis();
        String skillText = hud == null ? "skills=loading" : "mana=" + hud.mana() + "/" + hud.manaCap()
                + ", cooldowns=" + hud.learnedSkills().stream()
                .map(entry -> entry.skillId() + ":" + entry.remainingSeconds(now) + "s")
                .toList();
        SkillService skillService = OptionalServices.enabled(plugin, SkillService.class);
        Map<String, SkillProgress> learned = skillService == null ? Map.of()
                : skillService.cachedSkills(playerId).orElse(Map.of());
        EquipmentState gear = equipmentState(player);
        JourneyService journeys = OptionalServices.enabled(plugin, JourneyService.class);
        JourneyProgress journey = journeys == null ? null : journeys.progress(playerId);
        CombatStateService combat = OptionalServices.enabled(plugin, CombatStateService.class);
        String journeyText = journey == null ? "journey=unavailable" : "journey="
                + journey.completedCount() + "/" + journey.totalCount() + ", next="
                + journey.nextStep().map(JourneyStep::id).orElse("freeplay");
        return profileText + "\n" + skillText + ", learned=" + learned.values() + "\n"
                + "equipment_count=" + gear.count() + ", revealed_slots=" + gear.revealed()
                + ", held=" + gear.held() + "\n" + journeyText + "\n"
                + "world=" + player.getWorld().getKey() + ", biome="
                + player.getLocation().getBlock().getBiome().getKey() + ", health="
                + Math.round(player.getHealth()) + "/" + Math.round(player.getAttribute(Attribute.MAX_HEALTH).getValue())
                + ", food=" + player.getFoodLevel() + ", combat="
                + (combat != null && combat.inCombat(playerId));
    }

    private void analyze(Player player) {
        UUID playerId = player.getUniqueId();
        SkillHudService skillHud = OptionalServices.enabled(plugin, SkillHudService.class);
        PlayerProfile profile = profiles.cached(playerId).orElse(null);
        JourneyService journeys = OptionalServices.enabled(plugin, JourneyService.class);
        JourneyProgress journey = journeys == null ? null : journeys.progress(playerId);
        SkillHudSnapshot hud = skillHud == null ? null : skillHud.snapshot(playerId).orElse(null);
        EquipmentState gear = equipmentState(player);
        player.sendMessage(Component.text(messages.text(player, "ai-npc.analysis-title"), NamedTextColor.AQUA));
        if (profile == null) {
            player.sendMessage(text(player, "ai-npc.analysis-loading"));
            return;
        }
        String className = messages.text(player,
                "classes." + profile.archetype().name().toLowerCase(Locale.ROOT));
        player.sendMessage(Component.text(messages.text(player, "ai-npc.analysis-profile", Map.of(
                "class", className, "ascension", profile.ascension(), "level", profile.level(),
                "energy", profile.energy(), "cap", profile.energyCap())), NamedTextColor.WHITE));
        player.sendMessage(Component.text(messages.text(player, "ai-npc.analysis-attributes", Map.of(
                "strength", profile.attribute(CoreAttribute.STRENGTH),
                "agility", profile.attribute(CoreAttribute.AGILITY),
                "intelligence", profile.attribute(CoreAttribute.INTELLIGENCE),
                "defense", profile.attribute(CoreAttribute.DEFENSE),
                "stamina", profile.attribute(CoreAttribute.STAMINA),
                "unspent", profile.unspentAttributePoints())), NamedTextColor.GRAY));
        int learned = hud == null ? 0 : hud.learnedSkills().size();
        int ready = hud == null ? 0 : (int) hud.learnedSkills().stream()
                .filter(entry -> entry.ready(System.currentTimeMillis())).count();
        player.sendMessage(Component.text(messages.text(player, "ai-npc.analysis-skills", Map.of(
                "ready", ready, "learned", learned, "points", profile.skillPoints())),
                NamedTextColor.LIGHT_PURPLE));
        player.sendMessage(Component.text(messages.text(player, "ai-npc.analysis-equipment", Map.of(
                "items", gear.count(), "revealed", gear.revealed(), "held", gear.held())),
                NamedTextColor.AQUA));
        String route = recommendation(profile, journey, hud, gear);
        player.sendMessage(Component.text(messages.text(player, "ai-npc.analysis-next", Map.of(
                "advice", messages.text(player, "ai-npc.advice." + route))), NamedTextColor.GREEN));
        player.sendMessage(Component.text(messages.text(player, "ai-npc.analysis-action", Map.of(
                "action", messages.text(player, "ai-npc.actions." + route))), NamedTextColor.YELLOW));
    }

    private String recommendation(
            PlayerProfile profile, @Nullable JourneyProgress journey,
            @Nullable SkillHudSnapshot hud, EquipmentState gear
    ) {
        JourneyStep next = journey == null ? null : journey.nextStep().orElse(null);
        return CompanionAdvicePolicy.route(profile.archetype(), profile.unspentAttributePoints(),
                hud != null && !hud.learnedSkills().isEmpty(), next, gear.count(),
                profile.skillPoints(), profile.energy());
    }

    private EquipmentState equipmentState(Player player) {
        EquipmentService equipment = OptionalServices.enabled(plugin, EquipmentService.class);
        if (equipment == null) return new EquipmentState(0, 0, "unavailable");
        Set<UUID> seen = new HashSet<>();
        int revealed = 0;
        String held = "none";
        for (org.bukkit.inventory.ItemStack item : player.getInventory().getContents()) {
            if (item == null || !equipment.isAuthentic(item)) continue;
            EquipmentSnapshot snapshot = equipment.inspect(item).orElse(null);
            if (snapshot == null || !seen.add(snapshot.itemId())) continue;
            revealed += snapshot.revealedSlots();
        }
        EquipmentSnapshot heldSnapshot = equipment.inspect(player.getInventory().getItemInMainHand()).orElse(null);
        if (heldSnapshot != null) held = heldSnapshot.equipmentType() + " "
                + heldSnapshot.revealedSlots() + "/" + heldSnapshot.totalSlots();
        return new EquipmentState(seen.size(), revealed, held);
    }

    private record EquipmentState(int count, int revealed, String held) {
    }

    private record RequestToken(
            long requestId, long loginGeneration, long runtimeGeneration, Player playerSession
    ) {
    }

    private static String sanitize(String input) {
        if (input == null) return "";
        StringBuilder result = new StringBuilder(input.length());
        boolean previousSpace = false;
        for (int offset = 0; offset < input.length();) {
            int codePoint = input.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint) || Character.isWhitespace(codePoint)) {
                if (!previousSpace && !result.isEmpty()) result.append(' ');
                previousSpace = true;
            } else {
                result.appendCodePoint(codePoint);
                previousSpace = false;
            }
        }
        return result.toString().strip();
    }

    private Component text(Player player, String key) {
        return Component.text(messages.text(player, key), NamedTextColor.YELLOW);
    }

    private Component text(Player player, String key, Map<String, ?> placeholders) {
        return Component.text(messages.text(player, key, placeholders), NamedTextColor.YELLOW);
    }

    @Override
    public @NotNull List<String> onTabComplete(
            @NotNull CommandSender sender, @NotNull Command command,
            @NotNull String alias, @NotNull String[] args
    ) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return List.of("analyze", "ask", "leave", "status", "reload").stream()
                .filter(value -> value.startsWith(prefix))
                .filter(value -> !value.equals("reload") || sender.hasPermission("rpg.ainpc.reload"))
                .toList();
    }
}
