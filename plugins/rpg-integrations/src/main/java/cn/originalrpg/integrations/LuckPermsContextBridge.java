package cn.originalrpg.integrations;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.ContextCalculator;
import net.luckperms.api.context.ContextConsumer;
import net.luckperms.api.node.Node;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;

/** Adds synchronous LuckPerms contexts using only the integration plugin's in-memory snapshot. */
final class LuckPermsContextBridge implements AutoCloseable {
    private final LuckPerms luckPerms;
    private final ContextCalculator<Player> calculator;

    private LuckPermsContextBridge(LuckPerms luckPerms, ContextCalculator<Player> calculator) {
        this.luckPerms = luckPerms;
        this.calculator = calculator;
    }

    static LuckPermsContextBridge register(JavaPlugin plugin, Map<UUID, RpgSnapshot> snapshots) {
        LuckPerms luckPerms = Objects.requireNonNull(
                plugin.getServer().getServicesManager().load(LuckPerms.class), "LuckPerms service");
        ContextCalculator<Player> calculator = new ContextCalculator<>() {
            @Override
            public void calculate(Player target, ContextConsumer consumer) {
                RpgSnapshot snapshot = snapshots.get(target.getUniqueId());
                if (snapshot == null) return;
                consumer.accept("sivita-class", snapshot.classId());
                consumer.accept("sivita-ascension", Integer.toString(snapshot.ascension()));
                if (!snapshot.branchId().equals("none")) consumer.accept("sivita-branch", snapshot.branchId());
            }
        };
        luckPerms.getContextManager().registerCalculator(calculator);
        configureDefaultPermissions(plugin, luckPerms);
        return new LuckPermsContextBridge(luckPerms, calculator);
    }

    private static void configureDefaultPermissions(JavaPlugin plugin, LuckPerms luckPerms) {
        Map<String, Boolean> nodes = new LinkedHashMap<>();
        if (plugin.getServer().getPluginManager().isPluginEnabled("SkinsRestorer")
                && plugin.getConfig().getBoolean("skin-permissions.enabled", true)) {
            nodes.put("skinsrestorer.player", false);
            nodes.put("skinsrestorer.command.*", false);
            nodes.put("skinsrestorer.command", true);
            nodes.put("skinsrestorer.command.set", true);
            nodes.put("skinsrestorer.command.update", true);
            nodes.put("skinsrestorer.command.clear", true);
            nodes.put("skinsrestorer.command.set.other", false);
            nodes.put("skinsrestorer.command.set.url", false);
            nodes.put("skinsrestorer.command.update.other", false);
            nodes.put("skinsrestorer.command.clear.other", false);
            nodes.put("skinsrestorer.command.gui", false);
            nodes.put("skinsrestorer.admin", false);
            nodes.put("skinsrestorer.admincommand.*", false);
        }
        if (plugin.getServer().getPluginManager().isPluginEnabled("MyPet")) {
            // Do not grant MyPet.player: that bundle includes native trade/release
            // capabilities which bypass the server ledger and binding rules.
            nodes.put("mypet.player", false);
            nodes.put("mypet.leash.*", true);
            nodes.put("mypet.command.capturehelper", true);
            nodes.put("mypet.command.name", true);
            nodes.put("mypet.command.name.color", false);
            nodes.put("mypet.command.name.format", false);
            nodes.put("mypet.command.switch", true);
            nodes.put("mypet.command.store", true);
            nodes.put("mypet.command.list", true);
            nodes.put("mypet.command.release", false);
            nodes.put("mypet.command.respawn", false);
            nodes.put("mypet.command.trade.offer.*", false);
            nodes.put("mypet.command.trade.receive.*", false);
            nodes.put("mypet.extended.feed", true);
            nodes.put("mypet.extended.inventory", true);
            nodes.put("mypet.extended.beacon", true);
            nodes.put("mypet.extended.control", true);
            nodes.put("mypet.extended.pickup", false);
            nodes.put("mypet.extended.behavior.friendly", false);
            nodes.put("mypet.extended.behavior.aggressive", true);
            nodes.put("mypet.extended.behavior.farm", false);
            nodes.put("mypet.extended.behavior.raid", true);
            nodes.put("mypet.extended.behavior.duel", false);
            nodes.put("mypet.extended.ride.fly", false);
            nodes.put("mypet.extended.toolbox", false);
        }
        if (nodes.isEmpty()) return;
        luckPerms.getGroupManager().modifyGroup("default", group -> nodes.forEach((permission, value) -> {
            group.data().remove(Node.builder(permission).value(!value).build());
            group.data().add(Node.builder(permission).value(value).build());
        })).whenComplete((ignored, error) -> {
            if (error == null) {
                plugin.getLogger().info("Applied least-privilege skin and pet permissions to the default group.");
            } else {
                plugin.getLogger().warning("Could not persist default permissions: " + error.getMessage());
            }
        });
    }

    void signal(Player player) {
        luckPerms.getContextManager().signalContextUpdate(player);
    }

    @Override
    public void close() {
        luckPerms.getContextManager().unregisterCalculator(calculator);
    }
}
