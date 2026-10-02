package cn.originalrpg.enhancement;

import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.model.SkillMutationRequest;
import cn.originalrpg.api.model.SkillMutationResult;
import cn.originalrpg.api.model.SkillProgress;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.SkillService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/** Preview-first, transaction-safe mutation and deterministic base restoration. */
final class MutationCommand implements CommandExecutor, TabCompleter, Listener {
    private static final String PREVIEW = "preview";
    private static final String CONFIRM = "confirm";
    private static final String RESTORE = "restore";

    private final Plugin plugin;
    private final SkillService skills;
    private final MessageService messages;
    private final EnhancementVisuals visuals;
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();
    private final Map<UUID, PreviewApproval> approvals = new ConcurrentHashMap<>();

    MutationCommand(Plugin plugin, SkillService skills,
                    MessageService messages, EnhancementVisuals visuals) {
        this.plugin = plugin;
        this.skills = skills;
        this.messages = messages;
        this.visuals = visuals;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::purgeExpiredApprovals,
                1_200L, 1_200L);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.text(sender, "common.player-only"));
            return true;
        }
        if (args.length < 1 || args.length > 2) {
            player.sendMessage(Component.text(messages.text(player, "enhancement.mutation-usage"),
                    NamedTextColor.YELLOW));
            return true;
        }
        String skill = args[0].toLowerCase(Locale.ROOT);
        String action = args.length == 1 ? PREVIEW : args[1].toLowerCase(Locale.ROOT);
        if (!Set.of(PREVIEW, CONFIRM, RESTORE).contains(action)) {
            player.sendMessage(Component.text(messages.text(player, "enhancement.mutation-usage"),
                    NamedTextColor.YELLOW));
            return true;
        }
        List<String> variants = plugin.getConfig().getStringList("mutations." + skill + ".variants");
        if (variants.isEmpty()) {
            player.sendMessage(Component.text(messages.text(player, "enhancement.mutation-unavailable"),
                    NamedTextColor.YELLOW));
            return true;
        }
        skills.skill(player.getUniqueId(), skill).whenComplete((progress, lookupError) ->
                sync(() -> {
                    if (!currentSession(player)) return;
                    if (lookupError != null) {
                        player.sendMessage(Component.text(messages.text(player,
                                "enhancement.skill-load-failed"), NamedTextColor.RED));
                    } else if (progress.isEmpty()) {
                        player.sendMessage(Component.text(messages.text(player,
                                "enhancement.skill-required"), NamedTextColor.RED));
                    } else if (action.equals(CONFIRM)) {
                        SkillProgress current = progress.orElseThrow();
                        if (claimPreview(player, skill, current)) startMutation(player, skill, variants, current);
                        else requirePreview(player, skill, variants, current);
                    } else if (action.equals(RESTORE)) {
                        SkillProgress current = progress.orElseThrow();
                        if (claimPreview(player, skill, current)) startRestore(player, skill, current);
                        else requirePreview(player, skill, variants, current);
                    } else {
                        showPreview(player, skill, variants, progress.orElseThrow());
                    }
                }));
        return true;
    }

    private void showPreview(Player player, String skill, List<String> variants, SkillProgress progress) {
        long previewSeconds = Math.clamp(plugin.getConfig().getLong(
                "mutation-preview-seconds", 60L), 15L, 300L);
        approvals.put(player.getUniqueId(), new PreviewApproval(skill,
                progress.variant().orElse(null), System.currentTimeMillis() + previewSeconds * 1_000L));
        String skillName = messages.text(player, "skills.names." + skill);
        String current = progress.variant().map(value -> variantName(player, value))
                .orElseGet(() -> messages.text(player, "enhancement.mutation-base"));
        BigDecimal price = configuredPrice(skill, "price", "3500.00");
        BigDecimal restorePrice = configuredPrice(skill, "restore-price", "900.00");
        double rate = Math.clamp(plugin.getConfig().getDouble(
                "mutations." + skill + ".success-rate", 0.25), 0.0, 1.0);

        player.sendMessage(Component.text(messages.text(player, "enhancement.mutation-preview-title", Map.of(
                "skill", skillName)), NamedTextColor.LIGHT_PURPLE));
        player.sendMessage(Component.text(messages.text(player, "enhancement.mutation-preview-current", Map.of(
                "current", current)), NamedTextColor.GRAY));
        for (String variant : variants) {
            String normalized = variant.toUpperCase(Locale.ROOT);
            player.sendMessage(Component.text(messages.text(player, "enhancement.mutation-preview-target", Map.of(
                    "variant", variantName(player, normalized))), NamedTextColor.AQUA));
            for (String line : messages.lines(player, "skill-variant-details." + messageId(normalized))) {
                player.sendMessage(Component.text("  " + line, NamedTextColor.GRAY));
            }
        }
        player.sendMessage(Component.text(messages.text(player, "enhancement.mutation-preview-cost", Map.of(
                "price", price.toPlainString(), "rate", percentage(rate))), NamedTextColor.GOLD));
        player.sendMessage(Component.text(messages.text(player, "enhancement.mutation-preview-rule", Map.of(
                "restore", restorePrice.toPlainString())), NamedTextColor.YELLOW));

        Component controls = Component.empty();
        if (progress.variant().isEmpty()) {
            controls = controls.append(button(player, "enhancement.mutation-confirm-button",
                    "/mutate " + skill + " confirm", NamedTextColor.GREEN));
        } else {
            controls = controls.append(button(player, "enhancement.mutation-restore-button",
                    "/mutate " + skill + " restore", NamedTextColor.YELLOW));
        }
        controls = controls.append(Component.space()).append(button(player,
                "enhancement.mutation-skills-button", "/skills", NamedTextColor.AQUA));
        player.sendMessage(controls);
    }

    private void requirePreview(Player player, String skill, List<String> variants, SkillProgress progress) {
        player.sendMessage(Component.text(messages.text(player,
                "enhancement.mutation-preview-required"), NamedTextColor.YELLOW));
        showPreview(player, skill, variants, progress);
    }

    private boolean claimPreview(Player player, String skill, SkillProgress progress) {
        PreviewApproval approval = approvals.remove(player.getUniqueId());
        return approval != null && approval.expiresAt() >= System.currentTimeMillis()
                && approval.skill().equals(skill)
                && Objects.equals(approval.variant(), progress.variant().orElse(null));
    }

    private void purgeExpiredApprovals() {
        long now = System.currentTimeMillis();
        approvals.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
    }

    @EventHandler
    public void onAuthenticationRevoked(AuthenticatedPlayerRevokedEvent event) {
        approvals.remove(event.player().getUniqueId());
        // Submitted atomic mutations retain active until completion.
    }

    private Component button(Player player, String key, String command, NamedTextColor color) {
        return Component.text("[" + messages.text(player, key) + "]", color)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(command, NamedTextColor.GRAY)));
    }

    private void startMutation(Player player, String skill, List<String> variants, SkillProgress progress) {
        if (progress.variant().isPresent()) {
            player.sendMessage(Component.text(messages.text(player, "enhancement.mutation-already-active", Map.of(
                    "variant", variantName(player, progress.variant().orElseThrow()))), NamedTextColor.YELLOW));
            showPreview(player, skill, variants, progress);
            return;
        }
        if (!active.add(player.getUniqueId())) {
            player.sendMessage(Component.text(messages.text(player, "enhancement.mutation-pending"),
                    NamedTextColor.YELLOW));
            return;
        }
        BigDecimal price = configuredPrice(skill, "price", "3500.00");
        String operationKey = "skill-mutation:" + UUID.randomUUID();
        double rate = Math.clamp(plugin.getConfig().getDouble(
                "mutations." + skill + ".success-rate", 0.25), 0.0, 1.0);
        Optional<String> candidate = ThreadLocalRandom.current().nextDouble() <= rate
                ? Optional.of(variants.get(ThreadLocalRandom.current().nextInt(variants.size()))
                .toUpperCase(Locale.ROOT))
                : Optional.empty();
        SkillMutationRequest request = new SkillMutationRequest(
                player.getUniqueId(), skill, SkillMutationRequest.Operation.MUTATE,
                progress.variant(), candidate, price, operationKey);
        skills.settleMutation(request).whenComplete((result, error) ->
                sync(() -> finishMutation(player, result, error)));
    }

    private void startRestore(Player player, String skill, SkillProgress progress) {
        if (progress.variant().isEmpty()) {
            player.sendMessage(Component.text(messages.text(player,
                    "enhancement.mutation-already-base"), NamedTextColor.YELLOW));
            return;
        }
        if (!active.add(player.getUniqueId())) {
            player.sendMessage(Component.text(messages.text(player,
                    "enhancement.mutation-pending"), NamedTextColor.YELLOW));
            return;
        }
        BigDecimal price = configuredPrice(skill, "restore-price", "900.00");
        String operationKey = "skill-mutation-restore:" + UUID.randomUUID();
        SkillMutationRequest request = new SkillMutationRequest(
                player.getUniqueId(), skill, SkillMutationRequest.Operation.RESTORE,
                progress.variant(), Optional.empty(), price, operationKey);
        skills.settleMutation(request).whenComplete((result, error) ->
                sync(() -> finishMutation(player, result, error)));
    }

    private void finishMutation(Player player, SkillMutationResult result, Throwable error) {
        active.remove(player.getUniqueId());
        if (!currentSession(player)) return;
        if (error != null || result == null) {
            player.sendMessage(Component.text(messages.text(player,
                    "enhancement.mutation-atomic-failed"), NamedTextColor.RED));
            return;
        }
        switch (result.status()) {
            case INSUFFICIENT_FUNDS -> player.sendMessage(Component.text(
                    messages.text(player, "enhancement.settlement-failed"), NamedTextColor.RED));
            case NOT_LEARNED, STALE_VARIANT -> player.sendMessage(Component.text(
                    messages.text(player, "enhancement.mutation-state-changed"), NamedTextColor.YELLOW));
            case INVALID -> player.sendMessage(Component.text(
                    messages.text(player, "enhancement.mutation-atomic-failed"), NamedTextColor.RED));
            case SETTLED, REPLAYED -> showMutationOutcome(player, result);
        }
    }

    private void showMutationOutcome(Player player, SkillMutationResult result) {
        switch (result.outcome()) {
            case FAILED_ROLL -> {
                player.sendMessage(Component.text(messages.text(player,
                        "enhancement.mutation-failed"), NamedTextColor.RED));
                visuals.mutationFailure(player);
            }
            case MUTATED -> {
                String variant = result.resultingVariant().orElseThrow();
                String name = variantName(player, variant);
                player.sendMessage(Component.text(messages.text(player,
                        "enhancement.mutation-success", Map.of("variant", name)),
                        NamedTextColor.LIGHT_PURPLE));
                visuals.mutationSuccess(player, name);
            }
            case RESTORED -> player.sendMessage(Component.text(messages.text(player,
                    "enhancement.mutation-restored"), NamedTextColor.GREEN));
            case NONE -> player.sendMessage(Component.text(messages.text(player,
                    "enhancement.mutation-atomic-failed"), NamedTextColor.RED));
        }
    }

    private BigDecimal configuredPrice(String skill, String field, String fallback) {
        return new BigDecimal(plugin.getConfig().getString(
                "mutations." + skill + "." + field, fallback));
    }

    private String variantName(CommandSender audience, String variant) {
        return messages.text(audience, "skill-variants." + messageId(variant));
    }

    private static String messageId(String variant) {
        return variant.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String percentage(double rate) {
        return String.format(Locale.ROOT, "%.0f%%", rate * 100.0);
    }

    private boolean currentSession(Player player) {
        return player != null && player.isOnline()
                && Bukkit.getPlayer(player.getUniqueId()) == player;
    }

    private void sync(Runnable action) {
        if (!plugin.isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (plugin.isEnabled()) action.run();
            });
        } catch (IllegalStateException ignored) {
            // The database result remains authoritative; the replacement login
            // will load it through the normal skill snapshot path.
        }
    }

    private record PreviewApproval(String skill, String variant, long expiresAt) {
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            org.bukkit.configuration.ConfigurationSection root =
                    plugin.getConfig().getConfigurationSection("mutations");
            return root == null ? List.of() : root.getKeys(false).stream()
                    .filter(value -> value.startsWith(prefix)).sorted().toList();
        }
        if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return List.of(PREVIEW, CONFIRM, RESTORE).stream()
                    .filter(value -> value.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
