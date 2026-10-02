package cn.originalrpg.mobs.ability;

/** A single readable combat twist carried by an otherwise vanilla natural mob. */
public enum MobBehavior {
    SKIRMISHER("skirmisher"),
    CHARGER("charger"),
    SPITTER("spitter"),
    WARDEN("warden"),
    CONDUCTOR("conductor"),
    BOOMERANG("boomerang"),
    BURROWER("burrower"),
    TRAPPER("trapper"),
    ORBITER("orbiter");

    private final String id;

    MobBehavior(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static MobBehavior fromId(String id) {
        if (id == null) return null;
        for (MobBehavior behavior : values()) {
            if (behavior.id.equals(id)) return behavior;
        }
        return null;
    }
}
