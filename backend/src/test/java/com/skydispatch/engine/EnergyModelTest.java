package com.skydispatch.engine;

import com.skydispatch.domain.entity.DroneModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 电量模型测试。
 *
 * <p>仿真结果可不可信，取决于能耗模型是否可验证。
 * 这里用"手算一遍"的方式固定住公式：任何改动只要偏离预期就会立刻失败。
 */
class EnergyModelTest {

    private final EnergyModel energyModel = new EnergyModel();
    private DroneModel model;

    @BeforeEach
    void setUp() {
        // 天巡 X4：42 Wh/km，悬停 900W，电池 480Wh
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
    @DisplayName("巡航能耗：1km 空载 = 42Wh")
    void cruiseEnergyEmpty() {
        assertThat(energyModel.cruiseEnergyWh(model, 1000, 0).doubleValue())
                .isCloseTo(42.0, within(1e-6));
    }

    @Test
    @DisplayName("载重惩罚：每公斤 +5%，载 10kg 时 1km = 63Wh")
    void cruiseEnergyWithPayload() {
        assertThat(energyModel.cruiseEnergyWh(model, 1000, 10).doubleValue())
                .isCloseTo(42.0 * 1.5, within(1e-6));
    }

    @Test
    @DisplayName("起降悬停能耗：900W 悬停 60s = 15Wh")
    void hoverEnergy() {
        assertThat(energyModel.hoverEnergyWh(model).doubleValue()).isCloseTo(15.0, within(1e-6));
    }

    @Test
    @DisplayName("整段飞行能耗 = 巡航 + 起降悬停")
    void totalEnergy() {
        double expected = 42.0 + 15.0;
        assertThat(energyModel.estimateEnergyWh(model, 1000, 0).doubleValue())
                .isCloseTo(expected, within(1e-6));
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
    @DisplayName("任务总能耗必须包含返程与两次起降（只算单程会低估风险）")
    void missionEnergyIncludesReturnLeg() {
        double oneWay = 5000;      // 单程 5km
        double payload = 2.0;

        BigDecimal outbound = energyModel.cruiseEnergyWh(model, oneWay, payload);
        BigDecimal inbound = energyModel.cruiseEnergyWh(model, oneWay, 0);
        BigDecimal expected = outbound.add(inbound)
                .add(energyModel.hoverEnergyWh(model).multiply(BigDecimal.valueOf(2)));

        BigDecimal mission = energyModel.missionEnergyWh(model, oneWay, payload);
        assertThat(mission.doubleValue()).isCloseTo(expected.doubleValue(), within(1e-3));
        // 必须严格大于"单程载货"的能耗
        assertThat(mission).isGreaterThan(outbound);
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
}
