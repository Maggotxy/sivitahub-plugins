package cn.originalrpg.core.persistence;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Transactional Outbox 后台消费者（G-102）。
 *
 * <p>投递语义：</p>
 * <ul>
 *   <li>按 {@code aggregate_type} 路由到 {@link OutboxHandler}；</li>
 *   <li>投递成功 → 写 {@code processed_at}，该事件不再被领取；</li>
 *   <li>投递失败 → 保留记录并累加 {@code attempts} + 记录 {@code last_error}，
 *       下一轮退避后再试；失败绝不影响业务事务的已提交结果；</li>
 *   <li>{@code idempotency_key} 唯一：producer 重复登记只保留一条，consumer
 *       对同一条重复投递由 handler 自行保证幂等（见 {@link OutboxHandler}）。</li>
 * </ul>
 *
 * <p>默认关闭：由 {@code outbox.dispatch.enabled} 控制，单实例部署下不启动轮询，
 * 零行为影响。</p>
 */
public final class OutboxDispatcher {
    private static final Logger LOGGER = Logger.getLogger(OutboxDispatcher.class.getName());
    private final DataSource dataSource;
    private final Map<String, OutboxHandler> handlers;
    /** 已观测到的投递成功/失败/退避计数，供运维只读查看。 */
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong deferred = new AtomicLong();

    public OutboxDispatcher(DataSource dataSource, List<OutboxHandler> handlers) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        Map<String, OutboxHandler> byType = new LinkedHashMap<>();
        for (OutboxHandler handler : Objects.requireNonNull(handlers, "handlers")) {
            OutboxHandler previous = byType.put(handler.aggregateType(), handler);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "Duplicate outbox handler for " + handler.aggregateType());
            }
        }
        this.handlers = Map.copyOf(byType);
    }

    public Map<String, OutboxHandler> handlers() {
        return handlers;
    }

    public Metrics metrics() {
        return new Metrics(delivered.get(), failed.get(), deferred.get());
    }

    /**
     * 领取并投递一批待处理事件。返回本轮结果，供定时任务与测试断言。
     *
     * <p>投递在 {@code executor} 之外同步执行（调用方已是后台线程），避免
     * 在 JDBC 事务里嵌套线程池造成连接被占而不释放。</p>
     */
    public DispatchResult dispatchBatch(int maximumEvents) {
        if (maximumEvents <= 0) return new DispatchResult(0, 0, 0, 0);
        List<OutboxEvent> pending = pendingEvents(maximumEvents);
        int sent = 0;
        int retried = 0;
        int skipped = 0;
        for (OutboxEvent event : pending) {
            OutboxHandler handler = handlers.get(event.aggregateType());
            if (handler == null) {
                // 未知类型是配置错误，留记录并显式报错，不静默丢弃。
                markFailed(event.eventId(), "no handler for aggregate type " + event.aggregateType());
                retried++;
                continue;
            }
            try {
                boolean handled = handler.handle(event);
                if (handled) {
                    markProcessed(event.eventId());
                    delivered.incrementAndGet();
                    sent++;
                } else {
                    // handler 主动声明「尚未就绪」，退避重试而不算失败。
                    recordDeferral(event.eventId(), "handler deferred");
                    deferred.incrementAndGet();
                    skipped++;
                }
            } catch (RuntimeException exception) {
                failed.incrementAndGet();
                retried++;
                recordDeferral(event.eventId(), summarize(exception));
                LOGGER.log(Level.WARNING, "Outbox delivery failed for event "
                        + event.eventId() + " (" + event.aggregateType() + ")", exception);
            }
        }
        return new DispatchResult(pending.size(), sent, retried, skipped);
    }

    private List<OutboxEvent> pendingEvents(int maximumEvents) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT event_id, aggregate_type, aggregate_id, idempotency_key,
                            payload, attempts, created_at
                     FROM rpg_outbox
                     WHERE processed_at IS NULL
                     ORDER BY created_at, event_id
                     LIMIT ?
                     """)) {
            query.setInt(1, maximumEvents);
            try (ResultSet result = query.executeQuery()) {
                List<OutboxEvent> events = new java.util.ArrayList<>();
                while (result.next()) {
                    Timestamp created = result.getTimestamp("created_at");
                    events.add(new OutboxEvent(
                            result.getString("event_id"),
                            result.getString("aggregate_type"),
                            result.getString("aggregate_id"),
                            result.getString("idempotency_key"),
                            result.getString("payload"),
                            result.getInt("attempts"),
                            created == null ? Instant.EPOCH : created.toInstant(),
                            null));
                }
                connection.rollback();
                return events;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not read pending outbox events", exception);
        }
    }

    private void markProcessed(String eventId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE rpg_outbox
                     SET processed_at = CURRENT_TIMESTAMP, attempts = attempts + 1, last_error = NULL
                     WHERE event_id = ? AND processed_at IS NULL
                     """)) {
            update.setString(1, eventId);
            if (update.executeUpdate() != 1) {
                throw new IllegalStateException("Outbox event was already processed: " + eventId);
            }
            connection.commit();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not mark outbox event processed: " + eventId, exception);
        }
    }

    private void markFailed(String eventId, String reason) {
        recordDeferral(eventId, reason);
        LOGGER.warning("Outbox event " + eventId + " has no registered handler: " + reason);
    }

    private void recordDeferral(String eventId, String reason) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE rpg_outbox
                     SET attempts = attempts + 1, last_error = ?
                     WHERE event_id = ? AND processed_at IS NULL
                     """)) {
            String trimmed = reason == null ? "" : reason.substring(0, Math.min(reason.length(), 240));
            update.setString(1, trimmed);
            update.setString(2, eventId);
            update.executeUpdate();
            connection.commit();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not record outbox retry: " + eventId, exception);
        }
    }

    private static String summarize(RuntimeException exception) {
        String message = exception.getMessage();
        return exception.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /** 一轮投递的结果。 */
    public record DispatchResult(int claimed, int delivered, int retried, int deferred) {
    }

    /** 运维只读指标。 */
    public record Metrics(long delivered, long failed, long deferred) {
    }

    /** 单条事件的投递处理器。 */
    public interface OutboxHandler {
        /** 该 handler 负责的 {@code aggregate_type}。 */
        String aggregateType();

        /**
         * 投递一条事件。
         *
         * @return true = 已投递，可标记完成；false = 暂不可投递，退避重试
         */
        boolean handle(OutboxEvent event) throws RuntimeException;
    }
}
