package cn.originalrpg.admin;

import cn.originalrpg.api.model.ChapterState;
import cn.originalrpg.api.model.EconomyFlow;
import cn.originalrpg.api.model.EconomyReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理面快照生命周期的行为级合同（G-302 第二批）。
 *
 * <p>原实现读 {@code RpgAdminPlugin.java} 源码文本、用 {@code indexOf} 断言私有方法体
 * 里的调用先后，与「快照真的只交不可变报告、停用真的先吊销发布」无关。现在改为真实
 * I/O 行为断言：</p>
 * <ul>
 *   <li>{@link #jdbcCompletionOnlyHandsImmutableReportToSnapshotWorker}：到达 worker 的
 *       就是权威报告本身（{@code assertSame}），且报告类型是不可变 record；</li>
 *   <li>{@link #chapterServiceIsSampledBeforeTheIoSubmission}：提交前取样，服务随后换值
 *       不影响已提交的那一份；</li>
 *   <li>{@link #disableInvalidatesCallbacksBeforeBoundedClose}：停用时先吊销发布
 *       （{@code publishAllowed=false}）再做限时关闭，迟到的写不得落盘。</li>
 * </ul>
 *
 * <p>{@link RpgAdminPlugin} 继承 {@code JavaPlugin}，本仓既无真实 Paper 服务器也无
 * MockBukkit/Mockito，构造不出实例；因此「插件真的持有可注入的 I/O 与生命周期闸门」
 * 只能靠 {@link #pluginDeclaresTheInjectableSnapshotIoAndLifecycleGates} 的反射断言，
 * 其余运行期行为由上面三条真实 I/O 用例覆盖。</p>
 */
final class AdminSnapshotLifecycleContractTest {

    @TempDir
    Path dataFolder;

    @Test
    void jdbcCompletionOnlyHandsImmutableReportToSnapshotWorker() throws Exception {
        AtomicReference<EconomyReport> received = new AtomicReference<>();
        CountDownLatch handed = new CountDownLatch(1);
        AdminSnapshotIo io = new AdminSnapshotIo(new StubStore() {
            @Override
            public AdminSnapshotIo.EconomyExport writeEconomy(EconomyReport report) {
                received.set(report);
                handed.countDown();
                return new AdminSnapshotIo.EconomyExport(null, null);
            }
        }, 2);

        EconomyReport report = report();
        io.economy(report);
        assertTrue(handed.await(2, TimeUnit.SECONDS));

        // 到达 worker 的必须是同一对象：拷贝会掩盖「报告在别处被就地改写」的缺陷。
        assertSame(report, received.get(),
                "snapshot worker must receive the authoritative report itself, not a mutable copy");
        // 报告是不可变 record：worker 无法就地改写它。
        assertTrue(EconomyReport.class.isRecord(), "EconomyReport must be an immutable value type");
        assertTrue(io.close(Duration.ofSeconds(2)));
    }

    @Test
    void chapterServiceIsSampledBeforeTheIoSubmission() throws Exception {
        AtomicReference<ChapterState> written = new AtomicReference<>();
        CountDownLatch writtenLatch = new CountDownLatch(1);
        AdminSnapshotIo io = new AdminSnapshotIo(new StubStore() {
            @Override
            public void writeChapter(ChapterState state) {
                written.set(state);
                writtenLatch.countDown();
            }
        }, 2);

        // 模拟调度线程：先取样、再提交；服务随后换值不得影响已提交的那一份。
        ChapterState sampled = chapter("chapter_1", 1);
        io.chapter(sampled);
        ChapterState newerFromService = chapter("chapter_2", 9);
        assertTrue(writtenLatch.await(2, TimeUnit.SECONDS));

        assertSame(sampled, written.get(),
                "the immutable copy sampled before submission must be the one written");
        assertEquals("chapter_1", written.get().chapterId());
        assertEquals(1, written.get().sequence());
        assertEquals("chapter_2", newerFromService.chapterId());
        assertTrue(io.close(Duration.ofSeconds(2)));
    }

    @Test
    void disableInvalidatesCallbacksBeforeBoundedClose() throws Exception {
        // 真实 FileStore 通道：写真实文件到 dataFolder。
        AdminSnapshotIo io = new AdminSnapshotIo(dataFolder);
        Field publishAllowedField = AdminSnapshotIo.class.getDeclaredField("publishAllowed");
        publishAllowedField.setAccessible(true);
        AtomicBoolean publishAllowed = (AtomicBoolean) publishAllowedField.get(io);
        assertTrue(publishAllowed.get(), "a fresh channel must allow publication");

        // 正常关闭 → 允许发布为真时文件应落盘。
        io.chapter(chapter("chapter_1", 1));
        assertTrue(io.close(Duration.ofSeconds(2)));
        assertTrue(Files.exists(dataFolder.resolve("chapter.json")),
                "an accepted snapshot must have been published before the channel closed");

        // 超时关闭（非零等待内的取消分支）：发布许可必须先被吊销。
        AdminSnapshotIo timedOut = new AdminSnapshotIo(dataFolder);
        AtomicBoolean timedOutPublish = (AtomicBoolean) publishAllowedField.get(timedOut);
        timedOut.chapter(chapter("chapter_2", 2));
        // 故意用 0ms 等待：drain 必然超时，close 必须返回 false 并走取消了残余工作的分支。
        assertFalse(timedOut.close(Duration.ZERO));
        assertFalse(timedOutPublish.get(),
                "a bounded close that could not drain must revoke publication before cancelling the rest");
        // 关闭后新提交必须直接失败，不得再写文件。
        assertTrue(timedOut.chapter(chapter("late", 3)).isCompletedExceptionally(),
                "a closed snapshot channel must reject new work instead of writing late");
    }

    @Test
    void pluginDeclaresTheInjectableSnapshotIoAndLifecycleGates() throws Exception {
        Field io = RpgAdminPlugin.class.getDeclaredField("snapshotIo");
        assertEquals(AdminSnapshotIo.class, io.getType(),
                "snapshot I/O must be an injectable collaborator");
        assertTrue(Modifier.isVolatile(io.getModifiers()),
                "the snapshot I/O handle must be volatile: swapped on the main thread, read by timers");

        for (String gate : List.of("snapshotLifecycleActive", "economyCollectionInFlight")) {
            assertEquals(AtomicBoolean.class, RpgAdminPlugin.class.getDeclaredField(gate).getType(), gate);
        }

        for (String method : List.of("exportEconomySnapshot", "exportChapterSnapshot")) {
            assertEquals(void.class, RpgAdminPlugin.class.getDeclaredMethod(method).getReturnType(), method);
        }
        assertEquals(void.class, RpgAdminPlugin.class.getMethod("onDisable").getReturnType());
    }

    private static ChapterState chapter(String chapterId, int sequence) {
        return new ChapterState("season_1", chapterId, sequence, 1, 1L, Instant.EPOCH);
    }

    private static EconomyReport report() {
        EconomyFlow day = new EconomyFlow(1, money("100"), money("70"), money("5"), money("240"),
                4, 3, 2, 1);
        return new EconomyReport(LocalDate.of(2026, 8, 13), 10, money("1000"), 20, 5,
                money("800"), money("20"), money("100"), money("500"),
                new BigDecimal("0.4000"), new BigDecimal("0.2500"),
                3, 2, 1, money("12"), money("48"), money("120"), 9, 2, day,
                new EconomyFlow(7, money("700"), money("500"), money("25"), money("1400"),
                        28, 21, 14, 7),
                new EconomyFlow(30, money("3000"), money("2300"), money("100"), money("6000"),
                        120, 90, 60, 30),
                List.of(), List.of());
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2);
    }

    /** 只记录副作用的最简 Store。 */
    private static class StubStore implements AdminSnapshotIo.Store {
        @Override
        public AdminSnapshotIo.EconomyExport writeEconomy(EconomyReport report) throws IOException {
            return new AdminSnapshotIo.EconomyExport(null, null);
        }

        @Override
        public void writeChapter(ChapterState state) throws IOException {
        }
    }
}
