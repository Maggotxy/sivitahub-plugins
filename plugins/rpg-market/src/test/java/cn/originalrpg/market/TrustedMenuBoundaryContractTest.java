package cn.originalrpg.market;

import cn.originalrpg.api.ui.TrustedMenuHolder;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 容器转移豁免口径的行为合同（G-302 第二批）。
 *
 * <p>原实现读 {@code TradeRestrictionListener.java} 源码做
 * {@code contains("holder instanceof TrustedMenuHolder")}，并全仓正则扫
 * 「裸 implements InventoryHolder」。现在直接调用真实的
 * {@link TradeRestrictionListener#isTrustedInventory}，按输入 holder 断言返回值：</p>
 * <ul>
 *   <li>{@link Player} ⇒ 豁免（自己的背包）；</li>
 *   <li>{@link TrustedMenuHolder} 的实例 ⇒ 豁免；</li>
 *   <li>其它任何 holder ⇒ 不豁免 —— 特别是「类名以插件命名空间开头」的 holder
 *       必须**不**被命名空间猜测放行（那正是这条缝要掐死的绕过）。</li>
 * </ul>
 *
 * <p>「全仓不许出现裸 implements InventoryHolder」这条是**结构扫描**，语义上等价于
 * 「所有自建菜单都必须显式 opt-in」。它无法行为化（一个还没被构造出来的菜单类不会在任何
 * 运行期路径上出现），保留在 {@code scripts/check-trusted-menu-holders.sh} 静态门禁里，
 * 由 {@link #everyCustomInventoryHolderOptsIntoTheMarker()} 真实执行该脚本。</p>
 */
final class TrustedMenuBoundaryContractTest {

    @Test
    void trustIsAnExplicitMarkerNeverANamespaceGuess() throws Exception {
        Method trust = TradeRestrictionListener.class.getDeclaredMethod(
                "isTrustedInventory", InventoryHolder.class);
        trust.setAccessible(true);
        // 只读这个纯判定，不需要真实服务协作者。
        TradeRestrictionListener listener = new TradeRestrictionListener(null, null);

        assertTrue((boolean) trust.invoke(listener, playerHolder()),
                "a player's own inventory must stay exempt");
        assertTrue((boolean) trust.invoke(listener, holder(TrustedMenuHolder.class)),
                "an explicit TrustedMenuHolder must be exempt");
        assertFalse((boolean) trust.invoke(listener, holder(InventoryHolder.class)),
                "a plain InventoryHolder must not be exempt");

        // 命名空间前缀不再授予信任：插件包名下的普通 holder 必须被拒绝。
        InventoryHolder namespaced = namespacedHolder();
        assertFalse((boolean) trust.invoke(listener, namespaced),
                "namespace-prefix trust is the bypass this seam replaced; it must not return");
        assertTrue(namespaced.getClass().getName().startsWith("cn.originalrpg."),
                "the negative case must actually live in the plugin namespace");
    }

    @Test
    void everyCustomInventoryHolderOptsIntoTheMarker() throws Exception {
        // 结构扫描（连未构造的菜单类都要覆盖）→ 交给脚本门禁，测试真实执行它。
        java.nio.file.Path root = repositoryRoot();
        java.nio.file.Path gate = root.resolve("scripts/check-trusted-menu-holders.sh");
        assertTrue(java.nio.file.Files.isRegularFile(gate), "缺少菜单宿主门禁脚本: " + gate);
        Process process = new ProcessBuilder("sh", gate.toString())
                .directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS));
        org.junit.jupiter.api.Assertions.assertEquals(0, process.exitValue(),
                "菜单宿主门禁失败：所有自建 InventoryHolder 必须实现 TrustedMenuHolder\n" + output);
    }

    /** 位于插件命名空间、但没有显式 opt-in 的 holder。 */
    private static InventoryHolder namespacedHolder() {
        return new InventoryHolder() {
            @Override
            public org.bukkit.inventory.Inventory getInventory() {
                return null;
            }
        };
    }

    private static InventoryHolder holder(Class<?> type) {
        return (InventoryHolder) Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> null);
    }

    private static Player playerHolder() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> null);
    }

    private static java.nio.file.Path repositoryRoot() {
        java.nio.file.Path current = java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null
                && !java.nio.file.Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("Cannot locate repository root");
        return current;
    }
}
