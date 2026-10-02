package cn.originalrpg.economy;

import cn.originalrpg.api.service.ActivityEconomyService;
import cn.originalrpg.api.service.CurrencyExchangeService;
import cn.originalrpg.api.service.CommissionEvidenceService;
import cn.originalrpg.api.service.CommerceService;
import cn.originalrpg.api.service.EquipmentProgressService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.ProfileService;
import cn.originalrpg.api.service.MarketService;
import cn.originalrpg.api.service.MerchantService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class RpgEconomyPlugin extends JavaPlugin {
    private EconomicActivityListener activityListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ActivityEconomyService economy = Objects.requireNonNull(
                getServer().getServicesManager().load(ActivityEconomyService.class), "ActivityEconomyService");
        ProfileService profiles = Objects.requireNonNull(
                getServer().getServicesManager().load(ProfileService.class), "ProfileService");
        CurrencyExchangeService currencyExchange = Objects.requireNonNull(
                getServer().getServicesManager().load(CurrencyExchangeService.class), "CurrencyExchangeService");
        MessageService messages = Objects.requireNonNull(
                getServer().getServicesManager().load(MessageService.class), "MessageService");
        EquipmentProgressService equipmentProgress = getServer().getServicesManager().load(
                EquipmentProgressService.class);
        MarketService market = Objects.requireNonNull(
                getServer().getServicesManager().load(MarketService.class), "MarketService");
        MerchantService merchant = Objects.requireNonNull(
                getServer().getServicesManager().load(MerchantService.class), "MerchantService");
        CommissionEvidenceService commissionEvidenceService = Objects.requireNonNull(
                getServer().getServicesManager().load(CommissionEvidenceService.class),
                "CommissionEvidenceService");
        CommerceService commerce = Objects.requireNonNull(
                getServer().getServicesManager().load(CommerceService.class), "CommerceService");
        CommissionEvidenceTracker commissionEvidence = new CommissionEvidenceTracker(
                this, commissionEvidenceService);
        // G-208：配置把承诺的 10% 往返净损耗降到更低值时直接拒绝启动，
        // 而不是等玩家第一次打开商人菜单才抛异常（那时已经算"运行期惊喜"）。
        MerchantPricing.requiredMinimumRoundtripLoss(
                getConfig().getDouble("black-market.minimum-roundtrip-loss", 0.10));
        ShopIntegrityValidator.Report shopIntegrity = ShopIntegrityValidator.validate(this);
        if (shopIntegrity.findings().isEmpty()) {
            getLogger().info("Shop integrity checks passed: no supply, recipe, commission, or buyback loop detected.");
        } else {
            shopIntegrity.findings().forEach(finding -> getLogger().severe("Shop integrity: " + finding));
        }
        EconomyCommand command = new EconomyCommand(this, economy, currencyExchange, commerce,
                market, merchant, messages,
                commissionEvidence, shopIntegrity);
        PluginCommand economyCommand = Objects.requireNonNull(getCommand("economy"), "economy command");
        economyCommand.setExecutor(command);
        economyCommand.setTabCompleter(command);
        getServer().getPluginManager().registerEvents(command, this);
        activityListener = new EconomicActivityListener(
                this, economy, profiles, messages, equipmentProgress, commissionEvidence);
        getServer().getPluginManager().registerEvents(activityListener, this);
        activityListener.start();
    }

    @Override
    public void onDisable() {
        if (activityListener != null) activityListener.shutdown();
    }
}
