package cn.originalrpg.ainpc;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class StaticKnowledge {
    private final YamlConfiguration knowledge;

    StaticKnowledge(YamlConfiguration knowledge) {
        this.knowledge = knowledge;
    }

    String answer(String locale, String question) {
        String selected = knowledge.isConfigurationSection(locale) ? locale : "zh_cn";
        String normalized = question.toLowerCase(Locale.ROOT);
        ConfigurationSection topics = knowledge.getConfigurationSection(selected + ".topics");
        if (topics != null) {
            for (String id : topics.getKeys(false)) {
                ConfigurationSection topic = topics.getConfigurationSection(id);
                if (topic == null || topic.getStringList("keywords").stream()
                        .map(keyword -> keyword.toLowerCase(Locale.ROOT))
                        .noneMatch(normalized::contains)) continue;
                List<String> answer = topic.getStringList("answer");
                if (!answer.isEmpty()) return String.join(" ", answer);
            }
        }
        List<String> generic = new ArrayList<>(knowledge.getStringList(selected + ".generic"));
        if (generic.isEmpty() && !selected.equals("zh_cn")) generic.addAll(knowledge.getStringList("zh_cn.generic"));
        return generic.isEmpty() ? "Use /menu to view the gameplay guide." : String.join(" ", generic);
    }
}
