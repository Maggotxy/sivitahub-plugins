package cn.originalrpg.admin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-101 的机械门禁：{@code preview()} 与 {@code advance()} 必须在 {@code src/main} 里
 * 有非自身的生产消费者。
 *
 * <p>与行为测试 {@link SeasonSettlementCommandTest} 是两个必需且互不替代的判据：
 * 那个证明「入口跑起来是什么行为」，这个证明「入口真的存在于生产代码里」——
 * 一个只有测试调用点的入口不算接线（L-008）。</p>
 */
final class SeasonSettlementWiringTest {
    @Test
    void advanceAndPreviewHaveProductionConsumersOutsideTheServiceItself() throws IOException {
        assertConsumed("advance(");
        assertConsumed("preview(");
    }

    @Test
    void adminSeasonEntryIsSourcedFromTheAuthoritativeFacadeNotFromASecondStateMachine() throws IOException {
        Path admin = repositoryRoot().resolve("plugins/rpg-admin/src/main/java/cn/originalrpg/admin");
        String command = Files.readString(admin.resolve("SeasonSettlementCommand.java"));
        String plugin = Files.readString(admin.resolve("RpgAdminPlugin.java"));

        // 管理面只允许经 SeasonSettlementService 动手，不得直接读写换季表。
        assertTrue(command.contains("SeasonSettlementService"), "管理面必须依赖服务接口");
        for (String table : List.of(
                "rpg_season_settlements", "rpg_season_settlement_transitions",
                "rpg_season_settlement_progress", "rpg_season_settlement_gate")) {
            assertFalse(command.contains(table), "管理面不得直接读写 " + table);
        }
        assertTrue(plugin.contains("SeasonSettlementCommand"), "onCommand 分派链必须接上 season 子命令");
        assertTrue(plugin.contains("\"season\""), "season 子命令名必须出现在分派里");
    }

    @Test
    void destructiveSeasonPathRequiresTheConfirmLiteralAndShipsDisabledByDefault() throws IOException {
        Path admin = repositoryRoot().resolve("plugins/rpg-admin/src/main");
        String command = Files.readString(admin.resolve("java/cn/originalrpg/admin/SeasonSettlementCommand.java"));
        String config = Files.readString(admin.resolve("resources/config.yml"));

        // 没有 CONFIRM 就不得把动作转发出去，且开关默认关（L-009）。
        assertTrue(command.contains("AdminConfirmation.isConfirmed"), "advance 必须过确认字面量");
        assertTrue(config.contains("season-settlement:"), "config.yml 必须有换季开关段");
        int section = config.indexOf("season-settlement:");
        assertTrue(config.substring(section, section + 400).contains("enabled: false"),
                "换季管理面必须默认关闭");
    }

    /** 在各个插件主源码目录里找出引用了该方法名的文件（排除定义文件自身）。 */
    private static void assertConsumed(String methodToken) throws IOException {
        Path root = repositoryRoot().resolve("plugins");
        List<Path> definitions = new ArrayList<>();
        List<Path> consumers = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String normalized = file.toString().replace('\\', '/');
                if (!normalized.contains("/src/main/")) continue;
                String source = Files.readString(file);
                if (!source.contains(methodToken)) continue;
                if (normalized.endsWith("/SeasonSettlementService.java")
                        || normalized.endsWith("/JdbcSeasonSettlementService.java")) {
                    definitions.add(file);
                    continue;
                }
                consumers.add(file);
            }
        }
        assertFalse(definitions.isEmpty(), "找不到 " + methodToken + " 的定义");
        assertTrue(!consumers.isEmpty(),
                "G-101 违规：" + methodToken + " 在 src/main 里零生产消费者（只有定义与自身）");
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
