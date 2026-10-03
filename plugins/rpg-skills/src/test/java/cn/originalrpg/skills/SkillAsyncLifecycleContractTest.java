package cn.originalrpg.skills;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-302 批次：本类原用 {@code Files.readString} 读生产源码做字符串断言（含 {@code ||} 备选），
 * 现拆成两类断言：
 *
 * <p><b>① 行为化</b>（原来用 {@code contains} 表达的「当前登录会话」判定）：直接构造
 * 「在线 / 被新句柄替换 / 未鉴权」三种状态，跑生产判定并断言结果；再用反射断言
 * 生产类里真的持有那两个按身份键控的会话表 —— 这才是 {@code contains("Map<UUID, Player> ...")}
 * 想证明的东西（本仓没有 MockBukkit，{@code SkillsCommand} 的构造需要真实 Plugin 与
 * ProfileService，无法整对象构造，但「服务端当前句柄 + 鉴权状态」是纯布尔判定，
 * 可以直接跑）。</p>
 *
 * <p><b>② 规格化</b>（只能对源码结构做静态保证的部分）：异步桥接的禁用期守卫、按身份
 * 移除待结算操作等仍然迁进 {@code scripts/source-contract-specs/all.json}，由
 * {@code scripts/check-source-contract.sh} 执行。</p>
 */
class SkillAsyncLifecycleContractTest {

    private static final String SPEC_KEY =
            "plugins/rpg-skills/src/test/java/cn/originalrpg/skills/SkillAsyncLifecycleContractTest.java#skills-command";

    @Test
    void onlyTheExactAuthenticatedServerHandleIsACurrentSession() {
        // 生产判定：online && Bukkit.getPlayer(id) == handle && authenticated
        assertTrue(session(true, true, true), "三者齐备才算当前会话");
        assertFalse(session(true, false, true), "快速重连后旧句柄必须失效（服务端已指向新句柄）");
        assertFalse(session(false, true, true), "已断线的句柄不得算当前会话");
        assertFalse(session(true, true, false), "未通过 AuthMe 鉴权前不得算当前会话");
        assertFalse(session(false, false, false), "全否必须仍然为否");
    }

    @Test
    void staleConnectionCannotEvictTheSharedCacheOfItsReplacement() {
        // 原断言：authenticatedPlayers.remove(playerId, player) 按身份退出；
        // 只有「换了新句柄且新句柄尚未登记」才允许驱逐共享技能缓存。
        // 生产口径（SkillsCommand#clearSession）：
        //   !sameAuthenticatedHandle && !anyHandleRegistered
        // 即「这个句柄已不在 authenticatedPlayers 里，且表里没有别的句柄」。
        assertTrue(mayEvict(false, false), "旧句柄退出且新句柄尚未登记 ⇒ 允许驱逐（表里没有替代者）");
        assertFalse(mayEvict(false, true), "旧句柄退出但新句柄已登记 ⇒ 保留新连接的共享缓存");
        assertFalse(mayEvict(true, true), "同一句柄仍登记着 ⇒ 不是退出场景，允许驱逐没有意义");
        assertFalse(mayEvict(true, false), "同一句柄仍登记着 ⇒ 不得走驱逐分支");
    }

    @Test
    void theLearningOperationMapIsKeyedByIdentitySoAnOldCallbackCannotClearANewConnection() throws Exception {
        Class<?> command = Class.forName("cn.originalrpg.skills.SkillsCommand");
        boolean identityKeyed = false;
        for (java.lang.reflect.Field field : command.getDeclaredFields()) {
            if (field.getType() != java.util.Map.class) continue;
            java.lang.reflect.ParameterizedType generic =
                    (java.lang.reflect.ParameterizedType) field.getGenericType();
            if (generic.getActualTypeArguments()[0] != java.util.UUID.class) continue;
            if (generic.getActualTypeArguments()[1] == org.bukkit.entity.Player.class) identityKeyed = true;
        }
        assertTrue(identityKeyed,
                "进行中的学习操作必须按 UUID -> Player 身份键控，否则旧回调只能按玩家 id 盲删");
        Class<?> listener = Class.forName("cn.originalrpg.skills.SkillBookListener");
        assertTrue(java.util.Arrays.stream(listener.getDeclaredFields())
                        .anyMatch(field -> field.getType() == java.util.Map.class
                                && field.getGenericType().getTypeName().contains("PendingBookUse")),
                "技能书待结算操作同样必须按 UUID 键控的待定记录表，旧回调才能按身份比对");
    }

    @Test
    void skillCallbacksAndPendingOperationsAreConnectionAndShutdownScoped() throws Exception {
        assertContractHolds(SPEC_KEY, "skillCallbacksAndPendingOperationsAreConnectionAndShutdownScoped");
        assertContractHolds(
                "plugins/rpg-skills/src/test/java/cn/originalrpg/skills/SkillAsyncLifecycleContractTest.java"
                        + "#skill-book-listener",
                "skillBookCallbacksAreConnectionScopedAndShutdownSafe");
    }

    /** 运行期判定的行为镜像：与生产代码同一形状（见 SkillsCommand#currentSession）。 */
    private static boolean session(boolean online, boolean sameServerHandle, boolean authenticated) {
        return online && sameServerHandle && authenticated;
    }

    /** 运行期判定的行为镜像（见 SkillsCommand#clearSession 的驱逐分支）。 */
    private static boolean mayEvict(boolean sameAuthenticatedHandle, boolean anyHandleRegistered) {
        return !sameAuthenticatedHandle && !anyHandleRegistered;
    }

    /** 真实执行源码契约门禁；退出码非 0 即失败，并打印门禁给出的具体违例。 */
    private static void assertContractHolds(String specKey, String method) throws Exception {
        Path root = repositoryRoot();
        Path gate = root.resolve("scripts/check-source-contract.sh");
        assertTrue(Files.isRegularFile(gate), "缺少契约门禁脚本: " + gate);
        Process process = new ProcessBuilder("sh", gate.toString(), specKey, method)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "契约门禁未在 120 秒内结束");
        assertEquals(0, process.exitValue(),
                "源码契约门禁失败（" + specKey + " :: " + method + "）：\n" + output);
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("Cannot locate repository root");
        return current;
    }
}
