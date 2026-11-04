package cn.originalrpg.ainpc;

import cn.originalrpg.api.i18n.LocaleBundle;
import java.util.function.Function;

/** Picks the response language at delivery time without restarting a paid provider request. */
record AiResponsePresentation(String answer, boolean remote, boolean languageChanged) {
    static AiResponsePresentation select(String requestedLocale, String currentLocale,
                                         String completedAnswer, boolean remote,
                                         Function<String, String> localAnswer) {
        boolean changed = !LocaleBundle.normalize(requestedLocale).equals(LocaleBundle.normalize(currentLocale));
        return changed ? new AiResponsePresentation(localAnswer.apply(currentLocale), false, true)
                : new AiResponsePresentation(completedAnswer, remote, false);
    }
}
