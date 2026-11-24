package com.skydispatch.engine;

import com.skydispatch.config.SkyProperties;
import com.skydispatch.domain.entity.Drone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 高度层分配测试。
 *
 * <p>分层的目的不是"数字好看"，而是让冲突检测里的垂直间隔真正生效：
 * 相邻层间距必须大于冲突检测的垂直间隔（15m），否则分层等于没分。
 */
class AltitudeLayersTest {

    private static final List<Double> LAYERS = List.of(62.0, 80.0, 98.0, 116.0);

    private final AltitudeLayers layers = new AltitudeLayers(properties(LAYERS));

    @Test
    @DisplayName("不同无人机分配到不同高度层，不再全部挤在默认高度")
    void dronesSpreadAcrossLayers() {
        assertThat(layers.forDrone(drone(1L))).isEqualTo(62.0);
        assertThat(layers.forDrone(drone(2L))).isEqualTo(80.0);
        assertThat(layers.forDrone(drone(3L))).isEqualTo(98.0);
        assertThat(layers.forDrone(drone(4L))).isEqualTo(116.0);
        // 轮转：第 5 架回到第一层
        assertThat(layers.forDrone(drone(5L))).isEqualTo(62.0);
    }

    @Test
    @DisplayName("同一架飞机始终在同一层（可复现，不会中途换层）")
    void assignmentIsStable() {
        assertThat(layers.forDrone(drone(7L))).isEqualTo(layers.forDrone(drone(7L)));
    }

    @Test
    @DisplayName("相邻层间距必须大于冲突检测的垂直间隔 15m")
    void layersAreSeparatedEnough() {
        for (int i = 1; i < LAYERS.size(); i++) {
            assertThat(LAYERS.get(i) - LAYERS.get(i - 1))
                    .as("第 %d 层与第 %d 层的间距", i + 1, i)
                    .isGreaterThanOrEqualTo(15.0);
        }
        // 最高层不能超过机队里升限最低的机型（X4 为 120m）
        assertThat(LAYERS.stream().mapToDouble(Double::doubleValue).max().orElse(0))
                .isLessThanOrEqualTo(120.0);
    }

    @Test
    @DisplayName("没有配置高度层时退回默认巡航高度")
    void emptyLayersFallBackToDefault() {
        AltitudeLayers fallback = new AltitudeLayers(properties(List.of()));
        assertThat(fallback.forDrone(drone(3L))).isEqualTo(80.0);
    }

    // ------------------------------------------------------------------

    private static Drone drone(long id) {
        return Drone.builder()
                .id(id)
                .code("UAV-T" + id)
                .altitudeM(BigDecimal.ZERO)
                .speedMps(BigDecimal.ZERO)
                .headingDeg(BigDecimal.ZERO)
                .build();
    }

    private static SkyProperties properties(List<Double> layers) {
        return new SkyProperties(
                new SkyProperties.Algorithm("http://localhost:8000", 2000, 10000, false),
                new SkyProperties.Simulation(true, 1000, 8.0, 5, 5, 2, 10000,
                        false, 0.25, 98, 60, false, 3, 2, 60),
                new SkyProperties.Dispatch("NEAREST_AVAILABLE", 30000, 25,
                        1.0, 0.5, 0.3, false, 90, 1.15),
                new SkyProperties.Flight(80, 50, 30, 4, 3, 80, layers),
                new SkyProperties.Energy(1.5, 2.5, 0.30, 60.0),
                new SkyProperties.Wind(false, 225, 2.5, 10, 0.14, 0.3, 41, 6, 0.55, 0.6, 2.2,
                        false, 1.05),
                new SkyProperties.Bootstrap(false),
                new SkyProperties.Auth(false, "admin", "test", "test-secret", 60),
                new SkyProperties.Dynamics(2.5, 0.9, 120.0, 0.5, 2.0, 1.5),
                new SkyProperties.Charging(80, 0.35),
                new SkyProperties.Vertiport(4, 45, 15),
                new SkyProperties.Strategic(true, 15, 2),
                new SkyProperties.Fault(false, 0.004, 0.0002, 0.002, 0.002, 20, 15),
                new SkyProperties.Telemetry(true, 3.0, 0.8, 0.015, 1, 0.5));
    }
}
