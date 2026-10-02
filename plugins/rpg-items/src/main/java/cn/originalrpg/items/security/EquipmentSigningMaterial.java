package cn.originalrpg.items.security;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * G-003：装备 HMAC 签名密钥的外部注入 + 轮换。
 *
 * <p>唯一合法来源是环境变量（外部注入）。工程内不再存在
 * 「生成一个密钥并写回 config.yml」的回退路径：这样做的典型后果是
 * 每台机器、每次重建都会换一把密钥，而装备唯一性 / 解绑 / 传承 / 市场托管
 * 的安全全部押在这把密钥上。</p>
 *
 * <p>轮换：新装备始终用「当前密钥」签名；校验时依次尝试轮换集合里的每一把
 * （索引 0 为当前密钥，其后为退役密钥），因此旧签名仍然有效。</p>
 */
public final class EquipmentSigningMaterial {
    /** 环境变量名：当前密钥。 */
    public static final String CURRENT_KEY_ENV = "RPG_ITEM_SIGNING_KEY";
    /** 前缀环境变量名：退役密钥，即 RPG_ITEM_SIGNING_KEY_PREVIOUS、…_2、…_3，最多 3 把。 */
    public static final String PREVIOUS_KEY_ENV_PREFIX = CURRENT_KEY_ENV + "_PREVIOUS";
    // 装备 PDC 里已存的旧字段名；检测到即告警，见 G-003 第 3 条。
    public static final String LEGACY_CONFIG_PATH = "security.signing-key";
    public static final String LEGACY_PREVIOUS_CONFIG_PATH = "security.previous-signing-keys";
    public static final int MINIMUM_KEY_BYTES = 32;
    public static final int MAXIMUM_PREVIOUS_KEYS = 3;
    public static final String REMEDIATION = "把最新一把密钥注入 " + CURRENT_KEY_ENV
            + "，把被替换下来的密钥按新到旧依次注入 " + PREVIOUS_KEY_ENV_PREFIX + "、"
            + PREVIOUS_KEY_ENV_PREFIX + "_2、…，然后从 config.yml 删除 "
            + LEGACY_CONFIG_PATH + " / " + LEGACY_PREVIOUS_CONFIG_PATH
            + "（本插件不会自动删除你的配置）。";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] current;
    private final List<byte[]> verification;

    private EquipmentSigningMaterial(byte[] current, List<byte[]> previous) {
        this.current = current.clone();
        List<byte[]> all = new ArrayList<>(1 + previous.size());
        all.add(this.current);
        for (byte[] key : previous) all.add(key.clone());
        this.verification = List.copyOf(all);
    }

    /** @return 轮换密钥集合，索引 0 = 当前（签名用）密钥。 */
    public static EquipmentSigningMaterial fromEnvironment() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put(CURRENT_KEY_ENV, System.getenv(CURRENT_KEY_ENV));
        for (int index = 1; index <= MAXIMUM_PREVIOUS_KEYS; index++) {
            String name = index == 1 ? PREVIOUS_KEY_ENV_PREFIX : PREVIOUS_KEY_ENV_PREFIX + "_" + index;
            environment.put(name, System.getenv(name));
        }
        return from(environment);
    }

    /**
     * 纯函数入口：只读取给定环境变量视图，缺失即为空值。
     *
     * @throws IllegalStateException 当前密钥缺失或非法（fail-closed）
     */
    public static EquipmentSigningMaterial from(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        byte[] current = decode(CURRENT_KEY_ENV, environment.get(CURRENT_KEY_ENV));
        List<byte[]> previous = new ArrayList<>();
        for (int index = 1; index <= MAXIMUM_PREVIOUS_KEYS; index++) {
            String name = index == 1 ? PREVIOUS_KEY_ENV_PREFIX : PREVIOUS_KEY_ENV_PREFIX + "_" + index;
            String value = environment.get(name);
            if (value == null || value.isBlank()) continue;
            byte[] decoded = decode(name, value);
            if (MessageDigest.isEqual(current, decoded)) {
                throw new IllegalStateException(name
                        + " 与当前密钥相同；请不要把同一把密钥同时注入当前与退役槽位");
            }
            if (previous.stream().anyMatch(existing -> MessageDigest.isEqual(existing, decoded))) {
                throw new IllegalStateException(name + " 与另一把退役密钥重复");
            }
            previous.add(decoded);
        }
        return new EquipmentSigningMaterial(current, previous);
    }

    public byte[] current() {
        return current.clone();
    }

    /** @return 校验集合副本，索引 0 = 当前密钥，其后为退役密钥（新→旧）。 */
    public List<byte[]> verification() {
        List<byte[]> copy = new ArrayList<>(verification.size());
        for (byte[] key : verification) copy.add(key.clone());
        return List.copyOf(copy);
    }

    public int rotationCount() {
        return verification.size();
    }

    /**
     * G-003 第 3 条：旧 config.yml 里若残留明文密钥字段，启动时告警。
     * 只检测、只告警，绝不静默删除用户数据。
     *
     * @return 命中的旧配置路径；空表示干净
     */
    public static List<String> legacyConfigPaths(String signingKey, List<String> previousSigningKeys) {
        List<String> found = new ArrayList<>();
        if (signingKey != null && !signingKey.isBlank()) found.add(LEGACY_CONFIG_PATH);
        if (previousSigningKeys != null && previousSigningKeys.stream().anyMatch(value -> !value.isBlank())) {
            found.add(LEGACY_PREVIOUS_CONFIG_PATH);
        }
        return List.copyOf(found);
    }

    /**
     * G-003 第 1 条：fail-closed 的钩子。密钥缺失时唯一允许的动作是
     * 「拒绝启用依赖签名能力的功能 / 拒绝启动」，绝不生成并落盘。
     *
     * @return 决策；{@code disabled() == true} 时调用方不得启用签名功能
     */
    public static FailClosedDecision failClosed(String cause) {
        return new FailClosedDecision(true, "fail-closed: " + cause + "。装备签名能力已禁用；"
                + "本插件不会生成密钥，也不会把它写进 config.yml。修复步骤：" + REMEDIATION);
    }

    /** 仅供接线契约测试使用：永不落盘的随机密钥。 */
    public static String randomKeyForTesting() {
        byte[] generated = new byte[MINIMUM_KEY_BYTES];
        RANDOM.nextBytes(generated);
        return Base64.getEncoder().encodeToString(generated);
    }

    private static byte[] decode(String source, String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException(
                    "缺少环境变量 " + source + "；装备签名密钥只能由外部注入（fail-closed，不落盘也不生成）");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(source + " 必须是 Base64 编码的 HMAC 密钥", exception);
        }
        if (decoded.length < MINIMUM_KEY_BYTES) {
            throw new IllegalStateException(
                    source + " 至少需要 " + MINIMUM_KEY_BYTES + " 字节（当前 " + decoded.length + " 字节）");
        }
        return decoded;
    }

    /** fail-closed 决策结果。 */
    public record FailClosedDecision(boolean disabled, String reason) {
    }
}
