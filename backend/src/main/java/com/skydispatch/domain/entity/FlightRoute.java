package com.skydispatch.domain.entity;

import com.skydispatch.domain.enums.RouteStatus;
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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 航路（一次路径规划的结果头），对应表 {@code flight_route}。
 *
 * <p>为什么"头 + 明细"拆两张表：航路的元信息（总距离、预计能耗、算法耗时）和
 * 成百上千个航路点性质不同；头表给看板和任务执行用，明细表只在回放/绘线时才查，
 * 拆开后常用查询不必拖着大结果集。
 *
 * <p>rawResponse 保存算法服务原始 JSON，是排错的关键证据：算错了能直接对比输入输出，
 * 不用靠日志猜。
 */
@Entity
@Table(name = "flight_route")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FlightRoute {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属订单 id（只存 id，不建对象关联）。 */
    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /** 执行该航路的无人机 id；规划时可能还没定机，故可空。 */
    @Column(name = "drone_id")
    private Long droneId;

    /** 规划算法名，如 ASTAR；用于对比不同算法效果。 */
    @Column(name = "algorithm", nullable = false, length = 32)
    private String algorithm;

    /** 航路状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RouteStatus status;

    /** 航路总长度(m)。 */
    @Column(name = "distance_m", nullable = false)
    private BigDecimal distanceM;

    /** 预计耗时(秒)。 */
    @Column(name = "est_duration_s", nullable = false)
    private Integer estDurationS;

    /** 预计能耗(Wh)。 */
    @Column(name = "est_energy_wh", nullable = false)
    private BigDecimal estEnergyWh;

    /** 航路点个数，避免为拿数量而查明细表。 */
    @Column(name = "waypoint_count", nullable = false)
    private Integer waypointCount;

    /** 算法栅格分辨率(m)，影响精度与耗时。 */
    @Column(name = "grid_size_m")
    private BigDecimal gridSizeM;

    /** 算法耗时(毫秒)，性能观测用。 */
    @Column(name = "plan_elapsed_ms")
    private Integer planElapsedMs;

    /** 算法服务原始 JSON，长度 8000 对应 SQL 的 VARCHAR(8000)，排错用。 */
    @Column(name = "raw_response", length = 8000)
    private String rawResponse;

    /** 创建时间，仅写入一次。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 更新时间。 */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
