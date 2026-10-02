package cn.originalrpg.combat;

import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.model.CoreAttribute;
import cn.originalrpg.api.model.EnergyQuotaResult;
import cn.originalrpg.api.model.EquipmentSnapshot;
import cn.originalrpg.api.model.PlayerProfile;
import cn.originalrpg.api.model.SkillBookRedemption;
import cn.originalrpg.api.service.EquipmentService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.ProfileService;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 事件级回归：五维「防御」与装备 defense 词条必须在非实体伤害与实体互砍两条路径上都生效，
 * 且同一次伤害只减一次。规则测试 {@link DefensePolicyTest} 覆盖不到「监听器有没有接线」，
 * 所以这里直接构造并派发 Bukkit 事件。
 *
 * 事件来源用 deprecated 的简化构造器：Paper 的 DamageSource 版本要求运行中的注册表，
 * 而这里要验证的是监听器接线，不是伤害来源语义。
 */
@SuppressWarnings("removal")
class DefenseWiringTest {
    private static final int DEFENSE = 100;
    /** 100 防御、系数 0.009 ⇒ 19 × 1/1.9 = 10，与 config.yml 默认值一致。 */
    private static final double EXPECTED = 10.0;

    private FakePlayer target;
    private FakePlayer attacker;
    private ExecuteListener listener;

    @BeforeEach
    void setUp() {
        Plugin plugin = stub(Plugin.class);
        target = new FakePlayer(UUID.randomUUID());
        attacker = new FakePlayer(UUID.randomUUID());
        listener = new ExecuteListener(plugin, new FakeProfiles(target.playerId()), new NoEquipment(),
                stub(MessageService.class), new SkillDamageBridge(plugin));
    }

    @Test
    void nonEntityDamageIsReducedOnceByDefense() {
        EntityDamageEvent event = new EntityDamageEvent(target.entity, EntityDamageEvent.DamageCause.LAVA, 19.0);
        listener.onDefensiveDamage(event);
        assertEquals(EXPECTED, event.getDamage(), 1.0E-9);
    }

    @Test
    void meleeDamageIsReducedOnceEvenWhenBothListenersSeeTheSameEvent() {
        EntityDamageByEntityEvent event = melee(19.0);
        // 互砍的真实路径：Bukkit 只派发子事件，父类监听器收不到。
        listener.onDamage(event);
        assertEquals(EXPECTED, event.getDamage(), 1.0E-9);

        // 假如父类监听器也收到同一事件，不得二次减伤（幂等守卫）。
        listener.onDefensiveDamage(copy(event));
        assertEquals(EXPECTED, event.getDamage(), 1.0E-9);

        // 同一 tick 内重复派发也仍然只减一次。
        listener.onDefensiveDamage(copy(event));
        assertEquals(EXPECTED, event.getDamage(), 1.0E-9);
    }

    /** 同一伤害的新事件对象，用来验证「只生效一次」真的由守卫决定，而不是靠攻击倍率掩盖。 */
    private EntityDamageByEntityEvent copy(EntityDamageByEntityEvent source) {
        return event(source.getEntity(), source.getDamager(), source.getCause(), source.getDamage());
    }

    @Test
    void undefendableDamageIsLeftUntouchedOnBothPaths() {
        EntityDamageEvent voidDamage = new EntityDamageEvent(target.entity, EntityDamageEvent.DamageCause.VOID, 19.0);
        listener.onDefensiveDamage(voidDamage);
        assertEquals(19.0, voidDamage.getDamage(), 1.0E-9);

        EntityDamageByEntityEvent starvation = event(
                target.entity, attacker.entity, EntityDamageEvent.DamageCause.STARVATION, 19.0);
        listener.onDamage(starvation);
        assertEquals(19.0, starvation.getDamage(), 1.0E-9);
    }

