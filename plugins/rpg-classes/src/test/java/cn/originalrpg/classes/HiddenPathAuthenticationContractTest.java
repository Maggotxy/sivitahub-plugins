package cn.originalrpg.classes;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class HiddenPathAuthenticationContractTest {
    @Test
    void asyncEffectsFenceEveryPlayerMutationByAuthenticationGeneration() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/cn/originalrpg/classes/HiddenPathEffects.java"));

        assertTrue(source.contains("authenticatedGenerations"));
        assertTrue(source.contains("long generation = generation(playerId)"));
        assertTrue(source.contains("if (!valid(player, generation)) return;"));
        assertTrue(source.contains("if (!valid(player, generation) || owned.get(playerId) != HiddenPath.BLOODPACT) return;"));
        assertTrue(source.contains("if (!valid(player, generation) || !talismanEnabled.contains(playerId)) return;"));
    }
}
