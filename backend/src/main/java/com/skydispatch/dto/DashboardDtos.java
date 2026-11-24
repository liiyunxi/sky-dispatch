package com.skydispatch.dto;

import com.skydispatch.domain.entity.ConflictEvent;
import com.skydispatch.domain.enums.ConflictSeverity;
import com.skydispatch.domain.enums.ConflictStatus;
import com.skydispatch.domain.enums.ConflictType;
import com.skydispatch.domain.enums.DroneStatus;
import com.skydispatch.domain.enums.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 看板（Dashboard）与 WebSocket 推送用的数据模型。
 *
 * <p>这些字段是"前端一屏所需"的最小集合，因此在 Java 里被复用于：
 * REST 的 {@code /dashboard/*}、WebSocket 的 TELEMETRY/SNAPSHOT/KPI 消息。
 */
public final class DashboardDtos {

    private DashboardDtos() {
    }

    /**
     * 单架无人机的一帧遥测。
     * 字段名与 docs/03-api-contract.md 的 TelemetryFrame 完全一致。
     *
     * <p>{@code modelCode} 是给渲染层用的：三种机型的外形（机臂数量、机身尺寸、
     * 货舱数）不同，看板与立体视图要按型号画不同的机身，所以遥测帧里必须带上型号，
     * 否则前端只能画成一样的。
     */
    public record DroneFrame(
            Long droneId,
            String droneCode,
            /** 机型代码（SKY-X4 / SKY-M8 / SKY-H2），前端据此选择机身外形 */
            String modelCode,
            Long taskId,
            String orderNo,
            DroneStatus status,
            Double lat,
            Double lon,
            BigDecimal altitudeM,
            BigDecimal speedMps,
            BigDecimal headingDeg,
            BigDecimal batteryPct,
            BigDecimal progressPct,
            Integer waypointSeq,
            /** 这帧观测对应的采样时刻（延迟/陈旧时早于当前时刻）。 */
            Instant observedAt,
            /** 观测数据是否延迟。 */
            boolean delayed,
            /** 是否丢包后沿用的陈旧帧。 */
            boolean stale,
            /** 通信中断（失联）。 */
            boolean commLost,
            /** GPS 漂移。 */
            boolean gpsDrift,
            Instant ts
    ) {
    }

    /** 核心指标。一次查询拿到全部，避免看板发一堆请求。 */
    public record Kpi(
            long totalOrders,
            long pendingOrders,
            long inFlightOrders,
            long deliveredOrders,
            long failedOrders,
            long totalTasks,
            long completedTasks,
            long activeDrones,
            long totalDrones,
            double avgBatteryPct,
            Double avgDeliverySeconds,
            Double onTimeRate,
            long openConflicts,
            BigDecimal simTimeS,
            Long tickCount,
            String runStatus,
            int onlineClients
    ) {
    }

    /**
     * 订单地理标记：地图上画"从哪取货 / 送到哪里"用。
     *
     * <p>只带坐标与状态等最小字段，避免把整张订单明细塞进每秒推送的快照。
     * 前端据此区分待派（取货点 + 目的地）、在飞、已送达（目的地变绿）三种视觉。
     */
    public record OrderPin(
            Long orderId,
            String orderNo,
            OrderStatus status,
            BigDecimal weightKg,
            Double pickupLat,
            Double pickupLon,
            Double dropoffLat,
            Double dropoffLon,
            Long assignedDroneId
    ) {
    }

    /**
     * 当前风场读数（看板顶部 HUD 展示用）。
     *
     * @param enabled    风场是否启用
     * @param speedMps   瞬时风速（含阵风，米/秒）
     * @param directionDeg 风的来向（度，正北 0、顺时针）
     */
    public record WindView(
            boolean enabled,
            BigDecimal speedMps,
            BigDecimal directionDeg
    ) {
    }

    /** 连接建立时的全量快照。 */
    public record Snapshot(
            BigDecimal simTimeS,
            String runStatus,
            Long tickCount,
            List<DroneFrame> drones,
            Kpi kpi,
            List<ConflictView> openConflicts,
            List<OrderPin> orders,
            WindView wind
    ) {
    }

    /** 每个 tick 的增量推送（只含位置/电量等变化频繁的字段）。 */
    public record TelemetryPayload(BigDecimal simTimeS, Long tickCount, List<DroneFrame> drones,
                                   WindView wind) {
    }

    /** 冲突事件视图。 */
    public record ConflictView(
            Long id,
            ConflictType conflictType,
            ConflictSeverity severity,
            ConflictStatus status,
            Long droneAId,
            Long droneBId,
            Long zoneId,
            BigDecimal horizontalDistanceM,
            BigDecimal verticalDistanceM,
            BigDecimal closingSpeedMps,
            BigDecimal timeToConflictS,
            Double lat,
            Double lon,
            Instant detectedAt,
            String message
    ) {
        public static ConflictView from(ConflictEvent e, String message) {
            return new ConflictView(e.getId(), e.getConflictType(), e.getSeverity(), e.getStatus(),
                    e.getDroneAId(), e.getDroneBId(), e.getZoneId(),
                    e.getHorizontalDistanceM(), e.getVerticalDistanceM(), e.getClosingSpeedMps(),
                    e.getTimeToConflictS(), e.getLat(), e.getLon(), e.getDetectedAt(), message);
        }
    }
}
