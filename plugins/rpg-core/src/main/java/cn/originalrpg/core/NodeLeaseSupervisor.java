package cn.originalrpg.core;

import cn.originalrpg.core.persistence.JdbcNodeLease;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 节点租约的唯一消费者（C-014 多服前置）。
 *
 * <p>职责只有两件事：启动后周期续期，以及无法续期时按文档语义失败关闭。
 * docs/SCALE_ARCHITECTURE.md「必须保持的验收不变量」要求在失租时停止结算，
 * 而不是降级只读，因此这里的处置是 fail-stop：调用方收到 {@code onLeaseLost}
 * 后停止 Paper，绝不带着失效 token 继续写账本。</p>
 *
 * <p>本类只接线，不启用多服。单实例下 {@link JdbcNodeLease} 以本地 H2 文件锁
 * 作为进程外部的围栏（{@code allowExpiredSameProcessRenewal=true}），只要进程
 * 活着就一定续期成功；被墙只能是另一个进程拿走了同一 node id。</p>
 */
public final class NodeLeaseSupervisor {
    /** 数据库短暂故障时的最大重试预算上限，避免无限拖延关闭。 */
    static final Duration MAX_RETRY_BUDGET = Duration.ofSeconds(30);

    private final LeaseRenewal lease;
    private final Duration heartbeat;
    private final Logger logger;
    /** 失租处置回调：reason 为人类可读原因，error 可为 null。 */
    private final BiConsumer<String, Throwable> onLeaseLost;
    private final AtomicBoolean renewInFlight = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();

    public NodeLeaseSupervisor(
            JdbcNodeLease lease, Duration heartbeat, Logger logger,
            BiConsumer<String, Throwable> onLeaseLost
    ) {
        this(new JdbcLeaseRenewal(lease), heartbeat, logger, onLeaseLost);
    }

    NodeLeaseSupervisor(
            LeaseRenewal lease, Duration heartbeat, Logger logger,
            BiConsumer<String, Throwable> onLeaseLost
    ) {
        this.lease = Objects.requireNonNull(lease, "lease");
        this.heartbeat = Objects.requireNonNull(heartbeat, "heartbeat");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.onLeaseLost = Objects.requireNonNull(onLeaseLost, "onLeaseLost");
        if (heartbeat.isZero() || heartbeat.isNegative()) {
            throw new IllegalArgumentException("heartbeat must be positive");
        }
    }

    /** 心跳间隔。 */
    public Duration heartbeat() {
        return heartbeat;
    }

    /**
     * 续期预算：一次心跳失败后仍要能在 token 到期前重试。
     * 取 {@code heartbeat + 5s}，并夹在 {@link #MAX_RETRY_BUDGET} 内。
     */
    public Duration retryBudget() {
        Duration budget = heartbeat.plusSeconds(5L);
        return budget.compareTo(MAX_RETRY_BUDGET) > 0 ? MAX_RETRY_BUDGET : budget;
    }

    /** 是否已因失租而失败关闭（幂等）。 */
    public boolean stopped() {
        return stopped.get();
    }

    /**
     * 执行一次续期判定，可重复调用；失租后不再重复处置。
     *
     * @return true 表示续期成功；false 表示本次未续期但尚未越界（仅告警）
     */
    public CompletionStage<Boolean> renewOnce() {
        if (stopped.get()) return CompletableFuture.completedFuture(false);
        if (!renewInFlight.compareAndSet(false, true)) return CompletableFuture.completedFuture(false);
        return lease.renewAsync().handle((renewed, error) -> {
            renewInFlight.set(false);
            if (error != null) return handleFailure(error);
            if (Boolean.TRUE.equals(renewed)) return true;
            // 明确未能续期：token 已被外部进程围栏或已过期。文档要求停止结算。
            fail("Paper node lease was fenced by another process", null);
            return false;
        });
    }

    private boolean handleFailure(Throwable error) {
        // 数据库短暂故障：只要 token 仍可能在预算内有效，就告警重试而不是立刻关闭。
        if (!stopped.get() && lease.mayStillBeValidFor(retryBudget())) {
            logger.log(Level.WARNING,
                    "Paper node lease heartbeat failed; retrying before expiry: " + error.getMessage());
            return false;
        }
        fail("Paper node lease expired while its database was unavailable", error);
        return false;
    }

    private void fail(String reason, Throwable error) {
        if (!stopped.compareAndSet(false, true)) return;
        onLeaseLost.accept(reason, error);
    }

    /**
     * 启动断言：刚取得的 token 必须仍有有效期，否则说明启动窗口内已被围栏。
     *
     * @return true 表示 token 在租约时长内仍然有效
     */
    public boolean verifyAcquiredToken() {
        if (!lease.mayStillBeValid()) {
            fail("Paper node lease was not confirmed after acquisition", null);
            return false;
        }
        return true;
    }

    /** 注册周期续期任务；调用方提供调度器与执行器（Paper 端为单线程守护线程）。 */
    public AutoCloseable schedule(ScheduledExecutorService executor) {
        var task = executor.scheduleWithFixedDelay(() -> renewOnce().whenComplete((renewed, error) -> {
            if (error != null) logger.log(Level.WARNING, "Paper node lease renewal failed unexpectedly", error);
        }), heartbeat.toMillis(), heartbeat.toMillis(), TimeUnit.MILLISECONDS);
        return () -> task.cancel(false);
    }

    /** 便于替换实现的最小接缝：真实实现是 JdbcNodeLease。 */
    interface LeaseRenewal {
        CompletionStage<Boolean> renewAsync();

        boolean mayStillBeValid();

        boolean mayStillBeValidFor(Duration retryBudget);
    }

    private record JdbcLeaseRenewal(JdbcNodeLease delegate) implements LeaseRenewal {
        @Override
        public CompletionStage<Boolean> renewAsync() {
            return delegate.renewAsync();
        }

        @Override
        public boolean mayStillBeValid() {
            return delegate.mayStillBeValid();
        }

        @Override
        public boolean mayStillBeValidFor(Duration retryBudget) {
            return delegate.mayStillBeValidFor(retryBudget);
        }
    }
}
