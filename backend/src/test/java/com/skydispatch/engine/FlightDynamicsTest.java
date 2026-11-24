package com.skydispatch.engine;

import com.skydispatch.common.GeoPoint;
import com.skydispatch.common.GeoUtils;
import com.skydispatch.config.SkyProperties;
import com.skydispatch.domain.entity.Drone;
import com.skydispatch.domain.entity.DroneModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 飞行动力学测试：位置必须由速度积分产生，而不是沿航路插值。
 *
 * <p>锁定的行为：
 * <ol>
 *   <li>起步有惯性：第一拍不可能"瞬间出现在 8 秒后的航路点上"；</li>
 *   <li>末端会减速并停稳在终点，不会高速掠过；</li>
 *   <li>逆风时为了维持额定空速，地速被压下来，且每米能耗更高；</li>
 *   <li>能耗按功率曲线对时间积分，量级与解析模型一致。</li>
 * </ol>
 */
class FlightDynamicsTest {

    private static final double LAT = 31.23;
    private static final double LON = 121.47;
    private static final double TICK_SECONDS = 8.0;

    private final DroneModel model = model();

    @Test
    @DisplayName("起步有惯性：第一拍不会瞬移到 8 秒后的位置")
    void acceleratesInsteadOfTeleporting() {
        FlightDynamics dynamics = new FlightDynamics(properties(false), new EnergyModel(properties(false)),
                new WindField(properties(false)));
        Drone drone = drone(1L);
        List<GeoPoint> path = northPath(1000);

        FlightDynamics.Motion motion = dynamics.advance(
                drone, model, path, 0, 80, 2.0, TICK_SECONDS, 0);

        // 匀速直线的话 8 秒能飞 120m；带惯性不可能走这么远
        assertThat(motion.distanceM()).isGreaterThan(0).isLessThan(8 * 15);
        // 速度是逐渐建立的，第一拍照例低于巡航速度
        assertThat(motion.groundSpeedMps()).isLessThan(15.0);
        // 起飞阶段是垂直爬升先行的
        assertThat(motion.altitudeM()).isGreaterThan(0);
    }

    @Test
    @DisplayName("末端减速并停稳：最终到达终点，速度与高度归零")
    void deceleratesAndStopsAtDestination() {
        FlightDynamics dynamics = new FlightDynamics(properties(false), new EnergyModel(properties(false)),
                new WindField(properties(false)));
        Drone drone = drone(2L);
        List<GeoPoint> path = northPath(1000);

        double flown = 0;
        double energyWh = 0;
        FlightDynamics.Motion motion = null;
        for (int tick = 0; tick < 200 && (motion == null || !motion.arrived()); tick++) {
            motion = dynamics.advance(drone, model, path, flown, 80, 2.0, TICK_SECONDS, tick * TICK_SECONDS);
            flown = motion.distanceM();
            energyWh += motion.energyWh();
        }

        assertThat(motion).isNotNull();
        assertThat(motion.arrived()).isTrue();
        assertThat(motion.distanceM()).isCloseTo(1000.0, within(1.0));
        assertThat(motion.groundSpeedMps()).isZero();
        assertThat(motion.altitudeM()).isZero();
        // 终点吸附误差在米级
        GeoPoint end = path.get(1);
        assertThat(new GeoPoint(motion.lat(), motion.lon()).distanceTo(end)).isLessThan(5.0);
        // 1km 载 2kg 的积分能耗应在解析模型量级（42Wh 巡航 + 起降约 12Wh）
        assertThat(energyWh).isBetween(45.0, 75.0);
    }

