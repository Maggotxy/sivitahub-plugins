package cn.originalrpg.tools.dbmigration;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperStopGateTest {
    @Test
    void recognizesPaperOwnedBySelectedRuntime() {
        Path runtime = Path.of("/srv/sivita/server/runtime");
        assertTrue(PaperStopGate.commandLooksLikePaper(
                "/srv/sivita/server/runtime/java/bin/java",
                new String[]{"-Xmx8G", "-jar", "paper.jar", "--nogui"},
                runtime,
                runtime
        ));
    }

    @Test
    void ignoresOtherJavaAndOtherRuntime() {
        Path runtime = Path.of("/srv/sivita/server/runtime");
        assertFalse(PaperStopGate.commandLooksLikePaper(
                "/usr/bin/java", new String[]{"-jar", "migration.jar"}, runtime, runtime
        ));
        assertFalse(PaperStopGate.commandLooksLikePaper(
                "/srv/other/java/bin/java", new String[]{"-jar", "paper.jar"},
                Path.of("/srv/other"), runtime
        ));
    }
}
