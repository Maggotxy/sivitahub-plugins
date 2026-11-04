package cn.originalrpg.skills;

import cn.originalrpg.api.service.SkillDamageService;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.entity.SulfurCube;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Executes concrete area and hitscan skills against the pinned Paper entity hierarchy. */
final class SkillPveTargetingTest {
    @Test
    void toxicBurstActuallyDamagesSulfurWaveAndNativeNonMonsterEnemies() {
        Fixture fixture = new Fixture();
        Mob sulfur = fixture.mob(SulfurCube.class, "rpgdungeons:wave_mob", 2.0, false);
        Mob slime = fixture.mob(Slime.class, null, 3.0, false);
        Mob ghast = fixture.mob(Ghast.class, null, 4.0, false);
        fixture.mob(SulfurCube.class, null, 1.0, false);
        fixture.mob(Cow.class, null, 1.0, false);
        fixture.mob(Zombie.class, null, 1.0, true);

        fixture.mechanics.toxicBurst(fixture.caster, 7.0, 4.5, "toxic-burst");

        assertEquals(List.of(sulfur, slime, ghast), fixture.hits.stream().map(Hit::target).toList());
        assertEquals(List.of(4.5, 4.5, 4.5), fixture.hits.stream().map(Hit::damage).toList());
        assertEquals(List.of("toxic-burst", "toxic-burst", "toxic-burst"),
                fixture.hits.stream().map(Hit::skillId).toList());
    }

    @Test
    void piercingRayIncludesSulfurBossAndStillHonorsItsTargetLimit() {
        Fixture fixture = new Fixture();
        Mob sulfur = fixture.mob(SulfurCube.class, "rpgdungeons:boss", 2.0, false);
        Mob slime = fixture.mob(Slime.class, null, 3.0, false);
        fixture.mob(Ghast.class, null, 4.0, false);
        fixture.mob(SulfurCube.class, null, 1.0, false);
        fixture.mob(Cow.class, null, 1.0, false);

        fixture.mechanics.piercingRay(fixture.caster, 10.0, 1.0, 2,
                12.0, "piercing-shot", Color.WHITE);

        assertEquals(List.of(sulfur, slime), fixture.hits.stream().map(Hit::target).toList());
        assertEquals(List.of(12.0, 12.0), fixture.hits.stream().map(Hit::damage).toList());
    }

    private record Hit(LivingEntity target, double damage, String skillId) {
    }

    private static final class Fixture {
        private final List<Mob> entities = new ArrayList<>();
        private final List<Hit> hits = new ArrayList<>();
        private final World world;
        private final Player caster;
        private final SkillMechanics mechanics;

        private Fixture() {
            world = proxy(World.class, (self, method, args) -> {
                if (method.getName().equals("getNearbyEntitiesByType")) {
                    Class<?> type = (Class<?>) args[0];
                    return entities.stream().filter(type::isInstance).toList();
                }
                return zero(self, method, args);
            });
            caster = proxy(Player.class, (self, method, args) -> switch (method.getName()) {
                case "getWorld" -> world;
                case "getLocation" -> new Location(world, 0, 0, 0);
                case "getEyeLocation" -> new Location(world, 0, 1.6, 0);
                case "getTargetBlockExact" -> null;
                default -> zero(self, method, args);
            });
            YamlConfiguration config = new YamlConfiguration();
            config.set("visual-effects.enabled", false);
            Plugin plugin = proxy(Plugin.class, (self, method, args) ->
                    method.getName().equals("getConfig") ? config : zero(self, method, args));
            SkillDamageService damage = proxy(SkillDamageService.class, (self, method, args) -> {
                if (method.getName().equals("damage")) {
                    hits.add(new Hit((LivingEntity) args[1], (double) args[2], (String) args[3]));
                }
                return zero(self, method, args);
            });
            mechanics = new SkillMechanics(damage, new SkillVisuals(plugin, null), null, null);
        }

        private <T extends Mob> T mob(Class<T> type, String marker, double z, boolean dead) {
            UUID id = UUID.randomUUID();
            Map<String, Byte> markers = marker == null ? Map.of() : Map.of(marker, (byte) 1);
            PersistentDataContainer data = proxy(PersistentDataContainer.class, (self, method, args) ->
                    method.getName().equals("get")
                            ? markers.get(((NamespacedKey) args[0]).toString()) : zero(self, method, args));
            T mob = proxy(type, (self, method, args) -> switch (method.getName()) {
                case "getPersistentDataContainer" -> data;
                case "getUniqueId" -> id;
                case "getWorld" -> world;
                case "getLocation" -> new Location(world, 0, 0, z);
                case "getEyeLocation" -> new Location(world, 0, 1.6, z);
                case "isValid" -> true;
                case "isDead" -> dead;
                case "getHeight" -> 1.8;
                default -> zero(self, method, args);
            });
            entities.add(mob);
            return mob;
        }
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static Object zero(Object self, Method method, Object[] args) {
        return switch (method.getName()) {
            case "equals" -> self == args[0];
            case "hashCode" -> System.identityHashCode(self);
            case "toString" -> "skill-target-fixture";
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
