package com.skydispatch.dispatch;

import com.skydispatch.domain.enums.DispatchStrategyType;
import org.springframework.stereotype.Component;

import java.util.Comparator;

/**
 * 策略三：负载均衡（Load Balance）。
 *
 * <p><b>目标</b>：把任务摊平到所有飞机上，避免"少数飞机连轴转、多数飞机晒太阳"。
 *
 * <p><b>适用</b>：机队规模较大、注重设备寿命与维护成本的场景。
 * 均匀使用能让电池循环次数、电机磨损更平均，长期运维成本更低。
 *
 * <p><b>代价</b>：可能为了均衡而选择较远的飞机，牺牲单次响应时间。
 * 真实系统里通常用加权评分（距离 × w1 + 负载 × w2）来折中，
 * 权重就放在 {@code sky.dispatch.weight-*} 配置里 —— 这也是留给读者的第一个练习：
 * 实现一个 {@code WeightedStrategy}，把三种指标加权成一个分数。
 */
@Component
public class LoadBalanceStrategy implements DispatchStrategy {

    @Override
    public DispatchStrategyType type() {
        return DispatchStrategyType.LOAD_BALANCE;
    }

    /** 主指标就是"当前未结束任务数"，越少越优先。 */
    @Override
    public double score(DroneCandidate candidate) {
        return candidate.activeTaskCount();
    }

    /**
     * 负载相同时按距离、再按电量排序：
     * 先保证不绕远，再用高电量飞机换取后续排班空间。
     */
    @Override
    public Comparator<DroneCandidate> comparator() {
        return Comparator.comparingDouble(this::score)
                .thenComparingDouble(DroneCandidate::distanceToPickupM)
                .thenComparing(Comparator.comparingDouble(DroneCandidate::batteryPct).reversed());
    }

    @Override
    public String description() {
        return "负载均衡：当前任务数最少的无人机优先，摊平机队使用强度";
    }
}
