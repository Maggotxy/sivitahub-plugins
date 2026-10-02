package cn.originalrpg.dungeons;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonWorldChangeLifecycleContractTest {
    @Test
    void externalWorldChangesClearTheReturnMarkerBeforeReleasingMembership() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/cn/originalrpg/dungeons/DungeonManager.java"));
        int handler = source.indexOf("public void onWorldChange(PlayerChangedWorldEvent event)");
        int rule = source.indexOf("DungeonSessionRules.departedRegisteredInstance", handler);
        int clear = source.indexOf("remove(returnLocation)", rule);
        int save = source.indexOf("player.saveData()", clear);
        int release = source.indexOf("releasePlayer(player.getUniqueId(), event.getFrom())", save);

        assertTrue(handler >= 0 && rule > handler,
                "world-change handler must distinguish departure from the registered instance");
        assertTrue(clear > rule && save > clear && release > save,
                "return state must be durably cleared before membership and instance recycling");
    }
}
