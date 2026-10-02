package cn.originalrpg.market;

import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接线级用例：反射读取 {@link MarketCommand} 的事件处理器，断言创造模式容器事件
 * 与游标路径确实在注册范围内。编译期依赖 paper-api，但不需要 Bukkit 运行环境。
 */
class DeliveryFreezeWiringTest {

    private static List<Method> handlersFor(Class<?> eventType) {
        return Arrays.stream(MarketCommand.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(EventHandler.class))
                .filter(method -> Arrays.asList(method.getParameterTypes()).contains(eventType))
                .toList();
    }

    @Test
    void creativeContainerEventIsRegisteredAsAFreezeHandler() {
        List<Method> handlers = handlersFor(InventoryCreativeEvent.class);
        assertEquals(1, handlers.size(),
                "创造模式容器事件必须有且仅有一个交付冻结处理器");
        assertEquals("onCreativeClick", handlers.get(0).getName());
    }

    @Test
    void cursorProducingInventoryEventsAreAllRegistered() {
        // 任一事件都能把带凭据的物品落到游标上，因此必须全部接线。
        for (Class<?> event : List.of(
                org.bukkit.event.inventory.InventoryClickEvent.class,
                org.bukkit.event.inventory.InventoryDragEvent.class,
                InventoryCreativeEvent.class)) {
            assertTrue(handlersFor(event).size() == 1,
                    event.getSimpleName() + " 必须正好接线一个冻结处理器");
        }
    }

    @Test
    void freezeHandlersArePublicLikeEveryOtherBukkitListener() {
        for (Method handler : handlersFor(InventoryCreativeEvent.class)) {
            assertTrue(java.lang.reflect.Modifier.isPublic(handler.getModifiers()),
                    "Bukkit 只调用 public 事件处理器");
        }
    }
}
