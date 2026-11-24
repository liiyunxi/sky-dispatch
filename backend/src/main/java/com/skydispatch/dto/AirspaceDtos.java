package com.skydispatch.dto;

import com.skydispatch.common.GeoPoint;
import com.skydispatch.domain.entity.AirspaceZone;
import com.skydispatch.domain.enums.ConflictSeverity;
import com.skydispatch.domain.enums.ZoneShape;
import com.skydispatch.domain.enums.ZoneType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 空域（禁飞区/限飞区/走廊）相关的请求/响应模型。
 */
public final class AirspaceDtos {

    private AirspaceDtos() {
    }

    /**
     * 新建空域。
     *
     * <p>圆形区填 {@code center + radiusM}；多边形区填 {@code points}（≥3 个点，会自动转 WKT 存库）。
     * 两种形状的必填项由 Service 校验，因为"哪个字段必填"取决于 shapeType。
     *
     * <p>注意这里刻意<b>没有</b>写 {@code List<@Valid GeoDto>}：
     * 在 record 头部对类型参数加 {@code @Valid} 会让 javac 报
     * "type annotation not expected here"，而这属于<b>解析期</b>错误——
     * 一旦有解析错误，javac 会跳过整个注解处理阶段，Lombok 就不再生成
     * {@code builder()}，最终报出一大片"找不到 builder()"的假象错误。
     * 顶点校验改在 {@link com.skydispatch.service.AirspaceService} 里显式做。
     */
    public record ZoneRequest(
            /**
             * 区域编码，**可留空**。留空时按区域类型自动生成（如 NFZ-0003），
             * 前缀取自类型（禁飞区 NFZ / 限飞区 RFA / 走廊 CRD / 警示区 WRN）。
             * 需要对接既有命名规范（如"虹桥机场禁飞区-01"）时再显式传入。
             */
            @Size(max = 32) String code,
            /** 区域名称，**可留空**。留空时用「类型中文名 + 编号」拼一个，如「禁飞区 NFZ-0003」。 */
            @Size(max = 128) String name,
            @NotNull(message = "区域类型不能为空") ZoneType zoneType,
            @NotNull(message = "形状不能为空") ZoneShape shapeType,
            @Valid CommonDtos.GeoDto center,
            BigDecimal radiusM,
            List<CommonDtos.GeoDto> points,
            BigDecimal minAltitudeM,
            BigDecimal maxAltitudeM,
            /** 严重度，**可留空**。留空时按区域类型取默认值（禁飞区最严，走廊最松）。 */
            ConflictSeverity severity,
            Boolean enabled,
            Instant effectiveFrom,
            Instant effectiveTo,
            /** 仿真时间窗（秒）：临时管制区到点自动生效/解除。 */
            Long activeFromSimSec,
            Long activeToSimSec,
            @Size(max = 255) String remark
    ) {
    }

    /** 空域响应。多边形同时返回点列表，方便前端直接画图。 */
    public record Response(
            Long id,
            String code,
            String name,
            ZoneType zoneType,
            ZoneShape shapeType,
            CommonDtos.GeoDto center,
            BigDecimal radiusM,
            List<CommonDtos.GeoDto> points,
            BigDecimal minAltitudeM,
            BigDecimal maxAltitudeM,
            ConflictSeverity severity,
            Boolean enabled,
            Instant effectiveFrom,
            Instant effectiveTo,
            Long activeFromSimSec,
            Long activeToSimSec,
            String remark
    ) {
        /**
         * 由实体转换。
         *
         * @param polygon 已解析的多边形顶点（内部表示用 {@link GeoPoint}，
         *                对外统一用 {@link CommonDtos.GeoDto}，转换在这里收口）
         */
        public static Response from(AirspaceZone z, List<GeoPoint> polygon) {
            CommonDtos.GeoDto center = z.getCenterLat() == null ? null
                    : new CommonDtos.GeoDto(z.getCenterLat(), z.getCenterLon(), null);
            List<CommonDtos.GeoDto> points = polygon == null ? List.of()
                    : polygon.stream().map(p -> new CommonDtos.GeoDto(p.lat(), p.lon(), null)).toList();
            return new Response(z.getId(), z.getCode(), z.getName(), z.getZoneType(), z.getShapeType(),
                    center, z.getRadiusM(), points, z.getMinAltitudeM(), z.getMaxAltitudeM(),
                    z.getSeverity(), z.getEnabled(), z.getEffectiveFrom(), z.getEffectiveTo(),
                    z.getActiveFromSimSec(), z.getActiveToSimSec(), z.getRemark());
        }
    }
}
