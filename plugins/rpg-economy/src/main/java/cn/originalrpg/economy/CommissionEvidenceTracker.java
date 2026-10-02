package cn.originalrpg.economy;

import cn.originalrpg.api.model.CommissionEvidenceClaimResult;
import cn.originalrpg.api.model.CommissionEvidenceGrantResult;
import cn.originalrpg.api.model.CommissionEvidenceSnapshot;
import cn.originalrpg.api.service.CommissionEvidenceService;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;

/** Applies validated plugin policy to the database-authoritative evidence service. */
final class CommissionEvidenceTracker {
    private static final Pattern ID = Pattern.compile("[a-z0-9._-]{2,64}");

    private final Plugin plugin;
    private final CommissionEvidenceService service;
    private final ZoneId zoneId;

    CommissionEvidenceTracker(Plugin plugin, CommissionEvidenceService service) {
        this.plugin = plugin;
        this.service = service;
        ZoneId configured;
        try {
            configured = ZoneId.of(plugin.getConfig().getString(
                    "commissions-policy.timezone", "Asia/Shanghai"));
        } catch (RuntimeException exception) {
            configured = ZoneId.of("Asia/Shanghai");
        }
        zoneId = configured;
    }

    LocalDate today() {
        return LocalDate.now(zoneId);
    }

    CompletionStage<CommissionEvidenceSnapshot> snapshot(Player player) {
        return service.snapshot(player.getUniqueId(), today(), dailyCap());
    }

    CompletionStage<CommissionEvidenceGrantResult> grant(Player player, String source) {
        String normalized = source == null ? "" : source.toLowerCase(Locale.ROOT);
        String path = "commissions-policy.active-evidence.sources." + normalized;
        int requested = Math.clamp(plugin.getConfig().getInt(path + ".grant", 0), 0, 16);
        int sourceCap = Math.clamp(plugin.getConfig().getInt(path + ".daily-cap", 0), 0, 32);
        if (requested == 0 || sourceCap == 0 || !ID.matcher(normalized).matches()) {
            return snapshot(player).thenApply(value -> new CommissionEvidenceGrantResult(0, value));
        }
        return service.grant(player.getUniqueId(), today(), normalized, requested, sourceCap, dailyCap());
    }

    CompletionStage<CommissionEvidenceClaimResult> claim(
            Player player, UUID receiptId, String commissionId, int evidenceCost,
            int totalDailyLimit, int offerDailyLimit
    ) {
        return claim(player, today(), receiptId, commissionId, evidenceCost,
                totalDailyLimit, offerDailyLimit);
    }

    CompletionStage<CommissionEvidenceClaimResult> claim(
            Player player, LocalDate date, UUID receiptId, String commissionId, int evidenceCost,
            int totalDailyLimit, int offerDailyLimit
    ) {
        if (receiptId == null || commissionId == null || !ID.matcher(commissionId).matches()
                || evidenceCost <= 0 || totalDailyLimit <= 0 || offerDailyLimit <= 0) {
            return snapshot(player).thenApply(value -> new CommissionEvidenceClaimResult(
                    CommissionEvidenceClaimResult.Status.INVALID, value));
        }
        return service.claim(player.getUniqueId(), date, receiptId, commissionId, evidenceCost,
                totalDailyLimit, offerDailyLimit, dailyCap());
    }

    CompletionStage<Boolean> release(Player player, UUID receiptId) {
        return release(player, today(), receiptId);
    }

    CompletionStage<Boolean> release(Player player, LocalDate date, UUID receiptId) {
        return service.release(player.getUniqueId(), date, receiptId);
    }

    private int dailyCap() {
        return Math.clamp(plugin.getConfig().getInt(
                "commissions-policy.active-evidence.daily-cap", 4), 1, 32);
    }
}
