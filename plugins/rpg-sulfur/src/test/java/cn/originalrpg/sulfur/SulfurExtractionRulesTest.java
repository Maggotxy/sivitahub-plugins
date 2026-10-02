package cn.originalrpg.sulfur;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SulfurExtractionRulesTest {
    @Test
    void tunedCubeIsPreservedUntilThePlayerProvidesABlankImprint() {
        assertTrue(SulfurExtractionRules.cancelForMissingBlank(true, false));
        assertFalse(SulfurExtractionRules.cancelForMissingBlank(true, true));
        assertFalse(SulfurExtractionRules.cancelForMissingBlank(false, false));
    }
}
