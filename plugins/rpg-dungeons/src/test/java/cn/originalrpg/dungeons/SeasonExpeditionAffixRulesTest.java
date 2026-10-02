package cn.originalrpg.dungeons;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SeasonExpeditionAffixRulesTest {
    @Test
    void eachAffixChangesOnlyItsDocumentedServerSideBudget() {
        var fortified = SeasonExpeditionAffixRules.modifiers(List.of(SeasonExpeditionAffix.FORTIFIED));
        assertEquals(250.0, fortified.bossHealth(200.0), 0.0001);
        assertEquals(115.0, fortified.waveHealth(100.0), 0.0001);
        assertEquals(10.0, fortified.damage(10.0), 0.0001);

        var relentless = SeasonExpeditionAffixRules.modifiers(List.of(SeasonExpeditionAffix.RELENTLESS));
        assertEquals(82L, relentless.attackInterval(100L));

        var swarming = SeasonExpeditionAffixRules.modifiers(List.of(SeasonExpeditionAffix.SWARMING));
        assertEquals(7, swarming.waveCount(5));

        var brutal = SeasonExpeditionAffixRules.modifiers(List.of(SeasonExpeditionAffix.BRUTAL));
        assertEquals(11.5, brutal.damage(10.0), 0.0001);
    }

    @Test
    void noAffixIsAnIdentityTransform() {
        var normal = SeasonExpeditionAffixRules.modifiers(List.of());
        assertEquals(200.0, normal.bossHealth(200.0));
        assertEquals(100.0, normal.waveHealth(100.0));
        assertEquals(10.0, normal.damage(10.0));
        assertEquals(5, normal.waveCount(5));
        assertEquals(100L, normal.attackInterval(100L));
    }
}
