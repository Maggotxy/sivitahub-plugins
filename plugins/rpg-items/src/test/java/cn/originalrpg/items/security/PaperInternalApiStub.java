package cn.originalrpg.items.security;

import io.papermc.paper.InternalAPIBridge;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;

import java.lang.reflect.Proxy;

/**
 * 测试用的最小 {@link InternalAPIBridge}，只在伤害事件构造路径上提供实现。
 *
 * Paper 25+ 的 {@code DamageSource.builder} 会经 ServiceLoader 查这个桥接接口，测试进程
 * 里没有服务端实现，所以补一个壳。除 {@code createDamageSourceBuilder} 外的方法都不会
 * 被本测试调用，统一抛 {@link UnsupportedOperationException}，避免静默返回错值。
 * 这些空实现是 paper-api 版本升级时需要同步的样板，不是业务逻辑。
 */
public final class PaperInternalApiStub implements InternalAPIBridge {
    public PaperInternalApiStub() {
    }

    private static DamageSource damageSource(DamageType damageType) {
        return (DamageSource) Proxy.newProxyInstance(
                DamageSource.class.getClassLoader(),
                new Class<?>[]{DamageSource.class},
                (source, method, args) -> {
                    if (method.getReturnType() == DamageType.class) return damageType;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                });
    }

    @Override
    public org.bukkit.block.Biome constructLegacyCustomBiome() {
        throw new UnsupportedOperationException("bridge method not needed by this test");
    }

    @Override
    public io.papermc.paper.world.damagesource.CombatEntry createCombatEntry(org.bukkit.entity.LivingEntity arg0, org.bukkit.damage.DamageSource arg1, float arg2) {
        throw new UnsupportedOperationException("bridge method not needed by this test");
    }

    @Override
    public io.papermc.paper.world.damagesource.CombatEntry createCombatEntry(org.bukkit.damage.DamageSource arg0, float arg1, io.papermc.paper.world.damagesource.FallLocationType arg2, float arg3) {
        throw new UnsupportedOperationException("bridge method not needed by this test");
    }

    @Override
    public java.util.function.Predicate<io.papermc.paper.command.brigadier.CommandSourceStack> restricted(java.util.function.Predicate<io.papermc.paper.command.brigadier.CommandSourceStack> arg0) {
        throw new UnsupportedOperationException("bridge method not needed by this test");
    }

    @Override
    public io.papermc.paper.datacomponent.item.ResolvableProfile defaultMannequinProfile() {
        throw new UnsupportedOperationException("bridge method not needed by this test");
    }

    @Override
    public com.destroystokyo.paper.SkinParts.Mutable allSkinParts() {
        throw new UnsupportedOperationException("bridge method not needed by this test");
    }

    @Override
    public net.kyori.adventure.text.Component defaultMannequinDescription() {
        throw new UnsupportedOperationException("bridge method not needed by this test");
    }

    @Override
    public <MODERN, LEGACY> org.bukkit.GameRule<LEGACY> legacyGameRuleBridge(org.bukkit.GameRule<MODERN> arg0, java.util.function.Function<LEGACY, MODERN> arg1, java.util.function.Function<MODERN, LEGACY> arg2, java.lang.Class<LEGACY> arg3) {
        throw new UnsupportedOperationException("bridge method not needed by this test");
    }

    @Override
    public java.util.Set<org.bukkit.entity.Pose> validMannequinPoses() {
        throw new UnsupportedOperationException("bridge method not needed by this test");
    }

    @Override
    public io.papermc.paper.entity.poi.PoiType.Occupancy createOccupancy(java.lang.String arg0) {
        throw new UnsupportedOperationException("bridge method not needed by this test");
    }

    @Override
    public org.bukkit.damage.DamageSource.Builder createDamageSourceBuilder(DamageType damageType) {
        return (DamageSource.Builder) Proxy.newProxyInstance(
                DamageSource.Builder.class.getClassLoader(),
                new Class<?>[]{DamageSource.Builder.class},
                (builder, method, args) -> switch (method.getName()) {
                    case "withCausingEntity", "withDirectEntity", "withDamageLocation" -> builder;
                    case "build" -> damageSource(damageType);
                    default -> null;
                });
    }

    @Override
    public org.bukkit.damage.DamageEffect getDamageEffect(java.lang.String arg0) {
        throw new UnsupportedOperationException("bridge method not needed by this test");
    }

    @Override
    public java.lang.String getTranslationKey(org.bukkit.entity.EntityType arg0) {
        throw new UnsupportedOperationException("bridge method not needed by this test");
    }

    @Override
    public org.bukkit.entity.SpawnCategory getSpawnCategory(org.bukkit.entity.EntityType arg0) {
        throw new UnsupportedOperationException("bridge method not needed by this test");
    }

    @Override
    public org.bukkit.inventory.ItemStack deserializeItem(byte[] arg0) {
        throw new UnsupportedOperationException("bridge method not needed by this test");
    }

    @Override
    public boolean hasDefaultEntityAttributes(org.bukkit.NamespacedKey arg0) {
        throw new UnsupportedOperationException("bridge method not needed by this test");
    }

    @Override
    public org.bukkit.attribute.Attributable getDefaultEntityAttributes(org.bukkit.NamespacedKey arg0) {
        throw new UnsupportedOperationException("bridge method not needed by this test");
    }

    @Override
    public java.lang.String getStatisticCriteriaKey(org.bukkit.Statistic arg0) {
        throw new UnsupportedOperationException("bridge method not needed by this test");
    }

    @Override
    public io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager<org.bukkit.plugin.Plugin> createPluginLifecycleEventManager(org.bukkit.plugin.java.JavaPlugin arg0, java.util.function.BooleanSupplier arg1) {
        throw new UnsupportedOperationException("bridge method not needed by this test");
    }

    @Override
    public org.bukkit.inventory.ItemStack createEmptyStack() {
        throw new UnsupportedOperationException("bridge method not needed by this test");
    }

    @Override
    public net.kyori.adventure.text.Component resolveWithContext(net.kyori.adventure.text.Component arg0, org.bukkit.command.CommandSender arg1, org.bukkit.entity.Entity arg2, boolean arg3) throws java.io.IOException {
        throw new UnsupportedOperationException("bridge method not needed by this test");
    }

    @Override
    public net.kyori.adventure.text.flattener.ComponentFlattener componentFlattener() {
        throw new UnsupportedOperationException("bridge method not needed by this test");
    }
}
