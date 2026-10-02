package cn.originalrpg.admin;

import cn.originalrpg.api.model.ChapterState;
import cn.originalrpg.api.model.ChapterTransitionResult;
import cn.originalrpg.api.service.ChapterService;
import cn.originalrpg.api.service.ProfileService;
import cn.originalrpg.api.service.EconomyAnalyticsService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.AdminTestService;
import cn.originalrpg.api.service.AdminIncidentCompensationService;
import cn.originalrpg.api.service.PetRevivalService;
import cn.originalrpg.api.service.SeasonSettlementService;
import cn.originalrpg.api.service.EquipmentLedgerAuditService;
import cn.originalrpg.api.model.EconomyActivity;
import cn.originalrpg.api.model.IncidentActivityCompensation;
import cn.originalrpg.api.model.IncidentConditionalActivityCompensation;
import cn.originalrpg.api.model.IncidentCompensationRequest;
import cn.originalrpg.api.model.IncidentCompensationResult;
import cn.originalrpg.api.model.AdminTestState;
import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.model.PetRevivalIntent;
import cn.originalrpg.api.model.PetRevivalResult;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Objects;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.Locale;
import java.util.TreeMap;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

public final class RpgAdminPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {
    private ProfileService profiles;
    private EconomyAnalyticsService economyAnalytics;
    private MessageService messages;
    private ChapterService chapters;
    private AdminTestService adminTests;
    private AdminIncidentCompensationService incidentCompensations;
    private PetRevivalService petRevivals;
    private EquipmentLedgerAuditService ledgerAudit;
    private SeasonSettlementCommand seasonSettlements;
    private final AtomicBoolean economyCollectionInFlight = new AtomicBoolean();
    private final AtomicBoolean snapshotLifecycleActive = new AtomicBoolean();
    private final AtomicLong snapshotGenerations = new AtomicLong();
    private volatile AdminSnapshotIo snapshotIo;
    private volatile EconomyWeekComparison latestEconomyTrend;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        profiles = Objects.requireNonNull(getServer().getServicesManager().load(ProfileService.class), "ProfileService");
        economyAnalytics = Objects.requireNonNull(
                getServer().getServicesManager().load(EconomyAnalyticsService.class), "EconomyAnalyticsService");
        messages = Objects.requireNonNull(getServer().getServicesManager().load(MessageService.class), "MessageService");
        chapters = Objects.requireNonNull(getServer().getServicesManager().load(ChapterService.class), "ChapterService");
        adminTests = Objects.requireNonNull(
                getServer().getServicesManager().load(AdminTestService.class), "AdminTestService");
        incidentCompensations = Objects.requireNonNull(getServer().getServicesManager().load(
                AdminIncidentCompensationService.class), "AdminIncidentCompensationService");
        petRevivals = Objects.requireNonNull(getServer().getServicesManager().load(
                PetRevivalService.class), "PetRevivalService");
        ledgerAudit = Objects.requireNonNull(getServer().getServicesManager().load(
                EquipmentLedgerAuditService.class), "EquipmentLedgerAuditService");
        // Phase 5 换季「接线但不启用」（L-009）：服务与命令都在位，开关默认关。
        // SeasonSettlementService 缺失（RpgCore 未启用）时子命令不注册，而不是留一个抛错的壳。
        seasonSettlements = SeasonSettlementCommand.of(
                this, getServer().getServicesManager().load(SeasonSettlementService.class),
                getServer().getServicesManager().load(ChapterService.class), messages,
                getConfig().getBoolean("season-settlement.enabled", false)).orElse(null);
        if (seasonSettlements == null) {
            getLogger().warning("SeasonSettlementService is not registered; /rpgadmin season stays unbound.");
        }
        PluginCommand command = Objects.requireNonNull(getCommand("rpgadmin"), "rpgadmin command");
        command.setExecutor(this);
        command.setTabCompleter(this);
        snapshotIo = new AdminSnapshotIo(getDataFolder().toPath());
        snapshotLifecycleActive.set(true);
        economyCollectionInFlight.set(false);
        snapshotGenerations.incrementAndGet();
        if (getConfig().getBoolean("economy-snapshot.enabled", true)) {
            long seconds = Math.clamp(getConfig().getLong("economy-snapshot.interval-seconds", 300L), 60L, 3_600L);
            Bukkit.getScheduler().runTaskTimer(this, this::exportEconomySnapshot, 20L, seconds * 20L);
        }
        if (getConfig().getBoolean("chapter-snapshot.enabled", true)) {
            long seconds = Math.clamp(getConfig().getLong("chapter-snapshot.interval-seconds", 30L), 10L, 300L);
            Bukkit.getScheduler().runTaskTimer(this, this::exportChapterSnapshot, 1L, seconds * 20L);
        }
    }

    @Override
    public void onDisable() {
        snapshotLifecycleActive.set(false);
        snapshotGenerations.incrementAndGet();
        AdminSnapshotIo current = snapshotIo;
        snapshotIo = null;
        economyCollectionInFlight.set(false);
        if (current == null) return;
        long timeoutMillis = Math.clamp(
                getConfig().getLong("snapshot-io.shutdown-timeout-millis", 1_500L), 100L, 5_000L);
        if (!current.close(Duration.ofMillis(timeoutMillis))) {
            getLogger().warning("Snapshot I/O did not drain before the bounded shutdown deadline; remaining work was cancelled.");
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("test")) {
            return handleTest(sender, args);
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("chapter")) {
            return handleChapter(sender, args);
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("incident")) {
            return handleIncident(sender, args);
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("season")) {
            return handleSeason(sender, args);
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("petrevival")) {
            return handlePetRevival(sender, args);
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("equipment")) {
            return handleEquipmentLedger(sender, args);
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("economy")) {
            showEconomy(sender);
            return true;
        }
        if (args.length != 3) return false;
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        int amount;
        try {
            amount = Integer.parseInt(args[2]);
            if (amount <= 0) throw new NumberFormatException();
        } catch (NumberFormatException exception) {
            sender.sendMessage(Component.text(messages.text(sender, "admin.positive-integer"), NamedTextColor.RED));
            return true;
        }
        var operation = switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "xp" -> profiles.grantExperience(target.getUniqueId(), amount);
            case "energy" -> profiles.restoreEnergy(target.getUniqueId(), amount);
            default -> null;
        };
        if (operation == null) return false;
        getLogger().warning("AUDIT actor=" + sender.getName() + " action=" + args[0] + " target="
                + target.getUniqueId() + " amount=" + amount);
        operation.whenComplete((profile, error) -> Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(
                error == null
                        ? Component.text(messages.text(sender, "admin.operation-complete"), NamedTextColor.GREEN)
                        : Component.text(messages.text(sender, "admin.operation-failed"), NamedTextColor.RED))));
        return true;
    }

    private boolean handleIncident(CommandSender sender, String[] args) {
        if (args.length == 3 && args[1].equalsIgnoreCase("audit")) {
            incidentCompensations.audit(args[2]).whenComplete((result, error) ->
                    completeIncident(sender, result, error));
            return true;
        }
        if (args.length != 4 || !args[1].equalsIgnoreCase("apply") || !AdminConfirmation.isConfirmed(args[3])) {
            sender.sendMessage(Component.text(messages.text(sender, "admin.incident-usage"), NamedTextColor.YELLOW));
            return true;
        }
        IncidentCompensationRequest request = knownIncident(args[2], sender.getName());
        if (request == null) {
            sender.sendMessage(Component.text(messages.text(sender, "admin.incident-unknown"), NamedTextColor.RED));
            return true;
        }
        getLogger().warning("AUDIT actor=" + sender.getName() + " action=incident-apply incident="
                + request.incidentKey() + " target=" + request.playerId());
        incidentCompensations.apply(request).whenComplete((result, error) -> completeIncident(sender, result, error));
        return true;
    }

    /**
     * C-010 换季驱动入口（G-101 剩余半条）。九态状态机与资金事务在
     * {@link SeasonSettlementService} 内，本方法只做「开关 + 权限 + CONFIRM + 转发」。
     */
    private boolean handleSeason(CommandSender sender, String[] args) {
        if (seasonSettlements == null) {
            sender.sendMessage(Component.text(
                    messages.text(sender, "admin.season-unavailable"), NamedTextColor.RED));
            return true;
        }
        return seasonSettlements.handle(sender, args);
    }

    private boolean handlePetRevival(CommandSender sender, String[] args) {
        if (args.length == 2 && args[1].equalsIgnoreCase("queue")) {
            petRevivals.reviewQueue(25).whenComplete((intents, error) -> runMain(() -> {
                if (error != null || intents == null) {
                    sender.sendMessage(Component.text(messages.text(sender, "admin.pet-revival-failed"),
                            NamedTextColor.RED));
                    return;
                }
                sender.sendMessage(Component.text(messages.text(sender, "admin.pet-revival-queue", Map.of(
                        "count", intents.size())), NamedTextColor.GOLD));
                for (PetRevivalIntent intent : intents) sendPetRevivalAudit(sender, intent);
            }));
            return true;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("audit")) {
            UUID operationId = parseUuid(sender, args[2]);
            if (operationId == null) return true;
            petRevivals.find(operationId).whenComplete((intent, error) -> runMain(() -> {
                if (error != null || intent == null) {
                    sender.sendMessage(Component.text(messages.text(sender, "admin.pet-revival-failed"),
                            NamedTextColor.RED));
                } else if (intent.isEmpty()) {
                    sender.sendMessage(Component.text(messages.text(sender, "admin.pet-revival-not-found"),
                            NamedTextColor.YELLOW));
                } else {
                    sendPetRevivalAudit(sender, intent.orElseThrow());
                }
            }));
            return true;
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("retry") && args[3].equals("CONFIRM")) {
            UUID operationId = parseUuid(sender, args[2]);
            if (operationId == null) return true;
            getLogger().warning("AUDIT actor=" + sender.getName()
                    + " action=pet-revival-retry operation=" + operationId);
            petRevivals.retryQuarantined(operationId).whenComplete((result, error) ->
                    completePetRevival(sender, result, error));
            return true;
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("compensate") && args[3].equals("CONFIRM")) {
            UUID operationId = parseUuid(sender, args[2]);
            if (operationId == null) return true;
            getLogger().warning("AUDIT actor=" + sender.getName()
                    + " action=pet-revival-compensate operation=" + operationId);
            petRevivals.compensateQuarantined(operationId, safeActor(sender.getName()))
                    .whenComplete((result, error) -> completePetRevival(sender, result, error));
            return true;
        }
        sender.sendMessage(Component.text(messages.text(sender, "admin.pet-revival-usage"),
                NamedTextColor.YELLOW));
        return true;
    }

    /**
     * Phase 2 (C-013): lifecycle ledger window. Reads are free; a freeze needs
     * a reason and a thaw needs a reason plus CONFIRM, because releasing a
     * frozen identity is the one mutation no automated path may perform.
     */
    private boolean handleEquipmentLedger(CommandSender sender, String[] args) {
        if (args.length == 2 && args[1].equalsIgnoreCase("watchlist")) {
            ledgerAudit.watchlist(25).whenComplete((entries, error) -> runMain(() -> {
                if (error != null || entries == null) {
                    sender.sendMessage(Component.text(messages.text(sender, "admin.equipment-ledger-failed"),
                            NamedTextColor.RED));
                    return;
                }
                sender.sendMessage(Component.text(messages.text(sender, "admin.equipment-watchlist",
                        Map.of("count", entries.size())), NamedTextColor.GOLD));
                for (EquipmentLedgerAuditService.Entry entry : entries) sendEquipmentEntry(sender, entry);
            }));
            return true;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("inspect")) {
            UUID itemId = parseUuid(sender, args[2]);
            if (itemId == null) return true;
            ledgerAudit.entry(itemId).whenComplete((entry, error) -> runMain(() -> {
                if (error != null || entry == null) {
                    sender.sendMessage(Component.text(messages.text(sender, "admin.equipment-ledger-failed"),
                            NamedTextColor.RED));
                } else if (entry.isEmpty()) {
                    sender.sendMessage(Component.text(messages.text(sender, "admin.equipment-ledger-not-found"),
                            NamedTextColor.YELLOW));
                } else {
                    sendEquipmentEntry(sender, entry.orElseThrow());
                }
            }));
            return true;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("audit")) {
            UUID itemId = parseUuid(sender, args[2]);
            if (itemId == null) return true;
            ledgerAudit.auditTrail(itemId, 20).whenComplete((records, error) -> runMain(() -> {
                if (error != null || records == null) {
                    sender.sendMessage(Component.text(messages.text(sender, "admin.equipment-ledger-failed"),
                            NamedTextColor.RED));
                    return;
                }
                sender.sendMessage(Component.text(messages.text(sender, "admin.equipment-audit",
                        Map.of("count", records.size())), NamedTextColor.GOLD));
                for (EquipmentLedgerAuditService.AuditRecord record : records) {
                    sender.sendMessage(Component.text(messages.text(sender, "admin.equipment-audit-line",
                            Map.of("action", record.action(), "from", record.fromState(),
                                    "to", record.toState(), "actor", record.actor(),
                                    "note", record.note())), NamedTextColor.YELLOW));
                }
            }));
            return true;
        }
        if (args.length >= 4 && (args[1].equalsIgnoreCase("quarantine") || args[1].equalsIgnoreCase("restore"))) {
            boolean freezing = args[1].equalsIgnoreCase("quarantine");
            // A freeze is the conservative direction, so only the thaw is gated.
            if (!freezing && !AdminConfirmation.isConfirmed(args[3])) {
                sender.sendMessage(Component.text(messages.text(sender, "admin.equipment-ledger-usage"),
                        NamedTextColor.YELLOW));
                return true;
            }
            String note = String.join(" ", java.util.Arrays.copyOfRange(args, freezing ? 3 : 4, args.length));
            if (note.isBlank() || note.length() > 500) {
                sender.sendMessage(Component.text(messages.text(sender, "admin.equipment-note-required"),
                        NamedTextColor.YELLOW));
                return true;
            }
            UUID itemId = parseUuid(sender, args[2]);
            if (itemId == null) return true;
            String actor = safeActor(sender.getName());
            getLogger().warning("AUDIT actor=" + sender.getName() + " action=equipment-"
                    + (freezing ? "quarantine" : "restore") + " target=" + itemId + " note=" + note);
            CompletionStage<EquipmentLedgerAuditService.AuditResult> operation = freezing
                    ? ledgerAudit.quarantine(itemId, actor, note)
                    : ledgerAudit.restore(itemId, actor, note);
            operation.whenComplete((result, error) -> completeEquipmentLedger(sender, result, error));
            return true;
        }
        sender.sendMessage(Component.text(messages.text(sender, "admin.equipment-ledger-usage"),
                NamedTextColor.YELLOW));
        return true;
    }

    private void sendEquipmentEntry(
            CommandSender sender, EquipmentLedgerAuditService.Entry entry
    ) {
        sender.sendMessage(Component.text(messages.text(sender, "admin.equipment-entry", Map.of(
                "item", entry.itemId(),
                "state", entry.state(),
                "owner", entry.ownerUuid() == null ? "-" : entry.ownerUuid(),
                "operation", entry.lastOperation() == null ? "-" : entry.lastOperation())), NamedTextColor.YELLOW));
    }

    private void completeEquipmentLedger(
            CommandSender sender, EquipmentLedgerAuditService.AuditResult result, Throwable error
    ) {
        runMain(() -> {
            if (error != null || result == null) {
                sender.sendMessage(Component.text(messages.text(sender, "admin.equipment-ledger-failed"),
                        NamedTextColor.RED));
                return;
            }
            String key = switch (result.status()) {
                case APPLIED -> "admin.equipment-ledger-applied";
                case ALREADY_IN_STATE -> "admin.equipment-ledger-already";
                case NOT_FOUND -> "admin.equipment-ledger-not-found";
                case NOT_FROZEN -> "admin.equipment-not-frozen";
                case TERMINAL -> "admin.equipment-terminal";
                case RULES_REFUSED -> "admin.equipment-rules-refused";
                case INVALID_NOTE -> "admin.equipment-note-required";
            };
            NamedTextColor color = switch (result.status()) {
                case APPLIED -> NamedTextColor.GREEN;
                case NOT_FOUND, NOT_FROZEN, TERMINAL, RULES_REFUSED, INVALID_NOTE -> NamedTextColor.RED;
                case ALREADY_IN_STATE -> NamedTextColor.YELLOW;
            };
            sender.sendMessage(Component.text(messages.text(sender, key, Map.of(
                    "state", String.valueOf(result.state()))), color));
        });
    }

    private UUID parseUuid(CommandSender sender, String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(Component.text(messages.text(sender, "admin.pet-revival-invalid-id"),
                    NamedTextColor.RED));
            return null;
        }
    }

    private void completePetRevival(CommandSender sender, PetRevivalResult result, Throwable error) {
        runMain(() -> {
            if (error != null || result == null) {
                sender.sendMessage(Component.text(messages.text(sender, "admin.pet-revival-failed"),
                        NamedTextColor.RED));
                return;
            }
            String key = switch (result.status()) {
                case PENDING -> "admin.pet-revival-retried";
                case COMPENSATED -> "admin.pet-revival-compensated";
                case BALANCE_LIMIT -> "admin.pet-revival-balance-limit";
                case NOT_FOUND -> "admin.pet-revival-not-found";
                case CONFLICT -> "admin.pet-revival-conflict";
                case INVALID -> "admin.pet-revival-invalid";
                default -> "admin.pet-revival-conflict";
            };
            NamedTextColor color = result.status() == PetRevivalResult.Status.PENDING
                    || result.status() == PetRevivalResult.Status.COMPENSATED
                    ? NamedTextColor.GREEN : NamedTextColor.YELLOW;
            Map<String, Object> values = result.intent().<Map<String, Object>>map(intent -> Map.of(
                    "operation", intent.operationId(), "player", intent.playerId(),
                    "pet", intent.petId(), "cost", intent.goldCost().toPlainString(),
                    "state", intent.state().name(), "reason", Objects.requireNonNullElse(
                            intent.failureReason(), "—"))).orElseGet(() -> Map.of("operation", "—"));
            sender.sendMessage(Component.text(messages.text(sender, key, values), color));
        });
    }

    private void sendPetRevivalAudit(CommandSender sender, PetRevivalIntent intent) {
        sender.sendMessage(Component.text(messages.text(sender, "admin.pet-revival-line", Map.of(
                "operation", intent.operationId(), "player", intent.playerId(), "pet", intent.petId(),
                "cost", intent.goldCost().toPlainString(), "state", intent.state().name(),
                "reason", intent.failureReason().isBlank() ? "—" : intent.failureReason())),
                NamedTextColor.AQUA));
    }

    private static String safeActor(String value) {
        String normalized = Objects.requireNonNullElse(value, "console").replaceAll("[^A-Za-z0-9._:-]", "_");
        return normalized.isBlank() ? "console" : normalized.substring(0, Math.min(64, normalized.length()));
    }

    private void runMain(Runnable action) {
        if (!isEnabled()) return;
        Bukkit.getScheduler().runTask(this, () -> {
            if (isEnabled()) action.run();
        });
    }

    private void completeIncident(
            CommandSender sender, IncidentCompensationResult result, Throwable error
    ) {
        Bukkit.getScheduler().runTask(this, () -> {
            if (error != null || result == null) {
                getLogger().warning("Incident compensation failed closed: "
                        + (error == null ? "empty result" : rootMessage(error)));
                sender.sendMessage(Component.text(messages.text(sender, "admin.incident-failed"), NamedTextColor.RED));
                return;
            }
            String key = switch (result.status()) {
                case APPLIED -> "admin.incident-applied";
                case DUPLICATE -> "admin.incident-duplicate";
                case CONFLICT -> "admin.incident-conflict";
                case BALANCE_LIMIT -> "admin.incident-balance-limit";
                case MERIT_LIMIT -> "admin.incident-merit-limit";
                case NOT_FOUND -> "admin.incident-not-found";
                case INVALID -> "admin.incident-invalid";
            };
            NamedTextColor color = result.status() == IncidentCompensationResult.Status.APPLIED
                    ? NamedTextColor.GREEN : result.status() == IncidentCompensationResult.Status.CONFLICT
                    ? NamedTextColor.RED : NamedTextColor.YELLOW;
            Map<String, Object> values = result.audit().<Map<String, Object>>map(audit -> Map.of(
                    "incident", audit.incidentKey(), "player", audit.playerId(),
                    "experience", audit.experience(), "gold", audit.totalGold().toPlainString(),
                    "merit", audit.totalMerit(), "evidence", String.join(", ", audit.evidenceIds()),
                    "conditional_applied", audit.conditionalActivities().stream()
                            .filter(line -> line.status() == cn.originalrpg.api.model.IncidentConditionalActivityAudit.Status.APPLIED)
                            .count(),
                    "conditional_present", audit.conditionalActivities().stream()
                            .filter(line -> line.status() == cn.originalrpg.api.model.IncidentConditionalActivityAudit.Status.ALREADY_PRESENT)
                            .count(),
                    "time", audit.appliedAt().toString())).orElseGet(() -> Map.of("incident", "-"));
            sender.sendMessage(Component.text(messages.text(sender, key, values), color));
        });
    }

    static IncidentCompensationRequest knownIncident(String incidentKey, String actor) {
        if (!"ledger-outage-2026-08-12-cure-white".equals(incidentKey)) return null;
        return new IncidentCompensationRequest(
                incidentKey, UUID.fromString("b8229850-d84a-39ff-bd79-7529c4887403"),
                LocalDate.of(2026, 8, 12), 34_100L,
                List.of(new IncidentActivityCompensation(EconomyActivity.HUNTING,
                        new BigDecimal("245.36"), 7L)),
                List.of(
                        advancementRecovery("minecraft:nether/find_bastion"),
                        advancementRecovery("minecraft:nether/loot_bastion"),
                        advancementRecovery("minecraft:nether/obtain_ancient_debris")),
                List.of("minecraft:nether/find_bastion", "minecraft:nether/loot_bastion",
                        "minecraft:nether/obtain_ancient_debris"),
                actor, "Restore rewards lost after the 2026-08-12 authoritative-ledger outage");
    }

    private static IncidentConditionalActivityCompensation advancementRecovery(String advancement) {
        UUID playerId = UUID.fromString("b8229850-d84a-39ff-bd79-7529c4887403");
        UUID identity = UUID.nameUUIDFromBytes(advancement.getBytes(StandardCharsets.UTF_8));
        return new IncidentConditionalActivityCompensation(
                advancement, EconomyActivity.EXPLORATION, new BigDecimal("120.00"), 1L,
                "adv:" + playerId + ":" + identity, "vanilla-advancement:" + advancement);
    }

    private boolean handleTest(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text(messages.text(sender, "admin.test-usage"), NamedTextColor.YELLOW));
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
        return switch (action) {
            case "status" -> {
                if (args.length != 3) {
                    sender.sendMessage(Component.text(messages.text(sender, "admin.test-usage"), NamedTextColor.YELLOW));
                    yield true;
                }
                auditTest(sender, action, target, "");
                completeTest(sender, target, action, adminTests.status(target.getUniqueId()), false);
                yield true;
            }
            case "max" -> {
                if (args.length != 4) {
                    sender.sendMessage(Component.text(messages.text(sender, "admin.test-usage"), NamedTextColor.YELLOW));
                    yield true;
                }
                Archetype archetype = parseConcreteArchetype(sender, args[3]);
                if (archetype == null) yield true;
                Map<String, Integer> catalog = testSkillCatalog();
                if (catalog.isEmpty()) {
                    sender.sendMessage(Component.text(messages.text(sender, "admin.test-skills-unavailable"),
                            NamedTextColor.RED));
                    yield true;
                }
                auditTest(sender, action, target, archetype.name() + " skills=" + catalog.size());
                completeTest(sender, target, action, adminTests.activate(
                        target.getUniqueId(), archetype, catalog,
                        getConfig().getInt("test-mode.attribute-value", 64),
                        getConfig().getInt("test-mode.skill-points", 10_000)), true);
                yield true;
            }
            case "class" -> {
                if (args.length != 4) {
                    sender.sendMessage(Component.text(messages.text(sender, "admin.test-usage"), NamedTextColor.YELLOW));
                    yield true;
                }
                Archetype archetype = parseConcreteArchetype(sender, args[3]);
                if (archetype == null) yield true;
                auditTest(sender, action, target, archetype.name());
                completeTest(sender, target, action,
                        adminTests.switchArchetype(target.getUniqueId(), archetype), true);
                yield true;
            }
            case "restore" -> {
                if (args.length != 3) {
                    sender.sendMessage(Component.text(messages.text(sender, "admin.test-usage"), NamedTextColor.YELLOW));
                    yield true;
                }
                auditTest(sender, action, target, "");
                completeTest(sender, target, action, adminTests.restore(target.getUniqueId()), true);
                yield true;
            }
            default -> {
                sender.sendMessage(Component.text(messages.text(sender, "admin.test-usage"), NamedTextColor.YELLOW));
                yield true;
            }
        };
    }

    private Map<String, Integer> testSkillCatalog() {
        org.bukkit.plugin.Plugin plugin = Bukkit.getPluginManager().getPlugin("RpgSkills");
        if (!(plugin instanceof JavaPlugin javaPlugin) || !plugin.isEnabled()) return Map.of();
        ConfigurationSection definitions = javaPlugin.getConfig().getConfigurationSection("skills");
        if (definitions == null) return Map.of();
        List<Integer> caps = javaPlugin.getConfig().getIntegerList(
                "skill-leveling.maximum-level-by-ascension");
        int maximumLevel = caps.isEmpty() ? 5 : Math.clamp(caps.getLast(), 1, 100);
        Map<String, Integer> result = new TreeMap<>();
        for (String id : definitions.getKeys(false)) result.put(id, maximumLevel);
        return Map.copyOf(result);
    }

    private Archetype parseConcreteArchetype(CommandSender sender, String value) {
        Archetype archetype = Archetype.parse(value).orElse(null);
        if (archetype == null || archetype == Archetype.UNCHOSEN) {
            sender.sendMessage(Component.text(messages.text(sender, "admin.test-class-invalid", Map.of(
                    "classes", String.join(", ", classNames()))), NamedTextColor.RED));
            return null;
        }
        return archetype;
    }

    private void completeTest(
            CommandSender sender,
            OfflinePlayer target,
            String action,
            java.util.concurrent.CompletionStage<AdminTestState> operation,
            boolean openSkills
    ) {
        operation.whenComplete((state, error) -> Bukkit.getScheduler().runTask(this, () -> {
            if (error != null) {
                getLogger().warning("Administrator test operation failed: " + rootMessage(error));
                sender.sendMessage(Component.text(messages.text(sender, "admin.test-failed", Map.of(
                        "reason", rootMessage(error))), NamedTextColor.RED));
                return;
            }
            sender.sendMessage(Component.text(messages.text(sender,
                    state.active() ? "admin.test-active" : "admin.test-restored", Map.of(
                            "player", Objects.requireNonNullElse(target.getName(), target.getUniqueId().toString()),
                            "class", messages.text(sender, "classes."
                                    + state.profile().archetype().name().toLowerCase(Locale.ROOT)),
                            "ascension", state.profile().ascension(), "level", state.profile().level(),
                            "attributes", state.profile().attributes().values().stream()
                                    .mapToInt(Integer::intValue).max().orElse(0),
                            "skills", state.learnedSkills())), NamedTextColor.GREEN));
            if (openSkills && target.isOnline() && target.getPlayer() != null) {
                target.getPlayer().performCommand("skills");
                target.getPlayer().sendMessage(Component.text(messages.text(
                        target.getPlayer(), "admin.test-player-hint"), NamedTextColor.AQUA));
            }
        }));
    }

    private void auditTest(CommandSender sender, String action, OfflinePlayer target, String detail) {
        getLogger().warning("AUDIT actor=" + sender.getName() + " action=test-" + action
                + " target=" + target.getUniqueId() + (detail.isBlank() ? "" : " detail=" + detail));
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return Objects.requireNonNullElse(current.getMessage(), current.getClass().getSimpleName());
    }

    private static List<String> classNames() {
        return java.util.Arrays.stream(Archetype.values())
                .filter(value -> value != Archetype.UNCHOSEN)
                .map(value -> value.name().toLowerCase(Locale.ROOT)).toList();
    }

    private boolean handleChapter(CommandSender sender, String[] args) {
        if (args.length == 1 || args.length == 2 && args[1].equalsIgnoreCase("status")) {
            showChapter(sender, chapters.current());
            return true;
        }
        if (args.length < 5 || !args[1].equalsIgnoreCase("advance")
                || !AdminConfirmation.isConfirmed(args[3])) {
            sender.sendMessage(Component.text(messages.text(sender, "admin.chapter-usage"), NamedTextColor.YELLOW));
            return true;
        }
        String target = args[2].toLowerCase(Locale.ROOT);
        String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length)).strip();
        getLogger().warning("AUDIT actor=" + sender.getName() + " action=chapter-advance target="
                + target + " reason=" + reason);
        chapters.advance(target, sender.getName(), reason).whenComplete((result, error) ->
                Bukkit.getScheduler().runTask(this, () -> {
                    if (error != null) {
                        sender.sendMessage(Component.text(
                                messages.text(sender, "admin.chapter-failed"), NamedTextColor.RED));
                        getLogger().warning("Chapter advance failed closed: " + error.getMessage());
                        return;
                    }
                    String key = switch (result.status()) {
                        case SUCCESS -> "admin.chapter-advance-success";
                        case ALREADY_CURRENT -> "admin.chapter-already-current";
                        case UNKNOWN_CHAPTER -> "admin.chapter-unknown";
                        case NOT_NEXT_CHAPTER -> "admin.chapter-not-next";
                        case INVALID_REQUEST -> "admin.chapter-invalid";
                    };
                    NamedTextColor color = result.status() == ChapterTransitionResult.Status.SUCCESS
                            ? NamedTextColor.GREEN : NamedTextColor.YELLOW;
                    exportChapterSnapshot();
                    sender.sendMessage(Component.text(messages.text(sender, key, Map.of(
                            "chapter", chapterName(sender, result.state()),
                            "id", result.state().chapterId(),
                            "revision", result.state().revision())), color));
                    showChapter(sender, result.state());
                }));
        return true;
    }

    private void showChapter(CommandSender sender, ChapterState state) {
        sender.sendMessage(Component.text(messages.text(sender, "admin.chapter-title"), NamedTextColor.GOLD));
        sender.sendMessage(Component.text(messages.text(sender, "admin.chapter-current", Map.of(
                "season", state.seasonId(), "chapter", chapterName(sender, state),
                "id", state.chapterId(), "maximum", state.maximumOpenAscension(),
                "revision", state.revision(), "changed", state.changedAt().toString())), NamedTextColor.LIGHT_PURPLE));
        String next = chapters.next()
                .map(definition -> messages.text(sender, "admin.chapter-next", Map.of(
                        "chapter", messages.text(sender, "chapters." + definition.id() + ".name"),
                        "id", definition.id(), "maximum", definition.maximumOpenAscension())))
                .orElseGet(() -> messages.text(sender, "admin.chapter-final"));
        sender.sendMessage(Component.text(next, NamedTextColor.AQUA));
    }

    private String chapterName(CommandSender sender, ChapterState state) {
        return messages.text(sender, "chapters." + state.chapterId() + ".name");
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] args
    ) {
        if (args.length == 1) return prefix(List.of(
                "economy", "chapter", "season", "incident", "petrevival", "equipment",
                "xp", "energy", "test"), args[0]);
        if (args[0].equalsIgnoreCase("season")) {
            return seasonSettlements == null ? List.of() : SeasonSettlementCommand.complete(args);
        }
        if (args[0].equalsIgnoreCase("incident")) {
            if (args.length == 2) return prefix(List.of("audit", "apply"), args[1]);
            if (args.length == 3) return prefix(List.of("ledger-outage-2026-08-12-cure-white"), args[2]);
            if (args.length == 4 && args[1].equalsIgnoreCase("apply")) return prefix(List.of("CONFIRM"), args[3]);
            return List.of();
        }
        if (args[0].equalsIgnoreCase("test")) {
            if (args.length == 2) return prefix(List.of("status", "max", "class", "restore"), args[1]);
            if (args.length == 3) return prefix(Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName).sorted().toList(), args[2]);
            if (args.length == 4 && (args[1].equalsIgnoreCase("max")
                    || args[1].equalsIgnoreCase("class"))) return prefix(classNames(), args[3]);
            return List.of();
        }
        if (args[0].equalsIgnoreCase("petrevival")) {
            if (args.length == 2) return prefix(List.of("queue", "audit", "retry", "compensate"), args[1]);
            if (args.length == 4 && (args[1].equalsIgnoreCase("retry")
                    || args[1].equalsIgnoreCase("compensate"))) return prefix(List.of("CONFIRM"), args[3]);
            return List.of();
        }
        if (args[0].equalsIgnoreCase("equipment")) {
            if (args.length == 2) {
                return prefix(List.of("watchlist", "inspect", "audit", "quarantine", "restore"), args[1]);
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("restore")) return prefix(List.of("CONFIRM"), args[3]);
            return List.of();
        }
        if (!args[0].equalsIgnoreCase("chapter")) return List.of();
        if (args.length == 2) return prefix(List.of("status", "advance"), args[1]);
        if (args.length == 3 && args[1].equalsIgnoreCase("advance")) {
            return chapters.next().map(next -> prefix(List.of(next.id()), args[2])).orElse(List.of());
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("advance")) {
            return prefix(List.of("CONFIRM"), args[3]);
        }
        return List.of();
    }

    private static List<String> prefix(List<String> values, String input) {
        String normalized = input.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized)).toList();
    }

    private void showEconomy(CommandSender sender) {
        economyAnalytics.dailyReport().whenComplete((report, error) -> Bukkit.getScheduler().runTask(this, () -> {
            if (error != null) {
                sender.sendMessage(Component.text(messages.text(sender, "admin.report-failed"), NamedTextColor.RED));
                return;
            }
            sender.sendMessage(Component.text(messages.text(sender, "admin.report-title", Map.of(
                    "date", report.date())), NamedTextColor.GOLD));
            sender.sendMessage(Component.text(messages.text(sender, "admin.report-supply", Map.of(
                    "accounts", report.accountCount(), "gold", report.totalGold().toPlainString(),
                    "merit", report.totalMerit(), "starlight", report.totalStarlight(),
                    "active", report.activeGold30d().toPlainString())), NamedTextColor.YELLOW));
            sender.sendMessage(Component.text(messages.text(sender, "admin.report-distribution", Map.of(
                    "median", report.medianGold().toPlainString(), "p90", report.p90Gold().toPlainString(),
                    "p99", report.p99Gold().toPlainString(),
                    "top", percent(report.topOnePercentGoldShare()))), NamedTextColor.AQUA));
            sender.sendMessage(Component.text(messages.text(sender, "admin.report-market", Map.of(
                    "listings", report.activeListingCount(), "deliveries", report.pendingDeliveryCount(),
                    "quarantined", report.quarantinedListingCount(),
                    "share", percent(report.marketTopSellerShare30d()),
                    "age", report.p90ActiveListingAgeHours().toPlainString(),
                    "sold", report.soldListings7d(), "expired", report.expiredListings7d())), NamedTextColor.GOLD));
            sendFlow(sender, "admin.report-flow-day", report.daily());
            sendFlow(sender, "admin.report-flow-seven", report.sevenDays());
            sendFlow(sender, "admin.report-flow-thirty", report.thirtyDays());
            sender.sendMessage(Component.text(messages.text(sender, "admin.report-secondary-flow", Map.of(
                    "meritMinted", report.sevenDays().mintedMerit(), "meritSpent", report.sevenDays().spentMerit(),
                    "starlightMinted", report.sevenDays().mintedStarlight(),
                    "starlightSpent", report.sevenDays().spentStarlight())), NamedTextColor.LIGHT_PURPLE));
            sender.sendMessage(Component.text(messages.text(sender, "admin.report-sources", Map.of(
                    "sources", reasons(report.topSources7d()))), NamedTextColor.GREEN));
            sender.sendMessage(Component.text(messages.text(sender, "admin.report-sinks", Map.of(
                    "sinks", reasons(report.topSinks7d()))), NamedTextColor.RED));
            EconomyWeekComparison weekComparison = latestEconomyTrend;
            sendHealth(sender, report.sevenDays().mintedGold(), report.sevenDays().sinkRatio(),
                    report.topOnePercentGoldShare(), report.marketTopSellerShare30d(),
                    largestSourceShare(report), report.quarantinedListingCount(),
                    report.p90ActiveListingAgeHours(), report.soldListings7d(), report.expiredListings7d(),
                    weekComparison);
            sendWeekComparison(sender, weekComparison);
        }));
    }

    private void sendWeekComparison(CommandSender sender, EconomyWeekComparison comparison) {
        if (comparison == null) {
            sender.sendMessage(Component.text(messages.text(sender,
                    "admin.report-week-pending"), NamedTextColor.GRAY));
            return;
        }
        if (comparison.status() == EconomyWeekComparison.Status.INSUFFICIENT_HISTORY) {
            sender.sendMessage(Component.text(messages.text(sender, "admin.report-week-insufficient", Map.of(
                    "days", comparison.historicalDays(), "baseline", comparison.baselineDate())),
                    NamedTextColor.GRAY));
            return;
        }
        if (comparison.status() == EconomyWeekComparison.Status.ZERO_BASELINE) {
            sender.sendMessage(Component.text(messages.text(sender, "admin.report-week-zero", Map.of(
                    "baseline", comparison.baselineDate())), NamedTextColor.GRAY));
            return;
        }
        sender.sendMessage(Component.text(messages.text(sender, "admin.report-week-comparison", Map.of(
                "baseline", comparison.baselineDate(),
                "active", comparison.currentActiveGold().toPlainString(),
                "activeDelta", comparison.activeGoldDelta().toPlainString(),
                "activeRatio", percent(comparison.activeGoldChangeRatio()),
                "total", comparison.currentTotalGold().toPlainString(),
                "totalDelta", comparison.totalGoldDelta().toPlainString(),
                "totalRatio", percent(comparison.totalGoldChangeRatio()))), NamedTextColor.LIGHT_PURPLE));
    }

    private void sendFlow(CommandSender sender, String key, cn.originalrpg.api.model.EconomyFlow flow) {
        sender.sendMessage(Component.text(messages.text(sender, key, Map.of(
                "minted", flow.mintedGold().toPlainString(), "sunk",
                flow.burnedGold().add(flow.feesBurned()).toPlainString(),
                "net", flow.netGold().toPlainString(), "ratio", flow.sinkRatio().toPlainString(),
                "volume", flow.transferVolume().toPlainString())), NamedTextColor.AQUA));
    }

    private void sendHealth(CommandSender sender, BigDecimal mintedGold, BigDecimal sinkRatio,
                            BigDecimal wealthShare, BigDecimal marketShare, BigDecimal largestSourceShare,
                            long quarantined, BigDecimal p90ListingAgeHours,
                            long soldListings7d, long expiredListings7d,
                            EconomyWeekComparison weekComparison) {
        EconomyHealthPolicy.State state = EconomyHealthPolicy.evaluate(mintedGold, sinkRatio, wealthShare,
                marketShare, largestSourceShare, quarantined, p90ListingAgeHours,
                soldListings7d, expiredListings7d,
                weekComparison != null && weekComparison.available()
                        ? weekComparison.activeGoldChangeRatio() : null);
        NamedTextColor color = switch (state) {
            case INSUFFICIENT_DATA -> NamedTextColor.GRAY;
            case HEALTHY -> NamedTextColor.GREEN;
            case WARNING -> NamedTextColor.YELLOW;
            case CRITICAL -> NamedTextColor.RED;
        };
        String health = messages.text(sender, switch (state) {
            case INSUFFICIENT_DATA -> "admin.report-health-insufficient";
            case HEALTHY -> "admin.report-health-healthy";
            case WARNING -> "admin.report-health-warning";
            case CRITICAL -> "admin.report-health-critical";
        });
        sender.sendMessage(Component.text(health, color));
    }

    private static BigDecimal largestSourceShare(cn.originalrpg.api.model.EconomyReport report) {
        if (report.sevenDays().mintedGold().signum() <= 0 || report.topSources7d().isEmpty()) {
            return BigDecimal.ZERO.setScale(4);
        }
        return report.topSources7d().getFirst().amount().divide(
                report.sevenDays().mintedGold(), 4, RoundingMode.HALF_UP);
    }

    private void exportEconomySnapshot() {
        AdminSnapshotIo io = snapshotIo;
        long generation = snapshotGenerations.get();
        if (!currentSnapshotIo(io, generation) || !economyCollectionInFlight.compareAndSet(false, true)) return;
        economyAnalytics.dailyReport().whenComplete((report, error) -> {
            if (!currentSnapshotIo(io, generation)) return;
            if (error != null || report == null) {
                economyCollectionInFlight.set(false);
                getLogger().warning("Could not collect economy snapshot: "
                        + (error == null ? "empty report" : rootMessage(error)));
                return;
            }
            io.economy(report).whenComplete((result, ioError) -> {
                if (!currentSnapshotIo(io, generation)) return;
                economyCollectionInFlight.set(false);
                if (ioError != null || result == null) {
                    getLogger().warning("Could not write economy snapshot: "
                            + (ioError == null ? "empty result" : rootMessage(ioError)));
                    return;
                }
                latestEconomyTrend = result.comparison();
                if (result.historyFailure() != null) {
                    getLogger().warning("Could not persist economy history: "
                            + result.historyFailure().getMessage());
                }
            });
        });
    }

    private void exportChapterSnapshot() {
        AdminSnapshotIo io = snapshotIo;
        long generation = snapshotGenerations.get();
        if (!currentSnapshotIo(io, generation)) return;
        // ChapterService is Bukkit-facing. Capture an immutable value on the
        // scheduler thread; the I/O worker never calls back into the service.
        ChapterState current = chapters.current();
        ChapterState snapshot = new ChapterState(current.seasonId(), current.chapterId(), current.sequence(),
                current.maximumOpenAscension(), current.revision(), current.changedAt());
        io.chapter(snapshot).whenComplete((ignored, error) -> {
            if (error != null && currentSnapshotIo(io, generation)
                    && !(rootCause(error) instanceof java.util.concurrent.RejectedExecutionException)) {
                getLogger().warning("Could not write chapter snapshot: " + rootMessage(error));
            }
        });
    }

    private boolean currentSnapshotIo(AdminSnapshotIo candidate, long generation) {
        return candidate != null && snapshotLifecycleActive.get() && snapshotIo == candidate
                && snapshotGenerations.get() == generation;
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current;
    }

    private static String percent(BigDecimal ratio) {
        return ratio.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private static String reasons(java.util.List<cn.originalrpg.api.model.EconomyReasonTotal> reasons) {
        if (reasons.isEmpty()) return "—";
        return reasons.stream().map(reason -> reason.reason() + "=" + reason.amount().toPlainString())
                .collect(java.util.stream.Collectors.joining(" · "));
    }
}
