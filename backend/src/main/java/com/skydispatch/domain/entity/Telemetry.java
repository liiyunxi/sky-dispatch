package com.skydispatch.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 遥测（无人机按 tick 上报的时序数据），对应表 {@code telemetry}。
 *
 * <p>建模取舍：
 * <ul>
 *   <li>这是写入最频繁、增长最快的表，因此除了主键不建任何关联对象，
 *       全部用普通列（runId/taskId/droneId），让批量插入尽量便宜。</li>
 *   <li>{@code ts} 不用 {@code @CreationTimestamp}：仿真引擎回放历史时会把时间拨到过去，
 *       必须由引擎显式赋值，交给 Hibernate 自动写"当前时间"就失真了。</li>
 *   <li>本表只追加不修改，所以没有 updated_at。</li>
 * </ul>
 */
@Entity
@Table(name = "telemetry")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Telemetry {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属仿真运行 id，可按运行批量清理。 */
    @Column(name = "run_id")
    private Long runId;

    /** 所属任务 id。 */
    @Column(name = "task_id")
    private Long taskId;

    /** 无人机 id（该表非空，因为遥测一定来自某架机）。 */
    @Column(name = "drone_id", nullable = false)
    private Long droneId;

    /** 采样时间戳；由仿真引擎显式赋值，故不加 @CreationTimestamp。 */
    @Column(name = "ts", nullable = false)
    private Instant ts;

    /** 仿真时间（秒），与真实时间区分开，回放时按它对齐。 */
    @Column(name = "sim_time_s")
    private BigDecimal simTimeS;

    /** 第几个 tick，同一次运行内单调递增。 */
    @Column(name = "step_index")
    private Long stepIndex;

    /** 纬度。 */
    @Column(name = "lat")
    private Double lat;

    /** 经度。 */
    @Column(name = "lon")
    private Double lon;

    /** 高度(m)。 */
    @Column(name = "altitude_m")
    private BigDecimal altitudeM;

    /** 速度(m/s)。 */
    @Column(name = "speed_mps")
    private BigDecimal speedMps;

    /** 航向角。 */
    @Column(name = "heading_deg")
    private BigDecimal headingDeg;

    /** 电量百分比。 */
    @Column(name = "battery_pct")
    private BigDecimal batteryPct;

    /** 任务进度百分比。 */
    @Column(name = "progress_pct")
    private BigDecimal progressPct;
}
