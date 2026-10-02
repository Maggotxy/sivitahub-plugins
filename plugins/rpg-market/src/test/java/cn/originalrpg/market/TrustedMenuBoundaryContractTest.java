package cn.originalrpg.market;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The container-transfer policy exempts exactly two kinds of inventory: a
 * player's own, and holders that explicitly opt in via TrustedMenuHolder.
 * The policy used to exempt everything whose class name started with the
 * plugin namespace, which granted trust to every GUI ever written under it.
 * These contracts keep the exemption from silently widening back: they scan
 * the whole repository, so an unmarked new menu in any module fails here
 * rather than shipping as a quiet transfer-policy bypass.
 */
final class TrustedMenuBoundaryContractTest {
    @Test
    void trustIsAnExplicitMarkerNeverANamespaceGuess() throws Exception {
        Path listener = repositoryRoot().resolve(
                "plugins/rpg-market/src/main/java/cn/originalrpg/market/TradeRestrictionListener.java");
        String source = Files.readString(listener);
        assertTrue(source.contains("holder instanceof TrustedMenuHolder"),
                "isTrustedInventory must consult the marker interface");
        assertFalse(source.contains("startsWith(\"cn.originalrpg.\")"),
                "namespace-prefix trust is the bypass this seam replaced; it must not return");
    }

    @Test
    void everyCustomInventoryHolderOptsIntoTheMarker() throws Exception {
        // Directly `implements InventoryHolder` is the signature of a first-party
        // menu that would silently regain the exemption through compilation
        // order or a future refactor; a holder that wants trust says so.
        Pattern bareHolder = Pattern.compile("\\bimplements\\s+[\\w.,\\s]*\\bInventoryHolder\\b");
        List<String> offenders = new ArrayList<>();
        Path plugins = repositoryRoot().resolve("plugins");
        try (Stream<Path> files = Files.walk(plugins)) {
            for (Path file : (Iterable<Path>) files
                    .filter(path -> path.getFileName().toString().endsWith(".java")
                            && path.toString().contains("src" + java.io.File.separator + "main"))::iterator) {
                if (bareHolder.matcher(Files.readString(file)).find()) offenders.add(plugins.relativize(file).toString());
            }
        }
        assertTrue(offenders.isEmpty(),
                () -> "menu holders must implement TrustedMenuHolder, not raw InventoryHolder: " + offenders);
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
