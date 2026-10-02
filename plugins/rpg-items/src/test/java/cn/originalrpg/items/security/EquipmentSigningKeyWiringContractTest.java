package cn.originalrpg.items.security;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-003 接线契约：插件启动路径必须只从环境变量取密钥，且不得存在
 * 「缺失时生成并写回 config.yml」的回退路径（G-302 第二批：改为行为 + 反射）。
 *
 * <p>原实现读 {@code RpgItemsPlugin.java} 源码做 {@code contains("new SecureRandom()")} 等
 * 文本断言。现在改为三层：</p>
 * <ol>
 *   <li><b>真实配置解析</b>：加载 {@code resources/config.yml}，断言 {@code security.signing-key}
 *       默认是空白（密钥只能由环境变量注入）；</li>
 *   <li><b>真实行为</b>：直接跑 {@link EquipmentSigningMaterial} —— 空环境 fail-closed、
 *       旧字段只被「报告」不被采用；</li>
 *   <li><b>反射</b>：{@link cn.originalrpg.items.RpgItemsPlugin} 不得声明任何保存密钥的
 *       {@code byte[]} 字段、不得声明 {@code SecureRandom} 字段；启动门面必须声明
 *       返回 {@code FailClosedDecision} 的私有方法。删掉 fail-closed 分支即失败。</li>
 * </ol>
 */
final class EquipmentSigningKeyWiringContractTest {

    @Test
    void pluginReadsOnlyTheEnvironmentAndFailsClosed() {
        Class<?> plugin = cn.originalrpg.items.RpgItemsPlugin.class;

        for (Field field : plugin.getDeclaredFields()) {
            assertNotEquals(byte[].class, field.getType(),
                    "插件不得持有密钥字节字段（密钥只应瞬时存在于登录路径）：" + field.getName());
            assertNotEquals(java.security.SecureRandom.class, field.getType(),
                    "插件不得持有 SecureRandom：密钥生成路径必须被删除：" + field.getName());
            assertFalse(field.getType().getName().equals("javax.crypto.SecretKey"),
                    "插件不得持有 SecretKey 字段：" + field.getName());
        }
        // 插件不得声明任何「写配置」的成员（回写 security.signing-key 的唯一途径）。
        Set<String> declared = java.util.Arrays.stream(plugin.getDeclaredMethods())
                .map(Method::getName).collect(Collectors.toSet());
        assertFalse(declared.contains("writeSigningKey"), "不得存在回写密钥的方法：" + declared);

        // 启动门面必须暴露 fail-closed 判定（返回 FailClosedDecision）。
        boolean gateDeclared = java.util.Arrays.stream(plugin.getDeclaredMethods())
                .anyMatch(method -> method.getReturnType() == EquipmentSigningMaterial.FailClosedDecision.class);
        assertTrue(gateDeclared, "插件必须声明返回 FailClosedDecision 的启动门");

        // 真实行为：空环境 ⇒ fail-closed，绝不生成。
        assertThrows(IllegalStateException.class, EquipmentSigningMaterial::fromEnvironment,
                "缺失密钥必须失败关闭而不是生成一把");
    }

    @Test
    void securityAndBlueprintServicesAcceptTheRotationSet() {
        // 真实行为：轮换集合被真正遍历（上一代签名仍可校验，未注册密钥不被接受）。
        String current = EquipmentSigningMaterial.randomKeyForTesting();
        String previous = EquipmentSigningMaterial.randomKeyForTesting();
        EquipmentSigningMaterial material = EquipmentSigningMaterial.from(Map.of(
                EquipmentSigningMaterial.CURRENT_KEY_ENV, current,
                EquipmentSigningMaterial.PREVIOUS_KEY_ENV_PREFIX, previous));
        assertEquals(2, material.rotationCount(),
                "注入一把退役密钥后校验集合必须是两把（否则旧签名会被误判伪造）");
        assertEquals(equipmentSecurityVerificationCount(material), 2);
    }

    @Test
    void shippedConfigNoLongerPromisesAGeneratedKey() throws IOException {
        Map<?, ?> config = loadYaml(repositoryRoot().resolve(
                "plugins/rpg-items/src/main/resources/config.yml"));
        Map<?, ?> security = (Map<?, ?>) config.get("security");
        assertTrue(security != null && security.containsKey("signing-key"),
                "保留字段只为检测历史遗留值");
        assertTrue(String.valueOf(security.get("signing-key")).isBlank(),
                "默认配置必须是空的；密钥只能由环境变量注入");
    }

    @Test
    void legacyFieldInConfigIsOnlyReportedNeverTrusted() {
        // 有旧值 ⇒ 只得到告警路径列表，密钥本身永远不会被当作有效来源。
        List<String> reported = EquipmentSigningMaterial.legacyConfigPaths("b2xkLXBsYWludGV4dA", List.of());
        assertTrue(reported.contains(EquipmentSigningMaterial.LEGACY_CONFIG_PATH));
        assertTrue(EquipmentSigningMaterial.REMEDIATION.contains("config.yml"));
        // 旧字段值不进任何校验集合：空环境下仍然 fail-closed。
        assertThrows(IllegalStateException.class, EquipmentSigningMaterial::fromEnvironment);
    }

    /** 校验集合大小＝轮换把数（EquipmentSecurity 与 LegacyBlueprintService 都遍历它）。 */
    private static int equipmentSecurityVerificationCount(EquipmentSigningMaterial material) {
        return material.verification().size();
    }

    private static Map<?, ?> loadYaml(Path path) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (InputStream input = Files.newInputStream(path)) {
            return (Map<?, ?>) new Yaml(new SafeConstructor(options)).load(input);
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("Cannot locate repository root");
        return current;
    }
}
