package com.skydispatch.dto;

import com.skydispatch.domain.entity.DispatchDecision;
import com.skydispatch.domain.entity.DispatchTask;
import com.skydispatch.domain.entity.EventLog;
import com.skydispatch.domain.entity.FlightWaypoint;
import com.skydispatch.domain.enums.EventSeverity;
import com.skydispatch.domain.enums.TaskStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 任务、航路点、事件、调度决策的响应模型。
 */
public final class TaskDtos {

    private TaskDtos() {
    }

    /** 任务详情。orderNo / droneCode 是冗余字段，省掉前端两次额外查询。 */
    public record Response(
            Long id,
            String taskNo,
            Long orderId,
            String orderNo,
            Long droneId,
            String droneCode,
            Long routeId,
            String strategy,
            TaskStatus status,
            BigDecimal progressPct,
            Integer currentWaypointSeq,
            BigDecimal plannedDistanceM,
            BigDecimal actualDistanceM,
            BigDecimal plannedEnergyWh,
            BigDecimal consumedEnergyWh,
            BigDecimal batteryStartPct,
            BigDecimal batteryEndPct,
            Instant assignedAt,
            Instant startedAt,
            Instant finishedAt,
            Integer durationS,
            String failReason
    ) {
        public static Response from(DispatchTask t, String orderNo, String droneCode) {
            return new Response(t.getId(), t.getTaskNo(), t.getOrderId(), orderNo,
                    t.getDroneId(), droneCode, t.getRouteId(), t.getStrategy(), t.getStatus(),
                    t.getProgressPct(), t.getCurrentWaypointSeq(),
                    t.getPlannedDistanceM(), t.getActualDistanceM(),
                    t.getPlannedEnergyWh(), t.getConsumedEnergyWh(),
                    t.getBatteryStartPct(), t.getBatteryEndPct(),
                    t.getAssignedAt(), t.getStartedAt(), t.getFinishedAt(), t.getDurationS(), t.getFailReason());
        }
    }

    /** 航路点。 */
    public record WaypointResponse(
            Integer seq, Double lat, Double lon, BigDecimal altitudeM,
            BigDecimal cumulativeDistanceM, Integer etaSeconds
    ) {
        public static WaypointResponse from(FlightWaypoint w) {
            return new WaypointResponse(w.getSeq(), w.getLat(), w.getLon(), w.getAltitudeM(),
                    w.getCumulativeDistanceM(), w.getEtaSeconds());
        }
    }

    /** 事件时间线的一条记录（回放的基础数据）。 */
    public record EventResponse(
            Long id, Long taskId, Long orderId, Long droneId,
            String eventType, EventSeverity severity, String message,
            String payload, BigDecimal simTimeS, Instant ts
    ) {
        public static EventResponse from(EventLog e) {
            return new EventResponse(e.getId(), e.getTaskId(), e.getOrderId(), e.getDroneId(),
                    e.getEventType(), e.getSeverity(), e.getMessage(), e.getPayload(),
                    e.getSimTimeS(), e.getTs());
        }
    }

    /** 调度评分明细：解释"为什么选它"。 */
    public record DecisionResponse(
            Long id, Long orderId, Long droneId, String droneCode, String strategy,
            BigDecimal score, Integer rankNo, Boolean selected, String rejectedReason,
            String detail, Instant createdAt
    ) {
        public static DecisionResponse from(DispatchDecision d, String droneCode) {
            return new DecisionResponse(d.getId(), d.getOrderId(), d.getDroneId(), droneCode,
                    d.getStrategy(), d.getScore(), d.getRankNo(), d.getSelected(),
                    d.getRejectedReason(), d.getDetail(), d.getCreatedAt());
        }
    }

    /** 派单结果：任务 + 是否新规划了航路 + 规划耗时，便于观测算法服务表现。 */
    public record DispatchResult(
            Response task,
            String strategy,
            boolean routePlanned,
            long planElapsedMs,
            String message
    ) {
    }

    /** 中止任务请求。reason 会写进任务与订单的失败原因，便于事后归因。 */
    public record AbortRequest(String reason) {
    }

    /**
     * 派单前"搜罗可用无人机"的预览结果。
     *
     * <p>与 {@link DecisionResponse} 的区别：决策明细是<b>事后</b>完整留痕（含被淘汰候选），
     * 而这个预览是<b>事前</b>只把"能承接这单"的无人机捞出来给用户看——
     * 载重够、电量够往返，按策略排好序，用户一眼看到可用运力，而不是全机队列表。
     */
    public record CandidatePreview(
            Long orderId,
            String orderNo,
            String strategy,
            /** 本次共评估的无人机总数（含被淘汰的，用于显示"筛选自 N 架"）。 */
            int evaluatedTotal,
            int qualifiedCount,
            int rejectedCount,
            /** 入围者中无需补能、直飞即可完成的架数。 */
            int directCount,
            /** 入围者中必须中途补能才能完成的架数。 */
            int relayCount,
            List<QualifiedDrone> qualified
    ) {
    }

    /** 通过全部硬条件的入围候选，已按策略评分排序。 */
    public record QualifiedDrone(
            Long droneId,
            String droneCode,
            String modelCode,
            String modelName,
            BigDecimal maxPayloadKg,
            /** 当前位置到取货点的直线距离（米）。 */
            double distanceToPickupM,
            BigDecimal batteryPct,
            BigDecimal usableEnergyWh,
            /** 完整任务（含补能绕行）的实际飞行能耗；直飞时即调机+去程载货+回程。 */
            BigDecimal missionEnergyWh,
            /** 中途补能次数；0 表示直飞可达。 */
            int chargingStops,
            /** 可达性结论（一句话），可直接展示给运营。 */
            String reachability,
            /** 途经补能站名称，按飞行顺序；空表示直飞。 */
            List<String> chargingStopNames,
            long activeTaskCount,
            BigDecimal score,
            int rank
    ) {
    }
}
