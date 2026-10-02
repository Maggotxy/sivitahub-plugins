package cn.originalrpg.market;

import cn.originalrpg.api.service.EquipmentService;
import cn.originalrpg.api.service.EquipmentProgressService;
import cn.originalrpg.api.service.MarketService;
import cn.originalrpg.api.service.MessageService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class RpgMarketPlugin extends JavaPlugin {
    private MarketCommand marketCommand;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        validateEconomicConfiguration();
        EquipmentService equipment = Objects.requireNonNull(
                getServer().getServicesManager().load(EquipmentService.class), "EquipmentService");
        EquipmentProgressService equipmentProgress = Objects.requireNonNull(
                getServer().getServicesManager().load(EquipmentProgressService.class),
                "EquipmentProgressService");
        MarketService market = Objects.requireNonNull(
                getServer().getServicesManager().load(MarketService.class), "MarketService");
        MessageService messages = Objects.requireNonNull(
                getServer().getServicesManager().load(MessageService.class), "MessageService");
        MarketCommand command = new MarketCommand(this, market, equipment, equipmentProgress, messages);
        marketCommand = command;
        PluginCommand pluginCommand = Objects.requireNonNull(getCommand("market"), "market command");
        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);
        getServer().getPluginManager().registerEvents(command, this);
        getServer().getPluginManager().registerEvents(new TradeRestrictionListener(equipment, messages), this);
        long interval = Math.clamp(getConfig().getLong("listing.expiry-scan-ticks", 6000L), 1200L, 72000L);
        getServer().getScheduler().runTaskTimer(this, () -> {
            long hours = Math.clamp(getConfig().getLong("listing.expiry-hours", 168L), 24L, 720L);
            int batch = Math.clamp(getConfig().getInt("listing.expiry-batch-size", 100), 1, 500);
            market.expireListings(Instant.now().minus(Duration.ofHours(hours)), batch)
                    .whenComplete((expired, error) -> {
                        if (error != null) getLogger().warning("Market expiry scan failed: " + error.getMessage());
                        else if (expired > 0) getLogger().info("Expired and returned " + expired + " market listings");
                    });
        }, 1200L, interval);
    }

    @Override
    public void onDisable() {
        if (marketCommand != null) marketCommand.close();
        marketCommand = null;
    }

    private void validateEconomicConfiguration() {
        try {
            MarketConfigPolicy.validate(
                    new BigDecimal(getConfig().getString("fees.normal", "")),
                    new BigDecimal(getConfig().getString("fees.custom-equipment", "")),
                    new BigDecimal(getConfig().getString("listing.minimum-price", "")),
                    new BigDecimal(getConfig().getString("listing.maximum-price", "")));
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Unsafe player-market configuration; refusing to enable an untaxed or unbounded market",
                    exception);
        }
    }
}
