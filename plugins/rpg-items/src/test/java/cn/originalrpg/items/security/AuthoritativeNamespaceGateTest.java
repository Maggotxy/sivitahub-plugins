package cn.originalrpg.items.security;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-112 行为级测试：{@code AuthoritativeItemPolicy.isAuthoritativeNamespace()}
 * 不再零消费者。判定口径是「非权威命名空间 fail-closed」——签名正确的物品，
 * 只要 PDC 里出现不属于本服权威命名空间的标记，就必须被拒。
 *
 * <p>负向对照：把 {@code EquipmentSecurity#authoritativeNamespace} 的调用注释掉后，
 * {@link #foreignNamespaceMarkerFailsClosedOnEveryAuthenticityCheck()} 会失败
 * （见任务证据的 negative control）。所以这个测试真的在测那条接线，不是空跑。</p>
 */
final class AuthoritativeNamespaceGateTest {
    /** 固定假密钥：32 字节的 'a'，只用于测试，不是任何生产密钥。 */
    private static final byte[] TEST_SIGNING_KEY =
            Base64.getDecoder().decode(Base64.getEncoder().encodeToString("a".repeat(32).getBytes()));

    private final EquipmentSecurity security = new EquipmentSecurity(TEST_SIGNING_KEY);

    @Test
    void signedEquipmentInAuthoritativeNamespaceStaysAuthentic() {
        ItemStack item = signed("rpgitems");

        assertTrue(security.isAuthentic(item),
                "权威命名空间 + 正确签名必须通过；否则所有自制装备会被命名空间门误杀");
        assertTrue(ItemStackTestHarness.keysOf(item).stream()
                        .allMatch(key -> cn.originalrpg.api.rules.AuthoritativeItemPolicy
                                .isAuthoritativeNamespace(key.substring(0, key.indexOf(':')))),
                "夹具物品的每个标记都应在权威命名空间内");
    }

    @Test
    void foreignNamespaceMarkerFailsClosedOnEveryAuthenticityCheck() {
        for (String namespace : Set.of("luckperms", "shopguiplus", "rpgthirdparty", "minecraft")) {
            ItemStack item = ItemStackTestHarness.withForeignMarker(signed("rpgitems"), namespace, "payload");

            assertFalse(security.isAuthentic(item),
                    namespace + " 不是本服权威命名空间，签名匹配也必须 fail-closed");
        }
    }

    @Test
    void counterfeitedIdentityInForeignNamespaceIsNeverAuthentic() {
        // 冒充者把身份字段整套搬到外来命名空间；本服签名算法不认识这组字段，
        // 所以拿不到有效签名。断言「伪造品永远进不来」。
        ItemStack item = ItemStackTestHarness.equipmentSkeleton("luckperms");

        assertFalse(security.isAuthentic(item), "外来命名空间的身份字段不得被当作自制装备");
    }

    /** 造一把带有效 HMAC 签名的自制装备，命名空间由参数决定。 */
    private ItemStack signed(String namespace) {
        ItemStack item = ItemStackTestHarness.equipmentSkeleton(namespace);
        security.sign(item);
        return item;
    }
}
