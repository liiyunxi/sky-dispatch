package com.skydispatch.domain.entity;

import com.skydispatch.domain.enums.EventSeverity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 事件日志（时间线 / 回放 / 审计），对应表 {@code event_log}。
 *
 * <p>建模取舍：{@code eventType} 保留为字符串而不是枚举。
 * 因为事件类型会随功能迭代不断增加（ORDER_CREATED / TASK_ASSIGNED / CONFLICT_DETECTED …），
 * 若做成枚举，每加一个类型都要改 Java 代码并重新发版；用字符串则只需约定命名规范。
 * 相对地，{@code severity} 取值稳定且需要分级过滤，因此用枚举。
 *
 * <p>同时冗余 runId/taskId/orderId/droneId 四个维度，是为了让"按任一维度查时间线"
 * 都不必 join 其他表——日志表最怕查询链路过长。
 */
@Entity
@Table(name = "event_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventLog {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属仿真运行 id。 */
    @Column(name = "run_id")
    private Long runId;

    /** 关联任务 id。 */
    @Column(name = "task_id")
    private Long taskId;

    /** 关联订单 id。 */
    @Column(name = "order_id")
    private Long orderId;

    /** 关联无人机 id。 */
    @Column(name = "drone_id")
    private Long droneId;

    /** 事件类型字符串，如 ORDER_CREATED；刻意不做成枚举，便于扩展。 */
    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;

    /** 日志级别。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 10)
    private EventSeverity severity;

    /** 事件描述。 */
    @Column(name = "message", nullable = false, length = 500)
    private String message;

    /** 附加 JSON，回放时用它还原上下文；长度 8000 对应 SQL 的 VARCHAR(8000)。 */
    @Column(name = "payload", length = 8000)
    private String payload;

    /** 事件发生时的仿真时间（秒）。 */
    @Column(name = "sim_time_s")
    private BigDecimal simTimeS;

    /** 事件时间；由写入方显式赋值（回放时会填写历史时间），故不加 @CreationTimestamp。 */
    @Column(name = "ts", nullable = false)
    private Instant ts;
}
