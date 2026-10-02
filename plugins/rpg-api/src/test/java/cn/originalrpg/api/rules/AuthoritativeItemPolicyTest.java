package cn.originalrpg.api.rules;

import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthoritativeItemPolicyTest {
    @Test
    void everyCurrentItemMarkerNamespaceIsAuthoritative() {
        Set<String> namespaces = Set.of(
                "rpgitems", "rpgapi", "rpgenhancement", "rpgmarket", "rpgcommerce", "rpgeconomy",
                "rpgdungeons", "rpgskills", "rpgguide", "rpgworld", "rpgmobs",
                "rpgpets", "rpgsulfur", "originalrpg");
        namespaces.forEach(namespace -> assertTrue(
                AuthoritativeItemPolicy.isAuthoritativeKey(new NamespacedKey(namespace, "contract_marker")),
                namespace));
        assertFalse(AuthoritativeItemPolicy.isAuthoritativeKey(NamespacedKey.minecraft("ordinary")));
        assertFalse(AuthoritativeItemPolicy.isAuthoritativeKey(new NamespacedKey("luckperms", "ordinary")));
        assertFalse(AuthoritativeItemPolicy.isAuthoritativeKey(new NamespacedKey("rpgthirdparty", "ordinary")),
                "A third-party namespace merely beginning with rpg is not owned by this server");
    }

    @Test
    void concreteCrossPluginItemMarkersRemainBehindTheBoundary() {
        for (String marker : Set.of(
                "rpgitems:item_id",
                "rpgitems:forge_essence",
                "rpgenhancement:refine_count",
                "rpgmarket:delivery_id",
                "rpgcommerce:reward_marker",
                "rpgeconomy:vanilla_origin",
                "rpgdungeons:boss_material",
                "rpgdungeons:ticket_id",
                "rpgskills:skill_book",
                "rpgguide:handbook",
                "rpgworld:hunt_material",
                "rpgmobs:adaptive_material",
                "rpgpets:companion_signal",
                "rpgsulfur:blank_imprint",
                "originalrpg:traveler_compass")) {
            assertTrue(AuthoritativeItemPolicy.isAuthoritativeKey(
                    NamespacedKey.fromString(marker)), marker);
        }
    }

    @Test
    void ordinaryVanillaInputsRemainUsableForEveryOperation() {
        var ordinary = evidence(false, false, false, false, false, false);
        for (var operation : AuthoritativeItemPolicy.Operation.values()) {
            assertTrue(AuthoritativeItemPolicy.allows(operation, ordinary), operation.name());
        }
    }

    @Test
    void metadataBlindOperationsAlwaysRejectAuthoritativeInputs() {
        var apparentlyPreservedEquipment = evidence(true, true, false, true, true, true);
        for (var operation : Set.of(
                AuthoritativeItemPolicy.Operation.CRAFTING,
                AuthoritativeItemPolicy.Operation.AUTOMATIC_CRAFTING,
                AuthoritativeItemPolicy.Operation.COOKING,
                AuthoritativeItemPolicy.Operation.STONECUTTING,
                AuthoritativeItemPolicy.Operation.GRINDSTONE)) {
            assertFalse(AuthoritativeItemPolicy.allows(operation, apparentlyPreservedEquipment), operation.name());
        }
    }

    @Test
    void anvilAndSmithingRequireAuthenticSameIdentityWithExactPdc() {
        var safe = evidence(true, true, false, true, true, true);
        assertTrue(AuthoritativeItemPolicy.allows(AuthoritativeItemPolicy.Operation.ANVIL, safe));
        assertTrue(AuthoritativeItemPolicy.allows(AuthoritativeItemPolicy.Operation.SMITHING, safe));

        for (var unsafe : Set.of(
                evidence(true, false, false, true, true, true),
                evidence(true, true, true, true, true, true),
                evidence(true, true, false, false, true, true),
                evidence(true, true, false, true, false, true),
                evidence(true, true, false, true, true, false))) {
            assertFalse(AuthoritativeItemPolicy.allows(AuthoritativeItemPolicy.Operation.ANVIL, unsafe));
            assertFalse(AuthoritativeItemPolicy.allows(AuthoritativeItemPolicy.Operation.SMITHING, unsafe));
        }
    }

    private static AuthoritativeItemPolicy.TransformEvidence evidence(
            boolean authoritative,
            boolean inputEquipment,
            boolean authoritativeSecondary,
            boolean outputEquipment,
            boolean sameIdentity,
            boolean pdcPreserved
    ) {
        return new AuthoritativeItemPolicy.TransformEvidence(authoritative, inputEquipment,
                authoritativeSecondary, outputEquipment, sameIdentity, pdcPreserved);
    }
}
