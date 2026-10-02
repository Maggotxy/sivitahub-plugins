package cn.originalrpg.ainpc;

import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.ProfileService;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class RpgAiNpcPlugin extends JavaPlugin {
    private final AtomicBoolean lifecycleActive = new AtomicBoolean();
    private final AtomicLong runtimeGenerations = new AtomicLong();
    private volatile AiNpcRuntime runtime;
    private AiNpcController controller;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadRuntime();
        ProfileService profiles = Objects.requireNonNull(
                getServer().getServicesManager().load(ProfileService.class), "ProfileService");
        MessageService messages = Objects.requireNonNull(
                getServer().getServicesManager().load(MessageService.class), "MessageService");
        controller = new AiNpcController(this, profiles, messages);
        PluginCommand command = Objects.requireNonNull(getCommand("ainpc"), "ainpc command");
        command.setExecutor(controller);
        command.setTabCompleter(controller);
        getServer().getPluginManager().registerEvents(controller, this);
        lifecycleActive.set(true);
        getLogger().info("AI NPC guidance active: remote=" + runtime.client().configured()
                + ", maximum-concurrent=" + runtime.client().maximumConcurrent() + ", local-fallback=true");
    }

    @Override
    public void onDisable() {
        lifecycleActive.set(false);
        if (controller != null) controller.shutdown();
        AiNpcRuntime current = runtime;
        if (current != null) current.client().close();
    }

    void reloadRuntime() {
        reloadConfig();
        AiNpcRuntime previous = runtime;
        loadRuntime();
        controller.reloadSettings();
        if (previous != null) previous.client().close();
        getLogger().info("AI NPC guidance reloaded: remote=" + runtime.client().configured());
    }

    AiNpcRuntime runtime() {
        return runtime;
    }

    boolean current(AiNpcRuntime candidate) {
        AiNpcRuntime current = runtime;
        return lifecycleActive.get() && current != null && current == candidate
                && current.generation() == candidate.generation();
    }

    boolean executeOnMain(Runnable action) {
        if (!lifecycleActive.get()) return false;
        try {
            getServer().getScheduler().runTask(this, () -> {
                if (lifecycleActive.get()) action.run();
            });
            return true;
        } catch (IllegalPluginAccessException exception) {
            return false;
        }
    }

    private void loadRuntime() {
        File knowledgeFile = new File(getDataFolder(), "knowledge.yml");
        if (!knowledgeFile.isFile()) saveResource("knowledge.yml", false);
        StaticKnowledge knowledge = new StaticKnowledge(YamlConfiguration.loadConfiguration(knowledgeFile));
        AiProviderClient client = new AiProviderClient(this, getConfig());
        runtime = new AiNpcRuntime(runtimeGenerations.incrementAndGet(), client, knowledge);
    }
}
