package cn.originalrpg.items;

import cn.originalrpg.items.forge.ForgeListener;
import cn.originalrpg.items.forge.SalvageCommand;
import cn.originalrpg.items.forge.UnbindCommand;
import cn.originalrpg.items.forge.LegacyBlueprintService;
import cn.originalrpg.items.forge.LegacyCommand;
import cn.originalrpg.items.forge.EquipmentRecoveryListener;
import cn.originalrpg.items.forge.EquipmentExitPathRules;
import cn.originalrpg.items.forge.DestroyCommand;
import cn.originalrpg.items.forge.EquipmentTerminalStateListener;
import cn.originalrpg.api.service.EquipmentService;
import cn.originalrpg.api.service.EquipmentProgressService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.ForgeImprintService;
import cn.originalrpg.api.service.EquipmentOperationService;
import cn.originalrpg.api.service.EquipmentOperationSessionService;
import cn.originalrpg.items.forge.EquipmentOperationCoordinator;
import cn.originalrpg.api.service.EquipmentLedgerAuditService;
import cn.originalrpg.api.service.ProfileService;
import cn.originalrpg.api.model.EquipmentTraitCatalog;
import cn.originalrpg.items.security.EquipmentSecurity;
import cn.originalrpg.items.security.EquipmentSigningMaterial;
import cn.originalrpg.items.security.EquipmentSigningMaterial.FailClosedDecision;
import cn.originalrpg.items.security.EquipmentDurabilityListener;
import cn.originalrpg.items.security.AuthoritativeItemProcessingListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.ServicePriority;

import java.util.List;
import java.util.Objects;

