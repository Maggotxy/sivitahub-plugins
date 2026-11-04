package cn.originalrpg.tools.smoke;

import cn.originalrpg.api.service.DungeonEntryService;
import cn.originalrpg.api.service.DungeonRewardService;
import cn.originalrpg.api.service.EconomyService;
import cn.originalrpg.api.service.EquipmentOperationService;
import cn.originalrpg.api.service.MarketBuyOrderService;
import cn.originalrpg.api.service.MarketService;
import cn.originalrpg.api.service.ProfileService;
import cn.originalrpg.api.service.SkillService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Checks actual plugin/service state after Paper finishes loading, without modifying gameplay data. */
public final class SivitaRuntimeProbe extends JavaPlugin implements Listener {
    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler
    public void onLoaded(ServerLoadEvent event) {
        if (event.getType() != ServerLoadEvent.LoadType.STARTUP) return;
        getServer().getScheduler().runTaskLater(this, this::inspect, 40L);
    }

    private void inspect() {
        try (InputStream resource = getResource("expected-plugins.tsv")) {
            if (resource == null) throw new IllegalStateException("Missing generated plugin manifest");
            List<String> names;
            try (var reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
                names = reader.lines().filter(line -> !line.isBlank())
                        .map(line -> line.split("\\t", -1)[1]).toList();
            }
            if (names.size() != 25 || names.stream().distinct().count() != 25) {
                throw new IllegalStateException("Expected exactly 25 distinct custom plugins");
            }
            boolean ready = true;
            for (String name : names) {
                Plugin plugin = getServer().getPluginManager().getPlugin(name);
                boolean enabled = plugin != null && plugin.isEnabled();
                getLogger().info("SIVITA_PROBE_PLUGIN=" + name + ":" + (enabled ? "ENABLED" : "DISABLED"));
                ready &= enabled;
            }
            for (Class<?> service : List.of(ProfileService.class, EconomyService.class, MarketService.class,
                    MarketBuyOrderService.class, DungeonEntryService.class, DungeonRewardService.class,
                    EquipmentOperationService.class, SkillService.class)) {
                boolean present = getServer().getServicesManager().isProvidedFor(service);
                getLogger().info("SIVITA_PROBE_SERVICE=" + service.getSimpleName() + ":"
                        + (present ? "REGISTERED" : "MISSING"));
                ready &= present;
            }
            getLogger().info("SIVITA_PROBE_READY=" + (ready ? "YES" : "NO"));
        } catch (Exception exception) {
            getLogger().severe("SIVITA_PROBE_READY=NO " + exception.getClass().getSimpleName()
                    + ": " + exception.getMessage());
        }
    }
}
