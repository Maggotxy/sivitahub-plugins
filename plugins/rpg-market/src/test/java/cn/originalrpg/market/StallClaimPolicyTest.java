package cn.originalrpg.market;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StallClaimPolicyTest {
    @Test
    void creationRequiresAnAvailableAndSuccessfulClaimVerification() {
        assertEquals(StallClaimPolicy.Decision.INTEGRATION_UNAVAILABLE,
                StallClaimPolicy.decide(false, false, false, false));
        assertEquals(StallClaimPolicy.Decision.INTEGRATION_UNAVAILABLE,
                StallClaimPolicy.decide(true, false, false, false));
    }

    @Test
    void wildernessAndClaimsWithoutEditPermissionAreRejected() {
        assertEquals(StallClaimPolicy.Decision.CLAIM_REQUIRED,
                StallClaimPolicy.decide(true, true, false, false));
        assertEquals(StallClaimPolicy.Decision.CLAIM_REQUIRED,
                StallClaimPolicy.decide(true, true, true, false));
    }

    @Test
    void verifiedClaimEditPermissionAllowsCreation() {
        assertEquals(StallClaimPolicy.Decision.ALLOWED,
                StallClaimPolicy.decide(true, true, true, true));
    }

    @Test
    void marketLoadsAfterItsOptionalClaimProvider() throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("plugin.yml")) {
            assertNotNull(input);
            String descriptor = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(descriptor.lines().anyMatch(line -> line.strip()
                            .matches("softdepend:\\s*\\[[^]]*\\bGriefPrevention\\b[^]]*]")),
                    "plugin.yml must load RpgMarket after GriefPrevention when it is installed");
        }
    }
}
