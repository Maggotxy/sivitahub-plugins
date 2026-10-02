package cn.originalrpg.community;

import cn.originalrpg.api.model.CommunityApprovalOutcome;
import cn.originalrpg.api.model.CommunityContributionResult;
import cn.originalrpg.api.model.CommunityGovernanceAction;
import cn.originalrpg.api.model.CommunityLeaderboardEntry;
import cn.originalrpg.api.model.CommunityOperationResult;
import cn.originalrpg.api.model.CommunityProfile;
import cn.originalrpg.api.model.CommunityProjectRewardResult;
import cn.originalrpg.api.model.CommunityRolePower;
import cn.originalrpg.api.model.MentorBondResult;
import cn.originalrpg.api.model.PlayerProfile;
import cn.originalrpg.api.service.CommunityGovernanceService;
import cn.originalrpg.api.service.CommunityRoleAssignmentService;
import cn.originalrpg.api.service.CommunityService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.ProfileService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

final class CommunityCommand implements CommandExecutor, TabCompleter, Listener {
    private final RpgCommunityPlugin plugin;
    private final CommunityService communities;
    private final CommunityGovernanceService governance;
    private final CommunityRoleAssignmentService roleAssignments;
    private final ProfileService profiles;
    private final MessageService messages;
    private final Map<UUID, PendingCreate> pendingCreates = new HashMap<>();
    private final Map<UUID, PendingTransfer> pendingTransfers = new HashMap<>();
    private final Map<UUID, OperationLease> operations = new ConcurrentHashMap<>();

