package com.skydispatch.service;

import com.skydispatch.domain.entity.AirspaceZone;
import com.skydispatch.domain.enums.ConflictSeverity;
import com.skydispatch.domain.enums.ZoneShape;
import com.skydispatch.domain.enums.ZoneType;
import com.skydispatch.dto.AirspaceDtos;
import com.skydispatch.dto.CommonDtos;
import com.skydispatch.repository.AirspaceZoneRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 空域编辑：编码不变、时间窗可改、形状切换不残留旧字段。 */
class AirspaceServiceUpdateTest {

    private AirspaceZoneRepository repository;
    private AirspaceService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(AirspaceZoneRepository.class);
        service = new AirspaceService(repository);
        Mockito.when(repository.save(Mockito.any(AirspaceZone.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private AirspaceZone existingCircle() {
        return AirspaceZone.builder()
                .id(1L)
                .code("NFZ-0001")
                .name("旧名称")
                .zoneType(ZoneType.NO_FLY)
                .shapeType(ZoneShape.CIRCLE)
                .centerLat(31.20)
                .centerLon(121.40)
                .radiusM(BigDecimal.valueOf(500))
                .minAltitudeM(BigDecimal.ZERO)
                .maxAltitudeM(BigDecimal.valueOf(120))
                .severity(ConflictSeverity.CRITICAL)
                .enabled(true)
                .build();
    }

    @Test
    @DisplayName("编辑圆形空域：类型/高度带/时间窗/启停生效，编码保持不变")
    void updateKeepsCodeAndAppliesWindows() {
        AirspaceZone zone = existingCircle();
        Mockito.when(repository.findById(1L)).thenReturn(Optional.of(zone));

        AirspaceDtos.ZoneRequest request = new AirspaceDtos.ZoneRequest(
                "IGNORED-CODE", "临时管制", ZoneType.RESTRICTED, ZoneShape.CIRCLE,
                new CommonDtos.GeoDto(31.23, 121.47, null), BigDecimal.valueOf(800),
                null, BigDecimal.valueOf(30), BigDecimal.valueOf(90),
                ConflictSeverity.HIGH, false, null, null, 100L, 300L, "晚高峰临时限飞");
        AirspaceDtos.Response response = service.update(1L, request);

        assertThat(response.code()).isEqualTo("NFZ-0001");
        assertThat(response.name()).isEqualTo("临时管制");
        assertThat(response.zoneType()).isEqualTo(ZoneType.RESTRICTED);
        assertThat(response.minAltitudeM()).isEqualByComparingTo("30");
        assertThat(response.maxAltitudeM()).isEqualByComparingTo("90");
        assertThat(response.activeFromSimSec()).isEqualTo(100L);
        assertThat(response.activeToSimSec()).isEqualTo(300L);
        assertThat(response.enabled()).isFalse();
        assertThat(zone.getCenterLat()).isEqualTo(31.23);
        assertThat(zone.getRadiusM()).isEqualByComparingTo("800");
        assertThat(zone.getPolygonWkt()).isNull();
    }

    @Test
    @DisplayName("圆形改多边形：清空圆心/半径，响应返回真实顶点（不含 WKT 闭环重复点）")
    void switchingShapeClearsStaleFields() {
        AirspaceZone zone = existingCircle();
        Mockito.when(repository.findById(1L)).thenReturn(Optional.of(zone));

        List<CommonDtos.GeoDto> points = List.of(
                new CommonDtos.GeoDto(31.20, 121.40, null),
                new CommonDtos.GeoDto(31.22, 121.40, null),
                new CommonDtos.GeoDto(31.22, 121.43, null));
        AirspaceDtos.ZoneRequest request = new AirspaceDtos.ZoneRequest(
                null, null, ZoneType.NO_FLY, ZoneShape.POLYGON,
                null, null, points, BigDecimal.ZERO, BigDecimal.valueOf(120),
                null, true, null, null, null, null, null);

        AirspaceDtos.Response response = service.update(1L, request);

        assertThat(zone.getCenterLat()).isNull();
        assertThat(zone.getCenterLon()).isNull();
        assertThat(zone.getRadiusM()).isNull();
        assertThat(zone.getPolygonWkt()).startsWith("POLYGON((");
        // 对外只暴露 3 个真实顶点：WKT 首尾闭环点应被解析层去掉
        assertThat(response.points()).hasSize(3);
        assertThat(response.points().get(0).lat()).isEqualTo(31.20);
        assertThat(response.points().get(2).lon()).isEqualTo(121.43);
    }
}
