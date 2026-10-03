package cn.originalrpg.items.security;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-003 行为级测试（替代源码字符串断言）：
 * 1) 密钥缺失 ⇒ fail-closed，绝不生成/落盘；
 * 2) 双密钥 ⇒ 旧签名的载荷仍能通过校验；
 * 3) 旧 config.yml 字段只被检测与告警，不被读取。
 *
 * <p>下面所有密钥都是**固定假密钥**，只用于测试；仓库与日志里不存在真实密钥。</p>
 */
final class EquipmentSigningMaterialTest {
    // 固定假密钥：32 字节的 'a' / 'b'，不是任何生产密钥。
    private static final String CURRENT = Base64.getEncoder()
            .encodeToString("a".repeat(32).getBytes(StandardCharsets.UTF_8));
    private static final String PREVIOUS = Base64.getEncoder()
            .encodeToString("b".repeat(32).getBytes(StandardCharsets.UTF_8));

    @Test
    void missingKeyFailsClosedInsteadOfGeneratingOne() {
        // 空环境变量视图 = 未注入密钥（也刻意不放任何 config 回退值）。
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> EquipmentSigningMaterial.fromEnvironment());

        assertTrue(failure.getMessage().contains("RPG_ITEM_SIGNING_KEY"),
                "缺失时必须指名环境变量");
        assertTrue(failure.getMessage().contains("fail-closed"), "缺失时的行为必须是 fail-closed");

        EquipmentSigningMaterial.FailClosedDecision decision =
                EquipmentSigningMaterial.failClosed(failure.getMessage());
        assertTrue(decision.disabled(), "缺失密钥必须禁用依赖签名的功能");
        assertFalse(decision.reason().isBlank());
    }

    @Test
    void blankOrNonBase64OrShortKeyIsRejected() {
        for (Map<String, String> environment : List.of(
                Map.of(EquipmentSigningMaterial.CURRENT_KEY_ENV, "   "),
                Map.of(EquipmentSigningMaterial.CURRENT_KEY_ENV, "not-base64!!"),
                Map.of(EquipmentSigningMaterial.CURRENT_KEY_ENV,
                        Base64.getEncoder().encodeToString(new byte[16])))) {
            assertThrows(IllegalStateException.class, () -> EquipmentSigningMaterial.from(environment));
        }
    }

    @Test
    void missingKeyNeverWritesAKeyBackToAnySink() throws Exception {
        // G-302：原实现读 RpgItemsPlugin 源码做三条 contains 否定断言。这三条只能对源码
        // 结构做静态保证（「密钥生成路径已被删除 / 不得写回 config」），逐条迁进声明式
        // 源码契约规格，由本方法真实执行门禁；失败时不再需要读源码做字符串扫描。
        Path root = findRepositoryRoot();
        Path gate = root.resolve("scripts/check-source-contract.sh");
        assertTrue(java.nio.file.Files.isRegularFile(gate), "缺少契约门禁脚本: " + gate);
        Process process = new ProcessBuilder("sh", gate.toString(),
                "plugins/rpg-items/src/test/java/cn/originalrpg/items/security/"
                        + "EquipmentSigningMaterialTest.java#no-key-sink",
                "missingKeyNeverWritesAKeyBackToAnySink")
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS), "契约门禁未在 120 秒内结束");
        assertEquals(0, process.exitValue(),
                "插件不得携带密钥生成/写回路径（fail-closed 只读环境变量）：\n" + output);

        assertThrows(IllegalStateException.class, EquipmentSigningMaterial::fromEnvironment);
    }

    @Test
    void rotationSetAcceptsPreviousGenerationSignatures() {
        EquipmentSigningMaterial material = EquipmentSigningMaterial.from(Map.of(
                EquipmentSigningMaterial.CURRENT_KEY_ENV, CURRENT,
                EquipmentSigningMaterial.PREVIOUS_KEY_ENV_PREFIX, PREVIOUS));

        assertEquals(2, material.rotationCount());
        assertArrayEquals(decode(CURRENT), material.current(), "当前密钥必须是新签用密钥");

        byte[] signedByPrevious = mac(decode(PREVIOUS), "schema=5\nitem_id=legacy-item");
        assertTrue(verifiesAny(material, "schema=5\nitem_id=legacy-item", signedByPrevious),
                "双密钥时上一代签名必须仍能校验通过");

        byte[] signedByCurrent = mac(material.current(), "schema=5\nitem_id=new-item");
        assertTrue(verifiesAny(material, "schema=5\nitem_id=new-item", signedByCurrent));

        // 未注册的第三方密钥不得通过。
        assertFalse(verifiesAny(material, "schema=5\nitem_id=forged",
                mac(decode(Base64.getEncoder().encodeToString("c".repeat(32)
                        .getBytes(StandardCharsets.UTF_8))), "schema=5\nitem_id=forged")));
    }

    @Test
    void duplicateOrUnknownRotationSlotsAreRejected() {
        assertThrows(IllegalStateException.class, () -> EquipmentSigningMaterial.from(Map.of(
                EquipmentSigningMaterial.CURRENT_KEY_ENV, CURRENT,
                EquipmentSigningMaterial.PREVIOUS_KEY_ENV_PREFIX, CURRENT)));
        // 超出上限的槽位按未注入处理（不静默放大校验集合）。
        EquipmentSigningMaterial material = EquipmentSigningMaterial.from(Map.of(
                EquipmentSigningMaterial.CURRENT_KEY_ENV, CURRENT,
                EquipmentSigningMaterial.PREVIOUS_KEY_ENV_PREFIX + "_9", PREVIOUS));
        assertEquals(1, material.rotationCount());
    }

    @Test
    void legacyPlaintextConfigIsDetectedButOnlyReported() {
        assertTrue(EquipmentSigningMaterial.legacyConfigPaths("", List.of()).isEmpty());
        assertEquals(List.of(EquipmentSigningMaterial.LEGACY_CONFIG_PATH),
                EquipmentSigningMaterial.legacyConfigPaths("c29tZS1sZWdhY3kta2V5", List.of()));
        assertEquals(List.of(EquipmentSigningMaterial.LEGACY_CONFIG_PATH,
                        EquipmentSigningMaterial.LEGACY_PREVIOUS_CONFIG_PATH),
                EquipmentSigningMaterial.legacyConfigPaths("c29tZS1sZWdhY3kta2V5", List.of("b2xkLWtleQ")));
        // 告警文案必须给出人工迁移步骤，且不包含删除指令。
        assertTrue(EquipmentSigningMaterial.REMEDIATION.contains(EquipmentSigningMaterial.CURRENT_KEY_ENV));
        assertFalse(EquipmentSigningMaterial.REMEDIATION.toLowerCase().contains("delete the file"));
    }

    private static boolean verifiesAny(EquipmentSigningMaterial material, String payload, byte[] expected) {
        for (byte[] candidate : material.verification()) {
            if (MessageDigest.isEqual(mac(candidate, payload), expected)) return true;
        }
        return false;
    }

    private static byte[] mac(byte[] key, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static java.nio.file.Path findRepositoryRoot() {
        java.nio.file.Path current = java.nio.file.Path.of("").toAbsolutePath();
        while (current != null && !java.nio.file.Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("Cannot locate repository root");
        return current;
    }

    private static byte[] decode(String encoded) {
        return Base64.getDecoder().decode(encoded);
    }
}