    CommunityCommand(
            RpgCommunityPlugin plugin,
            CommunityService communities,
            CommunityGovernanceService governance,
            CommunityRoleAssignmentService roleAssignments,
            ProfileService profiles,
            MessageService messages
    ) {
        this.plugin = plugin;
        this.communities = communities;
        this.governance = governance;
        this.roleAssignments = roleAssignments;
        this.profiles = profiles;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.text(sender, "common.player-only"));
            return true;
        }
        if (args.length == 0) {
            open(player);
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        var governanceRoute = CommunityGovernanceCommandRouter.route(Arrays.copyOfRange(args, 1, args.length));
        if (governanceRoute.isPresent()) {
            dispatchGovernance(player, governanceRoute.orElseThrow());
            return true;
        }
        switch (action) {
            case "help" -> help(player);
            case "status" -> status(player);
            case "browse" -> leaderboard(player, false);
            case "leaderboard", "top" -> leaderboard(player, true);
            case "code" -> code(player);
            case "create" -> create(player, args);
            case "invite" -> invite(player, args);
            case "accept" -> accept(player, args);
            case "leave" -> leave(player);
            case "kick" -> kick(player, args);
            case "transfer" -> transfer(player, args);
            case "bond" -> bond(player, args);
            case "contribute", "donate" -> contribute(player, args);
            case "claim" -> claim(player);
            default -> help(player);
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        if (args.length == 1) {
            return filter(concat(List.of("help", "status", "browse", "create", "invite", "accept", "leave",
                            "kick", "transfer", "code", "bond", "contribute", "claim", "leaderboard"),
                            CommunityGovernanceCommandRouter.governanceSubcommands()), args[0]);
        }
        if (args.length == 2 && Set.of("invite", "kick", "transfer").contains(args[0].toLowerCase(Locale.ROOT))) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("assign")) {
            return filter(governanceHandler().assignableRoleNames(), args[1]);
        }
        if (args.length == 2 && Set.of("approve", "approvals", "deny")
                .contains(args[0].toLowerCase(Locale.ROOT))) {
            return filter(CommunityGovernanceCommandRouter.actionNames(), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("create")
                && pendingCreates.containsKey(sender instanceof Player player ? player.getUniqueId() : null)) {
            return filter(List.of("confirm"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("transfer")
                && pendingTransfers.containsKey(sender instanceof Player player ? player.getUniqueId() : null)) {
            return filter(List.of("confirm"), args[1]);
        }
        return List.of();
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof CommunityMenu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String action = menu.actionAt(event.getRawSlot());
        if (action == null) return;
        player.closeInventory();
        player.performCommand(action);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof CommunityMenu) event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clearPlayer(event.getPlayer());
        // Submitted database work owns its gate until the callback settles.
    }

    void clear() {
        pendingCreates.clear();
        pendingTransfers.clear();
        operations.clear();
    }

    void clearPlayer(Player player) {
        UUID playerId = player.getUniqueId();
        PendingCreate create = pendingCreates.get(playerId);
        if (create != null && create.player() == player) pendingCreates.remove(playerId, create);
        PendingTransfer transfer = pendingTransfers.get(playerId);
        if (transfer != null && transfer.player() == player) pendingTransfers.remove(playerId, transfer);
        // Do not reopen a mutation gate while its transaction is still in flight.
    }

    private void open(Player player) {
        UUID sessionToken = start(player, false);
        if (sessionToken == null) return;
        String period = plugin.periodKey();
        communities.profile(player.getUniqueId(), period, plugin.projectGoal())
                .thenCombine(communities.leaderboard(period, plugin.projectGoal(), 5), MenuData::new)
                .whenComplete((data, error) -> plugin.onMain(() -> {
                    release(player, sessionToken);
                    if (!plugin.isAuthenticated(player, sessionToken)) return;
                    if (error != null) {
                        failure(player, error);
                        return;
                    }
                    CommunityMenu menu = new CommunityMenu(player, messages, data.profile(), data.leaderboard(),
                            plugin.projectRewardMinimumContribution(), plugin.projectRewardHonor());
                    player.openInventory(menu.getInventory());
                }));
    }

    private void help(Player player) {
        for (String line : messages.lines(player, "community.help")) {
            player.sendMessage(Component.text(line, NamedTextColor.GRAY));
        }
        // Phase 6 治理命令只在真正启用时才出现在帮助里，避免未启用对玩家暗示可用功能。
        if (!plugin.communityGovernanceEnabled() || !plugin.governanceAvailable()) return;
        for (String line : messages.lines(player, "community.governance.help")) {
            player.sendMessage(Component.text(line, NamedTextColor.GRAY));
        }
    }

    private void status(Player player) {
        UUID sessionToken = start(player, false);
        if (sessionToken == null) return;
        communities.profile(player.getUniqueId(), plugin.periodKey(), plugin.projectGoal())
                .whenComplete((profile, error) -> plugin.onMain(() -> {
                    release(player, sessionToken);
                    if (!plugin.isAuthenticated(player, sessionToken)) return;
                    if (error != null) {
                        failure(player, error);
                        return;
                    }
                    showProfile(player, profile);
                }));
    }

    private void code(Player player) {
        UUID sessionToken = start(player, false);
        if (sessionToken == null) return;
        communities.profile(player.getUniqueId(), plugin.periodKey(), plugin.projectGoal())
                .whenComplete((profile, error) -> plugin.onMain(() -> {
                    release(player, sessionToken);
                    if (!plugin.isAuthenticated(player, sessionToken)) return;
                    if (error != null) {
                        failure(player, error);
                        return;
                    }
                    send(player, "community.code", Map.of("code", profile.inviteCode()));
                    send(player, "community.code-rule");
                }));
    }

    private void leaderboard(Player player, boolean ranked) {
        UUID sessionToken = start(player, false);
        if (sessionToken == null) return;
        communities.leaderboard(plugin.periodKey(), plugin.projectGoal(), ranked ? 10 : 20)
                .whenComplete((entries, error) -> plugin.onMain(() -> {
                    release(player, sessionToken);
                    if (!plugin.isAuthenticated(player, sessionToken)) return;
                    if (error != null) {
                        failure(player, error);
                        return;
                    }
                    send(player, ranked ? "community.leaderboard-title" : "community.browse-title",
                            Map.of("period", plugin.periodKey()));
                    if (entries.isEmpty()) {
                        send(player, "community.list-empty");
                        return;
                    }
                    for (CommunityLeaderboardEntry entry : entries) {
                        var community = entry.community();
                        send(player, "community.list-entry", Map.of(
                                "rank", entry.rank(), "tag", community.tag(), "name", community.name(),
                                "members", community.members(), "capacity", community.capacity(),
                                "amount", amount(community.periodContribution()),
                                "goal", amount(community.periodGoal()),
                                "recruits", community.verifiedRecruits(),
                                "state", messages.text(player, community.completed()
                                        ? "community.project-complete" : "community.project-building")));
                    }
                }));
    }

    private void create(Player player, String[] args) {
        if (args.length == 2 && args[1].equalsIgnoreCase("confirm")) {
            PendingCreate pending = pendingCreates.get(player.getUniqueId());
            if (pending == null || pending.player() != player || pending.expired()) {
                if (pending != null) pendingCreates.remove(player.getUniqueId(), pending);
                send(player, "community.create-missing");
                return;
            }
            UUID sessionToken = start(player, true);
            if (sessionToken == null) return;
            communities.create(player.getUniqueId(), pending.name(), pending.tag(),
                            plugin.getConfig().getInt("community.capacity", 40),
                            plugin.getConfig().getInt("community.minimum-creator-effective-level", 15),
                            decimal("community.creation-cost", "10000.00"), plugin.startingBalance(),
                            pending.idempotencyKey())
                    .whenComplete((result, error) -> completeOperation(
                            player, sessionToken, result, error,
                            () -> pendingCreates.remove(player.getUniqueId(), pending)));
            return;
        }
        if (args.length < 3) {
            send(player, "community.create-usage");
            return;
        }
        String tag = args[1];
        String name = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        long seconds = Math.clamp(plugin.getConfig().getLong("community.confirmation-seconds", 15L), 5L, 60L);
        PendingCreate pending = new PendingCreate(player, name, tag,
                "create-" + UUID.randomUUID().toString().replace("-", ""),
                Instant.now().plusSeconds(seconds));
        pendingCreates.put(player.getUniqueId(), pending);
        send(player, "community.create-preview", Map.of(
                "tag", tag, "name", name, "cost", amount(decimal("community.creation-cost", "10000.00")),
                "seconds", seconds));
    }

    private void invite(Player player, String[] args) {
        if (args.length != 2) {
            send(player, "community.invite-usage");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            send(player, "community.player-online-required");
            return;
        }
        UUID sessionToken = start(player, true);
        if (sessionToken == null) return;
        communities.invite(player.getUniqueId(), target.getUniqueId(),
                        plugin.getConfig().getInt("community.maximum-pending-invites-per-member", 10),
                        plugin.getConfig().getInt("community.invite-expiry-hours", 24),
                        plugin.periodKey(), plugin.projectGoal())
                .whenComplete((result, error) -> plugin.onMain(() -> {
                    release(player, sessionToken);
                    if (!plugin.isAuthenticated(player, sessionToken)) return;
                    if (error != null) {
                        failure(player, error);
                        return;
                    }
                    operation(player, result);
                    if (result.status() == CommunityOperationResult.Status.SUCCESS
                            && plugin.isAuthenticated(target)) {
                        String tag = result.community().map(summary -> summary.tag()).orElse("?");
                        send(target, "community.invitation", Map.of(
                                "player", player.getName(), "tag", tag,
                                "hours", plugin.getConfig().getInt("community.invite-expiry-hours", 24)));
                    }
                }));
    }

    private void accept(Player player, String[] args) {
        if (args.length != 2) {
            send(player, "community.accept-usage");
            return;
        }
        UUID sessionToken = start(player, true);
        if (sessionToken == null) return;
        communities.accept(player.getUniqueId(), args[1], plugin.periodKey(), plugin.projectGoal())
                .whenComplete((result, error) -> completeOperation(
                        player, sessionToken, result, error, () -> { }));
    }

    private void leave(Player player) {
        UUID sessionToken = start(player, true);
        if (sessionToken == null) return;
        communities.leave(player.getUniqueId(), plugin.periodKey(), plugin.projectGoal())
                .whenComplete((result, error) -> completeOperation(
                        player, sessionToken, result, error, () -> { }));
    }

    private void kick(Player player, String[] args) {
        if (args.length != 2) {
            send(player, "community.kick-usage");
            return;
        }
        OfflinePlayer target = knownPlayer(args[1]);
        if (target == null) {
            send(player, "community.player-unknown");
            return;
        }
        UUID sessionToken = start(player, true);
        if (sessionToken == null) return;
        communities.kick(player.getUniqueId(), target.getUniqueId(), plugin.periodKey(), plugin.projectGoal())
                .whenComplete((result, error) -> completeOperation(
                        player, sessionToken, result, error, () -> { }));
    }

    private void transfer(Player player, String[] args) {
        if (args.length == 2 && args[1].equalsIgnoreCase("confirm")) {
            PendingTransfer pending = pendingTransfers.get(player.getUniqueId());
            if (pending == null || pending.player() != player || pending.expired()) {
                if (pending != null) pendingTransfers.remove(player.getUniqueId(), pending);
                send(player, "community.transfer-missing");
                return;
            }
            UUID sessionToken = start(player, true);
            if (sessionToken == null) return;
            communities.transfer(player.getUniqueId(), pending.target(), plugin.periodKey(), plugin.projectGoal())
                    .whenComplete((result, error) -> completeOperation(
                            player, sessionToken, result, error,
                            () -> pendingTransfers.remove(player.getUniqueId(), pending)));
            return;
        }
        if (args.length != 2) {
            send(player, "community.transfer-usage");
            return;
        }
        OfflinePlayer target = knownPlayer(args[1]);
        if (target == null) {
            send(player, "community.player-unknown");
            return;
        }
        long seconds = Math.clamp(plugin.getConfig().getLong("community.confirmation-seconds", 15L), 5L, 60L);
        pendingTransfers.put(player.getUniqueId(),
                new PendingTransfer(player, target.getUniqueId(), target.getName() == null ? args[1] : target.getName(),
                        Instant.now().plusSeconds(seconds)));
        send(player, "community.transfer-preview",
                Map.of("player", args[1], "seconds", seconds));
    }

    private void bond(Player player, String[] args) {
        if (args.length != 2) {
            send(player, "community.bond-usage");
            return;
        }
        PlayerProfile profile = profiles.cached(player.getUniqueId()).orElse(null);
        if (profile == null) {
            send(player, "community.profile-loading");
            return;
        }
        UUID sessionToken = start(player, true);
        if (sessionToken == null) return;
        communities.bindMentor(player.getUniqueId(), args[1], profile.effectiveLevel(),
                        plugin.playMinutes(player),
                        plugin.getConfig().getInt("mentor-bond.bind-maximum-effective-level", 10),
                        plugin.getConfig().getLong("mentor-bond.bind-maximum-play-minutes", 360L),
                        plugin.getConfig().getInt("mentor-bond.mentor-minimum-effective-level", 15))
                .whenComplete((result, error) -> plugin.onMain(() -> {
                    release(player, sessionToken);
                    if (!plugin.isAuthenticated(player, sessionToken)) return;
                    if (error != null) {
                        failure(player, error);
                        return;
                    }
                    mentor(player, result);
                    if (result.status() == MentorBondResult.Status.SUCCESS) plugin.verifySoon(player);
                }));
    }

    private void contribute(Player player, String[] args) {
        if (args.length != 2) {
            send(player, "community.contribute-usage");
            return;
        }
        BigDecimal value;
        try {
            value = new BigDecimal(args[1]).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException exception) {
            send(player, "community.amount-invalid");
            return;
        }
        BigDecimal minimum = decimal("weekly-project.minimum-contribution", "100.00");
        BigDecimal maximum = decimal("weekly-project.maximum-per-command", "10000.00");
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            send(player, "community.amount-range",
                    Map.of("minimum", amount(minimum), "maximum", amount(maximum)));
            return;
        }
        UUID sessionToken = start(player, true);
        if (sessionToken == null) return;
        String key = "project-" + UUID.randomUUID().toString().replace("-", "");
        communities.contributeGold(player.getUniqueId(), value,
                        decimal("weekly-project.player-cap", "50000.00"), plugin.projectGoal(),
                        plugin.startingBalance(), plugin.periodKey(), key)
                .whenComplete((result, error) -> plugin.onMain(() -> {
                    release(player, sessionToken);
                    if (!plugin.isAuthenticated(player, sessionToken)) return;
                    if (error != null) {
                        failure(player, error);
                        return;
                    }
                    contribution(player, result);
                }));
    }

    private void claim(Player player) {
        UUID sessionToken = start(player, true);
        if (sessionToken == null) return;
        communities.claimWeeklyProjectReward(player.getUniqueId(), plugin.periodKey(), plugin.projectGoal(),
                        plugin.projectRewardMinimumContribution(), plugin.projectRewardHonor())
                .whenComplete((result, error) -> plugin.onMain(() -> {
                    release(player, sessionToken);
                    if (!plugin.isAuthenticated(player, sessionToken)) return;
                    if (error != null) {
                        failure(player, error);
                        return;
                    }
                    projectReward(player, result);
                }));
    }

    // ------------------------------------------------------------------
    // Phase 6 治理：七角色能力判定 + 双批审批。开关默认关（接线但不启用）。
    // ------------------------------------------------------------------

    private CommunityGovernanceHandler governanceHandler() {
        return new CommunityGovernanceHandler(
                governance,
                roleAssignments,
                governance == null ? null
                        : (playerId, capability) -> governance.can(playerId, capability)
                                .toCompletableFuture(),
                plugin.communityGovernanceEnabled());
    }

    private void dispatchGovernance(
            Player player, CommunityGovernanceCommandRouter.Route route
    ) {
        CommunityGovernanceHandler handler = governanceHandler();
        // 角色名/角色列表只是能力表展示，不依赖治理服务提供者。
        switch (route.action()) {
            case ROLE_LOOKUP -> {
                render(player, handler.roleLookup(route.argument()));
                return;
            }
            case ROLE_LIST -> {
                render(player, handler.roleLookup(null));
                return;
            }
            default -> {
                // 其余子命令都要读写库，必须先过「接线但不启用」双重判定。
            }
        }
        CommunityGovernanceAction action = route.governanceAction();
        switch (route.action()) {
            case ASSIGN -> assign(player, handler, route.argument());
            case APPROVE -> async(player, true, handler.approve(player.getUniqueId(), action));
            case APPROVALS -> async(player, false, handler.approvals(player.getUniqueId(), action));
            case DENY -> async(player, true, handler.deny(player.getUniqueId(), action));
            case ROLE_LOOKUP, ROLE_LIST -> throw new IllegalStateException("handled above");
        }
    }

    private void assign(Player player, CommunityGovernanceHandler handler, String targetName) {
        OfflinePlayer target = knownPlayer(targetName);
        if (target == null) {
            send(player, "community.player-unknown");
            return;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            send(player, "community.governance.assign-self");
            return;
        }
        async(player, true, handler.assign(player.getUniqueId(), target.getUniqueId(),
                "role-grant-" + UUID.randomUUID().toString().replace("-", "")));
    }

    /** 统一的异步执行：租约释放 + 会话校验 + 出错兜底 + 文案渲染。 */
    private void async(
            Player player, boolean mutation, CompletableFuture<CommunityGovernanceHandler.Rendering> pending
    ) {
        UUID sessionToken = start(player, mutation);
        if (sessionToken == null) return;
        pending.whenComplete((rendering, error) -> plugin.onMain(() -> {
            release(player, sessionToken);
            if (!plugin.isAuthenticated(player, sessionToken)) return;
            if (error != null) {
                failure(player, error);
                return;
            }
            render(player, rendering);
        }));
    }

    /** 把 handler 的渲染结果发出去；占位符里的 *Key 是文案键，需要先解析成本地化文本。 */
    private void render(Player player, CommunityGovernanceHandler.Rendering rendering) {
        for (CommunityGovernanceHandler.Rendering.Message line : rendering.messages()) {
            Map<String, Object> placeholders = new HashMap<>();
            line.placeholders().forEach((name, value) -> placeholders.put(name, name.endsWith("Key")
                    ? messages.text(player, String.valueOf(value)) : value));
            send(player, line.key(), placeholders);
        }
    }

    private void showProfile(Player player, CommunityProfile profile) {
        send(player, "community.status-title", Map.of("period", plugin.periodKey()));
        if (profile.membership().isEmpty()) {
            send(player, "community.status-none");
        } else {
            var membership = profile.membership().orElseThrow();
            var summary = membership.community();
            send(player, "community.status-member", Map.of(
                    "tag", summary.tag(), "name", summary.name(),
                    "role", messages.text(player, "community.roles."
                            + membership.role().name().toLowerCase(Locale.ROOT)),
                    "members", summary.members(), "capacity", summary.capacity()));
            send(player, "community.status-project", Map.of(
                    "amount", amount(summary.periodContribution()), "goal", amount(summary.periodGoal()),
                    "personal", amount(membership.periodContribution()),
                    "state", messages.text(player, summary.completed()
                            ? "community.project-complete" : "community.project-building")));
            send(player, "community.status-lifetime", Map.of(
                    "lifetime", amount(membership.lifetimeContribution()),
                    "period", amount(membership.periodCapContribution())));
            send(player, "community.status-recruits", Map.of("count", summary.verifiedRecruits()));
            String state = profile.weeklyRewardClaimed()
                    ? messages.text(player, "community.reward-state.claimed")
                    : !summary.completed()
                    ? messages.text(player, "community.reward-state.incomplete")
                    : membership.periodContribution().compareTo(plugin.projectRewardMinimumContribution()) < 0
                    ? messages.text(player, "community.reward-state.contribution-required")
                    : messages.text(player, "community.reward-state.ready");
            send(player, "community.status-reward", Map.of(
                    "honor", profile.communityHonor(), "badges", profile.weeklyCommemorations(),
                    "minimum", amount(plugin.projectRewardMinimumContribution()), "state", state,
                    "title", commemorationTitle(player, profile.weeklyCommemorations())));
        }
        send(player, "community.status-code", Map.of("code", profile.inviteCode()));
        if (profile.mentorId().isEmpty()) send(player, "community.status-mentor-none");
        else send(player, profile.mentorVerified() ? "community.status-mentor-verified"
                : "community.status-mentor-pending");
        send(player, "community.status-mentees", Map.of("count", profile.verifiedMentees()));
    }

    private void completeOperation(
            Player player,
            UUID sessionToken,
            CommunityOperationResult result,
            Throwable error,
            Runnable cleanup
    ) {
        plugin.onMain(() -> {
            release(player, sessionToken);
            cleanup.run();
            if (!plugin.isAuthenticated(player, sessionToken)) return;
            if (error != null) {
                failure(player, error);
                return;
            }
            operation(player, result);
        });
    }

    private void operation(Player player, CommunityOperationResult result) {
        String suffix = result.status().name().toLowerCase(Locale.ROOT).replace('_', '-');
        send(player, "community.operation." + suffix,
                Map.of("balance", amount(result.balance())));
        if (result.status() == CommunityOperationResult.Status.SUCCESS) {
            result.community().ifPresent(summary -> send(player, "community.operation-summary",
                    Map.of("tag", summary.tag(), "name", summary.name(),
                            "members", summary.members(), "capacity", summary.capacity())));
        }
    }

    private void mentor(Player player, MentorBondResult result) {
        String suffix = result.status().name().toLowerCase(Locale.ROOT).replace('_', '-');
        send(player, "community.mentor." + suffix,
                Map.of("score", result.mentorVerifiedMentees()));
    }

    private void contribution(Player player, CommunityContributionResult result) {
        String suffix = result.status().name().toLowerCase(Locale.ROOT).replace('_', '-');
        send(player, "community.contribution." + suffix, Map.of(
                "amount", amount(result.amount()), "balance", amount(result.balance()),
                "personal", amount(result.playerPeriodContribution()),
                "total", amount(result.communityPeriodContribution()),
                "goal", amount(result.periodGoal())));
        if (result.newlyCompleted()) {
            send(player, "community.contribution.completed");
            plugin.verifySoon(player);
        }
    }

    private void projectReward(Player player, CommunityProjectRewardResult result) {
        String suffix = result.status().name().toLowerCase(Locale.ROOT).replace('_', '-');
        send(player, "community.reward." + suffix, Map.of(
                "honor", result.honorAwarded(), "total", result.totalHonor(),
                "badges", result.weeklyCommemorations(),
                "personal", amount(result.playerPeriodContribution()),
                "minimum", amount(result.minimumContribution()),
                "title", commemorationTitle(player, result.weeklyCommemorations())));
    }

    private String commemorationTitle(Player player, int commemorations) {
        String tier = commemorations >= 52 ? "yearkeeper"
                : commemorations >= 24 ? "hearthwarden"
                : commemorations >= 12 ? "builder"
                : commemorations >= 4 ? "companion" : "newcomer";
        String title = messages.text(player, "community.titles." + tier);
        if (commemorations >= 52) {
            return messages.text(player, "community.title-progress.complete",
                    Map.of("title", title, "badges", commemorations));
        }
        int next = commemorations < 4 ? 4 : commemorations < 12 ? 12 : commemorations < 24 ? 24 : 52;
        return messages.text(player, "community.title-progress.next",
                Map.of("title", title, "badges", commemorations, "next", next));
    }

    private UUID start(Player player, boolean mutation) {
        UUID sessionToken = plugin.sessionToken(player);
        if (sessionToken == null) return null;
        if (operations.putIfAbsent(player.getUniqueId(), new OperationLease(player, sessionToken)) != null) {
            send(player, mutation ? "community.operation-pending" : "community.loading");
            return null;
        }
        return sessionToken;
    }

    private void release(Player player, UUID sessionToken) {
        UUID playerId = player.getUniqueId();
        OperationLease lease = operations.get(playerId);
        if (lease != null && lease.player() == player && lease.sessionToken().equals(sessionToken)) {
            operations.remove(playerId, lease);
        }
    }

    private void failure(Player player, Throwable error) {
        plugin.getLogger().warning("Community operation failed for " + player.getUniqueId() + ": "
                + rootMessage(error));
        send(player, "community.error");
    }

    private void send(Player player, String key) {
        player.sendMessage(Component.text(messages.text(player, key), NamedTextColor.GRAY));
    }

    private void send(Player player, String key, Map<String, ?> placeholders) {
        player.sendMessage(Component.text(messages.text(player, key, placeholders), NamedTextColor.GRAY));
    }

    private BigDecimal decimal(String path, String fallback) {
        try {
            return new BigDecimal(plugin.getConfig().getString(path, fallback));
        } catch (NumberFormatException exception) {
            return new BigDecimal(fallback);
        }
    }

    private OfflinePlayer knownPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        return online == null ? Bukkit.getOfflinePlayerIfCached(name) : online;
    }

    private static String amount(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    @SafeVarargs
    private static List<String> concat(List<String>... lists) {
        List<String> joined = new ArrayList<>();
        for (List<String> list : lists) joined.addAll(list);
        return List.copyOf(joined);
    }

    private static List<String> filter(List<String> values, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized)).toList();
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null) cursor = cursor.getCause();
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }

    private record MenuData(CommunityProfile profile, List<CommunityLeaderboardEntry> leaderboard) {
    }

    private record OperationLease(Player player, UUID sessionToken) {
    }

    private record PendingCreate(Player player, String name, String tag, String idempotencyKey, Instant expiresAt) {
        boolean expired() {
            return expiresAt.isBefore(Instant.now());
        }
    }

    private record PendingTransfer(Player player, UUID target, String name, Instant expiresAt) {
        boolean expired() {
            return expiresAt.isBefore(Instant.now());
        }
    }
}
