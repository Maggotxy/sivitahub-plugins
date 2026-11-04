package cn.originalrpg.mobs;

import cn.originalrpg.api.service.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.Map;

/** Shared world names follow the server locale, including late blood-moon assignment. */
public final class AdaptiveMobNamePresentation {
    private AdaptiveMobNamePresentation() {
    }

    public static boolean mayReplace(MessageService messages, Component existing, String rareId) {
        if (existing == null) return true;
        if (rareId == null) return false; // Preserve names supplied by players or other plugins.
        String text = PlainTextComponentSerializer.plainText().serialize(existing);
        // This assignment only runs for mobs without a level. A rare name may
        // already have been assigned by rpg-world while profile loading delayed us.
        return messages.supportedLocales().stream().anyMatch(locale -> text.equals(
                rareName(messages, locale, rareId, null, null)));
    }

    public static Component render(MessageService messages, int level, String affix,
                                   String behaviorId, String entityTranslationKey, String rareId) {
        String locale = messages.defaultLocale();
        if (rareId != null) return Component.text(rareName(messages, locale, rareId, level, affix), NamedTextColor.DARK_RED);
        Component name = Component.text("Lv." + level + " ", affix == null ? NamedTextColor.GRAY : NamedTextColor.RED);
        if (affix != null) {
            name = Component.text(elitePrefix(messages, locale, affix), NamedTextColor.RED).append(name);
        } else if (behaviorId != null) {
            name = name.append(Component.text("[" + messages.textForLocale(locale,
                    "mobs.behaviors." + behaviorId + ".name") + "] ", NamedTextColor.AQUA));
        }
        return name.append(Component.translatable(entityTranslationKey));
    }

    private static String rareName(MessageService messages, String locale, String rareId, Integer level, String affix) {
        return messages.textForLocale(locale, level == null ? "world.rare-mob-name-unscaled" : "world.rare-mob-name",
                Map.of("elite", elitePrefix(messages, locale, affix),
                        "name", messages.textForLocale(locale, "world.blood-moon-mobs." + rareId + ".name"),
                        "level", level == null ? "" : level));
    }

    private static String elitePrefix(MessageService messages, String locale, String affix) {
        return affix == null ? "" : messages.textForLocale(locale, "mobs.elite-name",
                Map.of("affix", messages.textForLocale(locale, "mobs.affixes." + affix))) + " ";
    }
}
