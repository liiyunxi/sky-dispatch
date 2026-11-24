package com.skydispatch.engine;

import com.skydispatch.config.SkyProperties;
import com.skydispatch.domain.entity.DroneModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** 统一 ETA：不再用"距离 / 巡航速度"，而是爬升 + 下降 + 风修正巡航 + 起降开销。 */
class FlightTimeEstimatorTest {

    private final DroneModel model = DroneModel.builder()
            .code("TEST-X4").name("测试机")
            .maxPayloadKg(BigDecimal.valueOf(5)).cruiseSpeedMps(BigDecimal.valueOf(15))
            .maxFlightMin(35).batteryCapacityWh(BigDecimal.valueOf(480))
            .energyPerKmWh(BigDecimal.valueOf(42)).hoverPowerW(BigDecimal.valueOf(900))
            .maxAltitudeM(120).enabled(true).build();

    @Test
    @DisplayName("1.5km 航程的 ETA 必须大于纯几何时间（含爬升/下降/起降开销）")
    void etaIncludesVerticalAndOverhead() {
        SkyProperties props = TestProps.defaults();
        FlightTimeEstimator estimator = new FlightTimeEstimator(
                props, new WindField(props), new EnergyModel(props));

        FlightTimeEstimator.Estimate estimate = estimator.estimate(1500, model, 80, 0);
        assertThat(estimate.seconds()).isGreaterThan(100);           // 1500/15 = 100
        assertThat(estimate.groundSpeedMps()).isEqualTo(15.0);
    }

    @Test
    @DisplayName("逆风 ETA 变长、顺风变短（与规划风同一口径）")
    void windChangesEta() {
        SkyProperties calmProps = TestProps.defaults();
        SkyProperties windProps = TestProps.withWind(new SkyProperties.Wind(
                true, 0.0, 5.0, 10.0, 0.0, 0.0, 41.0, 0.0, 0.55, 0.60, 2.20, true, 1.05));

        FlightTimeEstimator calm = new FlightTimeEstimator(
                calmProps, new WindField(calmProps), new EnergyModel(calmProps));
        FlightTimeEstimator windy = new FlightTimeEstimator(
                windProps, new WindField(windProps), new EnergyModel(windProps));

        int headwind = windy.estimate(3000, model, 80, 0).seconds();     // 向北，逆风
        int tailwind = windy.estimate(3000, model, 80, 180).seconds();   // 向南，顺风
        int noWind = calm.estimate(3000, model, 80, 0).seconds();

        assertThat(headwind).isGreaterThan(noWind);
        assertThat(tailwind).isLessThan(noWind);
    }
}
