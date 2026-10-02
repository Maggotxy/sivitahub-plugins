package cn.originalrpg.classes;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Fail-fast validation for the complete, same-class ascension graph. */
final class ClassBranchGraph {
    static final Set<String> PLAYABLE_CLASSES = Set.of(
            "healer", "warrior", "assassin", "mage", "ranger", "freeform");

    private ClassBranchGraph() {
    }

    static void validate(Map<String, Map<String, Node>> graph) {
        Objects.requireNonNull(graph, "graph");
        if (!graph.keySet().equals(PLAYABLE_CLASSES)) {
            throw new IllegalStateException("Class branch owners must be exactly " + PLAYABLE_CLASSES);
        }
        Map<String, Node> global = new HashMap<>();
        for (Map.Entry<String, Map<String, Node>> ownerEntry : graph.entrySet()) {
            String owner = ownerEntry.getKey();
            Map<String, Node> nodes = ownerEntry.getValue();
            long stageOne = nodes.values().stream().filter(node -> node.enabled() && node.stage() == 1).count();
            long stageTwo = nodes.values().stream().filter(node -> node.enabled() && node.stage() == 2).count();
            long stageThree = nodes.values().stream().filter(node -> node.enabled() && node.stage() == 3).count();
            if (stageOne != 2 || stageTwo != 2 || stageThree != 1) {
                throw new IllegalStateException(owner + " must expose exactly 2/2/1 enabled branches across stages 1/2/3");
            }
            for (Map.Entry<String, Node> nodeEntry : nodes.entrySet()) {
                String id = nodeEntry.getKey();
                Node node = Objects.requireNonNull(nodeEntry.getValue(), "branch node " + id);
                if (!owner.equals(node.owner()) || node.stage() < 1 || node.stage() > 3) {
                    throw new IllegalStateException("Invalid owner or stage for branch " + id);
                }
                if (global.putIfAbsent(id, node) != null) {
                    throw new IllegalStateException("Branch IDs must be globally unique: " + id);
                }
            }
        }
        for (Map.Entry<String, Node> entry : global.entrySet()) {
            String id = entry.getKey();
            Node node = entry.getValue();
            if (!node.enabled()) continue;
            if (node.stage() == 1 && !node.parents().isEmpty()) {
                throw new IllegalStateException("Ascension 1 branch cannot declare a parent: " + id);
            }
            if (node.stage() > 1 && node.parents().isEmpty()) {
                throw new IllegalStateException("Higher ascension branch must declare a parent: " + id);
            }
            for (String parentId : node.parents()) {
                Node parent = global.get(parentId);
                if (parent == null || !parent.enabled()) {
                    throw new IllegalStateException("Branch " + id + " references missing or disabled parent " + parentId);
                }
                if (!node.owner().equals(parent.owner()) || parent.stage() != node.stage() - 1) {
                    throw new IllegalStateException("Branch " + id + " has a cross-class or non-adjacent parent " + parentId);
                }
            }
        }
    }

    record Node(String owner, int stage, boolean enabled, Set<String> parents) {
        Node {
            Objects.requireNonNull(owner, "owner");
            parents = Set.copyOf(Objects.requireNonNull(parents, "parents"));
        }
    }
}
