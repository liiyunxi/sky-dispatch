package com.skydispatch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skydispatch.common.BusinessException;
import com.skydispatch.common.ErrorCode;
import com.skydispatch.common.GeoPoint;
import com.skydispatch.config.SkyProperties;
import com.skydispatch.domain.entity.DeliveryOrder;
import com.skydispatch.domain.entity.Drone;
import com.skydispatch.domain.entity.DroneModel;
import com.skydispatch.domain.entity.FlightRoute;
import com.skydispatch.domain.entity.FlightWaypoint;
import com.skydispatch.domain.enums.RouteStatus;
import com.skydispatch.dto.algorithm.AlgorithmDtos;
import com.skydispatch.engine.AltitudeLayers;
import com.skydispatch.engine.EnergyModel;
import com.skydispatch.engine.FlightTimeEstimator;
import com.skydispatch.integration.AlgorithmServiceClient;
import com.skydispatch.repository.FlightRouteRepository;
import com.skydispatch.repository.FlightWaypointRepository;
import com.skydispatch.repository.SimulationRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 航路服务：把"订单 + 无人机"变成一条可执行的航路。
 *
 * <p>它是最能体现本平台分工的一层：<b>Java 决定算什么、什么时候算，Python 负责怎么算。</b>
 * 本类只做三件事：组装请求 → 校验算法返回 → 落库成 {@code flight_route}/{@code flight_waypoint}。
 *
 * <p>三种失败路径的处理策略（这是本节最值得读的部分）：
 * <table border="1">
 *   <caption>规划失败处理</caption>
 *   <tr><th>情况</th><th>处理</th><th>原因</th></tr>
 *   <tr><td>{@code sky.algorithm.enabled=false}</td><td>退化为两点直线航路</td>
 *       <td>开发者显式关闭了算法服务，用于纯 Java 离线开发</td></tr>
 *   <tr><td>算法服务连不上/超时</td><td>抛 2001，请求返回 503</td>
 *       <td>不能悄悄用直线穿过禁飞区，必须让人知道</td></tr>
 *   <tr><td>算法返回 found=false</td><td>抛 2002</td>
 *       <td>确实无可行航路（被禁飞区完全阻断），派单应当失败</td></tr>
 * </table>
 */
@Service
public class RouteService {

    private static final Logger log = LoggerFactory.getLogger(RouteService.class);

    /** 落库时 raw_response 字段的最大长度，与 V1 SQL 的 VARCHAR(8000) 对齐。 */
    private static final int RAW_RESPONSE_MAX_LENGTH = 8000;

    private final FlightRouteRepository routeRepository;
    private final FlightWaypointRepository waypointRepository;
    private final AirspaceService airspaceService;
    private final AlgorithmServiceClient algorithmClient;
    private final EnergyModel energyModel;
    private final AltitudeLayers altitudeLayers;
    private final FlightTimeEstimator timeEstimator;
    private final SkyProperties properties;
    private final ObjectMapper objectMapper;
    private final SimulationRunRepository runRepository;

    public RouteService(FlightRouteRepository routeRepository,
                        FlightWaypointRepository waypointRepository,
                        AirspaceService airspaceService,
                        AlgorithmServiceClient algorithmClient,
                        EnergyModel energyModel,
                        AltitudeLayers altitudeLayers,
                        FlightTimeEstimator timeEstimator,
                        SkyProperties properties,
                        ObjectMapper objectMapper,
                        SimulationRunRepository runRepository) {
        this.routeRepository = routeRepository;
        this.waypointRepository = waypointRepository;
        this.airspaceService = airspaceService;
        this.algorithmClient = algorithmClient;
        this.energyModel = energyModel;
        this.altitudeLayers = altitudeLayers;
        this.timeEstimator = timeEstimator;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.runRepository = runRepository;
    }

