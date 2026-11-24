package com.skydispatch.engine;

import com.skydispatch.common.GeoPoint;
import com.skydispatch.common.GeoUtils;
import com.skydispatch.domain.entity.ConflictEvent;
import com.skydispatch.domain.entity.DeliveryOrder;
import com.skydispatch.domain.entity.DispatchTask;
import com.skydispatch.domain.entity.Drone;
import com.skydispatch.domain.entity.DroneModel;
import com.skydispatch.domain.entity.FlightRoute;
import com.skydispatch.domain.entity.FlightWaypoint;
import com.skydispatch.domain.enums.ConflictSeverity;
import com.skydispatch.domain.enums.ConflictStatus;
import com.skydispatch.domain.enums.ConflictType;
import com.skydispatch.domain.enums.TaskStatus;
import com.skydispatch.dto.algorithm.AlgorithmDtos;
import com.skydispatch.repository.ConflictEventRepository;
import com.skydispatch.repository.DispatchTaskRepository;
import com.skydispatch.service.AirspaceService;
import com.skydispatch.service.EventService;
import com.skydispatch.service.RouteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地（Java 侧）碰撞检测与自动改航避让。
 *
 * <p><b>为什么不直接依赖 Python 算法服务</b>：路径规划可以在没有 Python 时降级为直线航路，
 * 但碰撞检测原先没有 Java 兜底——{@code sky.algorithm.enabled=false}（dev 默认）时
 * 每拍的检测调用直接返回空，相当于"闭着眼飞"。本服务用纯 Java 做预测与避让，
 * 不依赖外部进程，成为安全底线。
 *
 * <p><b>检测模型</b>：每架在飞无人机沿当前航路按巡航速度推进，对未来
 * {@value #LOOKAHEAD_SECONDS} 秒按 {@value #STEP_SECONDS} 秒采样：
 * <ul>
 *   <li>机-机：同一时刻两点的水平间距 &lt; {@value #MIN_SEPARATION_M} 且高度差
 *       &lt; {@value #VERTICAL_SEPARATION_M} 即预测碰撞（高度层不同不算）；</li>
 *   <li>机-区：采样点是否进入生效空域的高度层与几何范围（圆/多边形）。</li>
 * </ul>
 *
 * <p><b>自动改航</b>：在冲突点两侧插入"狗腿"侧向偏移航路点，并<b>用与检测完全相同的
 * 时空预测器对新航路做全量校验</b>——既要躲开原对手，也不能撞向第三架飞机或别的空域
 * （直接回应"改航导致二次碰撞"）。候选偏移量逐级加大，取第一个通过校验且最短的；
 * 都不通过则只告警、不动航线，交人工处置。
 */
@Service
public class CollisionAvoidanceService {

    private static final Logger log = LoggerFactory.getLogger(CollisionAvoidanceService.class);

    /** 预测安全间隔（米）：比运行时硬阈值 50m 略大，留出处置提前量。 */
    private static final double MIN_SEPARATION_M = 60.0;
    private static final double VERTICAL_SEPARATION_M = 15.0;
    private static final double LOOKAHEAD_SECONDS = 60.0;
    private static final double STEP_SECONDS = 4.0;
    /** 参与碰撞预测的任务状态（ASSIGNED 尚未起飞、CHARGING 停在桩上，都不占用空中间隔）。 */
    private static final List<TaskStatus> COLLISION_STATUSES = List.of(
            TaskStatus.TAKEOFF, TaskStatus.CRUISING, TaskStatus.DELIVERING, TaskStatus.RETURNING);
    /** 改航航线相对空域边界的额外净空（米）。 */
    private static final double ZONE_CLEARANCE_M = 100.0;
    /** 同一冲突指纹（或同一飞机）两次改航的最小仿真秒间隔。 */
    private static final double REROUTE_COOLDOWN_S = 45.0;
    /** 机-机避让的候选侧向偏移（米），逐级加大。 */
    private static final double[] DRONE_DETOUR_OFFSETS = { 120.0, 200.0, 300.0 };
    /** 冲突点前后插入狗腿的沿程距离（米）。 */
    private static final double DETOUR_ALONG_TRACK_M = 150.0;

    private final DispatchTaskRepository taskRepository;
    private final ConflictEventRepository conflictRepository;
    private final AirspaceService airspaceService;
    private final RouteService routeService;
    private final EventService eventService;
    private final RerouteRegistry rerouteRegistry;
    private final WindField windField;

    /** 冲突指纹 → 上次记录的仿真秒（窗口去重，避免同一冲突每拍刷屏）。 */
    private final Map<String, Double> recentFingerprints = new HashMap<>();
    /** 无人机 id → 上次改航的仿真秒（冷却）。 */
    private final Map<Long, Double> lastRerouteSimSec = new HashMap<>();

    public CollisionAvoidanceService(DispatchTaskRepository taskRepository,
                                     ConflictEventRepository conflictRepository,
                                     AirspaceService airspaceService,
                                     RouteService routeService,
                                     EventService eventService,
                                     RerouteRegistry rerouteRegistry,
                                     WindField windField) {
        this.taskRepository = taskRepository;
        this.conflictRepository = conflictRepository;
        this.airspaceService = airspaceService;
        this.routeService = routeService;
        this.eventService = eventService;
        this.rerouteRegistry = rerouteRegistry;
        this.windField = windField;
    }

    /**
     * 扫描并处置一拍的冲突。
     *
     * <p>任务 / 无人机 / 航路 / 航路点 / 订单都由调用方（仿真一拍）批量预加载后传入，
     * 避免在逐机检测里再做 N+1 查询；每拍查询次数从 5×N 降为 0。
     *
     * @return 本次<b>新记录</b>的冲突事件（已去重、已落库、已推送）
     */
    @Transactional
    public List<ConflictEvent> scanAndAvoid(BigDecimal simTimeBd, Long runId,
                                           List<DispatchTask> activeTasks,
                                           Map<Long, Drone> drones,
                                           Map<Long, FlightRoute> routes,
                                           Map<Long, List<FlightWaypoint>> waypointsByRoute,
                                           Map<Long, DeliveryOrder> orders) {
        double simTime = simTimeBd == null ? 0 : simTimeBd.doubleValue();
        recentFingerprints.values().removeIf(t -> simTime - t > 120.0);

        List<DispatchTask> flightsToCheck = activeTasks.stream()
                .filter(t -> COLLISION_STATUSES.contains(t.getStatus()))
                .toList();
        if (flightsToCheck.isEmpty()) {
            return List.of();
        }

        List<Flight> flights = new ArrayList<>();
        for (DispatchTask task : flightsToCheck) {
            Flight flight = buildFlight(task, drones, routes, waypointsByRoute, orders, simTime);
            if (flight != null) {
                flights.add(flight);
            }
        }
        if (flights.isEmpty()) {
            return List.of();
        }

        List<AlgorithmDtos.ObstacleSpec> obstacles = airspaceService.activeObstacleSpecs();
        List<ConflictEvent> events = new ArrayList<>();

        // 1) 机-机：逐对预测，确定式地让 id 较大的飞机避让
        for (int i = 0; i < flights.size(); i++) {
            for (int j = i + 1; j < flights.size(); j++) {
                Flight a = flights.get(i);
                Flight b = flights.get(j);
                PredictedConflict hit = predictPair(a, b);
                if (hit == null) {
                    continue;
                }
                Flight giveWay = a.drone.getId() >= b.drone.getId() ? a : b;
                Flight other = giveWay == a ? b : a;
                String fp = "DD:" + Math.min(a.drone.getId(), b.drone.getId())
                        + "-" + Math.max(a.drone.getId(), b.drone.getId());
                if (isDuplicate(fp, simTime)) {
                    continue;
                }
                boolean avoided;
                try {
                    avoided = tryDroneReroute(giveWay, other, hit, simTime, flights, obstacles);
                } catch (Exception ex) {
                    // 改航落库失败不能毒化整拍，也不能让去重指纹残留（否则后续不再重试）
                    recentFingerprints.remove(fp);
                    avoided = false;
                    log.error("机-机自动改航异常（按未避让记录冲突）: {}", ex.getMessage(), ex);
                }
                ConflictEvent event = recordDroneConflict(giveWay, other, hit, avoided, simTimeBd, runId);
                events.add(event);
                log.warn("机-机冲突预测: {} 与 {}，TTC={}s，间距={}m，{}",
                        giveWay.drone.getCode(), other.drone.getCode(),
                        Math.round(hit.timeToConflictS), Math.round(hit.distanceM),
                        avoided ? "已自动改航避让" : "无可用避让航线，保持告警");
            }
        }

        // 2) 机-区：逐架检查当前航段
        for (Flight flight : flights) {
            ZoneBreach breach = predictZoneBreach(flight, obstacles);
            if (breach == null) {
                continue;
            }
            String fp = "DZ:" + flight.drone.getId() + ":" + breach.zone.id();
            if (isDuplicate(fp, simTime)) {
                continue;
            }
            boolean avoided;
            try {
                avoided = tryZoneReroute(flight, breach, simTime, flights, obstacles);
            } catch (Exception ex) {
                recentFingerprints.remove(fp);
                avoided = false;
                log.error("机-区自动改航异常（按未避让记录冲突）: {}", ex.getMessage(), ex);
            }
            ConflictEvent event = recordZoneConflict(flight, breach, avoided, simTimeBd, runId);
            events.add(event);
            log.warn("机-区冲突预测: {} 将进入 {}，TTC={}s，{}",
                    flight.drone.getCode(), breach.zone.name(), Math.round(breach.timeToConflictS),
                    avoided ? "已自动改航避让" : "无可用避让航线，保持告警");
        }
        return events;
    }

    // ------------------------------------------------------------------
    // 飞行状态建模
    // ------------------------------------------------------------------

    /**
     * 一架在飞无人机的预测模型。
     *
     * @param futureLine 从当前时刻起、完整未来轨迹折线（去程剩余 + 回家航段）
     * @param startDist  当前位置在 futureLine 上的已走距离（米）
     * @param legTarget  当前航段目标（送货点或机巢）
     * @param returning  当前是否返航段
     */
    private static final class Flight {
        DispatchTask task;
        Drone drone;
        DroneModel model;
        DeliveryOrder order;
        /** 从当前时刻起的完整未来轨迹（含去程后的回家段），用于碰撞预测。 */
        List<GeoPoint> futureLine;
        /** 当前航段折线（当前位置 → 当前目标），用于生成避让候选。 */
        List<GeoPoint> legLine;
        double startDist;
        GeoPoint current;
        GeoPoint legTarget;
        boolean returning;
        double speedMps;
        double altitudeM;
        double payloadKg;
        /**
         * 风修正后的地速系数（逆风 &lt; 1）。
         *
         * <p>外推必须和仿真推进用同一个地速，否则"预测 40 秒后到某点"和实际到达时刻会漂移，
         * 冲突告警就会变成误报或漏报。
         */
        double groundSpeedFactor = 1.0;

        /** 未来 futureSec 秒的位置；航程结束（落地/回家）后返回 null，表示不再占用空域。 */
        GeoPoint positionAt(double futureSec) {
            double d = startDist + speedMps * groundSpeedFactor * futureSec;
            if (d < 0 || d > GeoUtils.polylineLengthMeters(futureLine) + 1.0) {
                return null;
            }
            return GeoUtils.pointAtDistance(futureLine, d);
        }

        /** 当前航段折线（避让候选沿它生成）。 */
        List<GeoPoint> currentLegLine() {
            return legLine;
        }
    }

    private Flight buildFlight(DispatchTask task,
                               Map<Long, Drone> drones,
                               Map<Long, FlightRoute> routes,
                               Map<Long, List<FlightWaypoint>> waypointsByRoute,
                               Map<Long, DeliveryOrder> orders,
                               double simTimeSec) {
        Drone drone = drones.get(task.getDroneId());
        if (drone == null || drone.getCurrentLat() == null || drone.getCurrentLon() == null
                || task.getRouteId() == null) {
            return null;
        }
        FlightRoute route = routes.get(task.getRouteId());
        if (route == null) {
            return null;
        }
        DroneModel model = drone.getModel();
        List<FlightWaypoint> routeWaypoints = waypointsByRoute.getOrDefault(route.getId(), List.of());
        if (routeWaypoints.size() < 2) {
            return null;
        }
        List<GeoPoint> line = routeWaypoints.stream()
                .map(w -> new GeoPoint(w.getLat(), w.getLon()))
                .toList();
        DeliveryOrder order = orders.get(task.getOrderId());
        double payload = order == null ? 0 : order.getWeightKg().doubleValue();
        double speed = model.getCruiseSpeedMps().doubleValue();
        double alt = routeService.waypoints(route.getId()).stream()
                .map(w -> w.getAltitudeM() == null ? 80.0 : w.getAltitudeM().doubleValue())
                .findFirst().orElse(80.0);
        double flown = task.getActualDistanceM() == null ? 0 : task.getActualDistanceM().doubleValue();
        double D = route.getDistanceM().doubleValue();
        GeoPoint current = new GeoPoint(drone.getCurrentLat(), drone.getCurrentLon());
        boolean returning = task.getStatus() == TaskStatus.RETURNING
                || rerouteRegistry.leg(task.getId()) == RerouteRegistry.Leg.RETURNING;

        Flight f = new Flight();
        f.task = task;
        f.drone = drone;
        f.model = model;
        f.order = order;
        f.speedMps = speed;
        // 冲突预测统一按航路巡航高度：无人机大部分时间在该高度平飞；
        // 若用起飞瞬间的 0 米做未来 60 秒预测，会把所有"顶层 60m 的限飞区"误报为冲突
        f.altitudeM = alt;
        f.payloadKg = returning ? 0 : payload;
        f.returning = returning;
        f.current = current;

        List<GeoPoint> future = new ArrayList<>();
        if (returning && rerouteRegistry.leg(task.getId()) == RerouteRegistry.Leg.RETURNING) {
            // 改航后的返航：新折线方向即"当前→机巢"，正向使用
            f.startDist = Math.min(Math.max(flown, 0), D);
            List<GeoPoint> leg = subLine(line, f.startDist);
            future.addAll(leg);
            f.legLine = leg;
            f.legTarget = line.get(line.size() - 1);
        } else if (returning) {
            // 正常返航：原折线反向，从当前返航位置到机巢
            List<GeoPoint> reversed = new ArrayList<>(line);
            java.util.Collections.reverse(reversed);
            double returnFlown = Math.min(Math.max(flown - D, 0), D);
            f.startDist = returnFlown;
            List<GeoPoint> leg = subLine(reversed, returnFlown);
            future.addAll(leg);
            f.legLine = leg;
            f.legTarget = reversed.get(reversed.size() - 1);
        } else {
            // 去程：当前位置 → 送货点（沿原/改航折线），到家直线作为预测延续
            double outboundFlown = Math.min(Math.max(flown, 0), D);
            List<GeoPoint> toDropoff = subLine(line, outboundFlown);
            f.legLine = toDropoff;
            f.legTarget = toDropoff.get(toDropoff.size() - 1);
            future.addAll(toDropoff);
            if (drone.getHomeLat() != null && drone.getHomeLon() != null) {
                future.add(new GeoPoint(drone.getHomeLat(), drone.getHomeLon()));
            }
            f.startDist = 0;
        }
        f.futureLine = future;
        // 用"当前位置 → 前方 50m"的方位角取风修正，保证外推速度与仿真推进一致
        double total = GeoUtils.polylineLengthMeters(future);
        GeoPoint ahead = GeoUtils.pointAtDistance(future, Math.min(50.0, total));
        double bearing = GeoUtils.bearingDegrees(current.lat(), current.lon(), ahead.lat(), ahead.lon());
        f.groundSpeedFactor = windField
                .adjust(speed, bearing, alt, simTimeSec)
                .groundSpeedFactor();
        return f;
    }

    /** 取折线上从 fromDist 到终点的子折线（起点替换为插值点，保留后续顶点）。 */
    private List<GeoPoint> subLine(List<GeoPoint> line, double fromDist) {
        List<GeoPoint> out = new ArrayList<>();
        out.add(GeoUtils.pointAtDistance(line, fromDist));
        double travelled = 0;
        for (int i = 1; i < line.size(); i++) {
            double seg = line.get(i - 1).distanceTo(line.get(i));
            if (travelled + seg > fromDist + 0.5) {
                out.add(line.get(i));
            }
            travelled += seg;
        }
        if (out.size() == 1) {
            out.add(line.get(line.size() - 1));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 预测
    // ------------------------------------------------------------------

    private record PredictedConflict(GeoPoint point, double timeToConflictS,
                                    double distanceM, double closingSpeedMps) {
    }

    private PredictedConflict predictPair(Flight a, Flight b) {
        if (Math.abs(a.altitudeM - b.altitudeM) >= VERTICAL_SEPARATION_M) {
            return null; // 高度层错开，水平接近不算冲突
        }
        GeoPoint bestPoint = null;
        double bestT = Double.MAX_VALUE;
        double bestDist = MIN_SEPARATION_M;
        for (double t = STEP_SECONDS; t <= LOOKAHEAD_SECONDS + 0.1; t += STEP_SECONDS) {
            GeoPoint pa = a.positionAt(t);
            GeoPoint pb = b.positionAt(t);
            if (pa == null || pb == null) {
                break; // 至少一方已落地，之后不再占用空中间隔
            }
            double dist = pa.distanceTo(pb);
            if (dist < MIN_SEPARATION_M && t < bestT) {
                bestT = t;
                bestDist = dist;
                bestPoint = new GeoPoint((pa.lat() + pb.lat()) / 2, (pa.lon() + pb.lon()) / 2);
            }
        }
        if (bestPoint == null) {
            return null;
        }
        double closing = 0;
        GeoPoint pa0 = a.positionAt(Math.max(0, bestT - STEP_SECONDS));
        GeoPoint pb0 = b.positionAt(Math.max(0, bestT - STEP_SECONDS));
        if (pa0 != null && pb0 != null) {
            closing = (pa0.distanceTo(pb0) - bestDist) / STEP_SECONDS;
        }
        return new PredictedConflict(bestPoint, bestT, bestDist, closing);
    }

    private record ZoneBreach(AlgorithmDtos.ObstacleSpec zone, GeoPoint point,
                             double timeToConflictS, double distanceM) {
    }

    private ZoneBreach predictZoneBreach(Flight f, List<AlgorithmDtos.ObstacleSpec> obstacles) {
        // 从下一个采样时刻开始预测：当前位置（t=0）已经侵入某个区域时，
        // 属于"既成事实"而不是预测，继续生成 "TTC=0s" 的提醒没有意义，只会刷屏。
        // 只对"未来会进入"的空域告警，已经在区内的区域直接跳过。
        for (double t = STEP_SECONDS; t <= LOOKAHEAD_SECONDS + 0.1; t += STEP_SECONDS) {
            GeoPoint p = f.positionAt(t);
            if (p == null) {
                break;
            }
            for (AlgorithmDtos.ObstacleSpec zone : obstacles) {
                if (!altitudeInBand(f.altitudeM, zone) || containsPoint(zone, f.current)) {
                    continue;
                }
                if (zone.shape().equals("CIRCLE") && zone.center() != null && zone.radiusM() != null) {
                    GeoPoint c = new GeoPoint(zone.center().lat(), zone.center().lon());
                    double dist = p.distanceTo(c);
                    if (dist <= zone.radiusM()) {
                        return new ZoneBreach(zone, p, t, dist);
                    }
                } else if (zone.shape().equals("POLYGON") && zone.points() != null) {
                    List<GeoPoint> poly = zone.points().stream()
                            .map(x -> new GeoPoint(x.lat(), x.lon())).toList();
                    if (GeoUtils.pointInPolygon(p.lat(), p.lon(), poly)) {
                        return new ZoneBreach(zone, p, t, 0);
                    }
                }
            }
        }
        return null;
    }

    /** 点是否已在该空域的几何范围内（不含额外净空余量，用于判断"当前已侵入"）。 */
    private boolean containsPoint(AlgorithmDtos.ObstacleSpec zone, GeoPoint p) {
        if (zone.shape().equals("CIRCLE") && zone.center() != null && zone.radiusM() != null) {
            GeoPoint c = new GeoPoint(zone.center().lat(), zone.center().lon());
            return p.distanceTo(c) <= zone.radiusM();
        }
        if (zone.shape().equals("POLYGON") && zone.points() != null) {
            List<GeoPoint> poly = zone.points().stream()
                    .map(x -> new GeoPoint(x.lat(), x.lon())).toList();
            return GeoUtils.pointInPolygon(p.lat(), p.lon(), poly);
        }
        return false;
    }

    private boolean altitudeInBand(double alt, AlgorithmDtos.ObstacleSpec zone) {
        double min = zone.minAltitudeM() == null ? 0 : zone.minAltitudeM();
        double max = zone.maxAltitudeM() == null ? 10_000 : zone.maxAltitudeM();
        return alt >= min && alt <= max;
    }

    // ------------------------------------------------------------------
    // 改航候选生成与校验
    // ------------------------------------------------------------------

    /**
     * 机-机避让：在冲突点两侧插入侧向狗腿。
     * 用与检测相同的预测器校验，确保改后航线与<b>所有</b>在飞飞机都保持间隔，
     * 这是避免"为躲 A 撞上 B"的关键。
     */
    private boolean tryDroneReroute(Flight giveWay, Flight other, PredictedConflict hit,
                                    double simTime, List<Flight> all,
                                    List<AlgorithmDtos.ObstacleSpec> obstacles) {
        Double last = lastRerouteSimSec.get(giveWay.drone.getId());
        if (last != null && simTime - last < REROUTE_COOLDOWN_S) {
            return false;
        }
        double legBearing = GeoUtils.bearingDegrees(
                giveWay.current.lat(), giveWay.current.lon(),
                giveWay.legTarget.lat(), giveWay.legTarget.lon());
        double dAlong = giveWay.current.distanceTo(hit.point);

        List<GeoPoint> best = null;
        double bestExtra = Double.MAX_VALUE;
        for (double offset : DRONE_DETOUR_OFFSETS) {
            for (int side = -1; side <= 1; side += 2) {
                double perpBearing = legBearing + (side > 0 ? 90 : -90);
                List<GeoPoint> leg = giveWay.currentLegLine();
                double legLen = GeoUtils.polylineLengthMeters(leg);
                GeoPoint before = GeoUtils.destinationPoint(
                        GeoUtils.pointAtDistance(leg, Math.max(0, dAlong - DETOUR_ALONG_TRACK_M)),
                        perpBearing, offset);
                GeoPoint middle = GeoUtils.destinationPoint(hit.point, perpBearing, offset * 1.25);
                GeoPoint after = GeoUtils.destinationPoint(
                        GeoUtils.pointAtDistance(leg, Math.min(legLen, dAlong + DETOUR_ALONG_TRACK_M)),
                        perpBearing, offset);
                List<GeoPoint> candidate = List.of(giveWay.current, before, middle, after, giveWay.legTarget);
                if (!energyFits(giveWay, candidate)) {
                    continue;
                }
                if (!zonesClear(candidate, giveWay.altitudeM, obstacles)) {
                    continue;
                }
                if (!separationFromAll(giveWay, candidate, all)) {
                    continue;
                }
                double extra = GeoUtils.polylineLengthMeters(candidate) - legLen;
                if (extra < bestExtra) {
                    bestExtra = extra;
                    best = candidate;
                }
            }
        }
        if (best == null) {
            return false;
        }
        applyReroute(giveWay, best, simTime);
        return true;
    }

    /**
     * 空域避让：先找出当前航线在<b>整个</b>穿越区间（进入点→离开点），
     * 再在区间外侧用逐级加大的侧向偏移做整段绕飞。
     *
     * <p>早期实现只在冲突点前后摆动几百米，若航线贯穿一个大半径机场空域（实测穿越段长达 5km），
     * 摆动后的"回到目标"直线会立刻扎回禁区。整段绕行才能真正绕开：
     * 绕飞点放在"进入前 / 穿越中点 / 离开后"，全部偏向远离区中心的同一侧。
     */
    private boolean tryZoneReroute(Flight f, ZoneBreach breach, double simTime,
                                   List<Flight> all, List<AlgorithmDtos.ObstacleSpec> obstacles) {
        Double last = lastRerouteSimSec.get(f.drone.getId());
        if (last != null && simTime - last < REROUTE_COOLDOWN_S) {
            return false;
        }

        List<GeoPoint> leg = f.currentLegLine();
        double legLen = GeoUtils.polylineLengthMeters(leg);

        // 1) 沿当前航段采样，找出穿越该空域的 [进入, 离开] 沿程区间和最深点
        double entryAlong = -1;
        double exitAlong = -1;
        double deepestAlong = 0;
        GeoPoint deepestPoint = null;
        GeoPoint zoneAnchor = zoneReferencePoint(breach.zone);
        double step = 150.0;
        for (double d = 0; d <= legLen + 1; d += step) {
            GeoPoint p = GeoUtils.pointAtDistance(leg, d);
            if (pointViolatesZone(p, f.altitudeM, breach.zone)) {
                if (entryAlong < 0) {
                    entryAlong = d;
                }
                exitAlong = d;
                // 取离区参考点最近的采样作为最深点，绕飞方向就选"从这里背离区域"
                if (deepestPoint == null
                        || p.distanceTo(zoneAnchor) < deepestPoint.distanceTo(zoneAnchor)) {
                    deepestPoint = p;
                    deepestAlong = d;
                }
            }
        }
        if (entryAlong < 0) {
            // 预测会进入但当前采样没采到（时间预测与空间采样粒度差异）：以预测点为穿越点
            entryAlong = exitAlong = deepestAlong = f.current.distanceTo(breach.point);
            deepestPoint = breach.point;
        }
        double midAlong = (entryAlong + exitAlong) / 2.0;
        double awayBearing = GeoUtils.bearingDegrees(
                zoneAnchor.lat(), zoneAnchor.lon(), deepestPoint.lat(), deepestPoint.lon());

        // 2) 候选侧向偏移逐级加大（米）：小穿越用小绕飞，贯穿大空域时必须绕到几公里外
        double[] lateralChoices = { 200, 400, 700, 1100, 1600, 2300, 3200 };
        boolean currentlyInside = pointViolatesZone(f.current, f.altitudeM, breach.zone);
        List<GeoPoint> best = null;
        double bestExtra = Double.MAX_VALUE;
        int rejectZone = 0;
        int rejectSeparation = 0;
        for (double lateral : lateralChoices) {
            GeoPoint q1 = GeoUtils.destinationPoint(
                    GeoUtils.pointAtDistance(leg, Math.max(0, entryAlong - 250)),
                    awayBearing, lateral);
            GeoPoint q2 = GeoUtils.destinationPoint(
                    GeoUtils.pointAtDistance(leg, midAlong),
                    awayBearing, lateral * 1.12);
            GeoPoint q3 = GeoUtils.destinationPoint(
                    GeoUtils.pointAtDistance(leg, Math.min(legLen, exitAlong + 250)),
                    awayBearing, lateral);
            List<GeoPoint> candidate = List.of(f.current, q1, q2, q3, f.legTarget);
            // 起点已在区内（上一拍避让失败/临界进入）时，允许前 250m 仍在区内，但必须尽快穿出
            if (!zonesClear(candidate, f.altitudeM, obstacles, currentlyInside ? 250.0 : 0.0)) {
                rejectZone++;
                continue;
            }
            if (!separationFromAll(f, candidate, all)) {
                rejectSeparation++;
                continue;
            }
            double extra = GeoUtils.polylineLengthMeters(candidate) - legLen;
            if (extra < bestExtra) {
                bestExtra = extra;
                best = candidate;
            }
        }
        if (best == null) {
            log.debug("[避让诊断] {} 空域绕飞候选全失败: 净空拦截{} 间隔拦截{} 穿越区间{}~{}m 最深点距心{}m 在区内={}",
                    breach.zone.id(), rejectZone, rejectSeparation,
                    Math.round(entryAlong), Math.round(exitAlong),
                    Math.round(deepestPoint.distanceTo(zoneAnchor)), currentlyInside);
            return false;
        }
        applyReroute(f, best, simTime);
        return true;
    }

    /** 单点是否违反某个空域（高度层 + 圆/多边形几何）。 */
    private boolean pointViolatesZone(GeoPoint p, double alt, AlgorithmDtos.ObstacleSpec zone) {
        if (!altitudeInBand(alt, zone)) {
            return false;
        }
        if (zone.shape().equals("CIRCLE") && zone.center() != null && zone.radiusM() != null) {
            return p.distanceTo(new GeoPoint(zone.center().lat(), zone.center().lon()))
                    <= zone.radiusM() + ZONE_CLEARANCE_M;
        }
        if (zone.shape().equals("POLYGON") && zone.points() != null) {
            List<GeoPoint> poly = zone.points().stream()
                    .map(x -> new GeoPoint(x.lat(), x.lon())).toList();
            if (GeoUtils.pointInPolygon(p.lat(), p.lon(), poly)) {
                return true;
            }
            for (int i = 1; i < poly.size(); i++) {
                if (GeoUtils.distanceToSegmentMeters(p, poly.get(i - 1), poly.get(i)) < ZONE_CLEARANCE_M) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 校验候选航线与其余所有在飞飞机在预测时段内保持安全间隔（含回家延续段）。 */
    private boolean separationFromAll(Flight self, List<GeoPoint> candidateLeg, List<Flight> all) {
        // 候选飞机的预测轨迹：当前航段 + 去程送达后的回家直线
        List<GeoPoint> candFuture = new ArrayList<>(candidateLeg);
        if (!self.returning && self.drone.getHomeLat() != null && self.drone.getHomeLon() != null) {
            candFuture.add(new GeoPoint(self.drone.getHomeLat(), self.drone.getHomeLon()));
        }
        double candLen = GeoUtils.polylineLengthMeters(candFuture);
        for (Flight other : all) {
            if (other.drone.getId().equals(self.drone.getId())) {
                continue;
            }
            if (Math.abs(self.altitudeM - other.altitudeM) >= VERTICAL_SEPARATION_M) {
                continue;
            }
            for (double t = 0; t <= LOOKAHEAD_SECONDS + 0.1; t += STEP_SECONDS) {
                double d = self.speedMps * t;
                if (d > candLen) {
                    break;
                }
                GeoPoint pc = GeoUtils.pointAtDistance(candFuture, d);
                GeoPoint po = other.positionAt(t);
                if (po == null) {
                    break;
                }
                if (pc.distanceTo(po) < MIN_SEPARATION_M) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 沿候选航线密集采样，确认每个点都与生效空域保持净空（圆用半径+余量，多边形用射线法）。 */
    private boolean zonesClear(List<GeoPoint> candidate, double alt,
                              List<AlgorithmDtos.ObstacleSpec> obstacles) {
        return zonesClear(candidate, alt, obstacles, 0.0);
    }

    /**
     * @param startToleranceM 允许航线开头这段距离仍在区内（飞机已经处于区内时，
     *                       不可能瞬移出去，只要求尽快穿出；之后必须全程净空）
     */
    private boolean zonesClear(List<GeoPoint> candidate, double alt,
                              List<AlgorithmDtos.ObstacleSpec> obstacles, double startToleranceM) {
        double total = GeoUtils.polylineLengthMeters(candidate);
        for (double d = 0; d <= total; d += 300.0) {
            if (d <= startToleranceM) {
                continue;
            }
            GeoPoint p = GeoUtils.pointAtDistance(candidate, d);
            for (AlgorithmDtos.ObstacleSpec zone : obstacles) {
                if (!altitudeInBand(alt, zone)) {
                    continue;
                }
                if (zone.shape().equals("CIRCLE") && zone.center() != null && zone.radiusM() != null) {
                    GeoPoint c = new GeoPoint(zone.center().lat(), zone.center().lon());
                    if (p.distanceTo(c) <= zone.radiusM() + ZONE_CLEARANCE_M) {
                        return false;
                    }
                } else if (zone.shape().equals("POLYGON") && zone.points() != null) {
                    List<GeoPoint> poly = zone.points().stream()
                            .map(x -> new GeoPoint(x.lat(), x.lon())).toList();
                    if (GeoUtils.pointInPolygon(p.lat(), p.lon(), poly)) {
                        return false;
                    }
                    // 边界余量：点到最近边的距离不足也判定不清净
                    for (int i = 1; i < poly.size(); i++) {
                        if (GeoUtils.distanceToSegmentMeters(p, poly.get(i - 1), poly.get(i)) < ZONE_CLEARANCE_M) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    /** 粗校验剩余电量是否够飞完候选航线（避免为了避让派出一架回不来的飞机）。 */
    private boolean energyFits(Flight f, List<GeoPoint> candidate) {
        double legLen = GeoUtils.polylineLengthMeters(candidate);
        double needWh;
        if (f.returning) {
            needWh = f.model.getEnergyPerKmWh().doubleValue() * legLen / 1000.0;
        } else {
            // 去程载货 + 回家空载
            double returnKm = (f.drone.getHomeLat() == null ? legLen
                    : new GeoPoint(f.drone.getHomeLat(), f.drone.getHomeLon())
                            .distanceTo(f.legTarget)) / 1000.0;
            needWh = f.model.getEnergyPerKmWh().doubleValue()
                    * (legLen / 1000.0 * (1 + 0.05 * f.payloadKg) + returnKm);
        }
        double usableWh = f.model.getBatteryCapacityWh().doubleValue()
                * Math.max(0, f.drone.getBatteryPct().doubleValue() - 25.0) / 100.0;
        return usableWh >= needWh;
    }

    private GeoPoint zoneReferencePoint(AlgorithmDtos.ObstacleSpec zone) {
        if (zone.center() != null) {
            return new GeoPoint(zone.center().lat(), zone.center().lon());
        }
        if (zone.points() != null && !zone.points().isEmpty()) {
            double lat = 0;
            double lon = 0;
            for (AlgorithmDtos.LatLon p : zone.points()) {
                lat += p.lat();
                lon += p.lon();
            }
            return new GeoPoint(lat / zone.points().size(), lon / zone.points().size());
        }
        return new GeoPoint(0, 0);
    }

    // ------------------------------------------------------------------
    // 应用改航
    // ------------------------------------------------------------------

    private void applyReroute(Flight f, List<GeoPoint> newLeg, double simTime) {
        double newLen = routeService.replaceWaypoints(f.task.getRouteId(), newLeg, f.altitudeM);
        f.task.setActualDistanceM(BigDecimal.ZERO);
        f.task.setProgressPct(BigDecimal.ZERO);
        f.task.setCurrentWaypointSeq(0);
        if (f.returning) {
            f.task.setStatus(TaskStatus.RETURNING);
            rerouteRegistry.mark(f.task.getId(), RerouteRegistry.Leg.RETURNING);
        } else {
            f.task.setStatus(TaskStatus.CRUISING);
            rerouteRegistry.mark(f.task.getId(), RerouteRegistry.Leg.OUTBOUND);
        }
        taskRepository.save(f.task);

        // 同步本拍内的预测模型，后续配对校验基于新航线，杜绝二次碰撞
        f.startDist = 0;
        f.current = newLeg.get(0);
        f.legLine = new ArrayList<>(newLeg);
        List<GeoPoint> updatedFuture = new ArrayList<>(newLeg);
        if (!f.returning && f.drone.getHomeLat() != null && f.drone.getHomeLon() != null) {
            updatedFuture.add(new GeoPoint(f.drone.getHomeLat(), f.drone.getHomeLon()));
        }
        f.futureLine = updatedFuture;
        lastRerouteSimSec.put(f.drone.getId(), simTime);
        log.info("自动改航: 任务 {}（{}）切换新航线，新航段长度 {}m，避让点 {}",
                f.task.getTaskNo(), f.drone.getCode(), Math.round(newLen),
                GeoUtils.format(newLeg.get(2).lat(), newLeg.get(2).lon()));
    }

    // ------------------------------------------------------------------
    // 事件落库
    // ------------------------------------------------------------------

    private ConflictEvent recordDroneConflict(Flight a, Flight b, PredictedConflict hit,
                                             boolean avoided, BigDecimal simTime, Long runId) {
        ConflictSeverity severity = hit.timeToConflictS <= 15 ? ConflictSeverity.CRITICAL
                : hit.timeToConflictS <= 35 ? ConflictSeverity.HIGH : ConflictSeverity.MEDIUM;
        String msg = String.format(
                "预测 %s 与 %s 将在 %.0f 秒后接近至 %.0fm（小于安全间隔 %.0fm），%s",
                a.drone.getCode(), b.drone.getCode(), hit.timeToConflictS,
                hit.distanceM, MIN_SEPARATION_M,
                avoided ? "系统已自动切换航线避让" : "暂无可用避让航线，请人工处置");
        ConflictEvent event = ConflictEvent.builder()
                .runId(runId)
                .taskId(a.task.getId())
                .droneAId(a.drone.getId())
                .droneBId(b.drone.getId())
                .conflictType(ConflictType.DRONE_DRONE)
                .severity(severity)
                .status(ConflictStatus.OPEN)
                .horizontalDistanceM(scale(hit.distanceM))
                .verticalDistanceM(scale(Math.abs(a.altitudeM - b.altitudeM)))
                .closingSpeedMps(scale(hit.closingSpeedMps))
                .timeToConflictS(scale(hit.timeToConflictS))
                .lat(hit.point.lat())
                .lon(hit.point.lon())
                .detectedAt(Instant.now())
                .detail(msg)
                .build();
        ConflictEvent saved = conflictRepository.save(event);
        eventService.conflictDetected(saved, simTime, runId);
        return saved;
    }

    private ConflictEvent recordZoneConflict(Flight f, ZoneBreach breach, boolean avoided,
                                            BigDecimal simTime, Long runId) {
        Long zoneDbId = airspaceService.idByCode(breach.zone.id()).orElse(null);
        String msg = String.format(
                "预测 %s 将在 %.0f 秒后进入%s「%s」，%s",
                f.drone.getCode(), breach.timeToConflictS,
                avoided ? "（已避开）" : "", breach.zone.name(),
                avoided ? "系统已自动切换航线避让" : "暂无可用避让航线，请人工处置");
        ConflictEvent event = ConflictEvent.builder()
                .runId(runId)
                .taskId(f.task.getId())
                .droneAId(f.drone.getId())
                .zoneId(zoneDbId)
                .conflictType(ConflictType.DRONE_ZONE)
                .severity(avoided ? ConflictSeverity.MEDIUM : ConflictSeverity.CRITICAL)
                .status(ConflictStatus.OPEN)
                .horizontalDistanceM(scale(breach.distanceM))
                .timeToConflictS(scale(breach.timeToConflictS))
                .lat(breach.point.lat())
                .lon(breach.point.lon())
                .detectedAt(Instant.now())
                .detail(msg)
                .build();
        ConflictEvent saved = conflictRepository.save(event);
        eventService.conflictDetected(saved, simTime, runId);
        return saved;
    }

    private boolean isDuplicate(String fingerprint, double simTime) {
        Double last = recentFingerprints.get(fingerprint);
        if (last != null && simTime - last < 120.0) {
            return true;
        }
        recentFingerprints.put(fingerprint, simTime);
        return false;
    }

    private BigDecimal scale(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }
}
