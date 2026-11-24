package com.skydispatch.domain.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 空域时间窗的边界语义。
 *
 * <p>规划、冲突检测与前端展示都依赖 {@link AirspaceZone#activeAt(double)}；
 * 边界差一秒就会出现"地图上显示生效、规划却不避让"的割裂，
 * 所以这里把起止点（含/不含）钉死在单元测试里。
 */
class AirspaceZoneWindowTest {

    private AirspaceZone zone(Long from, Long to) {
        return AirspaceZone.builder().activeFromSimSec(from).activeToSimSec(to).build();
    }

    @Test
    @DisplayName("完整时间窗：开始时刻含、结束时刻含、窗外不含")
    void boundedWindow() {
        AirspaceZone zone = zone(100L, 200L);
        assertThat(zone.activeAt(99.9)).isFalse();
        assertThat(zone.activeAt(100.0)).isTrue();
        assertThat(zone.activeAt(150.0)).isTrue();
        assertThat(zone.activeAt(200.0)).isTrue();
        assertThat(zone.activeAt(200.1)).isFalse();
    }

    @Test
    @DisplayName("只有开始时刻：到点生效，之后长期有效")
    void openEndedWindow() {
        AirspaceZone zone = zone(60L, null);
        assertThat(zone.activeAt(59.9)).isFalse();
        assertThat(zone.activeAt(60.0)).isTrue();
        assertThat(zone.activeAt(1_000_000)).isTrue();
    }

    @Test
    @DisplayName("只有结束时刻：从仿真开始就生效，到点解除")
    void openStartedWindow() {
        AirspaceZone zone = zone(null, 120L);
        assertThat(zone.activeAt(0)).isTrue();
        assertThat(zone.activeAt(120.0)).isTrue();
        assertThat(zone.activeAt(120.1)).isFalse();
    }

    @Test
    @DisplayName("没有时间窗：长期有效")
    void alwaysActive() {
        AirspaceZone zone = zone(null, null);
        assertThat(zone.activeAt(0)).isTrue();
        assertThat(zone.activeAt(999_999)).isTrue();
    }
}
