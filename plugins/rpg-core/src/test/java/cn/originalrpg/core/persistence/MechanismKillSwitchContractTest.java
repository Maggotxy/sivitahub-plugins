package cn.originalrpg.core.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-005 门禁：注册表里登记了机制却没有任何调用点，视为 Kill Switch 是死开关。
 *
 * <p>本测试用反射读取 {@link MechanismRegistry} 的机制常量，逐个在
 * {@code plugins/rpg-core/src/main} 内查找真实消费者。新增机制常量而不接
 * 消费者会让本测试失败。</p>
 */
final class MechanismKillSwitchContractTest {

    @Test
    void everyRegisteredMechanismHasARealConsumerInMainSources() throws Exception {
        Map<String, String> mechanisms = registeredMechanismValues();
        assertFalse(mechanisms.isEmpty(), "Mechanism registry must declare at least one mechanism");

        List<String> mainSources = readMainSources();
        Map<String, String> dead = new TreeMap<>();
        for (Map.Entry<String, String> entry : mechanisms.entrySet()) {
            String fieldName = entry.getKey();
            boolean wired = mainSources.stream().anyMatch(source ->
                    source.contains("requireEnabled(MechanismRegistry." + fieldName + ")"));
            if (!wired) dead.put(fieldName + "=" + entry.getValue(), "no requireEnabled(...) call site");
        }

        assertTrue(dead.isEmpty(),
                "C-005 违规：以下机制已注册但零消费者（Kill Switch 无法关停任何路径）: " + dead);
    }

    @Test
    void everyMechanismIsMappedToADeclaredFaucetEntryPoint() throws Exception {
        Map<String, String> mechanisms = registeredMechanismValues();
        for (Map.Entry<String, String> entry : mechanisms.entrySet()) {
            assertTrue(MechanismRegistry.FAUCET_ENTRY_POINTS.containsKey(entry.getValue()),
                    "机制缺少 FAUCET_ENTRY_POINTS 映射: " + entry.getValue());
        }
        assertEquals(mechanisms.size(), MechanismRegistry.FAUCET_ENTRY_POINTS.size(),
                "FAUCET_ENTRY_POINTS 与机制常量数量不一致");
    }

    @Test
    void emptyRegistryFailsClosedAndStillReportsMetrics() throws Exception {
        // 空缓存等价于「从未播种/未登记」：C-005 要求 fail closed。
        MechanismRegistry registry = new MechanismRegistry(null);
        assertFalse(registry.requireEnabled(MechanismRegistry.HUNT_GOLD));
        MechanismRegistry.MechanismMetric metric = registry.metrics().get(MechanismRegistry.HUNT_GOLD);
        assertNotNull(metric, "指标必须覆盖每个已注册机制");
        assertFalse(metric.enabled());
        assertEquals(1L, metric.denied());
        assertEquals(0L, metric.allowed());
        assertEquals(registeredMechanismValues().values().stream().distinct().count(),
                registry.mechanismIds().size(), "指标/清单必须覆盖全部机制常量");
    }

    /** 反射读取全部 public static final String 机制常量名 -> 值，值形如 {@code domain.scope.flag}。 */
    private static Map<String, String> registeredMechanismValues() throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        for (Field field : MechanismRegistry.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !Modifier.isFinal(field.getModifiers())
                    || field.getType() != String.class) continue;
            String value = (String) field.get(null);
            if (value != null && value.matches("[a-z0-9_.]+")) {
                result.put(field.getName(), value);
            }
        }
        return result;
    }

    private static List<String> readMainSources() throws IOException {
        Path root = findRepositoryRoot().resolve("plugins/rpg-core/src/main/java");
        assertTrue(Files.isDirectory(root), "Missing rpg-core main sources: " + root);
        List<String> sources = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                sources.add(Files.readString(file));
            }
        }
        return sources;
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        assertNotNull(current, "Cannot locate repository root from user.dir");
        return current;
    }
}
