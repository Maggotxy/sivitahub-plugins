package cn.originalrpg.core.i18n;

import cn.originalrpg.api.service.MessageService;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class YamlMessageService implements MessageService {
    private static final List<String> BUNDLED_LOCALES = List.of("zh_cn", "en_us");

    private final JavaPlugin plugin;
    private final String defaultLocale;
    private final boolean allowPlayerSelection;
    private final NamespacedKey localeKey;
    private final Map<String, YamlConfiguration> bundles;

    public YamlMessageService(JavaPlugin plugin) {
        this.plugin = plugin;
        defaultLocale = normalize(plugin.getConfig().getString("language.default", "zh_cn"));
        allowPlayerSelection = plugin.getConfig().getBoolean("language.allow-player-selection", true);
        localeKey = new NamespacedKey(plugin, "locale");
        bundles = loadBundles();
        if (!bundles.containsKey(defaultLocale)) {
            throw new IllegalStateException("Unsupported default locale: " + defaultLocale);
        }
    }

    @Override
    public String defaultLocale() {
        return defaultLocale;
    }

    @Override
    public Set<String> supportedLocales() {
        return bundles.keySet();
    }

    @Override
    public String locale(CommandSender audience) {
        if (allowPlayerSelection && audience instanceof Player player) {
            String selected = player.getPersistentDataContainer().get(localeKey, PersistentDataType.STRING);
            if (selected != null && bundles.containsKey(selected)) return selected;
        }
        return defaultLocale;
    }

    @Override
    public boolean setLocale(Player player, String locale) {
        if (!allowPlayerSelection || player == null) return false;
        String normalized = normalize(locale);
        if (!bundles.containsKey(normalized)) return false;
        player.getPersistentDataContainer().set(localeKey, PersistentDataType.STRING, normalized);
        player.saveData();
        return true;
    }

    @Override
    public String text(CommandSender audience, String key) {
        return text(audience, key, Map.of());
    }

    @Override
    public String text(CommandSender audience, String key, Map<String, ?> placeholders) {
        String value = lookup(locale(audience), key);
        return replace(value == null ? key : value, placeholders);
    }

    @Override
    public List<String> lines(CommandSender audience, String key) {
        return lines(audience, key, Map.of());
    }

    @Override
    public List<String> lines(CommandSender audience, String key, Map<String, ?> placeholders) {
        YamlConfiguration primary = bundles.get(locale(audience));
        List<String> values = primary == null ? List.of() : primary.getStringList(key);
        if (values.isEmpty()) values = bundles.get(defaultLocale).getStringList(key);
        if (values.isEmpty() && !defaultLocale.equals("en_us")) values = bundles.get("en_us").getStringList(key);
        return values.stream().map(value -> replace(value, placeholders)).toList();
    }

    private Map<String, YamlConfiguration> loadBundles() {
        Map<String, YamlConfiguration> loaded = new LinkedHashMap<>();
        for (String locale : BUNDLED_LOCALES) {
            String resourcePath = "lang/" + locale + ".yml";
            File file = new File(plugin.getDataFolder(), resourcePath);
            if (!file.exists()) plugin.saveResource(resourcePath, false);
            YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
            try (Reader reader = new InputStreamReader(
                    java.util.Objects.requireNonNull(plugin.getResource(resourcePath), resourcePath),
                    StandardCharsets.UTF_8)) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(reader);
                configuration.setDefaults(defaults);
                if (mergeMissingDefaults(configuration, defaults)) configuration.save(file);
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot load language defaults: " + resourcePath, exception);
            }
            loaded.put(locale, configuration);
        }
        return Map.copyOf(loaded);
    }

    /** Preserve operator translations while making newly shipped keys visible and editable after upgrades. */
    private boolean mergeMissingDefaults(YamlConfiguration target, YamlConfiguration defaults) {
        boolean changed = false;
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key) || target.contains(key, true)) continue;
            target.set(key, defaults.get(key));
            changed = true;
        }
        return changed;
    }

    private String lookup(String locale, String key) {
        String value = bundles.get(locale).getString(key);
        if (value == null) value = bundles.get(defaultLocale).getString(key);
        if (value == null && !defaultLocale.equals("en_us")) value = bundles.get("en_us").getString(key);
        return value;
    }

    private static String replace(String value, Map<String, ?> placeholders) {
        String result = value;
        for (Map.Entry<String, ?> entry : placeholders.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return result;
    }

    private static String normalize(String locale) {
        return locale == null ? "" : locale.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }
}
