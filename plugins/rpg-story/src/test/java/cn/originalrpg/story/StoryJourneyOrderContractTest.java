package cn.originalrpg.story;

import cn.originalrpg.story.testing.DeploymentEnvironmentFixtures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class StoryJourneyOrderContractTest {
    @Test
    @Tag(DeploymentEnvironmentFixtures.TAG)
    void qizhiConversationUsesTheExecutableFirstForgeOrder() throws Exception {
        String source = Files.readString(repositoryRoot().resolve(
                "server/config-templates/BetonQuest/QuestPackages/sivita_story/conversations/qizhi.yml"));
        String expected = "journeyHub,journeyClass,journeyAttribute,journeyForge,"
                + "journeyExplore,journeyHunt,journeyReveal,journeyDungeon";
        assertTrue(source.contains(expected));
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("Cannot locate repository root");
        return current;
    }
}
