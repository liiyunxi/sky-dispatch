package com.skydispatch.engine;

import com.skydispatch.domain.entity.ChargingStation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 充电站容量、排队与充电曲线。 */
class ChargingStationRegistryTest {

    private final ChargingStationRegistry registry = new ChargingStationRegistry(TestProps.defaults());
    private final ChargingStation station = ChargingStation.builder()
            .id(10L).code("CS-T").name("测试站").chargeSlots(2).enabled(true).build();

    @Test
    @DisplayName("容量：同时最多 2 架，第 3 架进入排队；释放后自动叫号")
    void capacityAndQueue() {
        assertThat(registry.capacity(station)).isEqualTo(2);
        assertThat(registry.tryAcquire(10L, 1L, 2)).isTrue();
        assertThat(registry.tryAcquire(10L, 2L, 2)).isTrue();
        assertThat(registry.tryAcquire(10L, 3L, 2)).isFalse();

        registry.enqueue(10L, 3L);
        assertThat(registry.isQueued(3L)).isTrue();
        assertThat(registry.queuePosition(3L)).isEqualTo(1);
        assertThat(registry.queueLength(10L)).isEqualTo(1);

        assertThat(registry.promote(10L, 2)).isZero();   // 没空桩，叫不到号
        registry.release(1L);
        assertThat(registry.promote(10L, 2)).isEqualTo(1);
        assertThat(registry.isCharging(3L)).isTrue();
        assertThat(registry.isQueued(3L)).isFalse();
        assertThat(registry.occupancy(10L)).isEqualTo(2);
    }

    @Test
    @DisplayName("释放会同时清理桩位与队列，不产生永久占用")
    void releaseCleansUp() {
        registry.tryAcquire(10L, 1L, 2);
        registry.enqueue(10L, 2L);
        registry.release(2L);
        assertThat(registry.isQueued(2L)).isFalse();
        assertThat(registry.queueLength(10L)).isZero();
        registry.release(1L);
        assertThat(registry.occupancy(10L)).isZero();
    }

    @Test
    @DisplayName("低电优先：插队飞机先于普通排队者获得桩位")
    void lowBatteryJumpsQueue() {
        registry.tryAcquire(10L, 1L, 2);
        registry.tryAcquire(10L, 2L, 2);
        registry.enqueue(10L, 3L);
        registry.enqueueFirst(10L, 4L);
        assertThat(registry.queuePosition(4L)).isEqualTo(1);
        assertThat(registry.queuePosition(3L)).isEqualTo(2);

        registry.release(1L);
        assertThat(registry.promote(10L, 2)).isEqualTo(1);
        assertThat(registry.isCharging(4L)).isTrue();
        assertThat(registry.isQueued(3L)).isTrue();
    }

    @Test
    @DisplayName("充电曲线：80% 以下恒流，之后线性衰减到 0.35")
    void chargingCurve() {
        assertThat(registry.powerFactor(50)).isCloseTo(1.0, within(1e-9));
        assertThat(registry.powerFactor(80)).isCloseTo(1.0, within(1e-9));
        assertThat(registry.powerFactor(90)).isCloseTo(0.675, within(1e-6));
        assertThat(registry.powerFactor(100)).isCloseTo(0.35, within(1e-6));
    }
}