    @Test
    @DisplayName("逆风：地速被额定空速压下来，每米能耗更高")
    void headwindReducesGroundSpeedAndRaisesEnergyPerMeter() {
        FlightDynamics calm = new FlightDynamics(properties(false), new EnergyModel(properties(false)),
                new WindField(properties(false)));
        FlightDynamics windy = new FlightDynamics(properties(true), new EnergyModel(properties(true)),
                new WindField(properties(true)));

        Drone calmDrone = drone(3L);
        Drone windyDrone = drone(4L);
        List<GeoPoint> path = northPath(3000);

        double calmFlown = 0;
        double windyFlown = 0;
        double calmEnergy = 0;
        double windyEnergy = 0;
        FlightDynamics.Motion calmMotion = null;
        FlightDynamics.Motion windyMotion = null;
        // 飞 10 拍（80 秒），此时双方都已进入巡航
        for (int tick = 0; tick < 10; tick++) {
            calmMotion = calm.advance(calmDrone, model, path, calmFlown, 80, 2.0,
                    TICK_SECONDS, tick * TICK_SECONDS);
            windyMotion = windy.advance(windyDrone, model, path, windyFlown, 80, 2.0,
                    TICK_SECONDS, tick * TICK_SECONDS);
            calmFlown = calmMotion.distanceM();
            windyFlown = windyMotion.distanceM();
            calmEnergy += calmMotion.energyWh();
            windyEnergy += windyMotion.energyWh();
        }

        assertThat(calmMotion).isNotNull();
        assertThat(windyMotion).isNotNull();
        // 北风 5 m/s：巡航地速应从 15 掉到 10 附近
        assertThat(windyMotion.groundSpeedMps())
                .isLessThan(calmMotion.groundSpeedMps() - 1.5);
        // 同样飞 1 米，逆风更费电
        double calmPerMeter = calmEnergy / calmFlown;
        double windyPerMeter = windyEnergy / windyFlown;
        assertThat(windyPerMeter).isGreaterThan(calmPerMeter * 1.05);
    }

    // ------------------------------------------------------------------

    private static Drone drone(long id) {
        return Drone.builder()
                .id(id)
                .code("UAV-T" + id)
                .currentLat(LAT)
                .currentLon(LON)
                .altitudeM(BigDecimal.ZERO)
                .speedMps(BigDecimal.ZERO)
                .headingDeg(BigDecimal.ZERO)
                .build();
    }

    private static List<GeoPoint> northPath(double meters) {
        GeoPoint start = new GeoPoint(LAT, LON);
        return List.of(start, GeoUtils.destinationPoint(start, 0.0, meters));
    }

    private static DroneModel model() {
        return DroneModel.builder()
                .code("TEST-X4")
                .name("测试四旋翼")
                .maxPayloadKg(BigDecimal.valueOf(5))
                .cruiseSpeedMps(BigDecimal.valueOf(15))
                .maxFlightMin(35)
                .batteryCapacityWh(BigDecimal.valueOf(480))
                .energyPerKmWh(BigDecimal.valueOf(42))
                .hoverPowerW(BigDecimal.valueOf(900))
                .maxAltitudeM(120)
                .enabled(true)
                .build();
    }

    /** 无剪切、无阵风的北风 5 m/s；关闭时完全无风。 */
    private static SkyProperties properties(boolean windEnabled) {
        return new SkyProperties(
                new SkyProperties.Algorithm("http://localhost:8000", 2000, 10000, false),
                new SkyProperties.Simulation(true, 1000, 8.0, 5, 5, 2, 10000,
                        false, 0.25, 98, 60, false, 3, 2, 60),
                new SkyProperties.Dispatch("NEAREST_AVAILABLE", 30000, 25,
                        1.0, 0.5, 0.3, false, 90, 1.15),
                new SkyProperties.Flight(80, 50, 30, 4, 3, 80,
                        List.of(62.0, 80.0, 98.0, 116.0)),
                new SkyProperties.Energy(1.5, 2.5, 0.30, 60.0),
                new SkyProperties.Wind(windEnabled, 0.0, 5.0, 10.0, 0.0, 0.0, 41.0, 0.0,
                        0.55, 0.60, 2.20, false, 1.05),
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
