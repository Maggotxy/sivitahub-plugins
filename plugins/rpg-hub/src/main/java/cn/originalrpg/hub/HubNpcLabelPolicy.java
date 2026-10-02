package cn.originalrpg.hub;

/** Pure single-line boundary for world-space NPC identity plates. */
final class HubNpcLabelPolicy {
    static final int MAX_CODE_POINTS = 24;

    private HubNpcLabelPolicy() {
    }

    static String label(String configured, String fallback) {
        String normalized = normalize(configured);
        if (normalized.isEmpty()) normalized = normalize(fallback);
        if (normalized.isEmpty()) normalized = "NPC";
        int length = normalized.codePointCount(0, normalized.length());
        if (length <= MAX_CODE_POINTS) return normalized;
        int end = normalized.offsetByCodePoints(0, MAX_CODE_POINTS - 1);
        return normalized.substring(0, end).stripTrailing() + "…";
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) return "";
        StringBuilder result = new StringBuilder(value.length());
        boolean previousWhitespace = true;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isISOControl(codePoint)) {
                if (!previousWhitespace) result.append(' ');
                previousWhitespace = true;
            } else {
                result.appendCodePoint(codePoint);
                previousWhitespace = false;
            }
        }
        return result.toString().strip();
    }
}
