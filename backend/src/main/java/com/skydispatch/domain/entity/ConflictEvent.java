package com.skydispatch.domain.entity;

import com.skydispatch.domain.enums.ConflictSeverity;
import com.skydispatch.domain.enums.ConflictStatus;
import com.skydispatch.domain.enums.ConflictType;
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
 * 冲突事件（机-机冲突 / 闯入空域 / 超时与电量风险），对应表 {@code conflict_event}。
 *
 * <p>建模取舍：把四类风险合到一张表，用 {@code conflictType} 区分，而不是建四张表。
 * 因为它们的生命周期完全一致（检测 → 告警 → 处置 → 关闭），
 * 合用一张表就能共用一套查询与看板指标，按类型读不同的关联 id 即可。
 *
 * <p>同时记录"相对几何量"（水平/垂直距离、接近速度、冲突剩余时间）而不是只记结果，
 * 是为了让复盘能看到"当时差多少"，用于调参。
 */
@Entity
@Table(name = "conflict_event")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConflictEvent {

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

    /** 主动方（或唯一涉及的）无人机 id。 */
    @Column(name = "drone_a_id", nullable = false)
    private Long droneAId;

    /** 对方无人机 id，仅机-机冲突有值。 */
    @Column(name = "drone_b_id")
    private Long droneBId;

    /** 涉及空域 id，仅机-区冲突有值。 */
    @Column(name = "zone_id")
    private Long zoneId;

    /** 冲突类型，决定读哪几个关联 id。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "conflict_type", nullable = false, length = 20)
    private ConflictType conflictType;

    /** 严重级别。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 10)
    private ConflictSeverity severity;

    /** 处置状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ConflictStatus status;

    /** 水平间隔(m)。 */
    @Column(name = "horizontal_distance_m")
    private BigDecimal horizontalDistanceM;

    /** 垂直间隔(m)。 */
    @Column(name = "vertical_distance_m")
    private BigDecimal verticalDistanceM;

    /** 相对接近速度(m/s)，越大越紧急。 */
    @Column(name = "closing_speed_mps")
    private BigDecimal closingSpeedMps;

    /** 预计多久后冲突(秒)，用于排序处置优先级。 */
    @Column(name = "time_to_conflict_s")
    private BigDecimal timeToConflictS;

    /** 事发点纬度。 */
    @Column(name = "lat")
    private Double lat;

    /** 事发点经度。 */
    @Column(name = "lon")
    private Double lon;

    /** 检测时间；由检测器显式赋值，不加 @CreationTimestamp。 */
    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    /** 处置说明（如"下降 20m 避让"）。 */
    @Column(name = "resolution", length = 255)
    private String resolution;

    /** 处置完成时间。 */
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** 明细 JSON（各距离分项、判定阈值等），长度 8000 对应 SQL 的 VARCHAR(8000)。 */
    @Column(name = "detail", length = 8000)
    private String detail;
}
