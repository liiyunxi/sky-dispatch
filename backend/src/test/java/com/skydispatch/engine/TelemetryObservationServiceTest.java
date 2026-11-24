package com.skydispatch.engine;

import com.skydispatch.common.GeoPoint;
import com.skydispatch.config.SkyProperties;
import com.skydispatch.domain.entity.Drone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** 遥测观测：噪声有界、丢包生效、通信中断时整帧缺失。 */
class TelemetryObservationServiceTest {

    private final Drone drone = Drone.builder()
            .id(1L).code("UAV-O1")
            .currentLat(31.2304).currentLon(121.4737)
            .altitudeM(BigDecimal.valueOf(80)).speedMps(BigDecimal.valueOf(15))
            .headingDeg(BigDecimal.valueOf(90)).batteryPct(BigDecimal.valueOf(90))
            .build();

    @Test
    @DisplayName("噪声有界：位置偏差在几十米内，电量偏差不超过 2 个百分点")
    void noiseIsBounded() {
        SkyProperties props = TestProps.of(
                new SkyProperties.Charging(80, 0.35),
                new SkyProperties.Vertiport(4, 45, 15),
                new SkyProperties.Strategic(true, 15, 2),
                new SkyProperties.Fault(false, 0.004, 0.0002, 0.002, 0.002, 20, 15),
                new SkyProperties.Telemetry(true, 3.0, 0.8, 0.0, 0, 0.5));
        FaultInjectionService faults = new FaultInjectionService(props);
        TelemetryObservationService service = new TelemetryObservationService(props, faults);

        TelemetryObservationService.Observation obs = service.observeForFrame(drone, 0, 7);
        assertThat(obs).isNotNull();
        double distance = new GeoPoint(obs.lat(), obs.lon())
                .distanceTo(new GeoPoint(31.2304, 121.4737));
        assertThat(distance).isLessThan(20.0);
        assertThat(Math.abs(obs.batteryPct() - 90)).isLessThan(2.0);
    }

    @Test
    @DisplayName("丢包率为 100% 时：实时帧标记 stale，落库遥测直接缺失")
    void fullDropRate() {
        SkyProperties props = TestProps.of(
                new SkyProperties.Charging(80, 0.35),
                new SkyProperties.Vertiport(4, 45, 15),
                new SkyProperties.Strategic(true, 15, 2),
                new SkyProperties.Fault(false, 0.004, 0.0002, 0.002, 0.002, 20, 15),
                new SkyProperties.Telemetry(true, 3.0, 0.8, 1.0, 0, 0.5));
        FaultInjectionService faults = new FaultInjectionService(props);
        TelemetryObservationService service = new TelemetryObservationService(props, faults);

        TelemetryObservationService.Observation frame = service.observeForFrame(drone, 0, 1);
        assertThat(frame).isNotNull();
        assertThat(frame.stale()).isTrue();
        assertThat(service.observeForRecord(drone, 0, 1)).isNull();
    }

    @Test
    @DisplayName("通信中断期间：实时帧标记失联，落库遥测缺失")
    void commLossHidesTelemetry() {
        SkyProperties props = TestProps.of(
                new SkyProperties.Charging(80, 0.35),
                new SkyProperties.Vertiport(4, 45, 15),
                new SkyProperties.Strategic(true, 15, 2),
                new SkyProperties.Fault(true, 0.0, 0.0, 1.0, 0.0, 20, 15),
                new SkyProperties.Telemetry(true, 3.0, 0.8, 0.0, 0, 0.5));
        FaultInjectionService faults = new FaultInjectionService(props);
        faults.maybeInject(drone, 60, 0, 1L);
        TelemetryObservationService service = new TelemetryObservationService(props, faults);

        TelemetryObservationService.Observation lost = service.observeForFrame(drone, 1, 2);
        assertThat(lost).isNotNull();
        assertThat(lost.commLost()).isTrue();
        assertThat(service.observeForRecord(drone, 1, 2)).isNull();
        // 故障窗口过后恢复
        assertThat(service.observeForFrame(drone, 25, 3).commLost()).isFalse();
    }

    @Test
    @DisplayName("关闭噪声时输出与真值一致")
    void noiseDisabledReturnsTruth() {
        SkyProperties props = TestProps.of(
                new SkyProperties.Charging(80, 0.35),
                new SkyProperties.Vertiport(4, 45, 15),
                new SkyProperties.Strategic(true, 15, 2),
                new SkyProperties.Fault(false, 0.004, 0.0002, 0.002, 0.002, 20, 15),
                new SkyProperties.Telemetry(false, 3.0, 0.8, 0.0, 0, 0.5));
        FaultInjectionService faults = new FaultInjectionService(props);
        TelemetryObservationService service = new TelemetryObservationService(props, faults);

        TelemetryObservationService.Observation obs = service.observeForFrame(drone, 0, 1);
        assertThat(obs.lat()).isEqualTo(31.2304);
        assertThat(obs.lon()).isEqualTo(121.4737);
        assertThat(obs.altitudeM()).isEqualTo(80.0);
        assertThat(obs.batteryPct()).isEqualTo(90.0);
    }
}
