package cn.originalrpg.classes;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClassMenuContractTest {
    @Test
    void hiddenPathCardHasBilingualPlayerCopyAndNoLayoutCollision() {
        Map<?, ?> root = loadConfig();
        Map<?, ?> text = (Map<?, ?>) root.get("menu-text");
        assertNotNull(text);
        for (String locale : Set.of("zh_cn", "en_us")) {
            Map<?, ?> localized = (Map<?, ?>) text.get(locale);
            assertNotNull(localized, locale);
            Map<?, ?> hidden = (Map<?, ?>) localized.get("hidden-path");
            assertNotNull(hidden, locale);
            assertEquals(Set.of("name", "summary", "rules", "open"), hidden.keySet(), locale);
            hidden.values().forEach(value -> assertTrue(!String.valueOf(value).isBlank(), locale));
        }
        Set<Integer> occupied = Set.of(4, 10, 11, 12, 13, 14, 15, 22,
                28, 29, 30, 31, 32, ClassMenuPolicy.ASCEND_SLOT,
                37, 38, 40, 42, 43, 45, 49, 53);
        assertTrue(!occupied.contains(ClassMenuPolicy.HIDDEN_PATH_SLOT));
    }

    private static Map<?, ?> loadConfig() {
        try (InputStream input = ClassMenuContractTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(input);
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            return (Map<?, ?>) new Yaml(new SafeConstructor(options)).load(input);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
