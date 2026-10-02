package cn.originalrpg.pets;

import cn.originalrpg.api.service.MessageService;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Map;

final class PetMessages {
    private final FileConfiguration config;
    private final MessageService languages;

    PetMessages(FileConfiguration config, MessageService languages) {
        this.config = config;
        this.languages = languages;
    }

    String locale(CommandSender audience) {
        String locale = languages.locale(audience);
        return config.isConfigurationSection("messages." + locale) ? locale : "zh_cn";
    }

    String render(CommandSender audience, String key) {
        return render(audience, key, Map.of());
    }

    String render(CommandSender audience, String key, Map<String, ?> placeholders) {
        String locale = locale(audience);
        String value = config.getString("messages." + locale + "." + key);
        if (value == null) value = config.getString("messages.zh_cn." + key, key);
        return replace(value, placeholders);
    }

    List<String> renderLines(CommandSender audience, String key) {
        return renderLines(audience, key, Map.of());
    }

    List<String> renderLines(CommandSender audience, String key, Map<String, ?> placeholders) {
        String locale = locale(audience);
        List<String> values = config.getStringList("messages." + locale + "." + key);
        if (values.isEmpty()) values = config.getStringList("messages.zh_cn." + key);
        return values.stream().map(value -> replace(value, placeholders)).toList();
    }

    private static String replace(String value, Map<String, ?> placeholders) {
        String output = value;
        for (Map.Entry<String, ?> entry : placeholders.entrySet()) {
            output = output.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return output;
    }
}
