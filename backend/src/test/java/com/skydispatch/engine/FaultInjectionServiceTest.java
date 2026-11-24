package com.skydispatch.engine;

import com.skydispatch.config.SkyProperties;
import com.skydispatch.domain.entity.Drone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** 人工故障注入：排队、下一拍生效、只生效一次。 */
class FaultInjectionServiceTest {

    private final FaultInjectionService service = new FaultInjectionService(TestProps.of(
            new SkyProperties.Charging(80, 0.35),
            new SkyProperties.Vertiport(4, 45, 15),
            new SkyProperties.Strategic(true, 15, 2),
            // 随机故障关闭：验证人工注入不依赖随机开关
            new SkyProperties.Fault(false, 0.004, 0.0002, 0.002, 0.002, 20, 15),
            new SkyProperties.Telemetry(true, 3.0, 0.8, 0.015, 1, 0.5)));

    @Test
    @DisplayName("人工电池压降：下一拍生效一次，之后回到无故障")
    void manualBatteryDropAppliedOnce() {
        service.injectManual(1L, FaultInjectionService.Type.BATTERY_DROP, 20.0);

        FaultInjectionService.Fault fault = service.maybeInject(drone(), 8, 100, 1L);

        assertThat(fault.type()).isEqualTo(FaultInjectionService.Type.BATTERY_DROP);
        assertThat(fault.batteryDropPct()).isEqualTo(20.0);
        assertThat(fault.message()).contains("人工注入");

        // 队列已消费；随机故障关闭 → 第二次不应再有故障
        assertThat(service.maybeInject(drone(), 8, 108, 1L).present()).isFalse();
    }

    @Test
    @DisplayName("人工通信中断：写入持续时间窗口")
    void manualCommLossWindow() {
        service.injectManual(1L, FaultInjectionService.Type.COMM_LOSS, null);

        FaultInjectionService.Fault fault = service.maybeInject(drone(), 8, 100, 1L);

        assertThat(fault.type()).isEqualTo(FaultInjectionService.Type.COMM_LOSS);
        assertThat(service.commLost(1L, 110)).isTrue();
        assertThat(service.commLost(1L, 130)).isFalse();
    }

    private static Drone drone() {
        return Drone.builder()
                .id(1L).code("UAV-F1")
                .status(com.skydispatch.domain.enums.DroneStatus.IN_FLIGHT)
                .batteryPct(BigDecimal.valueOf(80))
                .build();
    }
}
