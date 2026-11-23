package com.skydispatch.engine;

import com.skydispatch.common.GeoPoint;
import com.skydispatch.config.SkyProperties;
import com.skydispatch.domain.entity.ChargingStation;
import com.skydispatch.domain.entity.DroneModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可达性分析的核心行为：直飞 / 需补能 / 不可达。
 *
 * <p>机型取一组好算的参数：600Wh 电池、42Wh/km、900W 悬停（一次起降 15Wh）。
 * 出发可用 75%（450Wh），补能后可用到 90% - 25% = 65%（390Wh）。
 */
class ReachabilityServiceTest {

    private static final double LAT = 31.23;
    private static final double LON = 121.47;

    private final EnergyModel energyModel = new EnergyModel();
    private final ReachabilityService service = new ReachabilityService(energyModel, properties());
    private final DroneModel model = model();

    @Test
    @DisplayName("短途：直飞即可完成，不需要补能")
    void directFlightReachable() {
        GeoPoint home = new GeoPoint(LAT, LON);
        GeoPoint dropoff = east(home, 3);

        ReachabilityService.Plan plan = service.analyze(
                model, home, home, dropoff, home, 2, usable(75), List.of());

        assertTrue(plan.feasible(), "3km 短途应当直飞可达");
        assertTrue(plan.direct());
        assertEquals(0, plan.stopCount());
        assertTrue(plan.summary().startsWith("直飞可达"));
    }

    @Test
    @DisplayName("长途：直飞不够，但经停充电站可完成（去程一次、回程一次）")
    void relayFlightReachableViaCharging() {
        GeoPoint home = new GeoPoint(LAT, LON);
        GeoPoint dropoff = east(home, 8);
        ChargingStation station = station("虹桥补能站", east(home, 6));

        ReachabilityService.Plan plan = service.analyze(
                model, home, home, dropoff, home, 2, usable(75), List.of(station));

        assertTrue(plan.feasible(), "有补能站时 8km 往返应当可达");
        assertFalse(plan.direct());
        assertEquals(2, plan.stopCount(), "去程与回程各补一次");
        assertEquals(2, plan.stops().size());
        assertTrue(plan.stops().stream().allMatch(s -> "虹桥补能站".equals(s.stationName())));
        assertTrue(plan.summary().contains("需经停"));
    }

    @Test
    @DisplayName("超远：没有补能站时明确判定不可达，并说明卡在哪一段")
    void unreachableWithoutStation() {
        GeoPoint home = new GeoPoint(LAT, LON);
        GeoPoint dropoff = east(home, 20);

        ReachabilityService.Plan plan = service.analyze(
                model, home, home, dropoff, home, 2, usable(75), List.of());

        assertFalse(plan.feasible());
        assertNotNull(plan.failureReason());
        assertTrue(plan.failureReason().contains("送货点"), "应指出卡在载货段：" + plan.failureReason());
    }

    // ------------------------------------------------------------------

    private static BigDecimal usable(double pct) {
        return BigDecimal.valueOf(600).multiply(BigDecimal.valueOf(pct))
                .divide(BigDecimal.valueOf(100));
    }

    private static DroneModel model() {
        return DroneModel.builder()
                .code("TEST-X4")
                .name("测试四旋翼")
                .maxPayloadKg(BigDecimal.valueOf(5))
                .cruiseSpeedMps(BigDecimal.valueOf(15))
                .maxFlightMin(40)
                .batteryCapacityWh(BigDecimal.valueOf(600))
                .energyPerKmWh(BigDecimal.valueOf(42))
                .hoverPowerW(BigDecimal.valueOf(900))
                .maxAltitudeM(500)
                .enabled(true)
                .build();
    }

    private static ChargingStation station(String name, GeoPoint point) {
        return ChargingStation.builder()
                .id(1L)
                .code("TEST-001")
                .name(name)
                .city("上海")
                .lat(point.lat())
                .lon(point.lon())
                .enabled(true)
                .build();
    }

    /** 以给定点为原点，向东偏移若干公里（用于构造"直线航路"）。 */
    private static GeoPoint east(GeoPoint origin, double km) {
        double dLon = km / (111.32 * Math.cos(Math.toRadians(origin.lat())));
        return new GeoPoint(origin.lat(), origin.lon() + dLon);
    }

    private static SkyProperties properties() {
        return new SkyProperties(
                new SkyProperties.Algorithm("http://localhost:8000", 2000, 10000, true),
                new SkyProperties.Simulation(true, 1000, 4.0, 5, 5, 2, 10000,
                        false, 0.25, 98, 60, false, 3, 2, 60),
                new SkyProperties.Dispatch("NEAREST_AVAILABLE", 10000, 25,
                        1.0, 0.5, 0.3, true, 90, 1.15),
                new SkyProperties.Flight(80, 50, 30, 4, 3, 80),
                new SkyProperties.Bootstrap(true),
                new SkyProperties.Auth(false, "admin", "test", "test-secret", 60));
    }
}
