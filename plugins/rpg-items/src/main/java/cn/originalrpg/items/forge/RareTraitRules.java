package cn.originalrpg.items.forge;

import java.util.Map;

/** Pure weighted-selection boundary for the signed rare-trait pool. */
final class RareTraitRules {
    private RareTraitRules() {
    }

    static String select(Map<String, Integer> weights, long randomValue) {
        if (weights == null || weights.isEmpty()) {
            throw new IllegalArgumentException("Rare-trait weights are required");
        }
        java.util.List<Map.Entry<String, Integer>> entries = weights.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank()
                        && entry.getValue() != null && entry.getValue() > 0)
                .sorted(Map.Entry.comparingByKey())
                .toList();
        if (entries.isEmpty()) throw new IllegalArgumentException("Rare-trait weights must be positive");
        long total = entries.stream().mapToLong(entry -> Math.min(10_000, entry.getValue())).sum();
        long cursor = Math.floorMod(randomValue, total);
        for (Map.Entry<String, Integer> entry : entries) {
            cursor -= Math.min(10_000, entry.getValue());
            if (cursor < 0) return entry.getKey();
        }
        throw new IllegalStateException("Weighted rare-trait selection exhausted");
    }
}