    /**
     * 只计算航路，<b>不写库、不开启事务</b>。
     *
     * <p>这是三段式派单的第二段：此时订单/无人机已读出来、事务已结束，
     * 算法服务的 HTTP 调用（最长 10s）不会占用数据库连接，也不会持有任何行锁。
     * 计算结果交给 {@link #persistRoute} 在短事务里落库。
     */
    public PlannedRoute planRouteData(DeliveryOrder order, Drone drone, DroneModel model) {
        GeoPoint start = new GeoPoint(order.getPickupLat(), order.getPickupLon());
        GeoPoint goal = new GeoPoint(order.getDropoffLat(), order.getDropoffLon());
        // 高度层按无人机分配：同机队不再全部挤在 default-altitude-m 这一层
        double altitude = altitudeLayers.forDrone(drone);
        double cruiseSpeed = model.getCruiseSpeedMps().doubleValue();

        List<AlgorithmDtos.Waypoint> waypoints;
        String algorithm;
        Long planElapsedMs;
        String rawResponse;

        if (properties.algorithm().enabled()) {
            AlgorithmDtos.PlanPathRequest request = buildRequest(order, start, goal, altitude, cruiseSpeed);
            AlgorithmDtos.PathPlanResult result = algorithmClient.plan(request)
                    .orElseThrow(() -> new BusinessException(ErrorCode.ALGORITHM_UNAVAILABLE,
                            "路径规划调用失败，请检查算法服务 " + algorithmClient.baseUrl()));

            if (!result.found() || result.waypoints() == null || result.waypoints().size() < 2) {
                throw new BusinessException(ErrorCode.PATH_PLAN_FAILED,
                        "未找到可行航路（" + result.message() + "），订单 " + order.getOrderNo() + " 无法派发");
            }
            waypoints = result.waypoints();
            algorithm = result.algorithm() == null ? "ASTAR" : result.algorithm();
            planElapsedMs = result.elapsedMs() == null ? null : result.elapsedMs().longValue();
            rawResponse = truncate(toJson(result));
        } else {
            // 显式关闭算法服务：退化成两点直线，仅用于本地开发与教学演示
            log.warn("算法服务已禁用，订单 {} 使用直线航路降级（不避让禁飞区！）", order.getOrderNo());
            waypoints = List.of(
                    new AlgorithmDtos.Waypoint(0, start.lat(), start.lon(), altitude, 0.0),
                    new AlgorithmDtos.Waypoint(1, goal.lat(), goal.lon(), altitude, start.distanceTo(goal)));
            algorithm = "STRAIGHT_LINE";
            planElapsedMs = 0L;
            rawResponse = "{\"fallback\":\"straight_line\"}";
        }

        // 算法服务可能回填自己的高度；统一覆盖成本机的高度层，
        // 保证"规划航路高度 = 仿真巡航高度 = 冲突检测使用的高度"三者一致
        waypoints = waypoints.stream()
                .map(w -> new AlgorithmDtos.Waypoint(
                        w.seq(), w.lat(), w.lon(), altitude, w.cumulativeDistanceM()))
                .toList();

        double distanceM = waypoints.get(waypoints.size() - 1).cumulativeDistanceM();
        // 统一 ETA 口径：爬升 + 下降 + 风修正后的水平巡航 + 起降开销
        double bearing = com.skydispatch.common.GeoUtils.bearingDegrees(
                start.lat(), start.lon(), goal.lat(), goal.lon());
        int durationS = timeEstimator.estimate(distanceM, model, altitude, bearing).seconds();
        /*
         * 计划能耗按完整任务估算：空载调机 + 去程载货 + 回程空载 + 两次起降。
         * 调机段距离要与派单时的续航校验保持同一口径，
         * 否则任务详情里显示的能耗会小于实际消耗，运营会误判电量风险。
         */
        double deadheadM = distanceToPickup(drone, start);
        BigDecimal energyWh = energyModel.missionEnergyWh(
                model, deadheadM, distanceM, order.getWeightKg().doubleValue());

        return new PlannedRoute(algorithm, waypoints, planElapsedMs, rawResponse,
                distanceM, durationS, energyWh, cruiseSpeed);
    }

