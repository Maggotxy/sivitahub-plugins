package cn.originalrpg.ainpc;

import cn.originalrpg.api.i18n.LocaleBundle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

final class StaticKnowledge {
    private final LocaleBundle bundles;
    private final Set<String> topicIds;
    private final String defaultLocale;

    StaticKnowledge(YamlConfiguration knowledge) {
        this(knowledge, new YamlConfiguration(), "zh_cn", ignored -> { });
    }

    StaticKnowledge(YamlConfiguration knowledge, YamlConfiguration packaged,
                    String defaultLocale, Consumer<String> warning) {
        this.defaultLocale = LocaleBundle.normalize(defaultLocale);
        bundles = new LocaleBundle(this.defaultLocale, sections(knowledge), sections(packaged), warning);
        LinkedHashSet<String> topics = new LinkedHashSet<>();
        for (YamlConfiguration source : List.of(knowledge, packaged)) {
            for (String locale : source.getKeys(false)) {
                ConfigurationSection section = source.getConfigurationSection(locale + ".topics");
                if (section != null) topics.addAll(section.getKeys(false));
            }
        }
        topicIds = java.util.Collections.unmodifiableSet(topics);
    }

    String answer(String locale, String question) {
        String requested = LocaleBundle.normalize(locale);
        String selected = bundles.supportedLocales().contains(requested) ? requested : defaultLocale;
        String normalized = question == null ? "" : question.toLowerCase(Locale.ROOT);
        for (String id : topicIds) {
            List<String> keywords = bundles.linesInLocale(selected, "topics." + id + ".keywords");
            if (keywords.stream().map(keyword -> keyword.toLowerCase(Locale.ROOT))
                    .noneMatch(normalized::contains)) continue;
            List<String> answer = bundles.linesInLocale(selected, "topics." + id + ".answer");
            if (!answer.isEmpty()) return String.join(" ", answer);
        }
        List<String> generic = bundles.linesInLocale(selected, "generic");
        return generic.isEmpty() ? "Use /menu to view the gameplay guide." : String.join(" ", generic);
    }

    private static Map<String, Map<String, ?>> sections(YamlConfiguration source) {
        Map<String, Map<String, ?>> locales = new LinkedHashMap<>();
        for (String locale : source.getKeys(false)) {
            ConfigurationSection section = source.getConfigurationSection(locale);
            if (section != null) locales.put(locale, section.getValues(true));
        }
        return locales;
    }
}
