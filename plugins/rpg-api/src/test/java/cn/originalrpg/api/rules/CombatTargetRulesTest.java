package cn.originalrpg.api.rules;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.entity.SulfurCube;
import org.bukkit.entity.Wolf;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class CombatTargetRulesTest {
    @Test
    void nativeEnemiesDoNotHaveToImplementMonster() {
        for (Class<? extends LivingEntity> type : List.of(Zombie.class, Slime.class, Ghast.class, Phantom.class)) {
            assertTrue(CombatTargetRules.isPveTarget(entity(type, Map.of(), true, false)), type.getSimpleName());
        }
    }

    @Test
    void bothRegisteredSulfurEncounterRolesAreTargetable() {
        for (String marker : List.of("rpgdungeons:boss", "rpgdungeons:wave_mob")) {
            SulfurCube cube = entity(SulfurCube.class, Map.of(marker, (byte) 1), true, false);
            assertTrue(CombatTargetRules.isDungeonCombatant(cube));
            assertTrue(CombatTargetRules.isPveTarget(cube));
        }
    }

    @Test
    void naturalSulfurCubesAndFriendlyMobsRemainPassive() {
        for (Class<? extends LivingEntity> type : List.of(SulfurCube.class, Cow.class, Wolf.class)) {
            LivingEntity entity = entity(type, Map.of(), true, false);
            assertFalse(CombatTargetRules.isPveTarget(entity), type.getSimpleName());
            assertFalse(CombatTargetRules.isDungeonCombatant(entity), type.getSimpleName());
        }
    }

    @Test
    void pvpCannotBeEnabledByPveMarkers() {
        Player player = entity(Player.class, Map.of("rpgdungeons:boss", (byte) 1), true, false);
        assertFalse(CombatTargetRules.isPveTarget(player));
        assertFalse(CombatTargetRules.isDungeonCombatant(player));
    }

    @Test
    void deadAndRemovedMobsAreNotTargetsButTheirEncounterIdentityRemains() {
        SulfurCube dead = entity(SulfurCube.class, Map.of("rpgdungeons:boss", (byte) 1), true, true);
        SulfurCube removed = entity(SulfurCube.class, Map.of("rpgdungeons:wave_mob", (byte) 1), false, false);
        assertFalse(CombatTargetRules.isPveTarget(dead));
        assertFalse(CombatTargetRules.isPveTarget(removed));
        assertTrue(CombatTargetRules.isDungeonCombatant(dead));
        assertTrue(CombatTargetRules.isDungeonCombatant(removed));
        assertFalse(CombatTargetRules.isPveTarget(entity(Zombie.class, Map.of(), true, true)));
        assertFalse(CombatTargetRules.isPveTarget(null));
        assertFalse(CombatTargetRules.isDungeonCombatant(null));
    }

    @Test
    void markerPresenceAloneDoesNotPromotePassiveMobs() {
        assertFalse(CombatTargetRules.isPveTarget(entity(SulfurCube.class,
                Map.of("rpgdungeons:boss", (byte) 0), true, false)));
        assertFalse(CombatTargetRules.isPveTarget(entity(SulfurCube.class,
                Map.of("other:boss", (byte) 1), true, false)));
    }

    @Test
    void actualNearbyQueryRetainsEnemyAndMarkedCubeAndExcludesPassives() {
        Mob zombie = entity(Zombie.class, Map.of(), true, false);
        Mob slime = entity(Slime.class, Map.of(), true, false);
        Mob boss = entity(SulfurCube.class, Map.of("rpgdungeons:boss", (byte) 1), true, false);
        Mob passive = entity(SulfurCube.class, Map.of(), true, false);
        Mob friendly = entity(Wolf.class, Map.of(), true, false);
        Mob dead = entity(Zombie.class, Map.of(), true, true);
        World world = proxy(World.class, (self, method, args) -> {
            if (method.getName().equals("getNearbyEntitiesByType")) {
                assertSame(Mob.class, args[0]);
                assertEquals(7.0, args[2]);
                return List.of(zombie, slime, boss, passive, friendly, dead);
            }
            return zero(self, method, args);
        });
        List<Mob> targets = CombatTargetRules.nearbyPveTargets(new Location(world, 0, 0, 0), 7.0);
        assertEquals(List.of(zombie, slime, boss), targets);
    }

    @Test
    void invalidQueryCannotScanTheWorld() {
        World noQueries = proxy(World.class, (self, method, args) -> {
            if (method.getName().equals("getNearbyEntitiesByType")) fail("invalid query reached the world");
            return zero(self, method, args);
        });
        assertTrue(CombatTargetRules.nearbyPveTargets(new Location(null, 0, 0, 0), 8).isEmpty());
        assertTrue(CombatTargetRules.nearbyPveTargets(new Location(noQueries, 0, 0, 0), -1).isEmpty());
        assertTrue(CombatTargetRules.nearbyPveTargets(new Location(noQueries, 0, 0, 0), Double.NaN).isEmpty());
    }

    private static <T extends LivingEntity> T entity(
            Class<T> type, Map<String, Byte> markers, boolean valid, boolean dead
    ) {
        PersistentDataContainer data = proxy(PersistentDataContainer.class, (self, method, args) -> {
            if (method.getName().equals("get")) {
                assertSame(PersistentDataType.BYTE, args[1]);
                return markers.get(((NamespacedKey) args[0]).toString());
            }
            return zero(self, method, args);
        });
        return proxy(type, (self, method, args) -> switch (method.getName()) {
            case "getPersistentDataContainer" -> data;
            case "isValid" -> valid;
            case "isDead" -> dead;
            default -> zero(self, method, args);
        });
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static Object zero(Object self, Method method, Object[] args) {
        return switch (method.getName()) {
            case "equals" -> self == args[0];
            case "hashCode" -> System.identityHashCode(self);
            case "toString" -> "entity-fixture";
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
