package cn.originalrpg.pets;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class PetConfigurationContractTest {
    @Test
    void durableRevivalMessagesAndAuditCadenceShipInBothLanguages() throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            if (input == null) throw new IllegalStateException("config.yml is missing");
            @SuppressWarnings("unchecked")
            Map<String, Object> root = new Yaml().load(input);
            assertEquals(5, root.get("config-version"));
            Map<String, Object> revive = section(root, "revive");
            assertEquals(200, revive.get("recovery-interval-ticks"));
            Map<String, Object> messages = section(root, "messages");
            for (String locale : new String[]{"zh_cn", "en_us"}) {
                Map<String, Object> language = section(messages, locale);
                assertFalse(language.isEmpty(), locale + " must remain a mapping, not a help-line list");
                for (String key : new String[]{"revive-saved", "revive-resuming", "revive-support", "revive-pet"}) {
                    String value = String.valueOf(language.get(key));
                    assertFalse(value.isBlank(), locale + "." + key);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> parent, String key) {
        return (Map<String, Object>) parent.get(key);
    }
}
