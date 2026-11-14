package com.skydispatch.dispatch;

import com.skydispatch.domain.entity.Drone;
import com.skydispatch.domain.entity.DroneModel;
import com.skydispatch.domain.enums.DroneStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 派单策略测试。
 *
 * <p>策略是纯函数式的（输入候选、输出排序），非常适合单元测试。
 * 这里重点验证三件事：
 * <ol>
 *   <li>每种策略的主指标符合它的"人设"；</li>
 *   <li>并列时按声明好的次序规则兜底，保证结果可复现；</li>
 *   <li>三个策略在同一组候选上会给出<b>不同</b>的选择——这正是"策略可插拔"的意义。</li>
 * </ol>
 */
class DispatchStrategyTest {

    private final NearestAvailableStrategy nearest = new NearestAvailableStrategy();
    private final BatteryFirstStrategy batteryFirst = new BatteryFirstStrategy();
    private final LoadBalanceStrategy loadBalance = new LoadBalanceStrategy();

    /**
     * 三个候选，刻意设计成"没有一个是全面最优"：
     * <pre>
     *   A：最近（100m）但电量低（40%），已积压 2 单
     *   B：最远（900m）但电量高（95%），无积压
     *   C：居中（300m），电量中（70%），积压 1 单
     * </pre>
     * 于是三种策略分别会选 A / B / B（B 负载最低且电量最高）。
     */
    private final DroneCandidate near = candidate("UAV-A", 100, 40, 2);
    private final DroneCandidate far = candidate("UAV-B", 900, 95, 0);
    private final DroneCandidate middle = candidate("UAV-C", 300, 70, 1);

    private DroneCandidate candidate(String code, double distanceM, double batteryPct, long activeTasks) {
        DroneModel model = DroneModel.builder()
                .code("SKY-M8").name("测试机型")
                .maxPayloadKg(new BigDecimal("15.00"))
                .cruiseSpeedMps(new BigDecimal("18.00"))
                .maxFlightMin(45)
                .batteryCapacityWh(new BigDecimal("900.00"))
                .energyPerKmWh(new BigDecimal("58.000"))
                .hoverPowerW(new BigDecimal("1600.00"))
                .maxAltitudeM(150).enabled(true)
                .build();
        Drone drone = Drone.builder()
                .id((long) code.hashCode())
                .code(code)
                .model(model)
                .status(DroneStatus.IDLE)
                .batteryPct(BigDecimal.valueOf(batteryPct))
                .currentLat(31.23).currentLon(121.47)
                .homeLat(31.23).homeLon(121.47)
                .totalTasks(0).version(0L)
                .build();
        // 本用例只验证排序，能量数值给一组"够用"的固定值即可
        return new DroneCandidate(drone, model, distanceM, activeTasks,
                new BigDecimal("2000.000"), new BigDecimal("800.000"));
    }

    private String pick(DispatchStrategy strategy) {
        return List.of(far, middle, near).stream()
                .min(strategy.comparator())
                .orElseThrow()
                .code();
    }

    @Test
    @DisplayName("最近可用：选距离最小的 A")
    void nearestPicksClosest() {
        assertThat(pick(nearest)).isEqualTo("UAV-A");
        assertThat(nearest.score(near)).isEqualTo(100);
        assertThat(nearest.type().name()).isEqualTo("NEAREST_AVAILABLE");
    }

    @Test
    @DisplayName("电量优先：选电量最高的 B")
    void batteryFirstPicksMostCharged() {
        assertThat(pick(batteryFirst)).isEqualTo("UAV-B");
        // 主指标是 100 - 电量，越小越优先
        assertThat(batteryFirst.score(far)).isEqualTo(5.0);
    }

    @Test
    @DisplayName("负载均衡：选在途任务最少的 B")
    void loadBalancePicksIdlest() {
        assertThat(pick(loadBalance)).isEqualTo("UAV-B");
        assertThat(loadBalance.score(far)).isZero();
    }

    @Test
    @DisplayName("同一组候选下三种策略能给出不同结果，说明策略真的生效了")
    void strategiesDiffer() {
        assertThat(pick(nearest)).isNotEqualTo(pick(batteryFirst));
    }

    @Test
    @DisplayName("并列时按声明的兜底规则排序，保证结果可复现")
    void tieBreaking() {
        // 距离相同、电量不同 → 最近可用策略应选电量高的
        DroneCandidate lowBattery = candidate("UAV-LOW", 500, 30, 0);
        DroneCandidate highBattery = candidate("UAV-HIGH", 500, 90, 0);
        assertThat(List.of(lowBattery, highBattery).stream().min(nearest.comparator()).orElseThrow().code())
                .isEqualTo("UAV-HIGH");

        // 负载相同 → 负载均衡策略应选距离近的
        DroneCandidate farIdle = candidate("UAV-FAR", 2000, 90, 0);
        DroneCandidate nearIdle = candidate("UAV-NEAR", 200, 60, 0);
        assertThat(List.of(farIdle, nearIdle).stream().min(loadBalance.comparator()).orElseThrow().code())
                .isEqualTo("UAV-NEAR");
    }

    @Test
    @DisplayName("每种策略都有中文说明，便于落库与前端展示")
    void descriptionsPresent() {
        assertThat(nearest.description()).contains("最近");
        assertThat(batteryFirst.description()).contains("电量");
        assertThat(loadBalance.description()).contains("负载");
    }
}
