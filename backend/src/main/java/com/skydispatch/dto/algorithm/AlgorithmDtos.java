package com.skydispatch.dto.algorithm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/**
 * 与 Python 算法服务之间的数据契约（Java 侧）。
 *
 * <p>字段名必须与 {@code docs/03-api-contract.md} 第 4 节 **逐字一致**，
 * 因为 Java 直接把 record 序列化成 JSON 发给 Python，没有中间映射层。
 * 这是"契约优先"的代价，也是好处：改字段时双方都必须同步。
 *
 * <p>所有响应 record 都加了 {@code @JsonIgnoreProperties(ignoreUnknown = true)}：
 * 这样 Python 侧增加新字段（比如新版算法多返回一个统计量）不会把 Java 打挂，
 * 属于前后兼容的必要防御。
 */
public final class AlgorithmDtos {

    private AlgorithmDtos() {
    }

    /** 经纬度点（算法服务的通用输入）。 */
    public record LatLon(double lat, double lon) {
    }

    /**
     * 障碍物/空域定义。圆形用 {@code center + radiusM}，多边形用 {@code points}。
     * 高度区间决定该障碍在本次飞行高度下是否生效（三维空域的一维简化）。
     */
    public record ObstacleSpec(
            String id,
            String name,
            String shape,
            LatLon center,
            Double radiusM,
            List<LatLon> points,
            Double minAltitudeM,
            Double maxAltitudeM
    ) {
    }

    // ------------------------------------------------------------------
    // 路径规划：POST /api/v1/path/plan
    // ------------------------------------------------------------------

    /** 规划请求。 */
    public record PlanPathRequest(
            String requestId,
            LatLon start,
            LatLon goal,
            Double altitudeM,
            Double cruiseSpeedMps,
            Double gridSizeM,
            Integer maxSearchNodes,
            Double keepOutMarginM,
            Boolean smooth,
            List<ObstacleSpec> obstacles
    ) {
    }

    /** 规划结果。注意 {@code found=false} 是正常业务结果，不是异常。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PathPlanResult(
            boolean success,
            String requestId,
            String algorithm,
            boolean found,
            String message,
            Double distanceM,
            List<Waypoint> waypoints,
            Integer expandedNodes,
            Double elapsedMs
    ) {
    }

    /** 航路点。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Waypoint(
            int seq,
            double lat,
            double lon,
            double altitudeM,
            double cumulativeDistanceM
    ) {
    }

    // ------------------------------------------------------------------
    // 冲突检测：POST /api/v1/conflict/check
    // ------------------------------------------------------------------

    /** 参与检测的一架无人机的即时状态。 */
    public record DroneState(
            long droneId,
            String droneCode,
            double lat,
            double lon,
            double altitudeM,
            double speedMps,
            double headingDeg,
            double batteryPct
    ) {
    }

    /** 冲突检测请求。 */
    public record ConflictCheckRequest(
            Instant checkTime,
            Double lookaheadSeconds,
            Double minHorizontalSeparationM,
            Double minVerticalSeparationM,
            List<DroneState> drones,
            List<ObstacleSpec> zones
    ) {
    }

    /** 冲突检测结果。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConflictCheckResult(
            boolean success,
            int conflictCount,
            List<ConflictItem> conflicts
    ) {
    }

    /** 单条冲突。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConflictItem(
            String conflictType,
            String severity,
            Long droneAId,
            Long droneBId,
            /** 空域编码（如 Z-NF-001），不是数据库主键；Java 侧再解析成 zoneId */
            String zoneId,
            Double horizontalDistanceM,
            Double verticalDistanceM,
            Double closingSpeedMps,
            Double timeToConflictS,
            Double lat,
            Double lon,
            String message
    ) {
    }

    // ------------------------------------------------------------------
    // 健康检查：GET /health
    // ------------------------------------------------------------------

    /** 算法服务健康状态。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HealthResult(String status, String service, String version, String python) {
    }
}
