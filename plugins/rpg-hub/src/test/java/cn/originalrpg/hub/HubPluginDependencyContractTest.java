package cn.originalrpg.hub;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HubPluginDependencyContractTest {
    @Test
    void hubLoadsAfterItsOptionalPerformanceProvider() throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("plugin.yml")) {
            assertNotNull(input);
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Map<?, ?> plugin = (Map<?, ?>) new Yaml(new SafeConstructor(options)).load(input);
            Object softDepend = plugin.get("softdepend");
            assertTrue(softDepend instanceof Iterable<?>);
            Set<String> dependencies = new HashSet<>();
            ((Iterable<?>) softDepend).forEach(value -> dependencies.add(String.valueOf(value)));
            assertTrue(dependencies.contains("RpgPerformance"),
                    "RpgPerformance should load first; navigation resolves the live service on each pulse");
        }
    }
}
