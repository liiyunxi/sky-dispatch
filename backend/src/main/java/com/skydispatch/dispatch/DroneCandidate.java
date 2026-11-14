package com.skydispatch.dispatch;

import com.skydispatch.domain.entity.Drone;
import com.skydispatch.domain.entity.DroneModel;

import java.math.BigDecimal;

/**
 * 派单候选：一架"有资格执行本次任务"的无人机，以及评估它所需的所有上下文。
 *
 * <p>为什么要有这个 record，而不是直接把 {@code Drone} 传给策略？
 * 因为策略打分需要的信息不止无人机本身（还要算到取货点的距离、当前积压任务数、
 * 电量够不够飞完往返）。这些值只算一次，然后传给所有策略复用，
 * 避免每个策略各算一遍造成 N 倍重复计算——这是很典型的"上下文对象"模式。
 *
 * @param drone             无人机实体
 * @param model             机型（显式持有，避免策略里触发懒加载）
 * @param distanceToPickupM 当前点到取货点的直线距离（米）
 * @param activeTaskCount   该机当前未结束的任务数（负载指标）
 * @param usableEnergyWh    扣除安全冗余后本次可用的能量（Wh）
 * @param missionEnergyWh   完成本次任务（去程载货 + 回程空载）预计消耗的能量（Wh）
 */
public record DroneCandidate(
        Drone drone,
        DroneModel model,
        double distanceToPickupM,
        long activeTaskCount,
        BigDecimal usableEnergyWh,
        BigDecimal missionEnergyWh
) {

    public String code() {
        return drone.getCode();
    }

    public double batteryPct() {
        return drone.getBatteryPct() == null ? 0 : drone.getBatteryPct().doubleValue();
    }

    public double cruiseSpeedMps() {
        return model.getCruiseSpeedMps().doubleValue();
    }

    public double maxPayloadKg() {
        return model.getMaxPayloadKg().doubleValue();
    }

    /** 能量裕度（Wh）：正数表示飞得完还有富余，负数表示飞不到。 */
    public BigDecimal energyMarginWh() {
        return usableEnergyWh.subtract(missionEnergyWh);
    }
}
