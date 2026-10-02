package cn.originalrpg.items.security;

import cn.originalrpg.api.rules.AuthoritativeItemPolicy;
import cn.originalrpg.items.forge.ForgeListener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-112 第二处消费者：锻造核心材料门的命名空间判定
 * （{@code ForgeListener#authoritativeNamespaceOnly}，由 {@code isCoreMaterial} 调用）。
 *
 * <p>判据是 allMatch + fail-closed：材料的每个 PDC 标记都必须落在权威命名空间内。
 * 测试反射调用**生产方法本体**（不是复制一份判据），因此把生产代码里那行调用注释掉时
 * 本测试会失败 —— 见任务证据的 negative control。</p>
 *
 * <p>为什么不直接用 {@code AuthoritativeItemPolicy.isAuthoritative(item)}：
 * 它是 anyMatch，只要有一个权威标记就通过。用它会放行「第三方标记 + 一个权威标记」
 * 的伪造核心材料 —— 正是本任务要堵的洞，所以锻造门必须用 allMatch。</p>
 */
final class ForgeInputNamespaceGateTest {
    private static final String OWNER = "3f1d2c4b-5a69-4e78-9b0c-1d2e3f4a5b6c";

    @Test
    void authoritativeCoreMaterialPassesTheForgeInputGate() throws Exception {
        ItemStack core = ItemStackTestHarness.coreMaterial(OWNER);

        assertTrue(namespaceGateAllows(core), "权威核心材料必须通过锻造输入门，否则锻造整体不可用");
        assertTrue(core.getItemMeta().getPersistentDataContainer()
                        .has(new org.bukkit.NamespacedKey("rpgmobs", "adaptive_material"),
                                PersistentDataType.BYTE),
                "夹具确实写入了核心材料标记");
    }

    @Test
    void coreMaterialCarryingAForeignMarkerIsRejected() throws Exception {
        for (String namespace : new String[]{"luckperms", "shopguiplus", "rpgthirdparty"}) {
            ItemStack core = ItemStackTestHarness.withForeignMarker(
                    ItemStackTestHarness.coreMaterial(OWNER), namespace, "payload");

            assertFalse(namespaceGateAllows(core),
                    namespace + " 的标记让核心材料不再属于本服权威状态，必须 fail-closed");
            // 反向说明：anyMatch 版本会放行这种伪造品，所以这里必须用 allMatch。
            assertTrue(AuthoritativeItemPolicy.isAuthoritative(core),
                    "anyMatch 语义确实会放行该物品；锻造门因此不能复用它");
        }
    }

    /** 反射调用生产方法本体：删掉 ForgeListener 里的命名空间门会让本测试失败。 */
    private static boolean namespaceGateAllows(ItemStack item) throws Exception {
        Method method = ForgeListener.class.getDeclaredMethod("authoritativeNamespaceOnly", ItemStack.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, item);
    }
}
