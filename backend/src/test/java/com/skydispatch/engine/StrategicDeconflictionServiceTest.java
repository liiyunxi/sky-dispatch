package com.skydispatch.engine;

import com.skydispatch.common.GeoPoint;
import com.skydispatch.config.SkyProperties;
import com.skydispatch.domain.entity.DispatchTask;
import com.skydispatch.domain.entity.Drone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 战略时隙：预约可见、占用者可追溯、重置可清空。 */
class StrategicDeconflictionServiceTest {

    private final StrategicDeconflictionService service = new StrategicDeconflictionService(TestProps.of(
            new SkyProperties.Charging(80, 0.35),
            new SkyProperties.Vertiport(4, 45, 15),
            new SkyProperties.Strategic(true, 15, 2),
            new SkyProperties.Fault(false, 0.004, 0.0002, 0.002, 0.002, 20, 15),
            new SkyProperties.Telemetry(true, 3.0, 0.8, 0.015, 1, 0.5)));

    @Test
    @DisplayName("预约时隙后快照可见占用者；clearAll 后清空")
    void snapshotShowsOccupantAndClearAll() {
        DispatchTask task = DispatchTask.builder()
                .id(7L).taskNo("ST-TEST-7").orderId(9L)
                .build();
        Drone drone = Drone.builder().id(1L).code("UAV-S1").build();
        List<GeoPoint> path = List.of(new GeoPoint(31.2304, 121.4737), new GeoPoint(31.2400, 121.4900));

        double releaseAt = service.requestSlot(task, drone, path, 80, 0);

        StrategicDeconflictionService.Snapshot snapshot = service.snapshot();
        assertThat(snapshot.enabled()).isTrue();
        assertThat(snapshot.slots()).hasSize(1);
        StrategicDeconflictionService.SlotView slot = snapshot.slots().get(0);
        assertThat(slot.startSimSec()).isEqualTo(releaseAt);
        assertThat(slot.layerM()).isEqualTo(80);
        assertThat(slot.occupants()).hasSize(1);
        assertThat(slot.occupants().get(0).taskNo()).isEqualTo("ST-TEST-7");
        assertThat(slot.occupants().get(0).droneCode()).isEqualTo("UAV-S1");
        assertThat(slot.occupants().get(0).orderId()).isEqualTo(9L);

        service.clearAll();
        assertThat(service.snapshot().slots()).isEmpty();
    }
}
