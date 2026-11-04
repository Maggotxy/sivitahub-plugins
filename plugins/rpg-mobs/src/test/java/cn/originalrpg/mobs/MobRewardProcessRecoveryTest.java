package cn.originalrpg.mobs;

import cn.originalrpg.api.rules.CombatRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static cn.originalrpg.mobs.MobRewardFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Tests abrupt process death, not storage-controller power loss or a production filesystem benchmark. */
final class MobRewardProcessRecoveryTest {
    @TempDir Path directory;

    @Test void hardExitBeforeRenameLeavesOnlyAnUnacceptedStagingFile() throws Exception {
        MobRewardIntent intent = crashAt(MobRewardJournal.Step.FILE_FORCED);
        try (MobRewardJournal reopened = new MobRewardJournal(directory, 8, BYTE_LIMIT)) {
            assertEquals(List.of(), reopened.load());
            assertEquals(1, reopened.orphanedStagingFiles());
            assertTrue(directory.resolve(intent.killId() + ".staging").toFile().isFile());
        }
    }

    @Test void hardExitAfterRenameRecoversOnlyTheFrozenSurvivingFinalFile() throws Exception {
        MobRewardIntent intent = crashAt(MobRewardJournal.Step.RENAMED);
        try (MobRewardJournal reopened = new MobRewardJournal(directory, 8, BYTE_LIMIT)) {
            assertEquals(List.of(intent), reopened.load());
            assertEquals(0, reopened.orphanedStagingFiles());
        }
    }

    @Test void hardExitAfterDurabilityAcknowledgementRecoversExactIdsAndBytes() throws Exception {
        MobRewardIntent intent = crashAt(MobRewardJournal.Step.DIRECTORY_FORCED);
        try (MobRewardJournal reopened = new MobRewardJournal(directory, 8, BYTE_LIMIT)) {
            MobRewardIntent recovered = reopened.load().getFirst();
            assertEquals(intent, recovered);
            assertEquals(intent.experienceGrantId(), recovered.experienceGrantId());
            assertEquals(intent.deliveries(), recovered.deliveries());
            assertEquals(intent.pityRolls(), recovered.pityRolls());
        }
    }

    private MobRewardIntent crashAt(MobRewardJournal.Step step) throws Exception {
        MobRewardIntent intent = reward(UUID.randomUUID(), 42L, 2);
        // Gradle's test worker uses its own classloader; include these code sources
        // explicitly so the child runs the real production classes in either JUnit launcher.
        String classpath = String.join(File.pathSeparator,
                location(MobRewardCrashFixture.class), location(MobRewardIntent.class), location(CombatRules.class),
                System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, MobRewardCrashFixture.class.getName(), directory.toString(), step.name(),
                intent.killId().toString(), intent.playerId().toString()).redirectErrorStream(true).start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Crash fixture did not terminate within its bound");
        }
        String output = new String(process.getInputStream().readNBytes(4096), StandardCharsets.UTF_8);
        assertEquals(73, process.exitValue(), output);
        return intent;
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }
}
