package cn.originalrpg.skills;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SkillControlPolicyTest {
    @Test
    void onlyPlainFWithAnRpgWeaponOwnsTheVanillaEvent() {
        assertTrue(SkillControlPolicy.ownsSwapHands(true, false, true));
        assertFalse(SkillControlPolicy.ownsSwapHands(false, false, true));
        assertFalse(SkillControlPolicy.ownsSwapHands(true, true, true));
        assertFalse(SkillControlPolicy.ownsSwapHands(true, false, false));
    }
}
