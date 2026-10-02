package cn.originalrpg.items;

import cn.originalrpg.items.forge.ForgeListener;
import cn.originalrpg.items.forge.SalvageCommand;
import cn.originalrpg.items.forge.UnbindCommand;
import cn.originalrpg.items.forge.LegacyBlueprintService;
import cn.originalrpg.items.forge.LegacyCommand;
import cn.originalrpg.items.forge.EquipmentRecoveryListener;
import cn.originalrpg.api.service.EquipmentService;
import cn.originalrpg.api.service.EquipmentProgressService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.ForgeImprintService;
import cn.originalrpg.api.service.EquipmentOperationService;
import cn.originalrpg.api.service.EquipmentOperationSessionService;
import cn.originalrpg.items.forge.EquipmentOperationCoordinator;
import cn.originalrpg.api.service.ProfileService;
import cn.originalrpg.api.model.EquipmentTraitCatalog;
import cn.originalrpg.items.security.EquipmentSecurity;
import cn.originalrpg.items.security.EquipmentDurabilityListener;
import cn.originalrpg.items.security.AuthoritativeItemProcessingListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.ServicePriority;

import java.util.Objects;
import java.security.SecureRandom;
import java.util.Base64;

public final class RpgItemsPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        saveDefaultConfig();
        // Merge newly introduced catalog keys into existing installations while
        // preserving the generated signing key and operator-tuned values.
        getConfig().options().copyDefaults(true);
        saveConfig();
        validateRareTraits();
        byte[] signingKey = signingKey();
        EquipmentSecurity security = new EquipmentSecurity(signingKey);
        LegacyBlueprintService blueprints = new LegacyBlueprintService(signingKey);
        MessageService messages = Objects.requireNonNull(
                getServer().getServicesManager().load(MessageService.class), "MessageService");
        EquipmentOperationService equipmentOperations = Objects.requireNonNull(
                getServer().getServicesManager().load(EquipmentOperationService.class), "EquipmentOperationService");
        ProfileService profiles = Objects.requireNonNull(
                getServer().getServicesManager().load(ProfileService.class), "ProfileService");
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
        Objects.requireNonNull(getCommand("salvage"), "salvage command").setExecutor(salvage);
        Objects.requireNonNull(getCommand("unbind"), "unbind command").setExecutor(unbind);
        Objects.requireNonNull(getCommand("legacy"), "legacy command").setExecutor(legacy);
        getServer().getPluginManager().registerEvents(forge, this);
        getServer().getPluginManager().registerEvents(salvage, this);
        getServer().getPluginManager().registerEvents(unbind, this);
        getServer().getPluginManager().registerEvents(legacy, this);
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

    private byte[] signingKey() {
        String encoded = System.getenv("RPG_ITEM_SIGNING_KEY");
        if (encoded == null || encoded.isBlank()) {
            encoded = getConfig().getString("security.signing-key", "");
        }
        if (encoded == null || encoded.isBlank()) {
            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            encoded = Base64.getEncoder().encodeToString(generated);
            getConfig().set("security.signing-key", encoded);
            saveConfig();
            getLogger().warning("Generated a local equipment signing key. Back up RpgItems/config.yml before moving worlds.");
        }
        try {
            return Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("security.signing-key must be Base64", exception);
        }
    }
}
