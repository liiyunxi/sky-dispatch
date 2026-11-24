package com.skydispatch.engine;

import com.skydispatch.config.SkyProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 风场模型测试。
 *
 * <p>风是"真实感"的核心来源，但它必须同时满足两个硬约束：
 * <ol>
 *   <li><b>确定性</b>：同一 simTime 必须算出同一结果，否则仿真不可复现；</li>
 *   <li><b>物理方向正确</b>：逆风变慢且更耗电，顺风更快且更省电，侧风导致机头偏航。</li>
 * </ol>
 * 这两条一旦被改坏，看板上的"逆风延误"就会变成随机数，失去解释价值。
 *
 * <p>模型是<b>固定空速</b>：巡航速度 15 m/s 指空速，风会改变地速。
 * 因此逆风时地速 = 15 − 风速，而每米能耗 = 无风能耗 × (15 / 地速)。
 */
class WindFieldTest {

    /**
     * 构造一份显式配置，避免测试依赖 yml 的默认值（默认值可以调，测试不该跟着漂）。
     * 北风：来向 0°，吹向正南；关掉剪切/阵风/风向摆动，便于手算。
     */
    private static WindField field(boolean enabled, double baseSpeedMps) {
        SkyProperties.Wind wind = new SkyProperties.Wind(
                enabled,
                0.0,
                baseSpeedMps,
                10.0,
                0.0,
                0.0,
                41.0,
                0.0,
                0.55,
                0.60,
                2.20,
                true,
                1.05);
        return new WindField(properties(wind));
    }

    /** 其余参数取与生产默认值一致的字面量，只让风场成为变量。 */
    private static SkyProperties properties(SkyProperties.Wind wind) {
        return new SkyProperties(
                new SkyProperties.Algorithm("http://localhost:8000", 2000, 10000, true),
                new SkyProperties.Simulation(true, 1000, 4.0, 5, 5, 2, 10000,
                        false, 0.25, 98, 60, false, 3, 2, 60),
                new SkyProperties.Dispatch("NEAREST_AVAILABLE", 10000, 25,
                        1.0, 0.5, 0.3, false, 90, 1.15),
                new SkyProperties.Flight(80, 50, 30, 4, 3, 80,
                        java.util.List.of(62.0, 80.0, 98.0, 116.0)),
                new SkyProperties.Energy(1.5, 2.5, 0.30, 60.0),
                wind,
                new SkyProperties.Bootstrap(false),
                new SkyProperties.Auth(false, "admin", "test", "test-secret", 60),
                new SkyProperties.Dynamics(2.5, 0.9, 120.0, 0.5, 2.0, 1.5),
                new SkyProperties.Charging(80, 0.35),
                new SkyProperties.Vertiport(4, 45, 15),
                new SkyProperties.Strategic(true, 15, 2),
                new SkyProperties.Fault(false, 0.004, 0.0002, 0.002, 0.002, 20, 15),
                new SkyProperties.Telemetry(true, 3.0, 0.8, 0.015, 1, 0.5));
    }

    @Test
    @DisplayName("关闭风场时：地速系数与能耗系数都必须是 1")
    void disabledWindIsNeutral() {
        WindField wind = field(false, 5.0);
        WindField.Adjustment adj = wind.adjust(15.0, 90.0, 80.0, 123.0);

        assertThat(adj.groundSpeedFactor()).isEqualTo(1.0);
        assertThat(adj.energyFactor()).isEqualTo(1.0);
        assertThat(adj.windSpeedMps()).isZero();
        assertThat(adj.noseBearingDeg()).isEqualTo(90.0);
        assertThat(wind.vectorAt(10.0, 80.0).speedMps()).isZero();
    }

    @Test
    @DisplayName("同一仿真时刻必须得到同一结果（可复现）")
    void deterministicForSameSimTime() {
        WindField wind = field(true, 5.0);
        WindField.Adjustment a = wind.adjust(15.0, 0.0, 80.0, 77.5);
        WindField.Adjustment b = wind.adjust(15.0, 0.0, 80.0, 77.5);

        assertThat(a.groundSpeedFactor()).isEqualTo(b.groundSpeedFactor());
        assertThat(a.energyFactor()).isEqualTo(b.energyFactor());
        assertThat(a.noseBearingDeg()).isEqualTo(b.noseBearingDeg());
    }

