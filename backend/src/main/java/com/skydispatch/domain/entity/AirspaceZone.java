package com.skydispatch.domain.entity;

import com.skydispatch.domain.enums.ConflictSeverity;
import com.skydispatch.domain.enums.ZoneShape;
import com.skydispatch.domain.enums.ZoneType;
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
 * 空域（禁飞区 / 限飞区 / 航路走廊 / 警告区），对应表 {@code airspace_zone}。
 *
 * <p>建模取舍：不在实体里做几何对象映射，而是"形状标志 + 两组可空列"：
 * {@code shapeType=CIRCLE} 时读中心点与半径，{@code POLYGON} 时读 WKT 文本。
 * 这样不引入 PostGIS 也能跑，后续加 geometry 列时只需在原有基础上扩展。
 *
 * <p>时间窗（effectiveFrom/effectiveTo）与高度区间（min/maxAltitudeM）单独建模，
 * 因为临时管制区是"某时段、某高度才生效"，规划时两者都要参与判断。
 */
@Entity
@Table(name = "airspace_zone")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AirspaceZone {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 空域编码，唯一。 */
    @Column(name = "code", nullable = false, unique = true, length = 32)
    private String code;

    /** 空域名称，如"市中心禁飞区"。 */
    @Column(name = "name", nullable = false, length = 128)
    private String name;

    /** 空域类型，决定规划器是"绕开"还是"推荐通行"。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "zone_type", nullable = false, length = 16)
    private ZoneType zoneType;

    /** 几何形状，决定下面读写哪几列。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "shape_type", nullable = false, length = 16)
    private ZoneShape shapeType;

    /** 圆心纬度（仅 CIRCLE 有效）。 */
    @Column(name = "center_lat")
    private Double centerLat;

    /** 圆心经度（仅 CIRCLE 有效）。 */
    @Column(name = "center_lon")
    private Double centerLon;

    /** 半径(m)（仅 CIRCLE 有效）。 */
    @Column(name = "radius_m")
    private BigDecimal radiusM;

    /** 多边形 WKT 文本（仅 POLYGON 有效），长度 8000 对应 SQL 的 VARCHAR(8000)。 */
    @Column(name = "polygon_wkt", length = 8000)
    private String polygonWkt;

    /** 生效高度下限(m)。 */
    @Column(name = "min_altitude_m", nullable = false)
    private BigDecimal minAltitudeM;

    /** 生效高度上限(m)。 */
    @Column(name = "max_altitude_m", nullable = false)
    private BigDecimal maxAltitudeM;

    /** 严重级别，用于告警分级。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 10)
    private ConflictSeverity severity;

    /** 生效起始时间；为空表示长期有效。 */
    @Column(name = "effective_from")
    private Instant effectiveFrom;

    /** 生效结束时间；为空表示长期有效。 */
    @Column(name = "effective_to")
    private Instant effectiveTo;

    /**
     * 生效起始仿真时刻（秒）；为空表示从仿真开始即生效。
     *
     * <p>与 {@code effectiveFrom/To}（真实时钟）并行：仿真里的临时管制需要按
     * <b>仿真时间</b>开关，否则 8 倍速下窗口会被压缩得毫无意义。
     */
    @Column(name = "active_from_sim_sec")
    private Long activeFromSimSec;

    /** 生效结束仿真时刻（秒）；为空表示不自动关闭。 */
    @Column(name = "active_to_sim_sec")
    private Long activeToSimSec;

    /**
     * 在给定仿真时刻是否生效。
     *
     * <p>把时间窗判断放在实体上，规划、冲突检测与单元测试共用同一套边界语义，
     * 避免"规划认为生效、冲突检测认为没生效"这种口径分裂。
     */
    public boolean activeAt(double simTimeS) {
        if (activeFromSimSec != null && simTimeS < activeFromSimSec) {
            return false;
        }
        return activeToSimSec == null || simTimeS <= activeToSimSec;
    }

    /** 是否启用；停用而不删除，保留历史冲突引用。 */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    /** 备注。 */
    @Column(name = "remark", length = 255)
    private String remark;

    /** 创建时间，仅写入一次。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 更新时间。 */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
