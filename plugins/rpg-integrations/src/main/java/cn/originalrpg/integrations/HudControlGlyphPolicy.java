package cn.originalrpg.integrations;

import cn.originalrpg.api.service.MessageService;
import org.bukkit.entity.Player;

/** Selects one reviewed bitmap label without asking BetterHud to ship a CJK font atlas. */
final class HudControlGlyphPolicy {
    private HudControlGlyphPolicy() {
    }

    static String glyph(MessageService messages, Player player, boolean cast) {
        String locale = messages == null || player == null ? "zh_cn" : messages.locale(player);
        return glyphForLocale(locale, cast);
    }

    static String glyphForLocale(String locale, boolean cast) {
        String language = locale != null && locale.toLowerCase(java.util.Locale.ROOT).startsWith("en")
                ? "en" : "zh";
        return "<image:control_" + (cast ? "cast" : "cycle") + "_" + language + ">";
    }
}
