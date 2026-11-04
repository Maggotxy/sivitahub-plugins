package cn.originalrpg.hub;

import cn.originalrpg.api.i18n.ConfiguredDisplayText;
import cn.originalrpg.api.i18n.LocaleBundle;
import cn.originalrpg.api.service.MessageService;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class HubMessages {
    private final MessageService locales;
    private final LocaleBundle bundles;
    private final ConfigurationSection config;
    private final ConfigurationSection defaults;

    HubMessages(Plugin plugin, MessageService locales) {
        this.locales = locales;
        this.config = plugin.getConfig();
        this.defaults = plugin.getConfig().getDefaults();
        Map<String, Map<String, ?>> loaded = new LinkedHashMap<>();
        Map<String, Map<String, ?>> packaged = new LinkedHashMap<>();
        for (String locale : locales.supportedLocales()) {
            String resource = "lang/" + locale + ".yml";
            File target = new File(plugin.getDataFolder(), resource);
            if (!target.isFile()) plugin.saveResource(resource, false);
            loaded.put(locale, YamlConfiguration.loadConfiguration(target).getValues(true));
            try (InputStream stream = plugin.getResource(resource)) {
                if (stream != null) packaged.put(locale, YamlConfiguration.loadConfiguration(
                        new InputStreamReader(stream, StandardCharsets.UTF_8)).getValues(true));
            } catch (java.io.IOException exception) {
                plugin.getLogger().warning("Could not read packaged locale " + locale + ": " + exception.getMessage());
            }
        }
        bundles = new LocaleBundle(locales.defaultLocale(), loaded, packaged, plugin.getLogger()::warning);
    }

    String text(CommandSender audience, String key, Object... placeholders) {
        if (placeholders.length % 2 != 0) throw new IllegalArgumentException("Placeholders must be key/value pairs");
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < placeholders.length; index += 2) {
            values.put(String.valueOf(placeholders[index]), placeholders[index + 1]);
        }
        return LocaleBundle.format(bundles.text(locales.locale(audience), key), values);
    }

    String npcName(CommandSender audience, HubRole role) {
        return configured(audience, role, "name", "names", "wayfinding.roles." + role.id());
    }

    String npcSubtitle(CommandSender audience, HubRole role) {
        return configured(audience, role, "subtitle", "subtitles", "npc-subtitles." + role.id());
    }

    private String configured(CommandSender audience, HubRole role, String legacyField,
                              String localizedField, String key) {
        String root = "npcs." + role.id() + ".";
        return ConfiguredDisplayText.resolve(config.getString(root + legacyField),
                defaults == null ? null : defaults.getString(root + legacyField),
                config.getString(root + localizedField + "." + locales.locale(audience)),
                text(audience, key));
    }
}
