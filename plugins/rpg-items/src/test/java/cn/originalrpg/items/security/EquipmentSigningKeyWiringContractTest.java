package cn.originalrpg.items.security;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-003 接线契约：插件启动路径必须只从环境变量取密钥，且不得存在
 * 「缺失时生成并写回 config.yml」的回退路径。
 */
final class EquipmentSigningKeyWiringContractTest {
    @Test
    void pluginReadsOnlyTheEnvironmentAndFailsClosed() throws IOException {
        String source = read("plugins/rpg-items/src/main/java/cn/originalrpg/items/RpgItemsPlugin.java");

        assertTrue(source.contains("EquipmentSigningMaterial.fromEnvironment()"),
                "密钥必须来自外部注入的环境变量");
        assertTrue(source.contains("signingGate.disabled()"),
                "缺失密钥时必须走 fail-closed 分支并 return");
        assertTrue(source.contains("warnAboutLegacyPlaintextKey()"),
                "启动时必须检测并告警旧的明文密钥字段");

        // 明确禁止的回退实现。
        assertFalse(source.contains("new SecureRandom()"), "不得在插件内生成密钥");
        assertFalse(source.contains("Base64.getEncoder().encodeToString"), "不得自行编码新密钥");
        assertFalse(Pattern.compile("getConfig\\(\\)\\.set\\(").matcher(source).find(),
                "插件不得回写任何配置（尤其是 security.signing-key）");
        assertFalse(source.contains("RPG_ITEM_SIGNING_KEY\""),
                "插件本身不得直接读环境变量，必须经由 EquipmentSigningMaterial");
    }

    @Test
    void securityAndBlueprintServicesAcceptTheRotationSet() throws IOException {
        String security = read("plugins/rpg-items/src/main/java/cn/originalrpg/items/security/"
                + "EquipmentSecurity.java");
        assertTrue(security.contains("matchesAnyKey"), "校验必须遍历轮换集合");
        assertTrue(security.contains("List<byte[]> retiredSecrets"), "必须注入退役密钥集合");

        String blueprints = read("plugins/rpg-items/src/main/java/cn/originalrpg/items/forge/"
                + "LegacyBlueprintService.java");
        assertTrue(blueprints.contains("matchesAnyKey"), "传承蓝图校验也必须遍历轮换集合");
        assertTrue(blueprints.contains("List<byte[]> retiredSigningKeys"), "必须注入退役密钥集合");
    }

    @Test
    void shippedConfigNoLongerPromisesAGeneratedKey() throws IOException {
        Map<?, ?> config = loadYaml(findRepositoryRoot()
                .resolve("plugins/rpg-items/src/main/resources/config.yml"));
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
    }

    private static String read(String relative) throws IOException {
        return Files.readString(findRepositoryRoot().resolve(relative), StandardCharsets.UTF_8);
    }

    private static Map<?, ?> loadYaml(Path path) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (InputStream input = Files.newInputStream(path)) {
            return (Map<?, ?>) new Yaml(new SafeConstructor(options)).load(input);
        }
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("Cannot locate repository root");
        return current;
    }
}
