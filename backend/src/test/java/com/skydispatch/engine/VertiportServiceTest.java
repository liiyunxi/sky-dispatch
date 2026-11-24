package com.skydispatch.engine;

import com.skydispatch.config.SkyProperties;
import com.skydispatch.domain.entity.Drone;
import com.skydispatch.domain.entity.VertiportSite;
import com.skydispatch.repository.DroneRepository;
import com.skydispatch.repository.VertiportSiteRepository;
import com.skydispatch.service.VertiportSiteService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 机巢容量与地面周转。 */
class VertiportServiceTest {

    /** 站点台账：2 个坪位、周转 45s（每站独立配置，测试里对任意坐标返回同一份配置）。 */
    private static VertiportSiteService siteService() {
        VertiportSiteRepository repository = mock(VertiportSiteRepository.class);
        VertiportSite site = VertiportSite.builder()
                .id(1L).siteKey("test").code("VP-001").name("测试机巢")
                .lat(31.23).lon(121.47).slots(2).turnaroundSeconds(45.0)
                .build();
        when(repository.findBySiteKey(anyString())).thenReturn(Optional.of(site));
        return new VertiportSiteService(repository, mock(DroneRepository.class));
    }

    /** 2 个坪位、一次起降 10s → 平均 5s 放行一架；降落后周转 45s。 */
    private final VertiportService service = new VertiportService(TestProps.of(
            new SkyProperties.Charging(80, 0.35),
            new SkyProperties.Vertiport(2, 45, 10),
            new SkyProperties.Strategic(true, 15, 2),
            new SkyProperties.Fault(false, 0.004, 0.0002, 0.002, 0.002, 20, 15),
            new SkyProperties.Telemetry(true, 3.0, 0.8, 0.015, 1, 0.5)), siteService());

    @Test
    @DisplayName("起降坪容量：2 个坪位、10s 占用 → 每 5s 放行一架，其余排队")
    void departureCapacity() {
        Drone a = drone(1L, 31.23, 121.47);
        Drone b = drone(2L, 31.23, 121.47);
        Drone c = drone(3L, 31.23, 121.47);

        assertThat(service.requestTakeoff(a, 0).granted()).isTrue();
        VertiportService.Decision second = service.requestTakeoff(b, 0);
        assertThat(second.granted()).isFalse();
        assertThat(second.reason()).contains("起降坪繁忙");
        assertThat(second.notBeforeSimSec()).isEqualTo(5);
        assertThat(service.requestTakeoff(b, 5).granted()).isTrue();
        assertThat(service.requestTakeoff(c, 5).granted()).isFalse();
        assertThat(service.requestTakeoff(c, 10).granted()).isTrue();
    }

    @Test
    @DisplayName("地面周转：降落后 45s 内不能再起飞")
    void turnaroundBlocksDeparture() {
        Drone d = drone(9L, 31.31, 121.50);
        service.landed(d, 100);

        VertiportService.Decision denied = service.requestTakeoff(d, 120);
        assertThat(denied.granted()).isFalse();
        assertThat(denied.reason()).contains("地面周转");
        assertThat(denied.notBeforeSimSec()).isEqualTo(145);

        assertThat(service.requestTakeoff(d, 145).granted()).isTrue();
    }

    private static Drone drone(long id, double lat, double lon) {
        return Drone.builder()
                .id(id).code("UAV-V" + id)
                .homeLat(lat).homeLon(lon)
                .currentLat(lat).currentLon(lon)
                .altitudeM(BigDecimal.ZERO).speedMps(BigDecimal.ZERO)
                .build();
    }
}
