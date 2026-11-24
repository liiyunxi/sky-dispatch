package com.skydispatch.engine;

import com.skydispatch.config.SkyProperties;
import com.skydispatch.domain.entity.DroneModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 电量模型测试（旋翼功率曲线版）。
 *
 * <p>模型不再是"每公里固定 Wh × 距离"，而是：
 * <pre>
 *   P(v, m) = P_hover · ((m0 + payload) / m0)^1.5 + B · v³
 *   B = (energyPerKmWh · v_cruise · 3.6 − P_hover) / v_cruise³
 * </pre>
 * 测试锁定四件事：
 * <ol>
 *   <li><b>标定不变</b>：巡航、空载时每公里能耗仍严格等于参数表值；</li>
 *   <li><b>载重非线性</b>：边际能耗随载重递增，而不是每公斤固定 +5%；</li>
 *   <li><b>速度非线性</b>：低速与超速段的每公里能耗都显著高于巡航；</li>
 *   <li><b>口径自洽</b>：航程、任务能耗、实际扣电出自同一套公式。</li>
 * </ol>
 */
class EnergyModelTest {

    /** 显式配置，避免测试跟着 yml 默认值漂移。 */
    private static final SkyProperties.Energy ENERGY =
            new SkyProperties.Energy(1.5, 2.5, 0.30, 60.0);

    private final EnergyModel energyModel = new EnergyModel(properties());
    private DroneModel model;

    @BeforeEach
    void setUp() {
        // 天巡 X4：42 Wh/km，悬停 900W，电池 480Wh，巡航 15 m/s
        model = DroneModel.builder()
                .code("SKY-X4")
                .name("测试机型")
                .maxPayloadKg(new BigDecimal("5.00"))
                .cruiseSpeedMps(new BigDecimal("15.00"))
                .maxFlightMin(35)
                .batteryCapacityWh(new BigDecimal("480.00"))
                .energyPerKmWh(new BigDecimal("42.000"))
                .hoverPowerW(new BigDecimal("900.00"))
                .maxAltitudeM(120)
                .enabled(true)
                .build();
    }

    @Test
    @DisplayName("空机质量按最大载重 × 2.5 估算（5kg 载重 → 12.5kg 机身）")
    void airframeMass() {
        assertThat(energyModel.airframeMassKg(model)).isCloseTo(12.5, within(1e-9));
    }

    @Test
    @DisplayName("标定不变：巡航、空载 1km = 参数表 42Wh")
    void cruiseEnergyEmptyMatchesTable() {
        assertThat(energyModel.whPerKm(model, 15, 0)).isCloseTo(42.0, within(1e-6));
        assertThat(energyModel.cruiseEnergyWh(model, 1000, 0).doubleValue())
                .isCloseTo(42.0, within(1e-6));
    }

    @Test
    @DisplayName("标定可移植：换一组机型参数，巡航每公里仍等于其参数表值")
    void calibrationWorksForOtherModels() {
        DroneModel heavy = DroneModel.builder()
                .code("TEST-H2")
                .name("重载机型")
                .maxPayloadKg(new BigDecimal("10.00"))
                .cruiseSpeedMps(new BigDecimal("20.00"))
                .maxFlightMin(30)
                .batteryCapacityWh(new BigDecimal("800.00"))
                .energyPerKmWh(new BigDecimal("30.000"))
                .hoverPowerW(new BigDecimal("1200.00"))
                .maxAltitudeM(150)
                .enabled(true)
                .build();

        assertThat(energyModel.whPerKm(heavy, 20, 0)).isCloseTo(30.0, within(1e-6));
    }

    @Test
    @DisplayName("载重非线性：每多 1kg 的边际能耗递增（旧模型是固定 +5%）")
    void payloadCostIsSuperLinear() {
        double empty = energyModel.whPerKm(model, 15, 0);
        double half = energyModel.whPerKm(model, 15, 2.5);
        double full = energyModel.whPerKm(model, 15, 5);

        double firstHalf = half - empty;
        double secondHalf = full - half;
        assertThat(firstHalf).isGreaterThan(0);
        assertThat(secondHalf)
                .as("第二个 2.5kg 的边际能耗应大于第一个（诱导功率 ∝ 质量^1.5）")
                .isGreaterThan(firstHalf);
    }

