package cn.originalrpg.essentials;

import cn.originalrpg.api.service.CombatStateService;
import cn.originalrpg.api.service.MessageService;
import com.earth2me.essentials.CommandSource;
import net.ess3.api.IUser;
import net.ess3.api.events.TPARequestEvent;
import net.essentialsx.api.v2.events.TeleportRequestResponseEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class RpgEssentialsPlugin extends JavaPlugin implements Listener {
    private CombatStateService combat;
    private BridgeMessages messages;
    private EssentialsCommandPolicy commandPolicy;
    private Set<String> dungeonPrefixes;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        MessageService languageService = Objects.requireNonNull(
                getServer().getServicesManager().load(MessageService.class), "MessageService");
        combat = Objects.requireNonNull(
                getServer().getServicesManager().load(CombatStateService.class), "CombatStateService");
        messages = new BridgeMessages(getConfig(), languageService);
        commandPolicy = new EssentialsCommandPolicy(new HashSet<>(getConfig().getStringList("blocked-engine-aliases")));
        dungeonPrefixes = Set.copyOf(getConfig().getStringList("dungeon-world-prefixes"));
        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        EssentialsCommandPolicy.Decision decision = commandPolicy.inspect(event.getMessage());
        if (!decision.blocked()) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(messages.render(event.getPlayer(), "blocked-command",
                Map.of("command", decision.recommendation())));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTpaRequest(TPARequestEvent event) {
        CommandSource source = event.getRequester();
        Player requester = source == null ? null : source.getPlayer();
        IUser targetUser = event.getTarget();
        Player target = targetUser == null ? null : targetUser.getBase();
        if (requester == null || target == null || requester.hasPermission("rpg.essentials.travel-bypass")) return;
        String denial = denial(requester, target);
        if (denial == null) return;
        event.setCancelled(true);
        requester.sendMessage(denial);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTpaResponse(TeleportRequestResponseEvent event) {
        if (!event.isAccept()) return;
        Player requester = event.getRequester().getBase();
        Player requestee = event.getRequestee().getBase();
        if (requester == null || requestee == null || requester.hasPermission("rpg.essentials.travel-bypass")) return;
        String denial = denial(requester, requestee);
        if (denial == null) return;
        event.setCancelled(true);
        requester.sendMessage(denial);
        requestee.sendMessage(denial);
    }

    private String denial(Player requester, Player other) {
        if (inDungeon(requester) || inDungeon(other)) {
            return messages.render(requester, "tpa-dungeon", Map.of());
        }
        if (combat.inCombat(requester.getUniqueId())) {
            return messages.render(requester, "tpa-combat-self",
                    Map.of("seconds", combat.remainingSeconds(requester.getUniqueId())));
        }
        if (combat.inCombat(other.getUniqueId())) {
            return messages.render(requester, "tpa-combat-target", Map.of());
        }
        return null;
    }

    private boolean inDungeon(Player player) {
        String world = player.getWorld().getName();
        return dungeonPrefixes.stream().anyMatch(world::startsWith);
    }
}
