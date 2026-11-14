package com.skydispatch.domain.entity;

import com.skydispatch.domain.enums.TaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 配送任务（订单 × 无人机 × 航路的执行实例），对应表 {@code dispatch_task}。
 *
 * <p>为什么任务和订单要分开：一个订单可能因失败重派而产生多条任务，
 * 订单只保留最终结果，任务则记录每一次执行的完整过程指标。
 * 因此这里同时存"计划值"和"实际值"（planned/actual、consumed），
 * 复盘时可以直接算偏差，看算法预估准不准。
 */
@Entity
@Table(name = "dispatch_task")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DispatchTask {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 任务号，如 ST20260101001；唯一，便于日志与外部系统对齐。 */
    @Column(name = "task_no", nullable = false, unique = true, length = 40)
    private String taskNo;

    /** 来源订单 id。 */
    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /** 执行无人机 id。 */
    @Column(name = "drone_id", nullable = false)
    private Long droneId;

    /** 使用航路 id；任务生成时可能尚未规划完成，故可空。 */
    @Column(name = "route_id")
    private Long routeId;

    /** 派单策略名（与 DispatchStrategyType 取值一致），用于策略效果对比。 */
    @Column(name = "strategy", nullable = false, length = 32)
    private String strategy;

    /** 任务状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TaskStatus status;

    /** 进度百分比(0-100)，由当前航路点累计距离换算。 */
    @Column(name = "progress_pct", nullable = false)
    private BigDecimal progressPct;

    /** 当前所在航路点序号，断点续飞和回放定位都用它。 */
    @Column(name = "current_waypoint_seq", nullable = false)
    private Integer currentWaypointSeq;

    /** 规划距离(m)。 */
    @Column(name = "planned_distance_m", nullable = false)
    private BigDecimal plannedDistanceM;

    /** 实际飞行距离(m)。 */
    @Column(name = "actual_distance_m", nullable = false)
    private BigDecimal actualDistanceM;

    /** 规划能耗(Wh)。 */
    @Column(name = "planned_energy_wh", nullable = false)
    private BigDecimal plannedEnergyWh;

    /** 实际消耗能耗(Wh)。 */
    @Column(name = "consumed_energy_wh", nullable = false)
    private BigDecimal consumedEnergyWh;

    /** 起飞时电量百分比。 */
    @Column(name = "battery_start_pct")
    private BigDecimal batteryStartPct;

    /** 结束时电量百分比。 */
    @Column(name = "battery_end_pct")
    private BigDecimal batteryEndPct;

    /** 派单时间。 */
    @Column(name = "assigned_at")
    private Instant assignedAt;

    /** 起飞时间。 */
    @Column(name = "started_at")
    private Instant startedAt;

    /** 结束时间（完成或失败）。 */
    @Column(name = "finished_at")
    private Instant finishedAt;

    /** 实际耗时(秒)，与 finishedAt-startedAt 互为校验。 */
    @Column(name = "duration_s")
    private Integer durationS;

    /** 失败原因。 */
    @Column(name = "fail_reason", length = 255)
    private String failReason;

    /**
     * 乐观锁版本号。
     *
     * <p>仿真推进与"人工中止"可能同时改同一条任务，版本号让后提交的一方失败，
     * 避免"任务已中止又被推进回配送中"这类静默覆盖。
     */
    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;

    /** 创建时间，仅写入一次。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 更新时间。 */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
