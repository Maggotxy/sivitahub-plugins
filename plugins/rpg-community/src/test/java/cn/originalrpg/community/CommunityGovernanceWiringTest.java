package cn.originalrpg.community;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接线守卫（补充证据，不替代行为级测试）。
 *
 * <p>行为断言在 {@code CommunityRolePowerTest} 与
 * {@code CommunityGovernanceCommandRouterTest}；本测试只防止未来有人删掉
 * rpg-community 侧的消费者入口，让治理功能静默失效。</p>
 */
final class CommunityGovernanceWiringTest {

    @Test
    void runtimeResolvesTheGovernanceServicesAndRegistersTheCommandConsumer() throws Exception {
        String source = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-community/src/main/java/cn/originalrpg/community/RpgCommunityPlugin.java"));
        // 治理是可选接线：缺失 provider 必须降级为「未启用」而不是启动失败。
        assertTrue(source.contains("getServicesManager().load(CommunityGovernanceService.class)"));
        assertTrue(source.contains("getServicesManager().load(CommunityRoleAssignmentService.class)"));
        assertTrue(source.contains("public boolean governanceAvailable()"));
        assertTrue(source.contains("new CommunityGovernanceListener("));

        String command = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-community/src/main/java/cn/originalrpg/community/CommunityCommand.java"));
        assertTrue(command.contains("CommunityGovernanceCommandRouter.route("));
        assertTrue(command.contains("plugin.communityGovernanceEnabled()"));
        assertTrue(command.contains("plugin.governanceAvailable()"));
    }

    @Test
    void governanceIsWiredButDisabledByDefault() throws Exception {
        String config = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-community/src/main/resources/config.yml"));
        assertTrue(config.contains("governance:"));
        assertTrue(config.contains("enabled: false"), "Phase 6 治理必须默认关闭（接线但不启用）");

        String plugin = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-community/src/main/java/cn/originalrpg/community/RpgCommunityPlugin.java"));
        assertTrue(plugin.contains("community.governance.enabled"));
        assertTrue(plugin.contains("getConfig().getBoolean(\"community.governance.enabled\", false)"));
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("Cannot locate repository root");
        return current;
    }
}
