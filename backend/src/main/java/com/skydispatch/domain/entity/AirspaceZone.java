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
