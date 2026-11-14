package com.skydispatch.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 航路点（航路明细，按 seq 有序），对应表 {@code flight_waypoint}。
 *
 * <p>建模取舍：
 * <ul>
 *   <li>用业务序号 {@code seq}（从 0 开始）表达顺序，而不是靠主键 id 排序：
 *       重新规划或补点时 id 顺序不再可靠，seq 才是算法给出的真实次序。</li>
 *   <li>{@code (route_id, seq)} 建唯一约束，从数据库层面防止同一航路出现重复序号。</li>
 *   <li>本表只有追加和按 route 整体删除两种用法，因此不设 created_at/updated_at，
 *       少两列能给这张最"多行"的表省下可观空间。</li>
 * </ul>
 */
@Entity
@Table(
        name = "flight_waypoint",
        uniqueConstraints = @UniqueConstraint(name = "uk_waypoint_route_seq", columnNames = {"route_id", "seq"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FlightWaypoint {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属航路 id；只存 id，删除航路时在仓储层用 deleteByRouteId 清理明细。 */
    @Column(name = "route_id", nullable = false)
    private Long routeId;

    /** 航路点序号，从 0 开始。 */
    @Column(name = "seq", nullable = false)
    private Integer seq;

    /** 纬度。 */
    @Column(name = "lat", nullable = false)
    private Double lat;

    /** 经度。 */
    @Column(name = "lon", nullable = false)
    private Double lon;

    /** 该点飞行高度(m)。 */
    @Column(name = "altitude_m", nullable = false)
    private BigDecimal altitudeM;

    /** 从起点到该点的累计距离(m)，用于换算任务进度百分比。 */
    @Column(name = "cumulative_distance_m", nullable = false)
    private BigDecimal cumulativeDistanceM;

    /** 预计到达时刻（相对起飞的秒数），可为空。 */
    @Column(name = "eta_seconds")
    private Integer etaSeconds;
}
