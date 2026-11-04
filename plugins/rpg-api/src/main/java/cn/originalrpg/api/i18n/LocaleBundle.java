package cn.originalrpg.api.i18n;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable text lookup with operator overrides and same-language packaged fallback.
 * Inputs use flattened dotted keys; configuration-section objects are ignored.
 * Invalid overrides never hide a usable bundled translation and are never rewritten.
 */
public final class LocaleBundle {
    private static final Pattern TOKEN = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_-]*)}");
    private final String defaultLocale;
    private final Map<String, Map<String, Object>> overrides;
    private final Map<String, Map<String, Object>> packaged;

    public LocaleBundle(String defaultLocale, Map<String, ? extends Map<String, ?>> overrides,
                        Map<String, ? extends Map<String, ?>> packaged, Consumer<String> warning) {
        this.defaultLocale = normalize(defaultLocale);
        this.packaged = snapshot(packaged);
        Map<String, Map<String, Object>> validated = new LinkedHashMap<>();
        overrides.forEach((locale, values) -> {
            Map<String, Object> accepted = new LinkedHashMap<>();
            Map<String, Object> contracts = this.packaged.getOrDefault(normalize(locale), Map.of());
            values.forEach((key, raw) -> {
                Object value = textValue(raw);
                Object contract = contracts.get(key);
                if (contract != null && (value == null || !sameKind(contract, value)
                        || !tokens(contract).equals(tokens(value)))) {
                    warning.accept("Ignored invalid language override " + locale + ":" + key
                            + " (expected matching text type and placeholders); using packaged text.");
                } else if (value != null) {
                    accepted.put(key, value);
                }
            });
            validated.put(normalize(locale), Map.copyOf(accepted));
        });
        this.overrides = Map.copyOf(validated);
    }

    public Set<String> supportedLocales() {
        Set<String> locales = new LinkedHashSet<>(packaged.keySet());
        locales.addAll(overrides.keySet());
        return Set.copyOf(locales);
    }

    public String text(String locale, String key) {
        Object value = lookup(locale, key, false);
        return value instanceof String text ? text : key;
    }

    public List<String> lines(String locale, String key) {
        Object value = lookup(locale, key, true);
        if (!(value instanceof List<?> lines)) return List.of();
        return lines.stream().map(String.class::cast).toList();
    }

    /** Same-language lookup for content whose prose must never cross language boundaries. */
    public List<String> linesInLocale(String locale, String key) {
        Object value = lookupInLocale(normalize(locale), key, true);
        if (!(value instanceof List<?> lines)) return List.of();
        return lines.stream().map(String.class::cast).toList();
    }

    private Object lookup(String requested, String key, boolean list) {
        LinkedHashSet<String> locales = new LinkedHashSet<>(List.of(
                normalize(requested), defaultLocale, "en_us", "zh_cn"));
        for (String locale : locales) {
            Object value = lookupInLocale(locale, key, list);
            if (value != null) return value;
        }
        return null;
    }

    private Object lookupInLocale(String locale, String key, boolean list) {
        Object custom = overrides.getOrDefault(locale, Map.of()).get(key);
        if (correctKind(custom, list)) return custom;
        Object bundled = packaged.getOrDefault(locale, Map.of()).get(key);
        return correctKind(bundled, list) ? bundled : null;
    }

    /** Substitute only tokens in the original template, never inside player-supplied values. */
    public static String format(String template, Map<String, ?> placeholders) {
        Matcher matcher = TOKEN.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = placeholders.containsKey(key)
                    ? String.valueOf(placeholders.get(key)) : matcher.group();
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public static String normalize(String locale) {
        return locale == null ? "" : locale.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static Map<String, Map<String, Object>> snapshot(Map<String, ? extends Map<String, ?>> source) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        source.forEach((locale, entries) -> {
            Map<String, Object> values = new LinkedHashMap<>();
            entries.forEach((key, raw) -> {
                Object value = textValue(raw);
                if (value != null) values.put(key, value);
            });
            result.put(normalize(locale), Map.copyOf(values));
        });
        return Map.copyOf(result);
    }

    private static Object textValue(Object raw) {
        if (raw instanceof String text) return text.isBlank() ? null : text;
        if (!(raw instanceof List<?> list) || list.isEmpty()) return null;
        List<String> copy = new ArrayList<>(list.size());
        for (Object element : list) {
            if (!(element instanceof String text) || text.isBlank()) return null;
            copy.add(text);
        }
        return List.copyOf(copy);
    }

    private static boolean sameKind(Object first, Object second) {
        return first instanceof String && second instanceof String
                || first instanceof List<?> && second instanceof List<?>;
    }

    private static boolean correctKind(Object value, boolean list) {
        return list ? value instanceof List<?> : value instanceof String;
    }

    private static Set<String> tokens(Object value) {
        String text = value instanceof String single ? single
                : String.join("\n", ((List<?>) value).stream().map(String.class::cast).toList());
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) names.add(matcher.group(1));
        return Set.copyOf(names);
    }
}
