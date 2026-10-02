package cn.originalrpg.essentials;

import cn.originalrpg.api.service.MessageService;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Map;

final class BridgeMessages {
    private final FileConfiguration config;
    private final MessageService languages;

    BridgeMessages(FileConfiguration config, MessageService languages) {
        this.config = config;
        this.languages = languages;
    }

    String render(CommandSender audience, String key, Map<String, ?> placeholders) {
        String locale = languages.locale(audience);
        String value = config.getString("messages." + locale + "." + key);
        if (value == null) value = config.getString("messages.zh_cn." + key, key);
        for (Map.Entry<String, ?> entry : placeholders.entrySet()) {
            value = value.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return value;
    }
}
