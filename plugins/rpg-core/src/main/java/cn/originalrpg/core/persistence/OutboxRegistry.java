package cn.originalrpg.core.persistence;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Transactional Outbox 写入口（G-102）。
 *
 * <p>实现必须把 outbox 记录写进调用方传入的同一个 {@link Connection}，
 * 使「业务变更」与「待投递事件」原子提交：业务回滚时事件不得出现。</p>
 */
public interface OutboxRegistry {

    /**
     * 在同一事务里登记一条待投递事件。
     *
     * <p>{@code idempotencyKey} 全局唯一。同一键重复登记返回 false，调用方
     * 不得据此失败（说明该事件已存在，投递仍由消费者保证）。</p>
     *
     * @return true = 本次新登记；false = 幂等键已存在，未新增记录
     */
    boolean publish(
            Connection connection,
            String aggregateType,
            String aggregateId,
            String idempotencyKey,
            String payload
    ) throws SQLException;
}
