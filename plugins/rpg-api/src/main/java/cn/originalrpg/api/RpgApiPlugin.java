package cn.originalrpg.api;

import org.bukkit.plugin.java.JavaPlugin;

public final class RpgApiPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        getLogger().info("RPG service contracts loaded.");
    }
}

