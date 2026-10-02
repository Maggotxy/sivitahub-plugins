package cn.originalrpg.skills;

import java.util.Set;

/** IDs with concrete runtime effects; configuration may never advertise more. */
final class SkillCatalog {
    static final Set<String> IMPLEMENTED = Set.of(
            "heal", "renewal", "sanctuary", "fate-seal",
            "war-cry", "shield-wall", "fortress", "blood-rage",
            "shadow-step", "venom-blade", "smoke-bomb", "toxic-burst",
            "fireball", "frost-nova", "meteor", "mana-surge",
            "power-shot", "volley", "piercing-shot", "beast-call",
            "improvisation", "second-wind", "battle-trance", "arcane-edge",
            "life-thread", "omen-reversal", "soul-resonance",
            "rallying-standard", "ruin-charge", "unbroken-line",
            "void-cut", "plague-bloom", "vanished-horizon",
            "ash-domain", "time-lock", "axiomatic-break",
            "gale-step", "pack-hunt", "horizon-arrow",
            "adaptive-arsenal", "rune-reversal", "open-form");

    private SkillCatalog() {
    }
}
