package cn.originalrpg.items.forge;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ForgeInputBoundaryContractTest {
    @Test
    void previewAndBothCommitChecksRejectAlreadySignedEquipment() throws IOException {
        String source = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-items/src/main/java/cn/originalrpg/items/forge/ForgeListener.java"),
                StandardCharsets.UTF_8);
        int helper = source.indexOf("private boolean validForgeBase");
        int signedGuard = source.indexOf("!security.isCustomEquipment(base)", helper);
        int preview = source.indexOf("if (!validForgeBase(base)");
        int attempt = source.indexOf("if (!validForgeBase(base)", preview + 1);
        int commit = source.indexOf("if (!validForgeBase(base)", attempt + 1);

        assertTrue(helper >= 0 && signedGuard > helper, "forge bases must reject existing custom equipment");
        assertTrue(preview >= 0 && attempt > preview && commit > attempt,
                "preview, click validation and final commit must share the same base rule");
    }

    @Test
    void everyForgeInventoryMutationChecksTheMenuOwner() throws IOException {
        String source = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-items/src/main/java/cn/originalrpg/items/forge/ForgeListener.java"),
                StandardCharsets.UTF_8);
        int click = source.indexOf("public void onClick");
        int clickOwner = source.indexOf("!menu.ownerId().equals(player.getUniqueId())", click);
        int drag = source.indexOf("public void onDrag", clickOwner);
        int dragOwner = source.indexOf("!menu.ownerId().equals(event.getWhoClicked().getUniqueId())", drag);
        int close = source.indexOf("public void onClose", dragOwner);
        int closeOwner = source.indexOf("!menu.ownerId().equals(player.getUniqueId())", close);

        assertTrue(clickOwner > click && dragOwner > drag && closeOwner > close,
                "click, drag and close paths must enforce the forge inventory owner");
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
