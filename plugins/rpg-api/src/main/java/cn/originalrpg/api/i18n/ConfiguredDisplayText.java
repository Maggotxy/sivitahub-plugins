package cn.originalrpg.api.i18n;

/** Keeps a server owner's custom shared name while translating unchanged stock defaults. */
public final class ConfiguredDisplayText {
    private ConfiguredDisplayText() { }

    public static String resolve(String configured, String shippedDefault,
                                 String localeOverride, String translated) {
        if (localeOverride != null && !localeOverride.isBlank()) return localeOverride;
        if (configured != null && !configured.isBlank() && !configured.equals(shippedDefault)) return configured;
        return translated;
    }
}
