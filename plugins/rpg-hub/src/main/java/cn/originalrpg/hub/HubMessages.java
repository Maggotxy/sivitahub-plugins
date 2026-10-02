package cn.originalrpg.hub;

import cn.originalrpg.api.service.MessageService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class HubMessages {
    private final MessageService locales;
    private final Map<String, YamlConfiguration> bundles;
    private final Map<String, YamlConfiguration> packagedDefaults;

    HubMessages(Plugin plugin, MessageService locales) {
        this.locales = locales;
        Map<String, YamlConfiguration> loaded = new LinkedHashMap<>();
        Map<String, YamlConfiguration> defaults = new LinkedHashMap<>();
        for (String locale : locales.supportedLocales()) {
            String resource = "lang/" + locale + ".yml";
            File target = new File(plugin.getDataFolder(), resource);
            if (!target.isFile()) plugin.saveResource(resource, false);
            loaded.put(locale, YamlConfiguration.loadConfiguration(target));
            try (InputStream stream = plugin.getResource(resource)) {
                if (stream != null) defaults.put(locale, YamlConfiguration.loadConfiguration(
                        new InputStreamReader(stream, StandardCharsets.UTF_8)));
            } catch (java.io.IOException exception) {
                plugin.getLogger().warning("Could not read packaged locale " + locale + ": " + exception.getMessage());
            }
        }
        bundles = Map.copyOf(loaded);
        packagedDefaults = Map.copyOf(defaults);
    }

    String text(CommandSender audience, String key, Object... placeholders) {
        String locale = locales.locale(audience);
        YamlConfiguration primary = bundles.getOrDefault(locale, bundles.get(locales.defaultLocale()));
        String value = primary == null ? null : primary.getString(key);
        if (value == null) {
            YamlConfiguration fallback = bundles.get(locales.defaultLocale());
            value = fallback == null ? null : fallback.getString(key);
        }
        if (value == null) {
            YamlConfiguration packaged = packagedDefaults.getOrDefault(locale,
                    packagedDefaults.get(locales.defaultLocale()));
            value = packaged == null ? null : packaged.getString(key);
        }
        if (value == null) {
            YamlConfiguration packagedFallback = packagedDefaults.get(locales.defaultLocale());
            value = packagedFallback == null ? key : packagedFallback.getString(key, key);
        }
        if (placeholders.length % 2 != 0) throw new IllegalArgumentException("Placeholders must be key/value pairs");
        for (int index = 0; index < placeholders.length; index += 2) {
            value = value.replace("{" + placeholders[index] + "}", String.valueOf(placeholders[index + 1]));
        }
        return value;
    }
}