    @Test
    @DisplayName("超载 10kg：1km ≈ 65.58Wh，明显高于线性模型的 63Wh")
    void heavyPayloadExceedsLinearEstimate() {
        // 22.5/12.5 = 1.8 → 1.8^1.5 ≈ 2.41495
        // P = 900 × 2.41495 + 1368 ≈ 3541.46W → 3541.46 / 54 ≈ 65.58 Wh/km
        assertThat(energyModel.whPerKm(model, 15, 10)).isCloseTo(65.5826, within(1e-3));
        assertThat(energyModel.cruiseEnergyWh(model, 1000, 10).doubleValue())
                .isGreaterThan(42.0 * 1.5);
    }

    @Test
    @DisplayName("速度非线性：低速段每公里能耗显著上升（57.8 vs 巡航 42）")
    void lowSpeedRaisesWhPerKm() {
        // 30% 巡航 = 4.5 m/s：诱导功率基本不变，但要摊到很少的公里数上
        assertThat(energyModel.whPerKm(model, 4.5, 0)).isCloseTo(57.8356, within(1e-3));
        assertThat(energyModel.whPerKm(model, 4.5, 0))
                .isGreaterThan(energyModel.whPerKm(model, 15, 0));
    }

    @Test
    @DisplayName("速度非线性：2 倍巡航速度时每公里能耗按三次方抬升到约 2.61 倍")
    void highSpeedRaisesWhPerKmCubically() {
        double cruise = energyModel.whPerKm(model, 15, 0);
        double fast = energyModel.whPerKm(model, 30, 0);
        assertThat(fast).isCloseTo(109.6667, within(1e-3));
        assertThat(fast / cruise).isCloseTo(2.6111, within(1e-3));
        assertThat(fast).isGreaterThan(2.5 * cruise);
    }

    @Test
    @DisplayName("速度地板：速度趋近 0 时不会发散，按 30% 巡航兜底")
    void speedFloorPreventsDivergence() {
        assertThat(energyModel.whPerKm(model, 0, 0))
                .isCloseTo(energyModel.whPerKm(model, 4.5, 0), within(1e-9));
        assertThat(energyModel.powerAtSpeed(model, 0, 0)).isFinite();
    }

    @Test
    @DisplayName("起降悬停能耗：空载 900W × 60s = 15Wh")
    void hoverEnergyEmpty() {
        assertThat(energyModel.hoverEnergyWh(model).doubleValue()).isCloseTo(15.0, within(1e-6));
    }

    @Test
    @DisplayName("悬停能耗随载重上升：载 5kg 时 24.85Wh（旧模型恒为 15Wh）")
    void hoverEnergyWithPayload() {
        // 900 × 1.4^1.5 = 1490.85W → ×60/3600 = 24.8475Wh
        assertThat(energyModel.hoverEnergyWh(model, 5).doubleValue())
                .isCloseTo(24.8475, within(1e-3));
        assertThat(energyModel.hoverEnergyWh(model, 5))
                .isGreaterThan(energyModel.hoverEnergyWh(model, 0));
    }

    @Test
    @DisplayName("整段飞行能耗 = 巡航 + 带载起降悬停")
    void totalEnergy() {
        // 42 + 900×60/3600 = 57Wh
        assertThat(energyModel.estimateEnergyWh(model, 1000, 0).doubleValue())
                .isCloseTo(57.0, within(1e-6));
        // 载 5kg 时巡航每公里 52.94Wh，带载悬停 24.85Wh
        assertThat(energyModel.estimateEnergyWh(model, 1000, 5).doubleValue())
                .isCloseTo(52.9417 + 24.8475, within(1e-3));
    }

    @Test
    @DisplayName("电量百分比换算：48Wh / 480Wh = 10%")
    void batteryPctUsed() {
        assertThat(energyModel.batteryPctUsed(model, new BigDecimal("48")).doubleValue())
                .isCloseTo(10.0, within(1e-6));
    }

    @Test
    @DisplayName("最大航程：100% 可用电、空载时约 11.07km")
    void maxRange() {
        // (480 - 15) / 42 = 11.0714 km
        assertThat(energyModel.maxRangeMeters(model, 0, 100)).isCloseTo(11071.4, within(1.0));
        // 可用电量为 0 时应返回 0，而不是负数
        assertThat(energyModel.maxRangeMeters(model, 0, 0)).isZero();
        // 载重越大航程越短
        assertThat(energyModel.maxRangeMeters(model, 5, 100))
                .isLessThan(energyModel.maxRangeMeters(model, 0, 100));
    }

