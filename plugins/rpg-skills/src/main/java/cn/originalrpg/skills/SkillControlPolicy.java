package cn.originalrpg.skills;

/** Pure ownership rule for the vanilla swap-hands key. */
final class SkillControlPolicy {
    private SkillControlPolicy() {
    }

    static boolean ownsSwapHands(boolean authenticated, boolean sneaking, boolean rpgWeapon) {
        return authenticated && !sneaking && rpgWeapon;
    }
}
