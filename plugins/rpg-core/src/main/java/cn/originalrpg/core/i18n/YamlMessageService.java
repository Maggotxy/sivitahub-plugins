package cn.originalrpg.core.i18n;

import cn.originalrpg.api.event.PlayerLocaleChangeEvent;
import cn.originalrpg.api.i18n.LocaleBundle;
import cn.originalrpg.api.service.MessageService;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
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
import java.util.Map;
import java.util.Set;

public final class YamlMessageService implements MessageService {
    private static final List<String> BUNDLED_LOCALES = List.of("zh_cn", "en_us");
    private final JavaPlugin plugin;
    private final String defaultLocale;
    private final boolean allowPlayerSelection;
    private final NamespacedKey localeKey;
    private final LocaleBundle bundles;

    public YamlMessageService(JavaPlugin plugin) {
        this.plugin = plugin;
        defaultLocale = LocaleBundle.normalize(plugin.getConfig().getString("language.default", "zh_cn"));
        allowPlayerSelection = plugin.getConfig().getBoolean("language.allow-player-selection", true);
        localeKey = new NamespacedKey(plugin, "locale");
        bundles = loadBundles();
        if (!bundles.supportedLocales().contains(defaultLocale)) {
            throw new IllegalStateException("Unsupported default locale: " + defaultLocale);
        }
    }

    @Override
    public String defaultLocale() { return defaultLocale; }

    @Override
    public Set<String> supportedLocales() { return bundles.supportedLocales(); }

    @Override
    public String locale(CommandSender audience) {
        if (allowPlayerSelection && audience instanceof Player player) {
            String selected = player.getPersistentDataContainer().get(localeKey, PersistentDataType.STRING);
            String normalized = LocaleBundle.normalize(selected);
            if (bundles.supportedLocales().contains(normalized)) return normalized;
        }
        return defaultLocale;
    }

    @Override
    public boolean setLocale(Player player, String locale) {
        if (!allowPlayerSelection || player == null) return false;
        String normalized = LocaleBundle.normalize(locale);
        if (!bundles.supportedLocales().contains(normalized)) return false;
        String previous = locale(player);
        player.getPersistentDataContainer().set(localeKey, PersistentDataType.STRING, normalized);
        player.saveData();
        if (!previous.equals(normalized)) {
            plugin.getServer().getPluginManager().callEvent(
                    new PlayerLocaleChangeEvent(player, previous, normalized));
        }
        return true;
    }

    @Override
    public String text(CommandSender audience, String key) { return textForLocale(locale(audience), key); }

    @Override
    public String text(CommandSender audience, String key, Map<String, ?> placeholders) {
        return textForLocale(locale(audience), key, placeholders);
    }

    @Override
    public String textForLocale(String locale, String key) { return bundles.text(locale, key); }

    @Override
    public String textForLocale(String locale, String key, Map<String, ?> placeholders) {
        return LocaleBundle.format(bundles.text(locale, key), placeholders);
    }

    @Override
    public List<String> lines(CommandSender audience, String key) { return linesForLocale(locale(audience), key); }

    @Override
    public List<String> lines(CommandSender audience, String key, Map<String, ?> placeholders) {
        return linesForLocale(locale(audience), key, placeholders);
    }

    @Override
    public List<String> linesForLocale(String locale, String key) { return bundles.lines(locale, key); }

    @Override
    public List<String> linesForLocale(String locale, String key, Map<String, ?> placeholders) {
        return bundles.lines(locale, key).stream().map(value -> LocaleBundle.format(value, placeholders)).toList();
    }

    private LocaleBundle loadBundles() {
        Map<String, Map<String, ?>> overrides = new LinkedHashMap<>();
        Map<String, Map<String, ?>> packaged = new LinkedHashMap<>();
        for (String locale : BUNDLED_LOCALES) {
            String resourcePath = "lang/" + locale + ".yml";
            File file = new File(plugin.getDataFolder(), resourcePath);
            if (!file.exists()) plugin.saveResource(resourcePath, false);
            YamlConfiguration configuration = new YamlConfiguration();
            try {
                configuration.load(file);
            } catch (IOException | InvalidConfigurationException exception) {
                plugin.getLogger().warning("Cannot read " + resourcePath
                        + "; using packaged translations. The operator file was not modified.");
            }
            overrides.put(locale, configuration.getValues(true));
            try (Reader reader = new InputStreamReader(
                    java.util.Objects.requireNonNull(plugin.getResource(resourcePath), resourcePath),
                    StandardCharsets.UTF_8)) {
                packaged.put(locale, YamlConfiguration.loadConfiguration(reader).getValues(true));
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot load language defaults: " + resourcePath, exception);
            }
        }
        return new LocaleBundle(defaultLocale, overrides, packaged, plugin.getLogger()::warning);
    }
}