public final class RpgItemsPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        saveDefaultConfig();
        // Merge newly introduced catalog keys while preserving operator-tuned values.
        // 签名密钥不在 config.yml 里，因此这里不再需要为它回写配置。
        getConfig().options().copyDefaults(true);
        saveConfig();
        validateRareTraits();
        // C-008：高价值装备必须有退出路径。启动自检，缺一条就拒绝启用，
        // 避免「只进不出」的物品池在无人察觉时上线。
        java.util.List<String> exitPathFailures = EquipmentExitPathRules.exitPathFailures();
        if (!exitPathFailures.isEmpty()) {
            getLogger().severe("C-008 自检失败：装备状态机没有可用的退出路径（分解/献祭/传承/调谐/销毁），"
                    + "拒绝启用，以免出现只进不出的物品池。明细：" + exitPathFailures);
            return;
        }
        warnAboutLegacyPlaintextKey();
        FailClosedDecision signingGate = signingKeyGate();
        if (signingGate.disabled()) {
            getLogger().severe(signingGate.reason());
            return;
        }
        EquipmentSigningMaterial material = EquipmentSigningMaterial.fromEnvironment();
        if (material.rotationCount() > 1) {
            getLogger().warning("装备签名密钥轮换中：接受 " + material.rotationCount()
                    + " 把密钥（1 当前 + " + (material.rotationCount() - 1)
                    + " 退役）；新装备只用当前密钥签名。");
        }
        byte[] signingKey = material.current();
        EquipmentSecurity security = new EquipmentSecurity(signingKey, signingKeyRotationTail(material));
        LegacyBlueprintService blueprints = new LegacyBlueprintService(signingKey, signingKeyRotationTail(material));
        MessageService messages = Objects.requireNonNull(
                getServer().getServicesManager().load(MessageService.class), "MessageService");
        EquipmentOperationService equipmentOperations = Objects.requireNonNull(
                getServer().getServicesManager().load(EquipmentOperationService.class), "EquipmentOperationService");
        ProfileService profiles = Objects.requireNonNull(
                getServer().getServicesManager().load(ProfileService.class), "ProfileService");
        EquipmentLedgerAuditService ledgerAudit = Objects.requireNonNull(
                getServer().getServicesManager().load(EquipmentLedgerAuditService.class), "EquipmentLedgerAuditService");
        getServer().getServicesManager().register(EquipmentService.class, security, this, ServicePriority.Normal);
        ForgeImprintService imprints = getServer().getServicesManager().load(ForgeImprintService.class);
        EquipmentOperationCoordinator operationSessions = new EquipmentOperationCoordinator(
                this, equipmentOperations, messages);
        getServer().getServicesManager().register(
                EquipmentOperationSessionService.class, operationSessions, this, ServicePriority.Normal);
        ForgeListener forge = new ForgeListener(
                this, security, messages, imprints, blueprints, operationSessions);
        getServer().getServicesManager().register(
                EquipmentProgressService.class, forge, this, ServicePriority.Normal);
        PluginCommand command = Objects.requireNonNull(getCommand("forge"), "forge command");
        command.setExecutor(forge);
        SalvageCommand salvage = new SalvageCommand(this, security, messages, operationSessions);
        UnbindCommand unbind = new UnbindCommand(this, security, messages, forge, operationSessions);
        LegacyCommand legacy = new LegacyCommand(this, security, messages, blueprints, operationSessions);
        DestroyCommand destroy = new DestroyCommand(this, security, messages, operationSessions);
        Objects.requireNonNull(getCommand("salvage"), "salvage command").setExecutor(salvage);
        Objects.requireNonNull(getCommand("unbind"), "unbind command").setExecutor(unbind);
        Objects.requireNonNull(getCommand("legacy"), "legacy command").setExecutor(legacy);
        Objects.requireNonNull(getCommand("destroy"), "destroy command").setExecutor(destroy);
        getServer().getPluginManager().registerEvents(forge, this);
        getServer().getPluginManager().registerEvents(salvage, this);
        getServer().getPluginManager().registerEvents(unbind, this);
        getServer().getPluginManager().registerEvents(legacy, this);
        getServer().getPluginManager().registerEvents(destroy, this);
        // C-008 终态守卫：账本已写终态的装备不能再靠死亡掉落回到世界。
        getServer().getPluginManager().registerEvents(new EquipmentTerminalStateListener(
                this, security, ledgerAudit), this);
        getServer().getPluginManager().registerEvents(
                new AuthoritativeItemProcessingListener(security, messages), this);
        getServer().getPluginManager().registerEvents(new EquipmentDurabilityListener(
                security, getConfig().getBoolean("durability.custom-equipment-unbreakable", true)), this);
        getServer().getPluginManager().registerEvents(new EquipmentRecoveryListener(
                this, forge, security, profiles, messages), this);
    }

    private void validateRareTraits() {
        var weights = Objects.requireNonNull(
                getConfig().getConfigurationSection("rare-traits.weights"),
                "rare-traits.weights configuration");
        if (!weights.getKeys(false).equals(EquipmentTraitCatalog.implemented())) {
            var missing = new java.util.TreeSet<>(EquipmentTraitCatalog.implemented());
            missing.removeAll(weights.getKeys(false));
            var unknown = new java.util.TreeSet<>(weights.getKeys(false));
            unknown.removeAll(EquipmentTraitCatalog.implemented());
            throw new IllegalStateException("Rare-trait catalog mismatch; missing=" + missing
                    + " unknown=" + unknown);
        }
        for (String id : weights.getKeys(false)) {
            int weight = weights.getInt(id, 0);
            if (weight < 1 || weight > 10_000) {
                throw new IllegalStateException("Rare-trait weight out of range for " + id);
            }
        }
        double chance = getConfig().getDouble("rare-traits.random-roll-chance", -1.0);
        if (!Double.isFinite(chance) || chance < 0.0 || chance > 0.10) {
            throw new IllegalStateException("rare-traits.random-roll-chance must be between 0 and 0.10");
        }
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
    }

    /** 校验集合里除当前密钥之外的退役密钥（新→旧）。 */
    private static List<byte[]> signingKeyRotationTail(EquipmentSigningMaterial material) {
        List<byte[]> keys = material.verification();
        return List.copyOf(keys.subList(1, keys.size()));
    }

    /**
     * G-003：签名密钥只从环境变量读取。缺失即 fail-closed —— 拒绝启用依赖签名的
     * 功能（forge / salvage / unbind / legacy / 市场托管），而不是生成一把并落盘。
     */
    private FailClosedDecision signingKeyGate() {
        try {
            EquipmentSigningMaterial.fromEnvironment();
            return new FailClosedDecision(false, "");
        } catch (IllegalStateException exception) {
            return EquipmentSigningMaterial.failClosed(exception.getMessage());
        }
    }

    /**
     * G-003：旧安装的 config.yml 可能残留明文密钥。这里只检测并告警，
     * 不静默删除用户数据；迁移动作由运维完成。
     */
    private void warnAboutLegacyPlaintextKey() {
        List<String> legacyPaths = EquipmentSigningMaterial.legacyConfigPaths(
                getConfig().getString(EquipmentSigningMaterial.LEGACY_CONFIG_PATH, ""),
                getConfig().getStringList(EquipmentSigningMaterial.LEGACY_PREVIOUS_CONFIG_PATH));
        if (legacyPaths.isEmpty()) return;
        getLogger().severe("检测到 config.yml 里残留明文装备签名密钥字段 " + legacyPaths
                + "。明文密钥不再被读取，且会随配置文件一起被备份/分发。");
        getLogger().severe("迁移步骤：" + EquipmentSigningMaterial.REMEDIATION);
    }
}
