package cn.originalrpg.skills;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Pure codec and expiry policy for short-lived per-player skill state. */
final class SkillSessionState {
    private static final int MAX_SERIALIZED_LENGTH = 4096;
    private static final int MAX_COOLDOWNS = 64;

    private SkillSessionState() {
    }

    static String encodeCooldowns(Map<String, Long> cooldowns, long now) {
        if (cooldowns == null || cooldowns.isEmpty()) return "";
        return cooldowns.entrySet().stream()
                .filter(entry -> validId(entry.getKey()) && entry.getValue() != null && entry.getValue() > now)
                .sorted(Map.Entry.comparingByKey())
                .limit(MAX_COOLDOWNS)
                .map(entry -> entry.getKey() + ":" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(","));
    }

    static Map<String, Long> decodeCooldowns(String value, Set<String> configured, long now) {
        if (value == null || value.isBlank() || value.length() > MAX_SERIALIZED_LENGTH
                || configured == null || configured.isEmpty()) return Map.of();
        Map<String, Long> result = new LinkedHashMap<>();
        for (String entry : value.split(",", -1)) {
            if (result.size() >= MAX_COOLDOWNS) break;
            int separator = entry.indexOf(':');
            if (separator <= 0 || separator == entry.length() - 1) continue;
            String id = entry.substring(0, separator);
            if (!validId(id) || !configured.contains(id) || result.containsKey(id)) continue;
            try {
                long availableAt = Long.parseLong(entry.substring(separator + 1));
                if (availableAt > now) result.put(id, availableAt);
            } catch (NumberFormatException ignored) {
                // Player PDC is untrusted input. Ignore malformed legacy state.
            }
        }
        return Map.copyOf(result);
    }

    static int restoreMana(Integer stored, int cap) {
        return Math.clamp(stored == null ? cap : stored, 0, Math.max(0, cap));
    }

    private static boolean validId(String id) {
        return id != null && !id.isBlank() && id.length() <= 64
                && id.chars().allMatch(character -> character >= 'a' && character <= 'z'
                        || character >= '0' && character <= '9' || character == '-');
    }
}
