package cn.originalrpg.api.service;

import org.bukkit.plugin.Plugin;

import java.util.Objects;

/** Resolves an optional Bukkit service only while its owning plugin is enabled. */
public final class OptionalServices {
    private OptionalServices() {
    }

    public static <T> T enabled(Plugin consumer, Class<T> type) {
        Objects.requireNonNull(consumer, "consumer");
        Objects.requireNonNull(type, "type");
        var registration = consumer.getServer().getServicesManager().getRegistration(type);
        if (registration == null || !registration.getPlugin().isEnabled()) return null;
        return registration.getProvider();
    }
}
