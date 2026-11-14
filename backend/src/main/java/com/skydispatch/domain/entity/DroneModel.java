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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 无人机型号（字典表），对应表 {@code drone_model}。
 *
 * <p>为什么把它单独建表：型号描述的是"这类机器能干什么"（载重、速度、能耗），
 * 属于相对静态的字典数据；把能力和单机状态分开，换机型时只改 drone.model_id，
 * 既不用动无人机台账，也不用重复存一遍性能参数。
 *
 * <p>能耗参数（energyPerKmWh / hoverPowerW）单独建字段，是为了让调度算法能直接算电耗，
 * 而不必把公式硬编码在 Java 里。
 */
@Entity
@Table(name = "drone_model")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DroneModel {

    /** 主键，对应 BIGSERIAL。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 型号编码，如 SKY-X4；数据库有唯一约束，不允许多余字段自然键。 */
    @Column(name = "code", nullable = false, unique = true, length = 32)
    private String code;

    /** 型号名称。 */
    @Column(name = "name", nullable = false, length = 64)
    private String name;

    /** 最大载重(kg)，用 BigDecimal 保证与 NUMERIC 一致、无浮点误差。 */
    @Column(name = "max_payload_kg", nullable = false)
    private BigDecimal maxPayloadKg;

    /** 巡航速度(m/s)。 */
    @Column(name = "cruise_speed_mps", nullable = false)
    private BigDecimal cruiseSpeedMps;

    /** 满电最长飞行时长(分钟)，用于电量可行性校验。 */
    @Column(name = "max_flight_min", nullable = false)
    private Integer maxFlightMin;

    /** 电池容量(Wh)。 */
    @Column(name = "battery_capacity_wh", nullable = false)
    private BigDecimal batteryCapacityWh;

    /** 巡航能耗(Wh/km)，计算航路预计能耗的核心系数。 */
    @Column(name = "energy_per_km_wh", nullable = false)
    private BigDecimal energyPerKmWh;

    /** 悬停功率(W)，配送投放阶段会额外耗电。 */
    @Column(name = "hover_power_w", nullable = false)
    private BigDecimal hoverPowerW;

    /** 最大升限(m)，与空域高度上下限一起做越界判断。 */
    @Column(name = "max_altitude_m", nullable = false)
    private Integer maxAltitudeM;

    /** 是否启用；下线的型号不参与派单，用软删除保留历史引用。 */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    /** 备注。 */
    @Column(name = "remark", length = 255)
    private String remark;

    /** 创建时间，由 Hibernate 在 insert 时写入，之后不允许更新。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 更新时间，由 Hibernate 在每次 update 时刷新。 */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
