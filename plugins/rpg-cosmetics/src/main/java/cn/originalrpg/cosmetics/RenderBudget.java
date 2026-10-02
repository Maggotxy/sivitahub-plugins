package cn.originalrpg.cosmetics;

/** A pass-local hard cap; one successful take represents one receiver/particle packet. */
final class RenderBudget {
    private final RenderBudget parent;
    private int remaining;

    RenderBudget(int limit) {
        this(null, limit);
    }

    private RenderBudget(RenderBudget parent, int limit) {
        this.parent = parent;
        remaining = Math.max(0, limit);
    }

    boolean hasRemaining() {
        return remaining > 0 && (parent == null || parent.hasRemaining());
    }

    boolean take() {
        if (remaining <= 0) return false;
        if (parent != null && !parent.take()) return false;
        remaining--;
        return true;
    }

    RenderBudget child(int limit) {
        return new RenderBudget(this, limit);
    }

    int remaining() {
        return remaining;
    }
}
