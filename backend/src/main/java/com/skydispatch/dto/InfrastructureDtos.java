package com.skydispatch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 基础设施（充电站 / 机巢 / 战略时隙）对外契约。
 *
 * <p>三类数据在一次响应里返回，是因为它们共同回答同一个问题：
 * "这套基础设施现在能不能支撑下一波任务"。
 * 拆成三个接口会让页面出现"桩位和机巢不是同一时刻"的读数错位。
 */
public final class InfrastructureDtos {

    private InfrastructureDtos() {
    }

    /** 顶部汇总读数。 */
    public record Summary(
            int stationCount,
            int totalChargeSlots,
            int chargingDrones,
            int queuedDrones,
            long estimatedMaxWaitSeconds,
            int vertiportCount,
            int totalPads,
            int busyVertiports,
            int slotOccupancy,
            int slotCapacity
    ) {
    }

    /** 充电站实时视图：桩位、排队、预计等待。 */
    public record StationView(
            Long id,
            String code,
            String name,
            String city,
            Double lat,
            Double lon,
            Integer chargeSlots,
            Integer occupancy,
            Integer queueLength,
            Long estimatedWaitSeconds,
            Boolean enabled,
            String remark
    ) {
    }

    /** 机巢站点视图：每站单独的坪位/周转配置 + 当前运行态。 */
    public record VertiportView(
            Long id,
            String code,
            String name,
            String siteKey,
            Double lat,
            Double lon,
            Integer slots,
            Double turnaroundSeconds,
            Integer basedDrones,
            Integer busyDrones,
            Integer turnaroundDrones,
            Long nextFreeInSeconds
    ) {
    }

    /** 战略时隙里的一个占用者（任务 / 无人机 / 订单）。 */
    public record SlotOccupant(
            Long taskId,
            String taskNo,
            String droneCode,
            Long orderId
    ) {
    }

    /** 一条"走廊扇区 + 高度层"的时隙。 */
    public record SlotView(
            String key,
            String sector,
            Integer layerM,
            Double startSimSec,
            Double endSimSec,
            Integer capacity,
            List<SlotOccupant> occupants
    ) {
    }

    /** 战略时隙快照（只读）。 */
    public record SlotSnapshot(
            Boolean enabled,
            Double slotSeconds,
            Integer departuresPerSlot,
            List<SlotView> slots
    ) {
    }

    /** 基础设施总览。 */
    public record Overview(
            Instant ts,
            Double simTimeS,
            Summary summary,
            List<StationView> stations,
            List<VertiportView> vertiports,
            SlotSnapshot slots
    ) {
    }

    /** 修改机巢的坪位数 / 周转时间。字段都可选，只改传入的项。 */
    public record VertiportUpdate(
            Integer slots,
            Double turnaroundSeconds
    ) {
    }
}