    /**
     * 三段式派单的第三段：把已算好的航路在短事务里落库。
     *
     * <p>只做 INSERT，不调用算法服务；调用方必须已经在事务中（或本方法自己开一个）。
     */
    @Transactional
    public FlightRoute persistRoute(Long orderId, Long droneId, PlannedRoute planned) {
        FlightRoute route = FlightRoute.builder()
                .orderId(orderId)
                .droneId(droneId)
                .algorithm(planned.algorithm())
                .status(RouteStatus.PLANNED)
                .distanceM(BigDecimal.valueOf(planned.distanceM()).setScale(2, RoundingMode.HALF_UP))
                .estDurationS(planned.durationS())
                .estEnergyWh(planned.energyWh())
                .waypointCount(planned.waypoints().size())
                .gridSizeM(BigDecimal.valueOf(properties.flight().gridSizeM()))
                .planElapsedMs(planned.planElapsedMs() == null ? null : planned.planElapsedMs().intValue())
                .rawResponse(planned.rawResponse())
                .build();
        FlightRoute saved = routeRepository.save(route);

        int totalEta = planned.durationS();
        double totalDistance = Math.max(1e-6, planned.distanceM());
        List<FlightWaypoint> entities = new ArrayList<>(planned.waypoints().size());
        for (AlgorithmDtos.Waypoint wp : planned.waypoints()) {
            entities.add(FlightWaypoint.builder()
                    .routeId(saved.getId())
                    .seq(wp.seq())
                    .lat(wp.lat())
                    .lon(wp.lon())
                    .altitudeM(BigDecimal.valueOf(wp.altitudeM()).setScale(2, RoundingMode.HALF_UP))
                    .cumulativeDistanceM(BigDecimal.valueOf(wp.cumulativeDistanceM()).setScale(2, RoundingMode.HALF_UP))
                    // 航路点 ETA 按累计距离占比分摊总 ETA，口径与任务预计耗时一致
                    .etaSeconds((int) Math.round(totalEta * wp.cumulativeDistanceM() / totalDistance))
                    .build());
        }
        waypointRepository.saveAll(entities);

        log.info("航路落库: orderId={}, droneId={}, 算法={}, 航路点={}, 距离={}m, 预计耗时={}s, 预计能耗={}Wh",
                orderId, droneId, planned.algorithm(), entities.size(),
                String.format("%.0f", planned.distanceM()), planned.durationS(), planned.energyWh());
        return saved;
    }

    private List<AlgorithmDtos.Waypoint> fallbackWaypoints(GeoPoint start, GeoPoint goal) {
        return List.of(
                new AlgorithmDtos.Waypoint(0, start.lat(), start.lon(), properties.flight().defaultAltitudeM(), 0.0),
                new AlgorithmDtos.Waypoint(1, goal.lat(), goal.lon(), properties.flight().defaultAltitudeM(),
                        start.distanceTo(goal)));
    }

    /** 当前仿真时刻（秒）；没有运行中的仿真时按 0 处理。 */
    private java.math.BigDecimal currentSimTime() {
        return runRepository.findFirstByStatusOrderByIdDesc(com.skydispatch.domain.enums.RunStatus.RUNNING)
                .map(com.skydispatch.domain.entity.SimulationRun::getSimTimeS)
                .orElse(java.math.BigDecimal.ZERO);
    }

    /**
     * 规划结果（值对象）：只含数据，不含任何 JPA 实体，便于在事务之间传递。
     */
    public record PlannedRoute(
            String algorithm,
            List<AlgorithmDtos.Waypoint> waypoints,
            Long planElapsedMs,
            String rawResponse,
            double distanceM,
            int durationS,
            BigDecimal energyWh,
            double cruiseSpeedMps
    ) {
    }

    /**
     * 无人机当前位置到取货点的空载距离（米）。
     *
     * <p>这段"调机"飞行也是要耗电的，只是不载货。规划航路时把它的能耗算进去，
     * 计划能耗才和实际飞行对得上。
     */
    private double distanceToPickup(com.skydispatch.domain.entity.Drone drone, GeoPoint pickup) {
        if (drone.getCurrentLat() != null && drone.getCurrentLon() != null) {
            return new GeoPoint(drone.getCurrentLat(), drone.getCurrentLon()).distanceTo(pickup);
        }
        if (drone.getHomeLat() != null && drone.getHomeLon() != null) {
            return new GeoPoint(drone.getHomeLat(), drone.getHomeLon()).distanceTo(pickup);
        }
        return 0;
    }

    /** 组装算法服务请求。障碍物来自空域表，高度/栅格/安全边距来自配置。 */
    private AlgorithmDtos.PlanPathRequest buildRequest(DeliveryOrder order, GeoPoint start, GeoPoint goal,
                                                      double altitude, double cruiseSpeed) {
        // 按当前仿真时刻取障碍物：临时管制区只在时间窗内参与规划
        List<AlgorithmDtos.ObstacleSpec> obstacles = airspaceService.activeObstacleSpecs(currentSimTime());
        return new AlgorithmDtos.PlanPathRequest(
                UUID.randomUUID().toString(),
                new AlgorithmDtos.LatLon(start.lat(), start.lon()),
                new AlgorithmDtos.LatLon(goal.lat(), goal.lon()),
                altitude,
                cruiseSpeed,
                properties.flight().gridSizeM(),
                200_000,
                properties.flight().keepOutMarginM(),
                Boolean.TRUE,
                obstacles);
    }

