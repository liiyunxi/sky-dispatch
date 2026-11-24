package com.skydispatch.engine;

import com.skydispatch.common.GeoPoint;
import com.skydispatch.common.GeoUtils;
import com.skydispatch.config.SkyProperties;
import com.skydispatch.domain.entity.ConflictEvent;
import com.skydispatch.domain.entity.DeliveryOrder;
import com.skydispatch.domain.entity.DispatchTask;
import com.skydispatch.domain.entity.Drone;
import com.skydispatch.domain.entity.DroneModel;
import com.skydispatch.domain.entity.FlightRoute;
import com.skydispatch.domain.entity.FlightWaypoint;
import com.skydispatch.domain.entity.SimulationRun;
import com.skydispatch.domain.enums.DroneStatus;
import com.skydispatch.domain.enums.OrderStatus;
import com.skydispatch.domain.enums.TaskStatus;
import com.skydispatch.dto.DashboardDtos;
import com.skydispatch.repository.DeliveryOrderRepository;
import com.skydispatch.repository.DispatchTaskRepository;
import com.skydispatch.repository.DroneRepository;
import com.skydispatch.repository.FlightRouteRepository;
import com.skydispatch.repository.FlightWaypointRepository;
import com.skydispatch.service.DashboardService;
import com.skydispatch.service.EventService;
import com.skydispatch.service.OrderService;
import com.skydispatch.service.RouteService;
import com.skydispatch.service.TelemetryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 一拍仿真的完整执行逻辑（离散时间步进）。
 *
 * <p><b>为什么单独一个类？</b> 因为 {@code @Transactional} 是基于代理的：
 * 同一个类里 A 方法调用自己的 B 方法，B 上的事务注解不会生效。
 * 把"单拍工作单元"独立成 Bean，就能让每一拍都跑在一个干净的事务里。
 *
 * <p><b>任务状态机</b>（仿真推进的核心）：
 * <pre>
 *   ASSIGNED ──起飞──▶ TAKEOFF ──▶ CRUISING ──抵达──▶ DELIVERING
 *                                                      │ 卸货
 *                                                      ▼
 *                    COMPLETED ◀──回到机巢── RETURNING
 * </pre>
 *
 * <p><b>位置推进</b>用航路折线做参数化：已知"已飞距离"，就能求出当前位置
 * （{@link GeoUtils#pointAtDistance}）。这比逐点跳变平滑，也让进度百分比连续可算。
 *
 * <p><b>电量消耗</b>按本拍实际飞过的距离扣减（而不是一次扣完），
 * 这样看板上的电量曲线是斜的，能直观看出不同机型的能耗差异。
 */
@Service
public class SimulationTickService {

    private static final Logger log = LoggerFactory.getLogger(SimulationTickService.class);

    /** 参与推进的任务状态。CHARGING 也要收：停在桩上补能的飞机同样在"活动中"。 */
    private static final List<TaskStatus> ACTIVE_STATUSES = List.of(
            TaskStatus.ASSIGNED, TaskStatus.TAKEOFF, TaskStatus.CRUISING,
            TaskStatus.DELIVERING, TaskStatus.RETURNING, TaskStatus.CHARGING);

    /** 判定"到达"的距离容差（米），避免因浮点误差永远差一点点。 */
    private static final double ARRIVAL_TOLERANCE_M = 0.5;

    /**
     * 速度剖面的最小系数：起降瞬间速度会掉到巡航速度的这个比例。
     *
     * <p>为什么不能真的从 0 起步？因为推进量 = 速度 × 时间，速度一旦为 0，
     * 这一拍就前进不了、下一拍还是 0，飞机永远起飞不了。真实多旋翼起飞后
     * 会很快建立前进速度，用一个 30% 的地板既符合直觉又不会自锁。
     */
    private static final double MIN_SPEED_FACTOR = 0.30;

    /** 已发过低电量预警的无人机 id，防止每个 tick 重复报警。 */
    private final java.util.Set<Long> lowBatteryWarned = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final SimulationService simulationService;
    private final DispatchTaskRepository taskRepository;
    private final DroneRepository droneRepository;
    private final DeliveryOrderRepository orderRepository;
    private final FlightRouteRepository routeRepository;
    private final FlightWaypointRepository waypointRepository;
    private final RouteService routeService;
    private final OrderService orderService;
    private final EnergyModel energyModel;
    private final TelemetryService telemetryService;
    private final DashboardService dashboardService;
    private final EventService eventService;
    private final SkyProperties properties;
    private final FleetRechargeService rechargeService;
    private final CollisionAvoidanceService collisionAvoidance;
    private final RerouteRegistry rerouteRegistry;
    private final BatteryRelayService batteryRelay;
    private final WindField windField;

    public SimulationTickService(SimulationService simulationService,
                                 DispatchTaskRepository taskRepository,
                                 DroneRepository droneRepository,
                                 DeliveryOrderRepository orderRepository,
                                 FlightRouteRepository routeRepository,
                                 FlightWaypointRepository waypointRepository,
                                 RouteService routeService,
                                 OrderService orderService,
                                 EnergyModel energyModel,
                                 TelemetryService telemetryService,
                                 DashboardService dashboardService,
                                 EventService eventService,
                                 SkyProperties properties,
                                 FleetRechargeService rechargeService,
                                 CollisionAvoidanceService collisionAvoidance,
                                 RerouteRegistry rerouteRegistry,
                                 BatteryRelayService batteryRelay,
                                 WindField windField) {
        this.simulationService = simulationService;
        this.taskRepository = taskRepository;
        this.droneRepository = droneRepository;
        this.orderRepository = orderRepository;
        this.routeRepository = routeRepository;
        this.waypointRepository = waypointRepository;
        this.routeService = routeService;
        this.orderService = orderService;
        this.energyModel = energyModel;
        this.telemetryService = telemetryService;
        this.dashboardService = dashboardService;
        this.eventService = eventService;
        this.properties = properties;
        this.rechargeService = rechargeService;
        this.collisionAvoidance = collisionAvoidance;
        this.rerouteRegistry = rerouteRegistry;
        this.batteryRelay = batteryRelay;
        this.windField = windField;
    }

    /**
     * 推进一拍。
     *
     * @param run 当前仿真运行
     * @return 本拍结果（供调用方在事务外广播）
     */
    @Transactional
    public TickOutcome tick(SimulationRun run) {
        // 1) 推进仿真时钟：deltaSeconds = 每拍真实秒数 × 倍速
        BigDecimal deltaSeconds = BigDecimal.valueOf(run.getTickMs() / 1000.0)
                .multiply(run.getSpeedFactor());
        SimulationRun advanced = simulationService.advance(run, deltaSeconds);
        long step = advanced.getTickCount();
        BigDecimal simTime = advanced.getSimTimeS();
        double delta = deltaSeconds.doubleValue();

        // 2) 逐个活动任务推进
        List<DispatchTask> activeTasks = taskRepository.findByStatusIn(ACTIVE_STATUSES);
        int moved = 0;
        int completed = 0;
        boolean persistTelemetry = step % Math.max(1, properties.simulation().telemetryPersistEveryTicks()) == 0;

        // 批量预加载：任务循环里不再逐条查无人机 / 航路 / 订单 / 航路点。
        // 200 架在飞时，原来每拍会产生约 1000 条 SQL，现在固定为 4 条。
        Map<Long, Drone> drones = loadDrones(activeTasks);
        Map<Long, FlightRoute> routes = loadRoutes(activeTasks);
        Map<Long, DeliveryOrder> orders = loadOrders(activeTasks);
        Map<Long, List<FlightWaypoint>> waypointsByRoute = loadWaypoints(activeTasks);

        for (DispatchTask task : activeTasks) {
            Drone drone = drones.get(task.getDroneId());
            if (drone == null) {
                continue;
            }

            // 停在充电站补能的飞机：这一拍不推进航路，只把电量充上去。
            // 充满后它会自己把航路重建到"接下来那一段"的终点，下一拍继续飞。
            if (task.getStatus() == TaskStatus.CHARGING) {
                batteryRelay.advanceCharging(task, drone, delta, simTime, run.getId());
                continue;
            }

            DroneModel model = drone.getModel();
            FlightRoute route = task.getRouteId() == null ? null : routes.get(task.getRouteId());
            if (route == null) {
                failTask(task, drone, "航路数据缺失，无法推进", simTime, run.getId());
                continue;
            }
            DeliveryOrder order = orders.get(task.getOrderId());
            double payloadKg = order == null ? 0 : order.getWeightKg().doubleValue();

            // 电量安全底线：低于配置的冗余值就中止，真实系统里这叫"强制返航"。
            // 例外：正在自主补能途中（已选好站点、这点电够飞过去）不中止 ——
            // 否则"自己判断该补能"这件事会被这条底线提前掐断，永远落不了站。
            if (drone.getBatteryPct().doubleValue() <= properties.dispatch().minBatteryReservePct()
                    && !batteryRelay.isRelaying(task.getId())) {
                abortLowBattery(task, drone, order, simTime, run.getId());
                continue;
            }

            List<FlightWaypoint> routeWaypoints = waypointsByRoute.getOrDefault(route.getId(), List.of());
            boolean finished = advanceTask(task, drone, model, route, order, payloadKg,
                    delta, simTime, run.getId(), routeWaypoints, waypointsByRoute);
            moved++;
            if (finished) {
                completed++;
            }
            if (persistTelemetry) {
                telemetryService.record(run, task, drone, simTime, step, task.getProgressPct());
            }
        }

        // 3) 碰撞检测 + 自动改航避让（Java 本地预测，不依赖 Python 算法服务）。
        //    必须在位置推进之后、本拍遥测广播之前执行：检测的是"下一阶段会不会撞上"，
        //    改航后下一拍就沿新航路飞。
        //    Python 算法服务的补充检测已移到 tick 事务之外（SimulationEngine），
        //    避免一次最长 10s 的 HTTP 调用把整拍事务和数据库连接一直占着。
        List<ConflictEvent> newConflicts = collisionAvoidance.scanAndAvoid(
                simTime, run.getId(), activeTasks, drones, routes, waypointsByRoute, orders);

        // 3.2) 无人机自主补能判断（在位置推进之后、遥测广播之前）：
        //      剩余航程能耗超过可用电量时，自己改航到就近充电站，充满再继续未完成的那一段。
        //      与碰撞避让同一套"扫描 → 批量改航"模式，所以放在它旁边。
        int relayed = batteryRelay.scanAndRelay(simTime, run.getId(), activeTasks, drones, routes, orders);
        if (relayed > 0) {
            log.info("本拍 {} 架无人机自主改航至充电站补能", relayed);
        }

        // 3.5) 在站机队补能（dev 演示闭环；真机执行任务时不参与，见 FleetRechargeService）
        rechargeService.recharge(delta);

        // 4) 生成遥测帧（事务内取数据，事务外广播）；join fetch 机型，避免逐架懒加载
        List<DashboardDtos.DroneFrame> frames = dashboardService.buildFrames(droneRepository.findAllWithModel());

        return new TickOutcome(simTime, step, frames, moved, completed, newConflicts);
    }

    // ------------------------------------------------------------------
    // 批量预加载：把循环里的 N+1 收敛为固定几条 SQL
    // ------------------------------------------------------------------

    private Map<Long, Drone> loadDrones(List<DispatchTask> tasks) {
        Set<Long> ids = tasks.stream()
                .map(DispatchTask::getDroneId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        // join fetch 机型：后续循环里 drone.getModel() 不再逐架触发懒加载
        return droneRepository.findAllWithModelByIdIn(ids).stream()
                .collect(Collectors.toMap(Drone::getId, Function.identity()));
    }

    private Map<Long, FlightRoute> loadRoutes(List<DispatchTask> tasks) {
        Set<Long> ids = tasks.stream()
                .map(DispatchTask::getRouteId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return routeRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(FlightRoute::getId, Function.identity()));
    }

    private Map<Long, DeliveryOrder> loadOrders(List<DispatchTask> tasks) {
        Set<Long> ids = tasks.stream()
                .map(DispatchTask::getOrderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return orderRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(DeliveryOrder::getId, Function.identity()));
    }

    private Map<Long, List<FlightWaypoint>> loadWaypoints(List<DispatchTask> tasks) {
        Set<Long> ids = tasks.stream()
                .map(DispatchTask::getRouteId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return waypointRepository.findByRouteIdInOrderByRouteIdAscSeqAsc(ids).stream()
                .collect(Collectors.groupingBy(FlightWaypoint::getRouteId));
    }

    // ------------------------------------------------------------------
    // 单架无人机的状态机
    // ------------------------------------------------------------------

    /**
     * 推进一个任务。
     *
     * @return 本拍是否完成任务（返航回到机巢）
     */
    private boolean advanceTask(DispatchTask task, Drone drone, DroneModel model, FlightRoute route,
                               DeliveryOrder order, double payloadKg, double deltaSeconds,
                               BigDecimal simTime, Long runId, List<FlightWaypoint> routeWaypoints,
                               Map<Long, List<FlightWaypoint>> waypointsByRoute) {
        double routeDistance = route.getDistanceM().doubleValue();
        if (routeDistance <= 0) {
            failTask(task, drone, "航路长度为 0", simTime, runId);
            return false;
        }
        double cruiseSpeed = model.getCruiseSpeedMps().doubleValue();
        double flown = task.getActualDistanceM() == null ? 0 : task.getActualDistanceM().doubleValue();

        List<GeoPoint> outbound = routeWaypoints.stream()
                .map(w -> new GeoPoint(w.getLat(), w.getLon()))
                .toList();
        if (outbound.size() < 2) {
            failTask(task, drone, "航路点不足 2 个", simTime, runId);
            return false;
        }
        List<GeoPoint> inbound = new ArrayList<>(outbound);
        Collections.reverse(inbound);
        List<FlightWaypoint> waypoints = routeWaypoints;

        // 飞行剖面：把「水平匀速直线」升级为「起飞加速 → 爬升 → 巡航 → 下降 → 减速降落」。
        // 去程与回程长度相同（都是 routeDistance），所以两段共用同一个剖面参数。
        LegProfile profile = buildLegProfile(
                cruiseSpeed, cruiseAltitude(waypoints).doubleValue(), routeDistance);

        // 起飞瞬间：扣一次起降悬停能耗，订单进入"配送中"
        if (task.getStatus() == TaskStatus.ASSIGNED) {
            task.setStatus(TaskStatus.TAKEOFF);
            task.setStartedAt(Instant.now());
            consume(drone, model, task, energyModel.hoverEnergyWh(model, payloadKg));
            // 同步无人机运行态：派单后一直是 ASSIGNED，起飞这一刻起进入飞行中，
            // 否则地图与机队表全程显示"已分配"，看不出飞机其实已经在飞
            drone.setStatus(DroneStatus.IN_FLIGHT);
            if (order != null && order.getStatus() == OrderStatus.ASSIGNED) {
                orderService.markStatus(order, OrderStatus.IN_FLIGHT);
            }
            eventService.taskStatusChanged(task, TaskStatus.TAKEOFF.name(), simTime, runId);
        }

        // 上一拍刚在送货点降落，本拍开始返航
        if (task.getStatus() == TaskStatus.DELIVERING) {
            task.setStatus(TaskStatus.RETURNING);
            drone.setStatus(DroneStatus.RETURNING);
            // 去程是改航避让航线时，当前折线方向为"当前位置→送货点"，反向返航会沿绕路折回；
            // 直接重建成"送货点→机巢"直线，回家更快也更合理。
            if (rerouteRegistry.leg(task.getId()) == RerouteRegistry.Leg.OUTBOUND) {
                GeoPoint dropoff = new GeoPoint(drone.getCurrentLat(), drone.getCurrentLon());
                if (drone.getHomeLat() != null && drone.getHomeLon() != null) {
                    GeoPoint home = new GeoPoint(drone.getHomeLat(), drone.getHomeLon());
                    routeDistance = routeService.replaceWithStraightLine(
                            route.getId(), dropoff, home,
                            cruiseAltitude(waypoints).doubleValue());
                    task.setActualDistanceM(scale(0));
                    outbound = List.of(dropoff, home);
                    inbound = List.of(home, dropoff);
                    // 航路点已重建，刷新本地引用；同步回共享 map，
                    // 保证本拍后续的碰撞检测用的是新航路而不是预加载时的旧航路
                    waypoints = routeService.waypoints(route.getId());
                    waypointsByRoute.put(route.getId(), waypoints);
                    profile = buildLegProfile(cruiseSpeed,
                            cruiseAltitude(waypoints).doubleValue(), routeDistance);
                }
                rerouteRegistry.clear(task.getId());
            }
            eventService.taskStatusChanged(task, TaskStatus.RETURNING.name(), simTime, runId);
        }

        if (task.getStatus() == TaskStatus.RETURNING) {
            // 改航发生在返航段时，新折线方向就是"当前位置→机巢"，须正向飞而不是反向
            boolean reroutedReturn = rerouteRegistry.leg(task.getId()) == RerouteRegistry.Leg.RETURNING;
            List<GeoPoint> returnPath = reroutedReturn ? outbound : inbound;
            double returnFlown = reroutedReturn
                    ? Math.max(0, flown)
                    : Math.max(0, flown - routeDistance);
            double remain = routeDistance - returnFlown;
            // 风修正：地速随逆风下降，能耗按空速上升
            WindField.Adjustment wind = windAt(returnPath, returnFlown, profile.cruiseAltitude(),
                    cruiseSpeed, simTime);
            // rampSpeed 是"本拍的空速"（含起降加减速），legSpeed 是扣掉风之后的地速：
            // 能耗看空速，位置推进看地速
            double rampSpeed = profile.speedAt(returnFlown, remain);
            double legSpeed = rampSpeed * wind.groundSpeedFactor();
            double advance = Math.min(legSpeed * deltaSeconds, remain);
            returnFlown += advance;
            flown = reroutedReturn ? returnFlown : routeDistance + returnFlown;

            // 返航时已卸货，能耗按空载算
            consume(drone, model, task,
                    energyModel.cruiseEnergyWh(model, advance, 0, rampSpeed, wind.energyFactor()));

            GeoPoint position = GeoUtils.pointAtDistance(returnPath, returnFlown);
            double altitude = profile.altitudeAt(returnFlown, routeDistance - returnFlown);
            updatePosition(drone, position, legSpeed, altitude, returnPath, wind.noseBearingDeg());
            task.setActualDistanceM(scale(flown));
            task.setCurrentWaypointSeq(seqAt(waypoints, returnFlown));

            if (returnFlown >= routeDistance - ARRIVAL_TOLERANCE_M) {
                // 这条"返航"航路的终点是充电站（自主补能改航）→ 落站补能，不当作任务完成
                if (batteryRelay.isRelaying(task.getId())) {
                    batteryRelay.beginCharging(task, drone, simTime, runId);
                    return false;
                }
                rerouteRegistry.clear(task.getId());
                completeTask(task, drone, route, order, simTime, runId);
                return true;
            }
            return false;
        }

        // 去程（TAKEOFF / CRUISING）
        if (task.getStatus() == TaskStatus.TAKEOFF) {
            task.setStatus(TaskStatus.CRUISING);
            eventService.taskStatusChanged(task, TaskStatus.CRUISING.name(), simTime, runId);
        }

        double remain = routeDistance - flown;
        // 风修正：逆风 → 地速下降、空速上升；顺风反之。侧风体现在机头偏转上
        WindField.Adjustment wind = windAt(outbound, flown, profile.cruiseAltitude(), cruiseSpeed, simTime);
        double rampSpeed = profile.speedAt(flown, remain);
        double legSpeed = rampSpeed * wind.groundSpeedFactor();
        double advance = Math.min(legSpeed * deltaSeconds, remain);
        flown += advance;
        consume(drone, model, task,
                energyModel.cruiseEnergyWh(model, advance, payloadKg, rampSpeed, wind.energyFactor()));

        GeoPoint position = GeoUtils.pointAtDistance(outbound, flown);
        double altitude = profile.altitudeAt(flown, routeDistance - flown);
        updatePosition(drone, position, legSpeed, altitude, outbound, wind.noseBearingDeg());
        task.setActualDistanceM(scale(flown));
        task.setCurrentWaypointSeq(seqAt(waypoints, flown));
        task.setProgressPct(scale(Math.min(100.0, flown / routeDistance * 100)));

        if (remain <= advance + ARRIVAL_TOLERANCE_M) {
            // 航路终点是充电站（自主补能改航）时，这里是"到站落桩"而不是"送达卸货"：
            // 若按送达处理，订单会被提前标记 DELIVERED，货物其实还在机上
            if (batteryRelay.isRelaying(task.getId())) {
                batteryRelay.beginCharging(task, drone, simTime, runId);
                return false;
            }
            // 抵达送货点：降落卸货
            task.setStatus(TaskStatus.DELIVERING);
            task.setProgressPct(scale(100));
            consume(drone, model, task, energyModel.hoverEnergyWh(model, payloadKg));
            drone.setSpeedMps(BigDecimal.ZERO);
            drone.setAltitudeM(BigDecimal.ZERO);
            if (order != null && order.getStatus() == OrderStatus.IN_FLIGHT) {
                orderService.markStatus(order, OrderStatus.DELIVERED);
                eventService.orderStatusChanged(order, OrderStatus.IN_FLIGHT.name(), OrderStatus.DELIVERED.name());
            }
            eventService.taskStatusChanged(task, TaskStatus.DELIVERING.name(), simTime, runId);
            log.info("任务 {} 已送达，开始返航（电量 {}%）", task.getTaskNo(), drone.getBatteryPct());
        }
        return false;
    }

    /**
     * 写入无人机位置、航向、速度、高度。
     *
     * <p>高度不再直接等于巡航高度，而是由飞行剖面按当前航程位置算出 ——
     * 这样看板上能看到飞机"爬上去、平飞、再降下来"，而不是一直贴在天花板上。
     *
     * <p>{@code noseBearingDeg} 是<b>机头朝向</b>（含侧风偏航角），不是航迹方向：
     * 有侧风时飞机会"斜着飞"，地图上的朝向箭头略微偏离航线，这正是真实飞行姿态。
     */
    private void updatePosition(Drone drone, GeoPoint position, double speed, double altitudeM,
                                List<GeoPoint> polyline, double noseBearingDeg) {
        double heading = noseBearingDeg;
        if (!Double.isFinite(heading)) {
            // 兜底：拿不到风修正时沿用"上一位置 → 当前位置"的航迹方向
            if (drone.getCurrentLat() != null && drone.getCurrentLon() != null) {
                heading = GeoUtils.bearingDegrees(drone.getCurrentLat(), drone.getCurrentLon(),
                        position.lat(), position.lon());
            } else {
                int idx = Math.min(1, polyline.size() - 1);
                heading = GeoUtils.bearingDegrees(position.lat(), position.lon(),
                        polyline.get(idx).lat(), polyline.get(idx).lon());
            }
        }
        drone.setCurrentLat(position.lat());
        drone.setCurrentLon(position.lon());
        drone.setHeadingDeg(scale(heading).setScale(2, RoundingMode.HALF_UP));
        drone.setSpeedMps(BigDecimal.valueOf(speed).setScale(2, RoundingMode.HALF_UP));
        drone.setAltitudeM(BigDecimal.valueOf(Math.max(0.0, altitudeM)).setScale(2, RoundingMode.HALF_UP));
        drone.setLastHeartbeatAt(Instant.now());
        droneRepository.save(drone);
    }

    // ------------------------------------------------------------------
    // 风场
    // ------------------------------------------------------------------

    /**
     * 计算当前航段的风修正。
     *
     * <p>航迹方向取"当前点 → 前方 50m"的方位角，而不是整段航线的方向：
     * 绕飞航线是折线，取整段起点方向会让逆风分量算错。
     */
    private WindField.Adjustment windAt(List<GeoPoint> path, double flownM, double cruiseAltitudeM,
                                        double cruiseSpeedMps, BigDecimal simTime) {
        double bearing = trackBearing(path, flownM);
        return windField.adjust(cruiseSpeedMps, bearing, cruiseAltitudeM,
                simTime == null ? 0.0 : simTime.doubleValue());
    }

    /** 折线上"已飞 flownM 处"的前进方向（度）。 */
    private double trackBearing(List<GeoPoint> path, double flownM) {
        double total = GeoUtils.polylineLengthMeters(path);
        double at = Math.max(0.0, Math.min(flownM, total));
        GeoPoint a = GeoUtils.pointAtDistance(path, at);
        GeoPoint b = GeoUtils.pointAtDistance(path, Math.min(at + 50.0, total));
        if (a.distanceTo(b) < 0.5) {
            // 已到终点：用末段方向兜底
            GeoPoint end = GeoUtils.pointAtDistance(path, total);
            GeoPoint prev = GeoUtils.pointAtDistance(path, Math.max(0.0, total - 50.0));
            return GeoUtils.bearingDegrees(prev.lat(), prev.lon(), end.lat(), end.lon());
        }
        return GeoUtils.bearingDegrees(a.lat(), a.lon(), b.lat(), b.lon());
    }

    // ------------------------------------------------------------------
    // 飞行剖面（垂直 + 速度）
    // ------------------------------------------------------------------

    /**
     * 单段航程（去程或回程）的飞行剖面。
     *
     * <p>把一条航程拆成四段物理意义明确的区间：
     * <pre>
     *   0 ──爬升过渡── 巡航高度 ────────── 巡航高度 ──下降过渡── 0
     *   速度：低 → 巡航 → 巡航 → 低（起降加减速）
     * </pre>
     * 爬升/下降的"水平过渡距离"不是拍脑袋，而是从**垂直速率**换算出来的：
     * 爬 80 m 用 4 m/s 需要 20 s，这 20 s 里飞机以巡航速度前飞，
     * 于是水平方向走 20 × 巡航速度（15 m/s）≈ 300 m。这就是它开始平飞的位置。
     */
    private record LegProfile(
            double cruiseSpeed,
            double cruiseAltitude,
            double climbRunM,
            double descentRunM,
            double rampRunM
    ) {
        /** 本段已飞 covered、还剩 remaining 时的瞬时水平速度（米/秒）。 */
        double speedAt(double coveredM, double remainingM) {
            double factor = 1.0;
            if (rampRunM > 1e-6) {
                factor = Math.min(factor, MIN_SPEED_FACTOR + (1.0 - MIN_SPEED_FACTOR) * clamp01(coveredM / rampRunM));
                factor = Math.min(factor, MIN_SPEED_FACTOR + (1.0 - MIN_SPEED_FACTOR) * clamp01(remainingM / rampRunM));
            }
            return cruiseSpeed * factor;
        }

        /** 本段已飞 covered、还剩 remaining 时的高度（米）。 */
        double altitudeAt(double coveredM, double remainingM) {
            double factor = 1.0;
            if (climbRunM > 1e-6) {
                factor = Math.min(factor, clamp01(coveredM / climbRunM));
            }
            if (descentRunM > 1e-6) {
                factor = Math.min(factor, clamp01(remainingM / descentRunM));
            }
            return cruiseAltitude * factor;
        }
    }

    /** 由机型巡航速度、巡航高度与本段长度组装飞行剖面。 */
    private LegProfile buildLegProfile(double cruiseSpeed, double cruiseAltitudeM, double legDistanceM) {
        double leg = Math.max(1.0, legDistanceM);
        double climb = properties.flight().climbSpeedMps();
        double descent = properties.flight().descentSpeedMps();

        // 垂直速率 → 水平过渡距离；再用 45% 兜底，保证爬升与下降不会互相顶掉，
        // 中间还留出至少 10% 的平飞段（短航线"来不及爬到顶就下降"也是真实行为）。
        double climbRun = climb > 1e-6 ? cruiseAltitudeM / climb * cruiseSpeed : 0.0;
        double descentRun = descent > 1e-6 ? cruiseAltitudeM / descent * cruiseSpeed : 0.0;
        climbRun = Math.min(climbRun, leg * 0.45);
        descentRun = Math.min(descentRun, leg * 0.45);

        return new LegProfile(cruiseSpeed, cruiseAltitudeM, climbRun, descentRun,
                properties.flight().speedRampM());
    }

    private static double clamp01(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        return value > 1.0 ? 1.0 : value;
    }

    /** 从航路点里取巡航高度；没有航路点时用配置默认值。 */
    private BigDecimal cruiseAltitude(List<FlightWaypoint> waypoints) {
        if (waypoints.isEmpty() || waypoints.get(0).getAltitudeM() == null) {
            return BigDecimal.valueOf(properties.flight().defaultAltitudeM());
        }
        return waypoints.get(0).getAltitudeM();
    }

    /** 已飞距离落在第几个航路点之后。 */
    private int seqAt(List<FlightWaypoint> waypoints, double flown) {
        int seq = 0;
        for (FlightWaypoint wp : waypoints) {
            if (wp.getCumulativeDistanceM() != null && wp.getCumulativeDistanceM().doubleValue() <= flown) {
                seq = wp.getSeq();
            }
        }
        return seq;
    }

    /** 按能量消耗扣减电量，并累计到任务的能耗统计。 */
    private void consume(Drone drone, DroneModel model, DispatchTask task, BigDecimal energyWh) {
        if (energyWh == null || energyWh.signum() <= 0) {
            return;
        }
        BigDecimal usedPct = energyModel.batteryPctUsed(model, energyWh);
        BigDecimal remaining = drone.getBatteryPct().subtract(usedPct).max(BigDecimal.ZERO);
        drone.setBatteryPct(remaining.setScale(2, RoundingMode.HALF_UP));

        BigDecimal consumed = (task.getConsumedEnergyWh() == null ? BigDecimal.ZERO : task.getConsumedEnergyWh())
                .add(energyWh);
        task.setConsumedEnergyWh(consumed.setScale(3, RoundingMode.HALF_UP));

        // 低电量预警：每架机在每个运行里只提醒一次，避免看板被刷屏
        if (remaining.doubleValue() < properties.dispatch().minBatteryReservePct() + 10
                && lowBatteryWarned.add(drone.getId())) {
            eventService.droneBatteryLow(drone, null, null);
        }
    }

    /** 任务完成：飞机落回机巢、电量与里程入账、累计统计更新。 */
    private void completeTask(DispatchTask task, Drone drone, FlightRoute route,
                             DeliveryOrder order, BigDecimal simTime, Long runId) {
        rerouteRegistry.clear(task.getId());
        batteryRelay.forget(task.getId());
        Instant now = Instant.now();
        task.setStatus(TaskStatus.COMPLETED);
        task.setFinishedAt(now);
        task.setProgressPct(scale(100));
        task.setBatteryEndPct(drone.getBatteryPct());
        if (task.getStartedAt() != null) {
            // 至少记 1 秒：仿真加速后一次任务可能在同一个真实秒内完成，
            // 直接取整会得到 0，报表上看起来像"没有执行耗时"。
            task.setDurationS(Math.max(1, (int) Duration.between(task.getStartedAt(), now).toSeconds()));
        }
        taskRepository.save(task);

        // 回到机巢待命
        drone.setStatus(DroneStatus.IDLE);
        drone.setCurrentTaskId(null);
        drone.setSpeedMps(BigDecimal.ZERO);
        drone.setAltitudeM(BigDecimal.ZERO);
        drone.setHeadingDeg(BigDecimal.ZERO);
        if (drone.getHomeLat() != null && drone.getHomeLon() != null) {
            drone.setCurrentLat(drone.getHomeLat());
            drone.setCurrentLon(drone.getHomeLon());
        }
        drone.setLastHeartbeatAt(now);
        drone.setTotalTasks((drone.getTotalTasks() == null ? 0 : drone.getTotalTasks()) + 1);
        double km = route.getDistanceM().doubleValue() * 2 / 1000.0;   // 去 + 回
        drone.setTotalDistanceKm((drone.getTotalDistanceKm() == null ? BigDecimal.ZERO : drone.getTotalDistanceKm())
                .add(BigDecimal.valueOf(km)).setScale(3, RoundingMode.HALF_UP));
        if (task.getDurationS() != null) {
            drone.setTotalFlightMin((drone.getTotalFlightMin() == null ? BigDecimal.ZERO : drone.getTotalFlightMin())
                    .add(BigDecimal.valueOf(task.getDurationS() / 60.0)).setScale(2, RoundingMode.HALF_UP));
        }
        droneRepository.save(drone);

        routeService.updateStatus(route, com.skydispatch.domain.enums.RouteStatus.FINISHED);
        eventService.taskCompleted(task, simTime, runId);
        log.info("任务完成: {}，无人机 {} 已返回机巢，剩余电量 {}%",
                task.getTaskNo(), drone.getCode(), drone.getBatteryPct());

        if (order != null && order.getStatus() == OrderStatus.DELIVERED) {
            // 订单已在送达时置为 DELIVERED，这里不重复改状态，只保持语义清晰
            log.debug("订单 {} 已完成闭环", order.getOrderNo());
        }
    }

    /** 电量不足：任务失败，无人机下线待处理。 */
    private void abortLowBattery(DispatchTask task, Drone drone, DeliveryOrder order,
                                BigDecimal simTime, Long runId) {
        rerouteRegistry.clear(task.getId());
        batteryRelay.forget(task.getId());
        task.setStatus(TaskStatus.FAILED);
        task.setFailReason("电量低于安全冗余 " + properties.dispatch().minBatteryReservePct() + "%，强制中止");
        task.setFinishedAt(Instant.now());
        task.setBatteryEndPct(drone.getBatteryPct());
        taskRepository.save(task);

        drone.setStatus(DroneStatus.MAINTENANCE);
        drone.setCurrentTaskId(null);
        drone.setSpeedMps(BigDecimal.ZERO);
        droneRepository.save(drone);

        if (order != null && order.getStatus() != OrderStatus.DELIVERED) {
            order.setFailReason(task.getFailReason());
            orderService.markStatus(order, OrderStatus.FAILED);
            orderService.applyAssignment(order, null);
        }
        eventService.taskFailed(task, task.getFailReason(), simTime, runId);
        log.warn("任务 {} 因电量不足中止: {} 剩余 {}%", task.getTaskNo(), drone.getCode(), drone.getBatteryPct());
    }

    /** 通用失败处理（航路异常等）。 */
    private void failTask(DispatchTask task, Drone drone, String reason, BigDecimal simTime, Long runId) {
        rerouteRegistry.clear(task.getId());
        batteryRelay.forget(task.getId());
        task.setStatus(TaskStatus.FAILED);
        task.setFailReason(reason);
        task.setFinishedAt(Instant.now());
        taskRepository.save(task);

        drone.setStatus(DroneStatus.IDLE);
        drone.setCurrentTaskId(null);
        drone.setSpeedMps(BigDecimal.ZERO);
        droneRepository.save(drone);

        orderRepository.findById(task.getOrderId()).ifPresent(order -> {
            if (order.getStatus() != OrderStatus.DELIVERED) {
                order.setFailReason(reason);
                orderService.markStatus(order, OrderStatus.FAILED);
            }
        });
        eventService.taskFailed(task, reason, simTime, runId);
        log.error("任务 {} 推进失败: {}", task.getTaskNo(), reason);
    }

    private BigDecimal scale(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }
}
