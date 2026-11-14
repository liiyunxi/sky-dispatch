package com.skydispatch.event;

import com.skydispatch.domain.enums.EventSeverity;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 领域事件：系统里"发生了一件事"的统一载体。
 *
 * <p>它有两个身份：
 * <ol>
 *   <li>落库成 {@code event_log} 的一行，用于回放与审计；</li>
 *   <li>作为 Spring 应用事件发布，由监听器在<b>事务提交之后</b>推送给 WebSocket 客户端。</li>
 * </ol>
 *
 * <p>为什么强调"事务提交之后"？如果先推送再提交，事务一旦回滚，
 * 前端就收到了一个从未真正发生的事件——这类 bug 极难排查。
 */
public record SkyEvent(
        String eventType,
        EventSeverity severity,
        String message,
        Long runId,
        Long taskId,
        Long orderId,
        Long droneId,
        BigDecimal simTimeS,
        String payload,
        Instant ts
) {

    /** 最常用的构造入口：只给必要信息，其余留空。 */
    public static SkyEvent of(String eventType, EventSeverity severity, String message) {
        return new SkyEvent(eventType, severity, message, null, null, null, null, null, null, Instant.now());
    }

    public SkyEvent withTask(Long taskId) {
        return new SkyEvent(eventType, severity, message, runId, taskId, orderId, droneId, simTimeS, payload, ts);
    }

    public SkyEvent withOrder(Long orderId) {
        return new SkyEvent(eventType, severity, message, runId, taskId, orderId, droneId, simTimeS, payload, ts);
    }

    public SkyEvent withDrone(Long droneId) {
        return new SkyEvent(eventType, severity, message, runId, taskId, orderId, droneId, simTimeS, payload, ts);
    }

    public SkyEvent withRun(Long runId, BigDecimal simTimeS) {
        return new SkyEvent(eventType, severity, message, runId, taskId, orderId, droneId, simTimeS, payload, ts);
    }

    public SkyEvent withPayload(String payload) {
        return new SkyEvent(eventType, severity, message, runId, taskId, orderId, droneId, simTimeS, payload, ts);
    }

    public SkyEvent withSeverity(EventSeverity newSeverity) {
        return new SkyEvent(eventType, newSeverity, message, runId, taskId, orderId, droneId, simTimeS, payload, ts);
    }
}
