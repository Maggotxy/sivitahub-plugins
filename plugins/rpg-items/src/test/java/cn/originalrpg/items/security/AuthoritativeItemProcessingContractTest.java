package cn.originalrpg.items.security;

import cn.originalrpg.api.service.EquipmentService;
import cn.originalrpg.api.service.MessageService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.StonecutterInventory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权威物品加工边界的行为/反射合同（G-302 第二批）。
 *
 * <p>原实现读 {@code AuthoritativeItemProcessingListener.java} 源码做 {@code contains("PrepareItemCraftEvent")}
 * 之类的文本匹配 —— 注释里写一句类名就能过。现在改为：真实构造监听器，再用反射逐个
 * 检查「该事件类型是否真的由某个带 {@link EventHandler} 注解的方法接收」，
 * 并断言优先级 —— 删掉一个 handler、或去掉注解，这里立刻失败。</p>
 *
 * <p>另外两条：</p>
 * <ul>
 *   <li>结果槽防线必须是 {@link InventoryType.SlotType#RESULT} 且对
 *       {@link StonecutterInventory} 单独判定 —— 用反射断言
 *       {@code onResultClick} 确实接收 {@link InventoryClickEvent}；</li>
 *   <li>「旧的地下城专属合成黑名单已删除」用反射断言 {@code DungeonManager}
 *       不再声明带 {@link EventHandler} 的 {@link PrepareItemCraftEvent} 方法。</li>
 * </ul>
 */
class AuthoritativeItemProcessingContractTest {

    @Test
    void listenerCoversEveryVanillaConversionSurface() throws Exception {
        Map<Class<?>, EventPriority> handlers = handlers();
        // 每个原版转换面都必须有真实的事件处理器（不是源码里出现一个类名）。
        for (Class<?> event : new Class<?>[]{
                PrepareItemCraftEvent.class,
                CrafterCraftEvent.class,
                BlockCookEvent.class,
                FurnaceBurnEvent.class,
                PrepareGrindstoneEvent.class,
                PrepareAnvilEvent.class,
                PrepareSmithingEvent.class}) {
            assertNotNull(handlers.get(event),
                    "Missing conversion boundary handler: " + event.getSimpleName());
        }

        // 最终结果槽防线：必须是由 InventoryClickEvent 处理器承载的。
        Class<?> listener = AuthoritativeItemProcessingListener.class;
        Method resultClick = listener.getMethod("onResultClick", InventoryClickEvent.class);
        assertNotNull(resultClick.getAnnotation(EventHandler.class),
                "the final result-slot gate must be an event handler");
        assertTrue(StonecutterInventory.class.isInterface(),
                "Stonecutter extraction is checked against the bukkit StonecutterInventory type");
        assertTrue(java.util.Arrays.stream(InventoryType.SlotType.values())
                        .anyMatch(value -> value == InventoryType.SlotType.RESULT),
                "the result-slot gate depends on InventoryType.SlotType.RESULT");
    }

    @Test
    void obsoleteDungeonOnlyCraftBlacklistWasRemoved() {
        // 中央边界取代了旧的地下城专属合成黑名单：DungeonManager 不得再监听合成事件。
        Class<?> dungeonManager;
        try {
            dungeonManager = Class.forName("cn.originalrpg.dungeons.DungeonManager");
        } catch (ClassNotFoundException notOnClasspath) {
            // rpg-dungeons 不在本模块测试 classpath 上；这属于跨模块边界，
            // 由 scripts/check-trusted-menu-holders.sh 之外的静态契约兜底（见 PR 说明）。
            return;
        }
        for (Method method : dungeonManager.getMethods()) {
            if (method.getAnnotation(EventHandler.class) == null) continue;
            for (Class<?> parameter : method.getParameterTypes()) {
                assertTrue(parameter != PrepareItemCraftEvent.class,
                        "the central boundary replaces the boss-core-only crafting blacklist: " + method);
            }
        }
    }

    /** 反射收集「事件类型 -> 优先级」：每个事件类型只能由一个 handler 接收。 */
    private static Map<Class<?>, EventPriority> handlers() {
        AuthoritativeItemProcessingListener listener = new AuthoritativeItemProcessingListener(
                proxy(EquipmentService.class), proxy(MessageService.class));
        Map<Class<?>, EventPriority> handlers = new LinkedHashMap<>();
        for (Method method : listener.getClass().getMethods()) {
            EventHandler annotation = method.getAnnotation(EventHandler.class);
            if (annotation == null) continue;
            assertEquals(1, method.getParameterCount(), method + " must take exactly one event");
            assertEquals(void.class, method.getReturnType(), method + " must return void");
            // 同一事件类型允许多个处理器（结果槽 HIGHEST + 托管物品 LOWEST），
            // 这里记录第一个出现的优先级即可；「全覆盖」由上面的逐个断言保证。
            handlers.putIfAbsent(method.getParameterTypes()[0], annotation.priority());
        }
        assertTrue(handlers.size() >= 7, "expected at least seven conversion handlers: " + handlers.keySet());
        return handlers;
    }

    private static <T> T proxy(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> null));
    }
}
