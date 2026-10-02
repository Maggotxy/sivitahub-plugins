package cn.originalrpg.api.event;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class AuthenticatedPlayerRevokedEventTest {
    @Test
    void exposesTheExactRevokedPlayerAndStaticHandlerList() {
        Player player = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, arguments) -> defaultValue(method.getReturnType()));

        AuthenticatedPlayerRevokedEvent event = new AuthenticatedPlayerRevokedEvent(player);

        assertSame(player, event.player());
        assertSame(AuthenticatedPlayerRevokedEvent.getHandlerList(), event.getHandlers());
    }

    @Test
    void refusesAnAmbiguousRevocationSignal() {
        assertThrows(NullPointerException.class, () -> new AuthenticatedPlayerRevokedEvent(null));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0F;
        if (type == double.class) return 0.0D;
        throw new IllegalArgumentException("Unsupported primitive type: " + type);
    }
}
