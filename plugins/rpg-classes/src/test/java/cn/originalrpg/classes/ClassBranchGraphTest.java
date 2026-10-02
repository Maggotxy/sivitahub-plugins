package cn.originalrpg.classes;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClassBranchGraphTest {
    @Test
    void shippedGraphHasCompleteSameClassAscensionChains() {
        assertDoesNotThrow(() -> ClassBranchGraph.validate(loadGraph()));
    }

    @Test
    void rejectsCrossClassAndBrokenParents() {
        Map<String, Map<String, ClassBranchGraph.Node>> graph = mutable(loadGraph());
        graph.get("mage").put("ash-shaper", new ClassBranchGraph.Node(
                "mage", 2, true, Set.of("cleric")));
        Map<String, Map<String, ClassBranchGraph.Node>> crossClass = immutable(graph);
        assertThrows(IllegalStateException.class, () -> ClassBranchGraph.validate(crossClass));

        graph = mutable(loadGraph());
        graph.get("mage").put("ash-shaper", new ClassBranchGraph.Node("mage", 2, true, Set.of()));
        Map<String, Map<String, ClassBranchGraph.Node>> broken = immutable(graph);
        assertThrows(IllegalStateException.class, () -> ClassBranchGraph.validate(broken));
    }

    private static Map<String, Map<String, ClassBranchGraph.Node>> loadGraph() {
        try (InputStream input = ClassBranchGraphTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(input);
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Map<?, ?> root = (Map<?, ?>) new Yaml(new SafeConstructor(options)).load(input);
            Map<?, ?> branches = (Map<?, ?>) root.get("branches");
            Map<String, Map<String, ClassBranchGraph.Node>> graph = new LinkedHashMap<>();
            for (Map.Entry<?, ?> ownerEntry : branches.entrySet()) {
                String owner = String.valueOf(ownerEntry.getKey());
                Map<?, ?> sourceNodes = (Map<?, ?>) ownerEntry.getValue();
                Map<String, ClassBranchGraph.Node> nodes = new LinkedHashMap<>();
                for (Map.Entry<?, ?> nodeEntry : sourceNodes.entrySet()) {
                    String id = String.valueOf(nodeEntry.getKey());
                    Map<?, ?> definition = (Map<?, ?>) nodeEntry.getValue();
                    Object stageValue = definition.get("stage");
                    int stage = stageValue instanceof Number number ? number.intValue() : 1;
                    Object enabledValue = definition.get("enabled");
                    boolean enabled = enabledValue == null ? stage == 1
                            : Boolean.parseBoolean(String.valueOf(enabledValue));
                    Object parentsValue = definition.get("requires-any");
                    List<?> parents = parentsValue instanceof List<?> values ? values : List.of();
                    nodes.put(id, new ClassBranchGraph.Node(owner, stage, enabled,
                            parents.stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet())));
                }
                graph.put(owner, Map.copyOf(nodes));
            }
            return Map.copyOf(graph);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Map<String, Map<String, ClassBranchGraph.Node>> mutable(
            Map<String, Map<String, ClassBranchGraph.Node>> source
    ) {
        Map<String, Map<String, ClassBranchGraph.Node>> copy = new HashMap<>();
        source.forEach((owner, nodes) -> copy.put(owner, new HashMap<>(nodes)));
        return copy;
    }

    private static Map<String, Map<String, ClassBranchGraph.Node>> immutable(
            Map<String, Map<String, ClassBranchGraph.Node>> source
    ) {
        Map<String, Map<String, ClassBranchGraph.Node>> copy = new HashMap<>();
        source.forEach((owner, nodes) -> copy.put(owner, Map.copyOf(nodes)));
        return Map.copyOf(copy);
    }
}