    @Test
    @DisplayName("航程与实际能耗自洽：飞满最大航程恰好用完可用电")
    void maxRangeIsConsistentWithConsumption() {
        double range = energyModel.maxRangeMeters(model, 0, 100);
        double cruise = energyModel.cruiseEnergyWh(model, range, 0).doubleValue();
        double hover = energyModel.hoverEnergyWh(model, 0).doubleValue();
        assertThat(cruise + hover).isCloseTo(480.0, within(0.01));
    }

    @Test
    @DisplayName("飞行时间估算向安全侧取整")
    void duration() {
        assertThat(energyModel.estimateDurationSeconds(1500, 15)).isEqualTo(100);
        assertThat(energyModel.estimateDurationSeconds(1501, 15)).isEqualTo(101);
        // 速度为 0 时不能除零
        assertThat(energyModel.estimateDurationSeconds(1000, 0)).isZero();
    }

    @Test
    @DisplayName("任务总能耗必须包含空载调机段（否则会派出停在十几公里外的飞机）")
    void missionEnergyIncludesDeadheadLeg() {
        double deadhead = 12_000; // 飞机停在 12km 外的机巢
        double oneWay = 3_000;
        double payload = 2.0;

        BigDecimal withoutDeadhead = energyModel.missionEnergyWh(model, oneWay, payload);
        BigDecimal withDeadhead = energyModel.missionEnergyWh(model, deadhead, oneWay, payload);

        // 调机段是空载飞行，增量应等于空载巡航能耗
        BigDecimal expectedExtra = energyModel.cruiseEnergyWh(model, deadhead, 0);
        assertThat(withDeadhead.subtract(withoutDeadhead).doubleValue())
                .isCloseTo(expectedExtra.doubleValue(), within(1e-2));
        // 12km 空载调机在 X4 上是 504Wh 量级：足以让"可用只有 360Wh"的飞机被正确否决
        assertThat(withDeadhead.doubleValue()).isGreaterThan(500.0);
    }

    @Test
    @DisplayName("任务总能耗 = 去程载货 + 返程空载 + 两次悬停（一次带载）")
    void missionEnergyIncludesReturnLegAndLoadedHover() {
        double oneWay = 5000;
        double payload = 2.0;

        BigDecimal outbound = energyModel.cruiseEnergyWh(model, oneWay, payload);
        BigDecimal inbound = energyModel.cruiseEnergyWh(model, oneWay, 0);
        BigDecimal expected = outbound.add(inbound)
                .add(energyModel.hoverEnergyWh(model, payload))
                .add(energyModel.hoverEnergyWh(model, 0));

        BigDecimal mission = energyModel.missionEnergyWh(model, oneWay, payload);
        assertThat(mission.doubleValue()).isCloseTo(expected.doubleValue(), within(1e-3));
        // 载货去程必须比空载回程更费电（旧模型只靠 +5%/kg 才能体现）
        assertThat(outbound).isGreaterThan(inbound);
        // 载重越大，任务总能耗越高
        assertThat(energyModel.missionEnergyWh(model, oneWay, 5))
                .isGreaterThan(energyModel.missionEnergyWh(model, oneWay, 0));
    }

    @Test
    @DisplayName("可用能量 = 电池容量 × 可用电量百分比")
    void usableEnergy() {
        assertThat(energyModel.usableEnergyWh(model, 75).doubleValue()).isCloseTo(360.0, within(1e-6));
        assertThat(energyModel.usableEnergyWh(model, 0)).isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------------

    private static SkyProperties properties() {
        return new SkyProperties(
                new SkyProperties.Algorithm("http://localhost:8000", 2000, 10000, false),
                new SkyProperties.Simulation(true, 1000, 4.0, 5, 5, 2, 10000,
                        false, 0.25, 98, 60, false, 3, 2, 60),
                new SkyProperties.Dispatch("NEAREST_AVAILABLE", 10000, 25,
                        1.0, 0.5, 0.3, false, 90, 1.15),
                new SkyProperties.Flight(80, 50, 30, 4, 3, 80),
                ENERGY,
                new SkyProperties.Wind(false, 225, 2.5, 10, 0.14, 0.3, 41, 6, 0.55, 0.6, 2.2,
                        false, 1.05),
                new SkyProperties.Bootstrap(false),
                new SkyProperties.Auth(false, "admin", "test", "test-secret", 60));
    }
}
