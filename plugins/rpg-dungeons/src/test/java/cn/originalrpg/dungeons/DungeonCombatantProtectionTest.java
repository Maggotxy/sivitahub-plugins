package cn.originalrpg.dungeons;

import io.papermc.paper.event.entity.SulfurCubeSwallowItemEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.SulfurCube;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.SlimeSplitEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Calls the production listeners with Paper events, without a running server. */
final class DungeonCombatantProtectionTest {
    private final DungeonCombatantProtection protection = new DungeonCombatantProtection();
    private final Player player = proxy(Player.class, (self, method, args) -> zero(self, method, args));

    @Test
    void waveAndBossCannotBeMadeInvulnerableBySwallowingBlocks() {
        for (String marker : List.of("rpgdungeons:wave_mob", "rpgdungeons:boss")) {
            SulfurCube cube = cube(marker, false);
            SulfurCubeSwallowItemEvent event = new SulfurCubeSwallowItemEvent(
                    cube, player, new EventItem(), new EventItem());
            protection.onSwallow(event);
            assertTrue(event.isCancelled(), marker);
        }
    }

    @Test
    void registeredEntityCannotBeRemovedFromTheEncounterInABucket() {
        PlayerBucketEntityEvent event = new PlayerBucketEntityEvent(player,
                cube("rpgdungeons:wave_mob", false), new EventItem(), new EventItem(), EquipmentSlot.HAND);
        protection.onBucket(event);
        assertTrue(event.isCancelled());
    }

    @Test
    void interactionsAndGroundPickupCannotChangeEncounterIdentity() {
        SulfurCube cube = cube("rpgdungeons:boss", false);
        PlayerInteractEntityEvent interaction = new PlayerInteractEntityEvent(player, cube, EquipmentSlot.OFF_HAND);
        protection.onInteract(interaction);
        assertTrue(interaction.isCancelled());

        Item groundItem = proxy(Item.class, (self, method, args) -> zero(self, method, args));
        EntityPickupItemEvent pickup = new EntityPickupItemEvent(cube, groundItem, 0);
        protection.onPickup(pickup);
        assertTrue(pickup.isCancelled());
    }

    @Test
    void deadRegisteredCubeDoesNotCreateUnregisteredOrDuplicateChildren() {
        for (String marker : List.of("rpgdungeons:wave_mob", "rpgdungeons:boss")) {
            SulfurCube dead = cube(marker, true);
            SlimeSplitEvent split = new SlimeSplitEvent(dead, 2);
            protection.onSplit(split);
            assertTrue(split.isCancelled(), marker);
        }
    }

    @Test
    void transformationsCannotReplaceTheRegisteredActor() {
        EntityTransformEvent transform = new EntityTransformEvent(cube("rpgdungeons:wave_mob", false),
                List.<Entity>of(cube(null, false)), EntityTransformEvent.TransformReason.DROWNED);
        protection.onTransform(transform);
        assertTrue(transform.isCancelled());
    }

    @Test
    void ordinarySulfurExplorationAndImprintInteractionsStayAvailable() {
        SulfurCube natural = cube(null, false);
        PlayerInteractEntityEvent interaction = new PlayerInteractEntityEvent(player, natural);
        SulfurCubeSwallowItemEvent swallow = new SulfurCubeSwallowItemEvent(
                natural, player, new EventItem(), new EventItem());
        PlayerBucketEntityEvent bucket = new PlayerBucketEntityEvent(
                player, natural, new EventItem(), new EventItem(), EquipmentSlot.HAND);
        SlimeSplitEvent split = new SlimeSplitEvent(cube(null, true), 2);
        Item item = proxy(Item.class, (self, method, args) -> zero(self, method, args));
        EntityPickupItemEvent pickup = new EntityPickupItemEvent(natural, item, 0);
        protection.onInteract(interaction);
        protection.onSwallow(swallow);
        protection.onBucket(bucket);
        protection.onSplit(split);
        protection.onPickup(pickup);
        assertFalse(interaction.isCancelled());
        assertFalse(swallow.isCancelled());
        assertFalse(bucket.isCancelled());
        assertFalse(split.isCancelled());
        assertFalse(pickup.isCancelled());
    }

    private static SulfurCube cube(String marker, boolean dead) {
        PersistentDataContainer data = proxy(PersistentDataContainer.class, (self, method, args) -> {
            if (method.getName().equals("get")) {
                return ((NamespacedKey) args[0]).toString().equals(marker) ? (byte) 1 : null;
            }
            return zero(self, method, args);
        });
        return proxy(SulfurCube.class, (self, method, args) -> switch (method.getName()) {
            case "getPersistentDataContainer" -> data;
            case "isDead" -> dead;
            case "isValid" -> !dead;
            default -> zero(self, method, args);
        });
    }

    /** Event payload only; the guard never reads or mutates the item. */
    private static final class EventItem extends ItemStack {
        private EventItem() {
            super();
        }
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static Object zero(Object self, Method method, Object[] args) {
        return switch (method.getName()) {
            case "equals" -> self == args[0];
            case "hashCode" -> System.identityHashCode(self);
            case "toString" -> "encounter-fixture";
            default -> {
                Class<?> type = method.getReturnType();
                if (type == boolean.class) yield false;
                if (type == int.class) yield 0;
                if (type == double.class) yield 0.0;
                if (type == float.class) yield 0.0f;
                if (type == long.class) yield 0L;
                yield null;
            }
        };
    }
}
