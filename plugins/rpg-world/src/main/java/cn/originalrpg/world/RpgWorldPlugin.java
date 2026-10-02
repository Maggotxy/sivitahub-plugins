package cn.originalrpg.world;

import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.ProfileService;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.PluginCommand;
import org.bukkit.Difficulty;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.entity.SpawnCategory;

import java.util.Objects;

public final class RpgWorldPlugin extends JavaPlugin {
    private WorldEventListener listener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        // Death protection is a permanent gameplay invariant, not an operator
        // toggle. Repair stale runtime copies before saving them back to disk.
        getConfig().set("asset-protection.death.keep-inventory", true);
        getConfig().set("asset-protection.death.keep-experience", true);
        saveConfig();
        configureSurvivalRules();
        AssetProtectionListener assetProtection = new AssetProtectionListener(this);
        getServer().getPluginManager().registerEvents(assetProtection, this);
        assetProtection.configureLoadedWorlds();
        MessageService messages = Objects.requireNonNull(
                getServer().getServicesManager().load(MessageService.class), "MessageService");
        ProfileService profiles = Objects.requireNonNull(
                getServer().getServicesManager().load(ProfileService.class), "ProfileService");
        listener = new WorldEventListener(this, messages, profiles);
        getServer().getPluginManager().registerEvents(listener, this);
        WorldBoundaryManager boundaries = new WorldBoundaryManager(this);
        getServer().getPluginManager().registerEvents(boundaries, this);
        boundaries.configureLoadedWorlds();
        PluginCommand status = Objects.requireNonNull(getCommand("worldstatus"), "worldstatus command");
        status.setExecutor(new WorldStatusCommand(messages, listener));
        listener.start();
    }

    private void configureSurvivalRules() {
        String worldName = getConfig().getString("worlds.survival", "world");
        World world = getServer().getWorld(worldName);
        if (world == null) {
            getLogger().warning("Survival world is not loaded: " + worldName);
            return;
        }
        world.setDifficulty(Difficulty.HARD);
        world.setGameRule(GameRules.SPAWN_MOBS,
                getConfig().getBoolean("survival-rules.natural-mob-spawning", true));
        world.setGameRule(GameRules.PLAYERS_SLEEPING_PERCENTAGE,
                Math.clamp(getConfig().getInt("survival-rules.sleeping-percentage", 35), 1, 100));
        world.setSpawnLimit(SpawnCategory.MONSTER, Math.clamp(
                getConfig().getInt("survival-rules.monster-spawn-limit", 56), 16, 70));
    }

    @Override
    public void onDisable() {
        if (listener != null) listener.stop();
    }
}
