package cn.originalrpg.combat;

import cn.originalrpg.api.service.ProfileService;
import cn.originalrpg.api.service.EquipmentService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.CombatStateService;
import cn.originalrpg.api.service.SkillDamageService;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class RpgCombatPlugin extends JavaPlugin {
    private CombatTagService combatTags;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ProfileService profiles = Objects.requireNonNull(
                getServer().getServicesManager().load(ProfileService.class), "ProfileService");
        EquipmentService equipment = Objects.requireNonNull(
                getServer().getServicesManager().load(EquipmentService.class), "EquipmentService");
        MessageService messages = Objects.requireNonNull(
                getServer().getServicesManager().load(MessageService.class), "MessageService");
        combatTags = new CombatTagService(this);
        SkillDamageBridge skillDamage = new SkillDamageBridge(this);
        getServer().getServicesManager().register(
                CombatStateService.class, combatTags, this, ServicePriority.Normal);
        getServer().getServicesManager().register(
                SkillDamageService.class, skillDamage, this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(combatTags, this);
        getServer().getPluginManager().registerEvents(
                new ExecuteListener(this, profiles, equipment, messages, skillDamage), this);
    }

    @Override
    public void onDisable() {
        if (combatTags != null) combatTags.shutdown();
        getServer().getServicesManager().unregisterAll(this);
    }
}
