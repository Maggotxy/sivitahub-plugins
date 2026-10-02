package cn.originalrpg.enhancement;

import cn.originalrpg.api.model.SkillVariantCatalog;
import cn.originalrpg.api.service.EconomyService;
import cn.originalrpg.api.service.EquipmentService;
import cn.originalrpg.api.service.EquipmentProgressService;
import cn.originalrpg.api.service.EquipmentOperationSessionService;
import cn.originalrpg.api.service.SkillService;
import cn.originalrpg.api.service.MessageService;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class RpgEnhancementPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        saveDefaultConfig();
        validateRefinementConfiguration();
        validateMutationCatalog();
        EconomyService economy = Objects.requireNonNull(
                getServer().getServicesManager().load(EconomyService.class), "EconomyService");
        EquipmentService equipment = Objects.requireNonNull(
                getServer().getServicesManager().load(EquipmentService.class), "EquipmentService");
        EquipmentProgressService equipmentProgress = Objects.requireNonNull(
                getServer().getServicesManager().load(EquipmentProgressService.class),
                "EquipmentProgressService");
        EquipmentOperationSessionService equipmentOperations = Objects.requireNonNull(
                getServer().getServicesManager().load(EquipmentOperationSessionService.class),
                "EquipmentOperationSessionService");
        SkillService skills = Objects.requireNonNull(
                getServer().getServicesManager().load(SkillService.class), "SkillService");
        MessageService messages = Objects.requireNonNull(
                getServer().getServicesManager().load(MessageService.class), "MessageService");
        EnhancementVisuals visuals = new EnhancementVisuals(this, messages);
        PluginCommand command = Objects.requireNonNull(getCommand("refine"), "refine command");
        RefineCommand refine = new RefineCommand(
                this, equipment, equipmentProgress, messages, visuals, equipmentOperations);
        command.setExecutor(refine);
        command.setTabCompleter(refine);
        getServer().getPluginManager().registerEvents(refine, this);
        PluginCommand limitBreakCommand = Objects.requireNonNull(getCommand("limitbreak"), "limitbreak command");
        LimitBreakCommand limitBreak = new LimitBreakCommand(
                this, equipment, equipmentProgress, messages, visuals, equipmentOperations);
        limitBreakCommand.setExecutor(limitBreak);
        limitBreakCommand.setTabCompleter(limitBreak);
        getServer().getPluginManager().registerEvents(limitBreak, this);
        PluginCommand mutateCommand = Objects.requireNonNull(getCommand("mutate"), "mutate command");
        MutationCommand mutate = new MutationCommand(this, skills, messages, visuals);
        mutateCommand.setExecutor(mutate);
        mutateCommand.setTabCompleter(mutate);
        getServer().getPluginManager().registerEvents(mutate, this);
    }

    private void validateRefinementConfiguration() {
        int minimum = getConfig().getInt("refinement.minimum-roll", -5);
        int maximum = getConfig().getInt("refinement.maximum-roll", 18);
        RefinementRules.validateRollRange(minimum, maximum);
        java.math.BigDecimal price = new java.math.BigDecimal(
                getConfig().getString("refinement.price", "-1"));
        double successRate = getConfig().getDouble("refinement.success-rate", -1.0);
        double luckyBonus = getConfig().getDouble("refinement.lucky-charm-bonus", -1.0);
        if (price.signum() <= 0 || successRate < 0.0 || successRate > 1.0
                || luckyBonus < 0.0 || luckyBonus > 1.0) {
            throw new IllegalStateException("Refinement economy or probabilities are invalid");
        }
    }

    private void validateMutationCatalog() {
        ConfigurationSection root = Objects.requireNonNull(
                getConfig().getConfigurationSection("mutations"), "mutations configuration");
        if (!root.getKeys(false).equals(SkillVariantCatalog.implemented().keySet())) {
            throw new IllegalStateException("Mutation station must configure exactly the runtime-backed skills; found "
                    + root.getKeys(false));
        }
        for (var entry : SkillVariantCatalog.implemented().entrySet()) {
            java.util.Set<String> configured = new java.util.HashSet<>(
                    root.getStringList(entry.getKey() + ".variants"));
            if (!configured.equals(entry.getValue())) {
                throw new IllegalStateException("Mutation variants for " + entry.getKey()
                        + " do not match runtime effects: " + configured);
            }
            java.math.BigDecimal price = new java.math.BigDecimal(
                    root.getString(entry.getKey() + ".price", "-1"));
            java.math.BigDecimal restore = new java.math.BigDecimal(
                    root.getString(entry.getKey() + ".restore-price", "-1"));
            double rate = root.getDouble(entry.getKey() + ".success-rate", -1.0);
            if (price.signum() <= 0 || restore.signum() <= 0 || rate <= 0.0 || rate > 1.0) {
                throw new IllegalStateException("Mutation economy is invalid for " + entry.getKey());
            }
        }
    }
}