    @Test
    @DisplayName("逆风：地速下降、每米能耗上升")
    void headwindSlowsGroundSpeedAndRaisesEnergy() {
        WindField wind = field(true, 5.0);
        // 北风吹向南，向北飞（航向 0°）是纯逆风
        WindField.Adjustment adj = wind.adjust(15.0, 0.0, 80.0, 0.0);

        assertThat(adj.headwindMps()).isCloseTo(5.0, within(1e-6));
        // 地速 15 − 5 = 10 → 系数 0.6667
        assertThat(adj.groundSpeedFactor()).isCloseTo(10.0 / 15.0, within(1e-4));
        // 每米能耗 15 / 10 = 1.5
        assertThat(adj.energyFactor()).isCloseTo(1.5, within(1e-4));
        assertThat(adj.noseBearingDeg()).isCloseTo(0.0, within(0.5));
    }

    @Test
    @DisplayName("顺风：地速上升、每米能耗下降")
    void tailwindRaisesGroundSpeedAndLowersEnergy() {
        WindField wind = field(true, 5.0);
        // 向南飞（航向 180°）才是顺风
        WindField.Adjustment adj = wind.adjust(15.0, 180.0, 80.0, 0.0);

        assertThat(adj.headwindMps()).isCloseTo(-5.0, within(1e-6));
        assertThat(adj.groundSpeedFactor()).isCloseTo(20.0 / 15.0, within(1e-4));
        assertThat(adj.energyFactor()).isCloseTo(15.0 / 20.0, within(1e-4));
    }

    @Test
    @DisplayName("侧风：机头需要偏航（crab angle）才能保持航迹")
    void crosswindCrabsTheNose() {
        WindField wind = field(true, 5.0);
        // 向东飞（航向 90°），风从北吹向南 → 需要向左偏航修正
        WindField.Adjustment adj = wind.adjust(15.0, 90.0, 80.0, 0.0);

        assertThat(adj.headwindMps()).isCloseTo(0.0, within(1e-6));
        // 机头不再正东：偏航约 19°
        assertThat(Math.abs(adj.noseBearingDeg() - 90.0)).isGreaterThan(10.0);
        // 侧风也会让地速略降（空速被用来抵消侧风）
        assertThat(adj.groundSpeedFactor()).isLessThan(1.0);
    }

    @Test
    @DisplayName("规划风裕度：逆风航段 > 1，顺风航段 < 1，且附加安全系数生效")
    void planningMarginIsDirectionAware() {
        WindField wind = field(true, 5.0);

        // 向北（迎风）：能耗 1.5 × 1.05 = 1.575
        double head = wind.planningEnergyFactor(15.0, 0.0, 80.0);
        assertThat(head).isCloseTo(1.5 * 1.05, within(1e-4));

        // 向南（顺风）：能耗 0.75 × 1.05
        double tail = wind.planningEnergyFactor(15.0, 180.0, 80.0);
        assertThat(tail).isCloseTo(0.75 * 1.05, within(1e-4));

        // 往返平均略高于 1：能耗 ∝ 1/地速 是凸函数，逆风的惩罚大于顺风的收益，
        // 这就是"往返风向相反并不完全抵消"的物理原因（也是真实运营要留风裕度的理由）。
        assertThat((head + tail) / 2).isGreaterThan(1.05);
        assertThat((head + tail) / 2).isLessThan(head);
    }

    @Test
    @DisplayName("规划风裕度：关闭风场或关闭规划开关时恒为 1")
    void planningMarginDisabledIsNeutral() {
        assertThat(field(false, 5.0).planningEnergyFactor(15.0, 0.0, 80.0)).isEqualTo(1.0);

        SkyProperties.Wind off = new SkyProperties.Wind(
                true, 0.0, 5.0, 10.0, 0.0, 0.0, 41.0, 0.0, 0.55, 0.60, 2.20, false, 1.05);
        assertThat(new WindField(properties(off)).planningEnergyFactor(15.0, 0.0, 80.0)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("规划风裕度不随仿真时间变化（派单结果可复现）")
    void planningMarginIsTimeIndependent() {
        WindField wind = field(true, 5.0);
        double a = wind.planningEnergyFactor(15.0, 0.0, 80.0);
        double b = wind.planningEnergyFactor(15.0, 0.0, 80.0);
        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("极端逆风：地速有下限兜底，不会把仿真卡死")
    void extremeHeadwindKeepsPositiveGroundSpeed() {
        WindField wind = field(true, 80.0);
        WindField.Adjustment adj = wind.adjust(15.0, 0.0, 80.0, 0.0);

        assertThat(adj.groundSpeedFactor()).isGreaterThan(0.0);
        assertThat(adj.groundSpeedFactor()).isLessThanOrEqualTo(1.0);
        // 能耗封顶，避免电量被瞬间打空
        assertThat(adj.energyFactor()).isLessThanOrEqualTo(2.20);
    }
}
