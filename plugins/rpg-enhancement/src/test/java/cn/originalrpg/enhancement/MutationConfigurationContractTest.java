package cn.originalrpg.enhancement;

import cn.originalrpg.api.model.SkillVariantCatalog;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutationConfigurationContractTest {
    @Test
    void productionPricesAndVariantsStayAlignedWithPlayerFacingContracts() {
        Map<?, ?> root = loadConfig();
        Map<?, ?> refinement = (Map<?, ?>) root.get("refinement");
        Map<?, ?> limitBreak = (Map<?, ?>) root.get("limit-break");
        assertEquals(new BigDecimal("400.00"), decimal(refinement.get("price")));
        assertEquals(new BigDecimal("1200.00"), decimal(limitBreak.get("base-price")));

        Map<?, ?> mutations = (Map<?, ?>) root.get("mutations");
        assertNotNull(mutations);
        assertEquals(SkillVariantCatalog.implemented().keySet(), mutations.keySet());

        for (var entry : SkillVariantCatalog.implemented().entrySet()) {
            Map<?, ?> definition = (Map<?, ?>) mutations.get(entry.getKey());
            assertNotNull(definition, entry.getKey());
            Iterable<?> rawVariants = assertInstanceOf(Iterable.class, definition.get("variants"), entry.getKey());
            HashSet<String> configuredVariants = new HashSet<>();
            rawVariants.forEach(value -> configuredVariants.add(String.valueOf(value)));
            assertEquals(entry.getValue(), configuredVariants, entry.getKey());
            assertEquals(new BigDecimal("3500.00"), decimal(definition.get("price")), entry.getKey());
            assertEquals(new BigDecimal("900.00"), decimal(definition.get("restore-price")), entry.getKey());
            double rate = ((Number) definition.get("success-rate")).doubleValue();
            assertTrue(rate > 0.0 && rate <= 1.0, entry.getKey());
        }
    }

    private static BigDecimal decimal(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

    private static Map<?, ?> loadConfig() {
        try (InputStream input = MutationConfigurationContractTest.class.getClassLoader()
                .getResourceAsStream("config.yml")) {
            assertNotNull(input);
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            return (Map<?, ?>) new Yaml(new SafeConstructor(options)).load(input);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
