package cn.originalrpg.essentials;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EssentialsCommandPolicyTest {
    private final EssentialsCommandPolicy policy = new EssentialsCommandPolicy(
            Set.of("ehome", "eback", "return", "eeco", "money", "backpack", "ebackpack"));

    @Test
    void blocksNamespacedProgressionBypasses() {
        var decision = policy.inspect("/essentials:home base");
        assertTrue(decision.blocked());
        assertEquals("/home", decision.recommendation());
    }

    @Test
    void blocksLegacyAliasesButKeepsRpgOwners() {
        assertTrue(policy.inspect("/eback").blocked());
        assertTrue(policy.inspect("/money").blocked());
        assertFalse(policy.inspect("/home").blocked());
        assertFalse(policy.inspect("/eco status").blocked());
        assertFalse(policy.inspect("/msg Alex hello").blocked());
    }

    @Test
    void redirectsBrokenBackpackUiToTheAuthoritativeWarehouse() {
        assertEquals("/warehouse", policy.inspect("/backpack").recommendation());
        assertEquals("/warehouse", policy.inspect("/ebackpack").recommendation());
        assertEquals("/warehouse", policy.inspect("/betonquest:backpack").recommendation());
        assertFalse(policy.inspect("/rpgworld:warehouse").blocked());
    }
}
