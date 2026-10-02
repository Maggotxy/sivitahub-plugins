package cn.originalrpg.community;

import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.model.CosmeticMoment;
import cn.originalrpg.api.model.MentorBondResult;
import cn.originalrpg.api.model.PlayerProfile;
import cn.originalrpg.api.service.CommunityService;
import cn.originalrpg.api.service.CosmeticEffectService;
import cn.originalrpg.api.service.OptionalServices;
import cn.originalrpg.api.service.JourneyService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.ProfileService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RpgCommunityPlugin extends JavaPlugin implements Listener {
    private final java.util.Map<UUID, UUID> verificationInFlight = new ConcurrentHashMap<>();
    private final java.util.Map<UUID, AuthenticatedSession> authenticatedPlayers = new ConcurrentHashMap<>();
    private CommunityService communities;
    private ProfileService profiles;
    private JourneyService journeys;
    private MessageService messages;
    private CommunityCommand command;
    private AccountCommand accountCommand;
    private ZoneId projectZone;
    private int verificationCursor;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        communities = requireService(CommunityService.class);
        profiles = requireService(ProfileService.class);
        journeys = requireService(JourneyService.class);
        messages = requireService(MessageService.class);
        projectZone = zone(getConfig().getString("weekly-project.timezone", "Asia/Shanghai"));

        command = new CommunityCommand(this, communities, profiles, messages);
        var pluginCommand = Objects.requireNonNull(getCommand("community"), "community command");
        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);
        accountCommand = new AccountCommand(this, communities, profiles, journeys, messages);
        var account = Objects.requireNonNull(getCommand("account"), "account command");
        account.setExecutor(accountCommand);
        account.setTabCompleter(accountCommand);
        getServer().getPluginManager().registerEvents(command, this);
        getServer().getPluginManager().registerEvents(this, this);

        long poll = Math.clamp(getConfig().getLong(
                "mentor-bond.verification.poll-interval-ticks", 200L), 100L, 12_000L);
        getServer().getScheduler().runTaskTimer(this, this::verifyBatch, poll, poll);
        getLogger().info("Community persistence, mentor verification and weekly gold sinks are enabled.");
    }

    @Override
    public void onDisable() {
        authenticatedPlayers.clear();
        verificationInFlight.clear();
        if (command != null) command.clear();
        if (accountCommand != null) accountCommand.clear();
    }

    @EventHandler
    public void onAuthenticated(AuthenticatedPlayerReadyEvent event) {
        UUID playerId = event.player().getUniqueId();
        UUID sessionToken = UUID.randomUUID();
        authenticatedPlayers.put(playerId, new AuthenticatedSession(event.player(), sessionToken));
        getServer().getScheduler().runTaskLater(this, () -> {
            Player player = getServer().getPlayer(playerId);
            if (isAuthenticated(player, sessionToken)) verifySoon(player);
        }, 100L);
    }

    @EventHandler
    public void onAuthenticationRevoked(AuthenticatedPlayerRevokedEvent event) {
        clearPlayer(event.player());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clearPlayer(event.getPlayer());
    }

    private void clearPlayer(Player player) {
        UUID playerId = player.getUniqueId();
        AuthenticatedSession session = authenticatedPlayers.get(playerId);
        if (session == null || session.player() != player
                || !authenticatedPlayers.remove(playerId, session)) return;
        if (command != null) command.clearPlayer(player);
        if (accountCommand != null) accountCommand.clearPlayer(player);
    }

    public void verifySoon(Player player) {
        UUID sessionToken = sessionToken(player);
        if (sessionToken == null
                || verificationInFlight.putIfAbsent(player.getUniqueId(), sessionToken) != null) return;
        PlayerProfile profile = profiles.cached(player.getUniqueId()).orElse(null);
        if (profile == null) {
            verificationInFlight.remove(player.getUniqueId(), sessionToken);
            return;
        }
        boolean complete = journeys.progress(player.getUniqueId()).complete();
        long playMinutes = playMinutes(player);
        UUID playerId = player.getUniqueId();
        communities.verifyMentorBond(playerId, complete, profile.effectiveLevel(), playMinutes,
                        getConfig().getInt("mentor-bond.verify-minimum-effective-level", 15),
                        getConfig().getLong("mentor-bond.verify-minimum-play-minutes", 180L),
                        periodKey())
                .whenComplete((result, error) -> onMain(() -> {
                    verificationInFlight.remove(playerId, sessionToken);
                    if (!isAuthenticated(player, sessionToken)) return;
                    if (error != null) {
                        getLogger().warning("Mentor verification failed for " + playerId + ": " + error.getMessage());
                        return;
                    }
                    if (result.status() == MentorBondResult.Status.SUCCESS) announceVerified(playerId, result);
                }));
    }

    public String periodKey() {
        LocalDate date = LocalDate.now(projectZone);
        WeekFields fields = WeekFields.ISO;
        int year = date.get(fields.weekBasedYear());
        int week = date.get(fields.weekOfWeekBasedYear());
        return String.format(Locale.ROOT, "%04d-W%02d", year, week);
    }

    public BigDecimal projectGoal() {
        return decimal("weekly-project.goal", "250000.00");
    }

    public BigDecimal startingBalance() {
        return decimalFromCore("progression.starting-balance", "0.00");
    }

    public long playMinutes(Player player) {
        return Math.max(0L, player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L / 60L);
    }

    public void onMain(Runnable action) {
        if (!isEnabled()) return;
        if (Bukkit.isPrimaryThread()) {
            if (isEnabled()) action.run();
            return;
        }
        try {
            getServer().getScheduler().runTask(this, () -> {
                if (isEnabled()) action.run();
            });
        } catch (IllegalPluginAccessException ignored) {
            // A callback may finish between the enabled check and scheduler admission.
        }
    }

    public boolean isAuthenticated(Player player) {
        AuthenticatedSession session = player == null ? null : authenticatedPlayers.get(player.getUniqueId());
        return player != null && player.isOnline()
                && getServer().getPlayer(player.getUniqueId()) == player
                && session != null && session.player() == player;
    }

    public boolean isAuthenticated(UUID playerId) {
        if (playerId == null) return false;
        AuthenticatedSession session = authenticatedPlayers.get(playerId);
        return session != null && getServer().getPlayer(playerId) == session.player()
                && session.player().isOnline();
    }

    public UUID sessionToken(Player player) {
        if (!isAuthenticated(player)) return null;
        AuthenticatedSession session = authenticatedPlayers.get(player.getUniqueId());
        return session == null ? null : session.token();
    }

    public boolean isAuthenticated(Player player, UUID sessionToken) {
        if (sessionToken == null || !isAuthenticated(player)) return false;
        AuthenticatedSession session = authenticatedPlayers.get(player.getUniqueId());
        return session != null && session.player() == player && sessionToken.equals(session.token());
    }

    public BigDecimal projectRewardMinimumContribution() {
        BigDecimal configured = decimal("weekly-project.reward.minimum-contribution", "1000.00");
        if (configured.signum() <= 0 || configured.compareTo(new BigDecimal("1000000000000.00")) > 0) {
            getLogger().warning("Invalid weekly-project reward minimum; using 1000.00");
            return new BigDecimal("1000.00");
        }
        return configured.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public int projectRewardHonor() {
        return Math.clamp(getConfig().getInt("weekly-project.reward.honor", 100), 1, 1_000_000);
    }

    private void verifyBatch() {
        List<Player> players = authenticatedPlayers.values().stream()
                .map(AuthenticatedSession::player).filter(this::isAuthenticated).toList();
        if (players.isEmpty()) {
            verificationCursor = 0;
            return;
        }
        int maximum = Math.clamp(getConfig().getInt(
                "mentor-bond.verification.players-per-pass", 2), 1, 16);
        for (int index = 0; index < Math.min(maximum, players.size()); index++) {
            int selected = Math.floorMod(verificationCursor++, players.size());
            verifySoon(players.get(selected));
        }
    }

    private void announceVerified(UUID playerId, MentorBondResult result) {
        CosmeticEffectService cosmetics = OptionalServices.enabled(this, CosmeticEffectService.class);
        Player player = getServer().getPlayer(playerId);
        if (isAuthenticated(player)) {
            player.sendMessage(Component.text(messages.text(player, "community.mentor.verified"), NamedTextColor.GREEN));
            if (cosmetics != null) cosmetics.playMoment(player, CosmeticMoment.ACTIVITY_REWARD);
        }
        result.mentorId().map(getServer()::getPlayer).filter(this::isAuthenticated).ifPresent(mentor -> {
            mentor.sendMessage(Component.text(messages.text(mentor, "community.mentor.mentee-verified",
                    java.util.Map.of("player", player == null ? playerId.toString() : player.getName(),
                            "score", result.mentorVerifiedMentees())), NamedTextColor.GREEN));
            if (cosmetics != null) cosmetics.playMoment(mentor, CosmeticMoment.ACTIVITY_REWARD);
        });
    }

    private BigDecimal decimal(String path, String fallback) {
        try {
            return new BigDecimal(getConfig().getString(path, fallback));
        } catch (NumberFormatException exception) {
            getLogger().warning("Invalid decimal at " + path + "; using " + fallback);
            return new BigDecimal(fallback);
        }
    }

    private BigDecimal decimalFromCore(String path, String fallback) {
        var core = getServer().getPluginManager().getPlugin("RpgCore");
        if (core instanceof JavaPlugin javaPlugin) {
            try {
                return new BigDecimal(javaPlugin.getConfig().getString(path, fallback));
            } catch (NumberFormatException ignored) {
                // Fall through to the safe default.
            }
        }
        return new BigDecimal(fallback);
    }

    private ZoneId zone(String configured) {
        try {
            return ZoneId.of(configured);
        } catch (RuntimeException exception) {
            getLogger().warning("Invalid weekly-project timezone; using Asia/Shanghai");
            return ZoneId.of("Asia/Shanghai");
        }
    }

    private <T> T requireService(Class<T> type) {
        return Objects.requireNonNull(getServer().getServicesManager().load(type), type.getSimpleName());
    }

    private record AuthenticatedSession(Player player, UUID token) {
    }
}
