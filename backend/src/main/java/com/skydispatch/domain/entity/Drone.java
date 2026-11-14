package com.skydispatch.domain.entity;

import com.skydispatch.domain.enums.DroneStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
 * 无人机（单机台账 + 实时状态），对应表 {@code drone}。
 *
 * <p>建模取舍：
 * <ul>
 *   <li>除了型号之外，其余关联（currentTaskId 等）只保留外键 id 字段，不做 {@code @ManyToOne}。
 *       无人机是被高频读写的表（仿真每拍都更新位置电量），对象图越小，序列化和缓存越省心，
 *       也避免懒加载在事务外抛 LazyInitializationException。</li>
 *   <li>这里保留了 {@code @ManyToOne} 指向 DroneModel：型号是必然用到的只读字典，
 *       用 LAZY 关联既省一次手写 join 查询，也不会引发循环引用。</li>
 *   <li>{@code version} 加 {@code @Version}：仿真线程和用户请求可能同时改同一架无人机，
 *       乐观锁能让后到的写入失败重试，避免位置/电量被静默覆盖。</li>
 * </ul>
 */
@Entity
@Table(name = "drone")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Drone {

    /** 主键，对应 BIGSERIAL。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 机身编号，如 UAV-003；业务上的唯一标识。 */
    @Column(name = "code", nullable = false, unique = true, length = 32)
    private String code;

    /** 所属型号（只读字典），懒加载避免每次查机都带上型号全部列。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "model_id", nullable = false)
    private DroneModel model;

    /** 当前状态，用字符串存枚举名，可读性优先且与 SQL 的 CHECK 约束一致。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DroneStatus status;

    /** 剩余电量百分比(0-100)。 */
    @Column(name = "battery_pct", nullable = false)
    private BigDecimal batteryPct;

    /** 当前纬度；经纬度用 Double，与 DOUBLE PRECISION 对应。 */
    @Column(name = "current_lat")
    private Double currentLat;

    /** 当前经度。 */
    @Column(name = "current_lon")
    private Double currentLon;

    /** 当前高度(m)。 */
    @Column(name = "altitude_m", nullable = false)
    private BigDecimal altitudeM;

    /** 航向角(0-360)。 */
    @Column(name = "heading_deg")
    private BigDecimal headingDeg;

    /** 当前速度(m/s)。 */
    @Column(name = "speed_mps", nullable = false)
    private BigDecimal speedMps;

    /** 机巢（起降点）纬度，返航时以它为终点。 */
    @Column(name = "home_lat")
    private Double homeLat;

    /** 机巢纬度对应的经度。 */
    @Column(name = "home_lon")
    private Double homeLon;

    /** 当前执行的任务 id；只存 id，避免 drone 与 dispatch_task 互相引用成环。 */
    @Column(name = "current_task_id")
    private Long currentTaskId;

    /** 累计飞行时长(分钟)，用于维保周期统计。 */
    @Column(name = "total_flight_min", nullable = false)
    private BigDecimal totalFlightMin;

    /** 累计航程(km)。 */
    @Column(name = "total_distance_km", nullable = false)
    private BigDecimal totalDistanceKm;

    /** 累计完成任务数。 */
    @Column(name = "total_tasks", nullable = false)
    private Integer totalTasks;

    /** 最近一次心跳时间，超过阈值判定为 OFFLINE。 */
    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    /** 乐观锁版本号，初始 0；每次成功更新由 Hibernate 自增。 */
    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;

    /** 创建时间，仅写入一次。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 更新时间，由 Hibernate 自动刷新。 */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
