package com.skydispatch.dispatch;

import com.skydispatch.domain.enums.DispatchStrategyType;
import org.springframework.stereotype.Component;

import java.util.Comparator;

/**
 * 策略一：最近可用（Nearest Available）。
 *
 * <p><b>目标</b>：让无人机尽快到达取货点，把"接单响应时间"压到最短。
 *
 * <p><b>适用</b>：订单密集、无人机充足的城市核心区。
 * 这时瓶颈是响应速度，不是电量。
 *
 * <p><b>代价</b>：只看距离会导致"近的飞机被反复派单、远的飞机长期闲置"，
 * 电量和寿命分布会越来越不均衡。所以还要有负载均衡策略兜底。
 */
@Component
public class NearestAvailableStrategy implements DispatchStrategy {

    @Override
    public DispatchStrategyType type() {
        return DispatchStrategyType.NEAREST_AVAILABLE;
    }

    @Override
    public double score(DroneCandidate candidate) {
        return candidate.distanceToPickupM();
    }

    /**
     * 距离相同时，优先用"电量更高"的那架：
     * 同样的路程，电量高的飞机到达后剩余裕度更大，后续可接单空间更大。
     */
    @Override
    public Comparator<DroneCandidate> comparator() {
        return Comparator.comparingDouble(DroneCandidate::distanceToPickupM)
                .thenComparing(Comparator.comparingDouble(DroneCandidate::batteryPct).reversed());
    }

    @Override
    public String description() {
        return "最近可用：距离取货点最近的空闲无人机优先，追求最短响应时间";
    }
}
