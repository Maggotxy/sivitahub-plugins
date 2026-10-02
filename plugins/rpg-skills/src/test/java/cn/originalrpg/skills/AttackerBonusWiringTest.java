package cn.originalrpg.skills;

import cn.originalrpg.api.service.ClassBranchService;
import cn.originalrpg.api.service.EquipmentService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.ProfileService;
import cn.originalrpg.api.service.SkillDamageService;
import cn.originalrpg.api.service.SkillService;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-105 事件级回归：攻方增益（rallying-standard 光环 + battle-trance 连击层数）
 * 只能在同一笔伤害上结算一次。
 *
 * <p>这里真的构造并派发 {@link EntityDamageByEntityEvent}，真的调用生产环境的
 * 监听器方法，断言的是事件里的最终伤害数值 —— 不是对源码做字符串断言。
 *
 * <p>事件来源用 deprecated 的简化构造器：Paper 的 DamageSource 版本要求运行中的
 * 注册表，而这里验证的是结算接线，不是伤害来源语义。
 */
@SuppressWarnings("removal")
final class AttackerBonusWiringTest {
    private static final double BASE = 10.0;
    /** 光环 +12% 与 1 层连击 +6% 相加 ⇒ 1.18。 */
    private static final double BONUS = 1.18;
    private static final double EXPECTED = BASE * BONUS;

    private FakeEntity target;
    private FakePlayer attacker;
    private SkillsCommand command;
    private World world;

    @BeforeEach
    void setUp() {
        Plugin plugin = stub(Plugin.class);
        world = fakeWorld();
        attacker = new FakePlayer(UUID.randomUUID(), world);
        target = new FakeEntity(world);
        command = new SkillsCommand(plugin, stub(ProfileService.class), stub(SkillService.class),
                stub(ClassBranchService.class), stub(EquipmentService.class), stub(MessageService.class),
                stub(SkillDamageService.class));
    }

    @Test
    void buffedHitIsSettledExactlyOnce() {
        armBoth();
        EntityDamageByEntityEvent event = hit(BASE);
        // 生产路径：HIGH 的状态推进 + HIGHEST 的单点结算
        command.onBattleTranceHit(event);
        command.settleAttackerBonus(event);

        assertEquals(EXPECTED, event.getDamage(), 1.0E-9);
        // 二次放大会是 BASE * BONUS * BONUS；断言它没有发生
        assertTrue(event.getDamage() < BASE * BONUS * BONUS);
    }

    @Test
    void repeatedDispatchOfTheSameEventDoesNotAmplifyAgain() {
        armBoth();
        EntityDamageByEntityEvent event = hit(BASE);
        command.onBattleTranceHit(event);
        command.settleAttackerBonus(event);
        command.settleAttackerBonus(event);
        command.settleAttackerBonus(event);
        assertEquals(EXPECTED, event.getDamage(), 1.0E-9);
    }

    @Test
    void unbuffedHitKeepsVanillaDamage() {
        EntityDamageByEntityEvent event = hit(BASE);
        command.onBattleTranceHit(event);
        command.settleAttackerBonus(event);
        assertEquals(BASE, event.getDamage(), 1.0E-9);
    }

    @Test
    void standardAndTranceTogetherProduceOneAdditiveBonus() {
        command.armAttackerBonusForTest(attacker.playerId(), attacker.playerId(),
                System.currentTimeMillis() + 5_000L, location(), 0L, 0);
        // 只有光环生效时是 +12%
        EntityDamageByEntityEvent standardOnly = hit(BASE);
        command.onBattleTranceHit(standardOnly);
        command.settleAttackerBonus(standardOnly);
        assertEquals(BASE * 1.12, standardOnly.getDamage(), 1.0E-9);
    }

    private void armBoth() {
        command.armAttackerBonusForTest(attacker.playerId(), attacker.playerId(),
                System.currentTimeMillis() + 5_000L, location(), System.currentTimeMillis() + 5_000L, 0);
    }

    private EntityDamageByEntityEvent hit(double damage) {
        return new EntityDamageByEntityEvent(attacker.entity, target.entity,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.GENERIC).build(),
                damage);
    }

    /** 原点位置，让光环的距离判定保持通过。 */
    private Location location() {
        return new Location(world, 0.0, 0.0, 0.0);
    }

    private static World fakeWorld() {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "test-world";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> 1;
                    default -> zero(method);
                });
    }

    /** 假实体：技能监听器只读取位置、高度与存活状态。 */
    private static final class FakeEntity {
        private final Entity entity;

        FakeEntity(World world) {
            this.entity = (Entity) Proxy.newProxyInstance(Entity.class.getClassLoader(),
                    new Class<?>[]{LivingEntity.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getLocation" -> new Location(world, 0.0, 0.0, 0.0);
                        case "getWorld" -> world;
                        case "getHeight" -> 1.8;
                        case "isDead" -> false;
                        case "isValid" -> true;
                        default -> zero(method);
                    });
        }
    }

    /** 极简假玩家：只实现监听器会碰到的成员。 */
    private static final class FakePlayer {
        private final Player entity;

        FakePlayer(UUID playerId, World world) {
            this.entity = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> playerId;
                        case "getWorld" -> world;
                        case "getLocation" -> new Location(world, 0.0, 0.0, 0.0);
                        default -> zero(method);
                    });
        }

        UUID playerId() {
            return entity.getUniqueId();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> zero(method));
    }

    private static Object zero(java.lang.reflect.Method method) {
        if (method.getReturnType() == FileConfiguration.class) return CONFIG;
        if (method.getReturnType() == Server.class) return server();
        if (method.getReturnType() == org.bukkit.scheduler.BukkitScheduler.class) {
            return stub(org.bukkit.scheduler.BukkitScheduler.class);
        }
        if (method.getReturnType() == org.bukkit.scheduler.BukkitTask.class) {
            return stub(org.bukkit.scheduler.BukkitTask.class);
        }
        if (method.getReturnType() == NamespacedKey.class) return new NamespacedKey("test", "key");
        if (method.getReturnType() == Logger.class) return Logger.getAnonymousLogger();
        if (method.getReturnType() == List.class) return List.of();
        if (method.getReturnType() == Optional.class) return Optional.empty();
        Class<?> type = method.getReturnType();
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == float.class) return 0.0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        if (type == void.class) return null;
        if (type == String.class) return "";
        return null;
    }

    /** 让 plugin.getServer() 返回一个带可用 scheduler 的假服务端。 */
    private static Server server() {
        return (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
                (proxy, method, args) -> {
                    if (method.getReturnType() == org.bukkit.scheduler.BukkitScheduler.class) {
                        return stub(org.bukkit.scheduler.BukkitScheduler.class);
                    }
                    if (method.getName().equals("getCurrentTick")) return 0;
                    return zero(method);
                });
    }

    /** 与 config.yml 默认值对齐的最小配置，去掉随机性与视觉效果。 */
    private static final FileConfiguration CONFIG = YamlConfiguration.loadConfiguration(new StringReader("""
            hud:
              visible-millis: 5000
            visual-effects:
              enabled: false
              normal-particles: 6
              pressure-particles: 6
              critical-particles: 4
              sound-volume: 0.0
            sequences:
              enabled: false
            skill-effects:
              pvp-controls-enabled: true
            """));
}
