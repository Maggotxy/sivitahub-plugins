package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.CommunityLifecyclePolicy;
import org.bukkit.configuration.ConfigurationSection;

import java.time.Duration;

/** Reads the single Core policy; player commands and plugin-local overrides cannot shorten deadlines. */
public final class CommunityLifecycleConfiguration {
    private CommunityLifecycleConfiguration() {}

    public static CommunityLifecyclePolicy load(ConfigurationSection config) {
        return new CommunityLifecyclePolicy(
                Duration.ofDays(Math.clamp(config.getLong("community.lifecycle.owner-inactivity-days", 30), 1, 3650)),
                Duration.ofDays(Math.clamp(config.getLong("community.lifecycle.candidate-membership-days", 7), 1, 3650)),
                Duration.ofHours(Math.clamp(config.getLong("community.lifecycle.succession-cooling-hours", 72), 1, 87_600)),
                Duration.ofHours(Math.clamp(config.getLong("community.lifecycle.dissolution-cooling-hours", 72), 1, 87_600)));
    }
}
