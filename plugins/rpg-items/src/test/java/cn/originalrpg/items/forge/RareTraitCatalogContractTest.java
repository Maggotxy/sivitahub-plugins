package cn.originalrpg.items.forge;

import cn.originalrpg.api.model.EquipmentTraitCatalog;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RareTraitCatalogContractTest {
    @Test
    void forgePoolContainsOnlyRuntimeBackedTraitsWithPositiveWeights() throws Exception {
        try (InputStream input = RareTraitCatalogContractTest.class.getClassLoader()
                .getResourceAsStream("config.yml")) {
            assertNotNull(input);
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Map<?, ?> root = (Map<?, ?>) new Yaml(new SafeConstructor(options)).load(input);
            Map<?, ?> traits = (Map<?, ?>) root.get("rare-traits");
            assertNotNull(traits);
            double chance = ((Number) traits.get("random-roll-chance")).doubleValue();
            assertTrue(chance > 0.0 && chance <= 0.10);
            Map<?, ?> weights = (Map<?, ?>) traits.get("weights");
            assertEquals(EquipmentTraitCatalog.implemented(), weights.keySet());
            weights.forEach((id, weight) -> assertTrue(((Number) weight).intValue() > 0, String.valueOf(id)));
        }
    }
}
