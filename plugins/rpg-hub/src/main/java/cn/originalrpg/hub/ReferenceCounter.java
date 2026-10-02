package cn.originalrpg.hub;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Small main-thread reference counter for overlapping temporary resources. */
final class ReferenceCounter<T> {
    private final Map<T, Integer> references = new HashMap<>();

    /** @return true when the caller must acquire the underlying resource. */
    boolean retain(T value) {
        int count = references.merge(value, 1, Integer::sum);
        return count == 1;
    }

    /** @return true when the caller must release the underlying resource. */
    boolean release(T value) {
        Integer count = references.get(value);
        if (count == null) return false;
        if (count > 1) {
            references.put(value, count - 1);
            return false;
        }
        references.remove(value);
        return true;
    }

    Set<T> retained() {
        return Set.copyOf(references.keySet());
    }

    void clear() {
        references.clear();
    }
}