    /** 取航路的折线点，供仿真引擎推进位置。 */
    @Transactional(readOnly = true)
    public List<GeoPoint> polyline(Long routeId) {
        return waypointRepository.findByRouteIdOrderBySeqAsc(routeId).stream()
                .map(w -> new GeoPoint(w.getLat(), w.getLon()))
                .toList();
    }

    /** 取航路点实体。 */
    @Transactional(readOnly = true)
    public List<FlightWaypoint> waypoints(Long routeId) {
        return waypointRepository.findByRouteIdOrderBySeqAsc(routeId);
    }

    /** 取某订单最新的一条航路（不存在抛 404）。 */
    @Transactional(readOnly = true)
    public FlightRoute require(Long routeId) {
        return routeRepository.findById(routeId)
                .orElseThrow(() -> BusinessException.notFound("航路 id=" + routeId));
    }

    /**
     * 用新的折线<b>整体替换</b>某条航路的航路点（自动改航避让时调用）。
     *
     * <p>不新建航路：任务执行到一半时 {@code task.routeId} 已绑定，新增航路反而要同步改任务，
     * 且会让"一条任务对应一条实时航路"的前端假设失效。直接原地替换明细并更新长度最干净。
     *
     * @param routeId    航路 id
     * @param polyline   新折线（含起点与终点，按飞行顺序）
     * @param altitudeM  巡航高度
     * @return 新航路总长度（米）
     */
    @Transactional
    public double replaceWaypoints(Long routeId, List<GeoPoint> polyline, double altitudeM) {
        FlightRoute route = require(routeId);
        // 必须用批量 DELETE：Hibernate 默认先 flush 新 insert 再执行派生删除，
        // 会撞 (route_id, seq) 唯一索引导致改航回滚（clearAutomatically 同时清掉一级缓存）
        waypointRepository.deleteBulkByRouteId(routeId);

        double total = 0.0;
        List<FlightWaypoint> entities = new ArrayList<>();
        for (int seq = 0; seq < polyline.size(); seq++) {
            GeoPoint wp = polyline.get(seq);
            if (seq > 0) {
                total += polyline.get(seq - 1).distanceTo(wp);
            }
            entities.add(FlightWaypoint.builder()
                    .routeId(routeId)
                    .seq(seq)
                    .lat(wp.lat())
                    .lon(wp.lon())
                    .altitudeM(BigDecimal.valueOf(altitudeM).setScale(2, RoundingMode.HALF_UP))
                    .cumulativeDistanceM(BigDecimal.valueOf(total).setScale(2, RoundingMode.HALF_UP))
                    // ETA 仅用于展示，仿真推进不依赖它，改航航路点统一置 0
                    .etaSeconds(0)
                    .build());
        }
        waypointRepository.saveAll(entities);

        route.setDistanceM(BigDecimal.valueOf(total).setScale(2, RoundingMode.HALF_UP));
        route.setWaypointCount(entities.size());
        route.setAlgorithm("LOCAL_AVOIDANCE");
        routeRepository.save(route);
        return total;
    }

    /** 用起终点直线重建一条短航路（改航后的去程送达后，回程直接回家）。 */
    @Transactional
    public double replaceWithStraightLine(Long routeId, GeoPoint from, GeoPoint to, double altitudeM) {
        return replaceWaypoints(routeId, List.of(from, to), altitudeM);
    }

    /** 取某订单最新的一条航路。 */
    @Transactional(readOnly = true)
    public FlightRoute latestByOrder(Long orderId) {
        return routeRepository.findFirstByOrderIdOrderByIdDesc(orderId)
                .orElseThrow(() -> BusinessException.notFound("订单 " + orderId + " 的航路"));
    }

    /** 更新航路状态（PLANNED → ACTIVE → FINISHED）。 */
    @Transactional
    public void updateStatus(FlightRoute route, RouteStatus status) {
        route.setStatus(status);
        routeRepository.save(route);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"error\":\"serialize_failed\"}";
        }
    }

    private String truncate(String text) {
        if (text == null || text.length() <= RAW_RESPONSE_MAX_LENGTH) {
            return text;
        }
        return text.substring(0, RAW_RESPONSE_MAX_LENGTH);
    }
}