    @Test
    void projectileAndMeleeCausesShareTheSameDefenseMultiplier() {
        for (EntityDamageEvent.DamageCause cause : List.of(
                EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                EntityDamageEvent.DamageCause.PROJECTILE,
                EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK)) {
            EntityDamageByEntityEvent event = event(target.entity, attacker.entity, cause, 19.0);
            listener.onDamage(event);
            // 只有玩家受击目标的互砍才享受自定义防御；怪打怪不参与。
            listener.onDefensiveDamage(copy(event));
            assertEquals(EXPECTED, event.getDamage(), 1.0E-9, cause.name());
        }
    }

    private EntityDamageByEntityEvent melee(double damage) {
        return event(target.entity, attacker.entity, EntityDamageEvent.DamageCause.ENTITY_ATTACK, damage);
    }

    /**
     * Paper 的构造器签名是 {@code (damager, victim, cause, source, damage)}，
     * 形参顺序容易写反，这里用命名参数包一层，避免测试自己构造出「打自己」的事件。
     */
    private static EntityDamageByEntityEvent event(
            org.bukkit.entity.Entity victim,
            org.bukkit.entity.Entity damager,
            EntityDamageEvent.DamageCause cause,
            double damage
    ) {
        return new EntityDamageByEntityEvent(damager, victim, cause,
                org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.GENERIC).build(),
                damage);
    }

    /** 不返回任何自定义装备，攻击结算走空武器分支，只验证防御接线。 */
    private static final class NoEquipment implements EquipmentService {
        @Override public boolean isCustomEquipment(ItemStack item) { return false; }
        @Override public boolean isAuthentic(ItemStack item) { return false; }
        @Override public boolean isBound(ItemStack item) { return false; }
        @Override public boolean isTradeUnlocked(ItemStack item) { return false; }
        @Override public Optional<UUID> itemId(ItemStack item) { return Optional.empty(); }
        @Override public Optional<EquipmentSnapshot> inspect(ItemStack item) { return Optional.empty(); }
        @Override public boolean unlockForTrade(ItemStack item, UUID expectedOwner) { return false; }
        @Override public void sign(ItemStack item) { }
    }

    private static final class FakeProfiles implements ProfileService {
        private final Map<UUID, PlayerProfile> profiles = new ConcurrentHashMap<>();

        FakeProfiles(UUID playerId) {
            EnumMap<CoreAttribute, Integer> attributes = new EnumMap<>(CoreAttribute.class);
            attributes.put(CoreAttribute.DEFENSE, DEFENSE);
            profiles.put(playerId, new PlayerProfile(
                    playerId, Archetype.WARRIOR, 0, 1, 0, 100, 100, 0, 0, attributes));
        }

        @Override public CompletionStage<PlayerProfile> get(UUID playerId) {
            PlayerProfile profile = profiles.get(playerId);
            return profile == null
                    ? CompletableFuture.failedFuture(new IllegalStateException("unknown player"))
                    : CompletableFuture.completedFuture(profile);
        }

        @Override public Optional<PlayerProfile> cached(UUID playerId) {
            return Optional.ofNullable(profiles.get(playerId));
        }

        @Override public void evict(UUID playerId) { profiles.remove(playerId); }

        @Override public CompletionStage<PlayerProfile> chooseArchetype(UUID playerId, Archetype archetype) {
            throw new UnsupportedOperationException();
        }

        @Override public CompletionStage<PlayerProfile> allocateAttribute(UUID playerId, CoreAttribute attribute) {
            throw new UnsupportedOperationException();
        }

        @Override public CompletionStage<PlayerProfile> prepareStarterBuild(UUID playerId) {
            throw new UnsupportedOperationException();
        }

        @Override public CompletionStage<PlayerProfile> grantExperience(UUID playerId, long amount) {
            throw new UnsupportedOperationException();
        }

        @Override public CompletionStage<Boolean> spendEnergy(UUID playerId, int amount) {
            throw new UnsupportedOperationException();
        }

        @Override public CompletionStage<PlayerProfile> restoreEnergy(UUID playerId, int amount) {
            throw new UnsupportedOperationException();
        }

        @Override public CompletionStage<EnergyQuotaResult> restoreEnergyWithQuota(
                UUID playerId, String sourceId, String periodKey, int baseAmount, int decayEveryUses,
                int decayAmount, int minimumAmount, int periodLimit
        ) {
            throw new UnsupportedOperationException();
        }

        @Override public CompletionStage<PlayerProfile> ascend(UUID playerId) {
            throw new UnsupportedOperationException();
        }

        @Override public CompletionStage<SkillBookRedemption> redeemSkillBook(
                UUID playerId, UUID instanceId, String bookId, int points, String limitGroup, int maxUses
        ) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * 极简假玩家：只实现 ExecuteListener 会碰到的成员 —— UUID、空背包、无属性加成。
     */
    private static final class FakePlayer {
        private final Player entity;

        FakePlayer(UUID playerId) {
            PlayerInventory inventory = inventory();
            PersistentDataContainer data = stub(PersistentDataContainer.class);
            AttributeInstance absorption = stub(AttributeInstance.class);
            this.entity = (Player) Proxy.newProxyInstance(
                    Player.class.getClassLoader(), new Class<?>[]{Player.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> playerId;
                        case "getInventory" -> inventory;
                        case "getPersistentDataContainer" -> data;
                        case "getAttribute" -> Attribute.MAX_ABSORPTION.equals(args[0]) ? absorption : null;
                        default -> zero(method);
                    });
        }

        UUID playerId() {
            return entity.getUniqueId();
        }
    }

    /** 空防具栏位的背包；`getArmorContents()` 必须返回空数组而不是 null。 */
    private static PlayerInventory inventory() {
        return (PlayerInventory) Proxy.newProxyInstance(
                PlayerInventory.class.getClassLoader(), new Class<?>[]{PlayerInventory.class},
                (proxy, method, args) -> {
                    if (method.getReturnType() == ItemStack[].class) return new ItemStack[0];
                    return zero(method);
                });
    }

    /** 按返回类型造一个占位实现；config 与命名空间键在这里统一兜底。 */
    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> zero(method));
    }

    private static Object zero(java.lang.reflect.Method method) {
        if (method.getReturnType() == FileConfiguration.class) return CONFIG;
        if (method.getReturnType() == Server.class) return stub(Server.class);
        // PartyService 等查服务全部走这个入口，返回 null 表示「没有队伍服务」。
        if (method.getReturnType() == org.bukkit.plugin.ServicesManager.class) {
            return stub(org.bukkit.plugin.ServicesManager.class);
        }
        if (method.getReturnType() == NamespacedKey.class) return new NamespacedKey("test", "key");
        if (method.getReturnType() == Logger.class) return Logger.getAnonymousLogger();
        if (method.getReturnType() == List.class) return List.of();
        if (method.getReturnType() == Optional.class) return Optional.empty();
        Class<?> type = method.getReturnType();
        if (type == boolean.class) return false;
        if (type == int.class) return 7;
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

    /** 与 config.yml 默认值对齐的最小配置，去掉随机性（无暴击、无属性加成）。 */
    private static final FileConfiguration CONFIG = YamlConfiguration.loadConfiguration(new StringReader("""
            damage:
              melee-per-strength: 0.0
              ranged-per-agility: 0.0
              mage-per-intelligence: 0.0
              skill-per-primary-attribute: 0.0
              class-weapon-bonus: 0.0
              freeform-weapon-bonus: 0.0
              per-limit-break: 0.0
              critical-chance-per-point: 0.0
              maximum-critical-chance: 0.0
              critical-multiplier: 1.0
              maximum-attacker-multiplier: 4.0
              skill-pvp-multiplier: 0.50
              skill-boss-multiplier: 0.75
            defense:
              reduction-coefficient: 0.009
              maximum-reduction: 0.60
              maximum-vulnerability: 1.50
              per-armor-limit-break: 2
            execute:
              base-threshold: 0.10
              power-per-primary-attribute: 12.0
            traits:
              life-steal-ratio: 0.04
              fire-ticks: 60
            feedback:
              target-hud:
                enabled: false
                use-resource-glyphs: false
                segments: 10
              visual-effects:
                enabled: false
                critical-particles: 0
                execute-particles: 0
                sound-volume: 0.0
            """));
}
