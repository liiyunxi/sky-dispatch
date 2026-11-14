package com.skydispatch.dispatch;

import com.skydispatch.domain.enums.DispatchStrategyType;
import org.springframework.stereotype.Component;

import java.util.Comparator;

/**
 * 策略二：电量优先（Battery First）。
 *
 * <p><b>目标</b>：优先派出电量最高的无人机，最大化"一次派单后的可用裕度"，
 * 减少因电量不足导致的中途返航。
 *
 * <p><b>适用</b>：长航程、重载、或偏远区域任务（比如跨区配送、海岛配送）。
 *
 * <p><b>代价</b>：电量最高的飞机往往刚充完电停在机巢，可能离取货点很远，
 * 会出现"派了满电的飞机，但它要飞 8 公里去取货"的浪费。
 * 所以实践中常把它和搜索半径配合使用。
 */
@Component
public class BatteryFirstStrategy implements DispatchStrategy {

    @Override
    public DispatchStrategyType type() {
        return DispatchStrategyType.BATTERY_FIRST;
    }

    /**
     * 主指标用"剩余电量越低分越高"的等价写法：{@code 100 - batteryPct}。
     * 这样接口只暴露"越小越优先"一种语义，不需要为不同策略反转排序方向。
     */
    @Override
    public double score(DroneCandidate candidate) {
        return 100.0 - candidate.batteryPct();
    }

    /** 电量相同（比如都是 100%）时按距离近的优先，避免无意义的浪费。 */
    @Override
    public Comparator<DroneCandidate> comparator() {
        return Comparator.comparingDouble(this::score)
                .thenComparingDouble(DroneCandidate::distanceToPickupM);
    }

    @Override
    public String description() {
        return "电量优先：剩余电量最高的无人机优先，最大化续航裕度";
    }
}
