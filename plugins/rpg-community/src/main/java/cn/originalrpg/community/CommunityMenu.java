package cn.originalrpg.community;

import cn.originalrpg.api.model.CommunityLeaderboardEntry;
import cn.originalrpg.api.model.CommunityMembership;
import cn.originalrpg.api.model.CommunityProfile;
import cn.originalrpg.api.service.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import cn.originalrpg.api.ui.TrustedMenuHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.math.RoundingMode;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class CommunityMenu implements TrustedMenuHolder {
    private final Inventory inventory;
    private final Map<Integer, String> actions = new HashMap<>();

    CommunityMenu(
            Player player,
            MessageService messages,
            CommunityProfile profile,
            List<CommunityLeaderboardEntry> leaderboard,
            BigDecimal rewardMinimumContribution,
            int rewardHonor
    ) {
        inventory = Bukkit.createInventory(this, 27,
                Component.text(messages.text(player, "community.menu.title"), NamedTextColor.DARK_GREEN));
        fill();
        CommunityMembership membership = profile.membership().orElse(null);
        if (membership == null) {
            set(10, Material.OAK_SIGN, messages.text(player, "community.menu.no-community"),
                    List.of(messages.text(player, "community.menu.no-community-lore")), "community browse");
        } else {
            var summary = membership.community();
            set(10, Material.LODESTONE,
                    messages.text(player, "community.menu.community",
                            Map.of("tag", summary.tag(), "name", summary.name())),
                    List.of(
                            messages.text(player, "community.menu.members",
                                    Map.of("members", summary.members(), "capacity", summary.capacity())),
                            messages.text(player, "community.menu.role",
                                    Map.of("role", messages.text(player, "community.roles."
                                            + membership.role().name().toLowerCase(Locale.ROOT)))),
                            messages.text(player, "community.menu.lifetime",
                                    Map.of("amount", amount(membership.lifetimeContribution())))
                    ), "community status");
        }
        set(12, membership != null && profile.weeklyRewardClaimed()
                        ? Material.FILLED_MAP : Material.GOLD_INGOT,
                messages.text(player, "community.menu.project"),
                membership == null ? List.of(messages.text(player, "community.menu.project-join"))
                        : List.of(
                                messages.text(player, "community.menu.project-progress", Map.of(
                                        "current", amount(membership.community().periodContribution()),
                                        "goal", amount(membership.community().periodGoal()))),
                                messages.text(player, "community.menu.project-personal", Map.of(
                                        "amount", amount(membership.periodContribution()))),
                                messages.text(player, "community.menu.project-period-cap", Map.of(
                                        "amount", amount(membership.periodCapContribution()))),
                                messages.text(player, "community.menu.project-sink"),
                                messages.text(player, "community.menu.reward-progress", Map.of(
                                        "honor", profile.communityHonor(),
                                        "badges", profile.weeklyCommemorations(),
                                        "title", commemorationTitle(player, messages,
                                                profile.weeklyCommemorations()))),
                                messages.text(player, profile.weeklyRewardClaimed()
                                        ? "community.menu.reward-claimed"
                                        : !membership.community().completed()
                                        ? "community.menu.reward-incomplete"
                                        : membership.periodContribution().compareTo(rewardMinimumContribution) < 0
                                        ? "community.menu.reward-contribution"
                                        : "community.menu.reward-ready", Map.of(
                                                "minimum", amount(rewardMinimumContribution),
                                                "honor", rewardHonor))
                        ), membership != null && membership.community().completed()
                                && !profile.weeklyRewardClaimed()
                                && membership.periodContribution().compareTo(rewardMinimumContribution) >= 0
                                ? "community claim" : "community status");
        String mentorState;
        if (profile.mentorId().isEmpty()) mentorState = messages.text(player, "community.menu.mentor-unbound");
        else if (profile.mentorVerified()) mentorState = messages.text(player, "community.menu.mentor-verified");
        else mentorState = messages.text(player, "community.menu.mentor-pending");
        set(14, Material.WRITABLE_BOOK, messages.text(player, "community.menu.mentor"),
                List.of(mentorState, messages.text(player, "community.menu.code",
                        Map.of("code", profile.inviteCode())),
                        messages.text(player, "community.menu.mentor-rule")), "community code");
        String leader = leaderboard.isEmpty()
                ? messages.text(player, "community.menu.leaderboard-empty")
                : messages.text(player, "community.menu.leaderboard-first", Map.of(
                        "tag", leaderboard.getFirst().community().tag(),
                        "amount", amount(leaderboard.getFirst().community().periodContribution())));
        set(16, Material.BELL, messages.text(player, "community.menu.leaderboard"),
                List.of(leader, messages.text(player, "community.menu.leaderboard-lore")),
                "community leaderboard");
        set(22, Material.COMPASS, messages.text(player, "community.menu.help"),
                List.of(messages.text(player, "community.menu.help-lore")), "community help");
    }

    String actionAt(int slot) {
        return actions.get(slot);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    private void set(int slot, Material material, String name, List<String> lore, String action) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.GREEN));
        meta.lore(lore.stream().map(line -> Component.text(line, NamedTextColor.GRAY)).toList());
        item.setItemMeta(meta);
        inventory.setItem(slot, item);
        actions.put(slot, action);
    }

    private void fill() {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, pane);
    }

    private static String amount(java.math.BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String commemorationTitle(Player player, MessageService messages, int commemorations) {
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
}
