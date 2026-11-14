package com.skydispatch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skydispatch.common.BusinessException;
import com.skydispatch.common.ErrorCode;
import com.skydispatch.common.GeoPoint;
import com.skydispatch.config.SkyProperties;
import com.skydispatch.dispatch.DispatchStrategy;
import com.skydispatch.dispatch.DispatchStrategyFactory;
import com.skydispatch.dispatch.DispatchDecisionRow;
import com.skydispatch.dispatch.DroneCandidate;
import com.skydispatch.domain.entity.DeliveryOrder;
import com.skydispatch.domain.entity.DispatchDecision;
import com.skydispatch.domain.entity.DispatchTask;
import com.skydispatch.domain.entity.Drone;
import com.skydispatch.domain.entity.DroneModel;
import com.skydispatch.domain.entity.FlightRoute;
import com.skydispatch.domain.entity.ChargingStation;
import com.skydispatch.domain.enums.DroneStatus;
import com.skydispatch.domain.enums.OrderStatus;
import com.skydispatch.domain.enums.TaskStatus;
import com.skydispatch.dto.TaskDtos;
import com.skydispatch.engine.EnergyModel;
import com.skydispatch.engine.ReachabilityService;
import com.skydispatch.repository.ChargingStationRepository;
import com.skydispatch.repository.DispatchDecisionRepository;
import com.skydispatch.repository.DispatchTaskRepository;
import com.skydispatch.repository.DroneRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 调度服务 —— 本平台的大脑。
 *
 * <p>一次派单的完整决策链（建议对照 docs/04-scheduling-flow.md 阅读）：
 * <pre>
 *   订单 → 状态校验 → 候选筛选（硬条件）→ 策略打分排序 → 选中 → 路径规划 → 落库 → 事件
 *                        ↑ 记录淘汰原因            ↑ 记录排名与得分（可解释性）
 * </pre>
 *
 * <p><b>硬条件与软条件的分离</b>是这里最重要的设计：
 * <ul>
 *   <li>硬条件（载重、电量、续航、搜索半径、空闲状态）在 {@link #filterCandidates} 里判断，
 *       不满足直接淘汰，并写明原因存进 {@code dispatch_decision}。</li>
 *   <li>软条件（谁更近、谁电更多、谁更闲）交给 {@link DispatchStrategy} 排序。</li>
 * </ul>
 * 这样换策略时安全边界不会跟着变，运安逻辑不会因为"换了个算法"而失效。
 *
 * <p><b>三段式派单</b>（避免在数据库事务里等算法服务）：
 * <pre>
 *   ① 只读事务   校验状态 → 筛候选 → 策略排序 → 生成决策快照
 *   ② 事务外     调用 Python 算法服务规划航路（HTTP 最长 10s，不占连接、不持锁）
 *   ③ 短写事务   重新锁订单/无人机 → 复核 → 航路 + 任务 + 状态一次落库
 * </pre>
 * 第 ③ 段重新加锁复核，所以第 ① 段的快照过期不会造成重复派单；
 * 并发冲突会以业务异常返回，而不是静默覆盖。
 */
@Service
public class DispatchService {

    private static final Logger log = LoggerFactory.getLogger(DispatchService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 视为"占用中"的任务状态，用于负载均衡与重复派单校验。 */
    private static final List<TaskStatus> ACTIVE_TASK_STATUSES = List.of(
            TaskStatus.ASSIGNED, TaskStatus.TAKEOFF, TaskStatus.CRUISING,
            TaskStatus.DELIVERING, TaskStatus.RETURNING);

    private final OrderService orderService;
    private final DispatchTaskRepository taskRepository;
    private final DispatchDecisionRepository decisionRepository;
    private final DispatchDecisionRecorder decisionRecorder;
    private final DroneRepository droneRepository;
    private final DispatchStrategyFactory strategyFactory;
    private final RouteService routeService;
    private final EnergyModel energyModel;
    private final EventService eventService;
    private final SkyProperties properties;
    private final ReachabilityService reachability;
    private final ChargingStationRepository stationRepository;
    private final ObjectMapper objectMapper;
    private final SequenceService sequenceService;
    /** 第①段：只读事务（候选筛选 / 决策快照） */
    private final TransactionTemplate readTx;
    /** 第③段：短写事务（加锁复核 + 落库） */
    private final TransactionTemplate writeTx;

    public DispatchService(OrderService orderService,
                           DispatchTaskRepository taskRepository,
                           DispatchDecisionRepository decisionRepository,
                           DispatchDecisionRecorder decisionRecorder,
                           DroneRepository droneRepository,
                           DispatchStrategyFactory strategyFactory,
                           RouteService routeService,
                           EnergyModel energyModel,
                           EventService eventService,
                           SkyProperties properties,
                           ReachabilityService reachability,
                           ChargingStationRepository stationRepository,
                           ObjectMapper objectMapper,
                           SequenceService sequenceService,
                           PlatformTransactionManager transactionManager) {
        this.orderService = orderService;
        this.taskRepository = taskRepository;
        this.decisionRepository = decisionRepository;
        this.decisionRecorder = decisionRecorder;
        this.droneRepository = droneRepository;
        this.strategyFactory = strategyFactory;
        this.routeService = routeService;
        this.energyModel = energyModel;
        this.eventService = eventService;
        this.properties = properties;
        this.reachability = reachability;
        this.stationRepository = stationRepository;
        this.objectMapper = objectMapper;
        this.sequenceService = sequenceService;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(transactionManager);
    }

    /**
     * 为单个订单派单（三段式：只读筛选 → 事务外规划 → 短事务落库）。
     *
     * @param orderId      订单 id
     * @param strategyName 策略名，传空则用配置默认值
     */
    public TaskDtos.DispatchResult dispatchOrder(Long orderId, String strategyName) {
        PreparedDispatch prepared = readTx.execute(status -> prepare(orderId, strategyName));
        if (prepared == null) {
            throw BusinessException.illegalState("派单准备阶段没有返回结果，请重试");
        }
        // 第②段：事务外调用算法服务。此时没有任何数据库事务/行锁，
        // 算法超时只会拖慢这一次派单，不会占用连接池。
        RouteService.PlannedRoute planned = routeService.planRouteData(
                prepared.order(), prepared.drone(), prepared.model());
        return writeTx.execute(status -> commit(prepared, planned));
    }

    /** 第①段：只读事务内完成状态校验、候选筛选与决策快照。 */
    private PreparedDispatch prepare(Long orderId, String strategyName) {
        DeliveryOrder order = orderService.require(orderId);
        DispatchStrategy strategy = strategyFactory.get(strategyName);

        if (order.getStatus() != OrderStatus.CREATED
                && order.getStatus() != OrderStatus.QUEUED
                && order.getStatus() != OrderStatus.PLANNING) {
            throw BusinessException.illegalState(
                    "订单 " + order.getOrderNo() + " 当前状态为 " + order.getStatus() + "，不能派单");
        }

        List<ScoredCandidate> all = filterCandidates(order);
        List<ScoredCandidate> passed = all.stream().filter(ScoredCandidate::passed).toList();

        if (passed.isEmpty()) {
            // 失败也要留痕：决策明细用独立事务提交，不受只读事务回滚影响
            decisionRecorder.record(order.getId(), strategy.type().name(),
                    buildDecisionRows(all, strategy, null));
            throw new BusinessException(ErrorCode.NO_AVAILABLE_DRONE,
                    "订单 " + order.getOrderNo() + " 没有满足条件的无人机（共评估 "
                            + all.size() + " 架，全部被淘汰）");
        }

        List<ScoredCandidate> ranked = new ArrayList<>(passed);
        ranked.sort(Comparator.comparing(ScoredCandidate::candidate, strategy.comparator())
                .thenComparing(sc -> sc.candidate().code()));
        ScoredCandidate best = ranked.get(0);
        List<ScoredCandidate> allRanked = new ArrayList<>(ranked);
        all.stream().filter(sc -> !sc.passed()).forEach(allRanked::add);

        String reason = String.format("距取货点 %.0fm、电量 %.1f%%、在途任务 %d 个、可达性：%s",
                best.candidate().distanceToPickupM(), best.candidate().batteryPct(),
                best.candidate().activeTaskCount(),
                best.plan() == null ? "直飞可达" : best.plan().summary());

        return new PreparedDispatch(order, best.drone(), best.model(),
                strategy.type().name(), buildDecisionRows(allRanked, strategy, best), reason);
    }

    /** 第③段：短写事务，落库前重新加锁复核，防止并发派单重复占用。 */
    private TaskDtos.DispatchResult commit(PreparedDispatch prepared, RouteService.PlannedRoute planned) {
        DeliveryOrder order = orderService.requireForUpdate(prepared.order().getId());
        if (order.getStatus() != OrderStatus.CREATED
                && order.getStatus() != OrderStatus.QUEUED
                && order.getStatus() != OrderStatus.PLANNING) {
            throw BusinessException.illegalState(
                    "订单 " + order.getOrderNo() + " 已被其他请求处理（当前 " + order.getStatus() + "）");
        }

        Drone drone = droneRepository.findByIdForUpdate(prepared.drone().getId())
                .orElseThrow(() -> BusinessException.notFound("无人机 id=" + prepared.drone().getId()));
        if (drone.getStatus() != DroneStatus.IDLE) {
            throw BusinessException.illegalState(
                    "无人机 " + drone.getCode() + " 已被其他订单占用，请重试");
        }

        // 规划结果落库；若后续任一步失败，整个短事务回滚，不会留下半条航路
        FlightRoute route = routeService.persistRoute(order.getId(), drone.getId(), planned);

        DispatchTask task = DispatchTask.builder()
                .taskNo(nextTaskNo())
                .orderId(order.getId())
                .droneId(drone.getId())
                .routeId(route.getId())
                .strategy(prepared.strategy())
                .status(TaskStatus.ASSIGNED)
                .progressPct(BigDecimal.ZERO)
                .currentWaypointSeq(0)
                .plannedDistanceM(route.getDistanceM())
                .actualDistanceM(BigDecimal.ZERO)
                .plannedEnergyWh(route.getEstEnergyWh())
                .consumedEnergyWh(BigDecimal.ZERO)
                .batteryStartPct(drone.getBatteryPct())
                .assignedAt(Instant.now())
                .build();
        DispatchTask savedTask = taskRepository.save(task);

        drone.setStatus(DroneStatus.ASSIGNED);
        drone.setCurrentTaskId(savedTask.getId());
        drone.setLastHeartbeatAt(Instant.now());
        droneRepository.save(drone);

        OrderStatus from = order.getStatus();
        order.setAssignedDroneId(drone.getId());
        orderService.markStatus(order, OrderStatus.ASSIGNED);

        // 独立事务提交决策明细：即使后续事件失败，也能复盘"为什么选它"
        decisionRecorder.record(order.getId(), prepared.strategy(), prepared.decisionRows());

        eventService.taskAssigned(savedTask, drone, prepared.strategy(), prepared.reason());
        eventService.orderStatusChanged(order, from.name(), OrderStatus.ASSIGNED.name());

        log.info("派单成功: order={}, task={}, drone={}, 策略={}, 航程={}m",
                order.getOrderNo(), savedTask.getTaskNo(), drone.getCode(),
                prepared.strategy(), route.getDistanceM());

        return new TaskDtos.DispatchResult(
                TaskDtos.Response.from(savedTask, order.getOrderNo(), drone.getCode()),
                prepared.strategy(), true, 0L,
                "已派给 " + drone.getCode() + "：" + prepared.reason());
    }

    /** 把候选评估结果转成可独立提交的决策明细。 */
    private List<DispatchDecisionRow> buildDecisionRows(List<ScoredCandidate> candidates,
                                                        DispatchStrategy strategy,
                                                        ScoredCandidate selected) {
        List<DispatchDecisionRow> rows = new ArrayList<>(candidates.size());
        int rank = 1;
        for (ScoredCandidate sc : candidates) {
            boolean isSelected = selected != null && sc.drone().getId().equals(selected.drone().getId());
            rows.add(new DispatchDecisionRow(
                    sc.drone().getId(),
                    sc.drone().getCode(),
                    sc.model().getCode(),
                    sc.passed() ? BigDecimal.valueOf(strategy.score(sc.candidate()))
                            .setScale(4, RoundingMode.HALF_UP) : null,
                    sc.passed() ? rank++ : null,
                    isSelected,
                    sc.rejectedReason(),
                    describe(sc)));
        }
        return rows;
    }

    /**
     * 第①段与第②段之间传递的派单快照。
     *
     * <p>实体在只读事务结束后已脱离持久化上下文，只用于读取字段做规划；
     * 第③段一律按 id 重新加锁加载，绝不拿快照直接写库。
     */
    private record PreparedDispatch(
            DeliveryOrder order,
            Drone drone,
            DroneModel model,
            String strategy,
            List<DispatchDecisionRow> decisionRows,
            String reason
    ) {
    }

    /**
     * 派单前"搜罗可用无人机"预览（只读，不产生任何副作用）。
     *
     * <p>用户视角的诉求：给了一个任务（订单），别把整个机队都摊给我看，
     * 而是把"载重扛得住 + 电量够往返"的无人机捞出来、排好序。
     * 这里复用 {@link #filterCandidates} 的同一套硬条件，保证
     * "预览说能飞"和"真正派单能飞"永远一致——预览绝不展示派不出去的无人机。
     *
     * <p>与决策明细的分工：预览是<b>事前</b>只给入围者，决策明细是<b>事后</b>完整留痕（含淘汰原因）。
     */
    @Transactional(readOnly = true)
    public TaskDtos.CandidatePreview previewCandidates(Long orderId, String strategyName) {
        DeliveryOrder order = orderService.require(orderId);
        DispatchStrategy strategy = strategyFactory.get(strategyName);

        List<ScoredCandidate> all = filterCandidates(order);
        // stream().toList() 是不可变列表，排序前必须包一层 ArrayList
        List<ScoredCandidate> passed = new ArrayList<>(
                all.stream().filter(ScoredCandidate::passed).toList());
        passed.sort(Comparator.comparing(ScoredCandidate::candidate, strategy.comparator())
                .thenComparing(sc -> sc.candidate().code()));

        List<TaskDtos.QualifiedDrone> qualified = new ArrayList<>(passed.size());
        int directCount = 0;
        int relayCount = 0;
        int rank = 1;
        for (ScoredCandidate sc : passed) {
            DroneCandidate c = sc.candidate();
            ReachabilityService.Plan plan = sc.plan();
            int stops = plan == null ? 0 : plan.stopCount();
            if (stops == 0) {
                directCount++;
            } else {
                relayCount++;
            }
            List<String> stopNames = plan == null
                    ? List.of()
                    : plan.stops().stream().map(ReachabilityService.ChargingStop::stationName).toList();
            qualified.add(new TaskDtos.QualifiedDrone(
                    c.drone().getId(),
                    c.drone().getCode(),
                    c.model().getCode(),
                    c.model().getName(),
                    c.model().getMaxPayloadKg(),
                    round1(c.distanceToPickupM()),
                    c.drone().getBatteryPct(),
                    c.usableEnergyWh(),
                    c.missionEnergyWh(),
                    stops,
                    plan == null ? "直飞可达" : plan.summary(),
                    stopNames,
                    c.activeTaskCount(),
                    BigDecimal.valueOf(strategy.score(c)).setScale(4, RoundingMode.HALF_UP),
                    rank++));
        }

        return new TaskDtos.CandidatePreview(
                order.getId(), order.getOrderNo(), strategy.type().name(),
                all.size(), passed.size(), all.size() - passed.size(),
                directCount, relayCount, qualified);
    }

    /** 距离保留 1 位小数，避免前端拿到一长串浮点。 */
    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /**
     * 批量派单已移到 {@link DispatchBatchService}。
     *
     * <p><b>为什么不能写在这里？</b> 因为它需要循环调用 {@code dispatchOrder}。
     * 如果在同一个类里直接 {@code this.dispatchOrder(...)}，这就是"自调用"，
     * Spring 的 {@code @Transactional} 代理会被绕过——事务不生效，
     * 进而 {@code drone.getModel()} 这种懒加载会在事务外抛
     * {@code LazyInitializationException: no session}。
     * 把它拆到独立 Bean，调用就会穿过代理，每个订单各自一个事务，
     * 单个订单失败也不会污染其它订单。
     */

    /** 查询某订单的调度决策明细。 */
    @Transactional(readOnly = true)
    public List<TaskDtos.DecisionResponse> decisions(Long orderId) {
        return decisionRepository.findByOrderIdOrderByRankNoAsc(orderId).stream()
                .map(d -> TaskDtos.DecisionResponse.from(d, droneCode(d.getDroneId())))
                .toList();
    }

    // ------------------------------------------------------------------
    // 内部：候选筛选
    // ------------------------------------------------------------------

    /** 候选评估结果。{@code plan} 为空表示该机在前置硬条件就被淘汰，未做可达性分析。 */
    private record ScoredCandidate(Drone drone, DroneModel model, DroneCandidate candidate,
                                   ReachabilityService.Plan plan,
                                   boolean passed, String rejectedReason) {
    }

    /**
     * 遍历所有无人机，逐条应用硬条件。
     *
     * <p>"淘汰原因"必须写清楚，否则运营看到"没有可用无人机"时无从下手。
     *
     * <p><b>续航条件已升级为可达性分析</b>：不再用 {@code 任务能耗 <= 可用电量} 一刀切，
     * 而是交给 {@link ReachabilityService} 在"任务节点 + 充电站"的图上搜索
     * "最少经停几站能飞完"。直飞够电固然放行；直飞不够、但补能网络能兜住的单也放行，
     * 并在决策里写明经停哪几站 —— 这正是"有充电桩之后更深一层的研究"。
     */
    private List<ScoredCandidate> filterCandidates(DeliveryOrder order) {
        SkyProperties.Dispatch cfg = properties.dispatch();
        GeoPoint pickup = new GeoPoint(order.getPickupLat(), order.getPickupLon());
        GeoPoint dropoff = new GeoPoint(order.getDropoffLat(), order.getDropoffLon());
        double tripDistance = pickup.distanceTo(dropoff);
        double payload = order.getWeightKg().doubleValue();

        // 站点台账只查一次：本次评估的所有飞机共用同一张补能网络图
        List<ChargingStation> stations = cfg.relayChargingEnabled()
                ? stationRepository.findAllByEnabledTrue()
                : List.of();

        List<ScoredCandidate> results = new ArrayList<>();
        // 用 findAllWithModel()：一次 JOIN 取出机型，避免在事务边界外访问懒加载关联
        for (Drone drone : droneRepository.findAllWithModel()) {
            DroneModel model = drone.getModel();
            GeoPoint position = currentPosition(drone);
            double distanceToPickup = position.distanceTo(pickup);
            long activeTasks = taskRepository.countByDroneIdAndStatusIn(drone.getId(), ACTIVE_TASK_STATUSES);
            double battery = drone.getBatteryPct() == null ? 0 : drone.getBatteryPct().doubleValue();
            // 可用电量 = 当前电量 - 安全冗余，续航判断必须基于可用电量
            double usableBattery = Math.max(0, battery - cfg.minBatteryReservePct());
            BigDecimal usableEnergy = energyModel.usableEnergyWh(model, usableBattery);

            String reason = null;
            if (drone.getStatus() != DroneStatus.IDLE) {
                reason = "状态为 " + drone.getStatus() + "，非空闲";
            } else if (model.getMaxPayloadKg().doubleValue() < payload) {
                reason = String.format("载重不足（最大 %.1fkg < 需求 %.1fkg）",
                        model.getMaxPayloadKg().doubleValue(), payload);
            } else if (battery < cfg.minBatteryReservePct()) {
                reason = String.format("电量 %.1f%% 低于安全冗余 %.1f%%", battery, cfg.minBatteryReservePct());
            } else if (distanceToPickup > cfg.searchRadiusM()) {
                reason = String.format("距取货点 %.0fm 超出搜索半径 %.0fm", distanceToPickup, cfg.searchRadiusM());
            }

            // 前置条件都过了才做可达性分析：忙碌/超重/超半径的飞机没必要跑图上搜索
            ReachabilityService.Plan plan = null;
            if (reason == null) {
                plan = reachability.analyze(model, position, pickup, dropoff,
                        homePoint(drone), payload, usableEnergy, stations);
                if (!plan.feasible()) {
                    reason = "续航不可达：" + plan.failureReason();
                }
            }

            // 能耗只用于展示：可达性方案会算出含补能绕行的真实总能耗
            BigDecimal missionEnergy = plan != null && plan.feasible()
                    ? BigDecimal.valueOf(plan.totalEnergyWh()).setScale(2, RoundingMode.HALF_UP)
                    : energyModel.missionEnergyWh(model, distanceToPickup, tripDistance, payload);

            DroneCandidate candidate = new DroneCandidate(
                    drone, model, distanceToPickup, activeTasks, usableEnergy, missionEnergy);

            // 直飞不够但补能可行的，放行并把"经停方案"写进说明位，决策明细里能看到
            String note = reason;
            if (reason == null && plan != null && plan.needsCharging()) {
                note = plan.summary();
            }
            results.add(new ScoredCandidate(drone, model, candidate, plan, reason == null, note));
        }
        return results;
    }

    /** 无人机当前位置：优先用实时坐标，没有则回落到机巢。 */
    private GeoPoint currentPosition(Drone drone) {
        if (drone.getCurrentLat() != null && drone.getCurrentLon() != null) {
            return new GeoPoint(drone.getCurrentLat(), drone.getCurrentLon());
        }
        if (drone.getHomeLat() != null && drone.getHomeLon() != null) {
            return new GeoPoint(drone.getHomeLat(), drone.getHomeLon());
        }
        // 理论上不会发生；给个安全默认值，避免空指针把派单整个打挂
        return new GeoPoint(0, 0);
    }

    /** 返航终点：有起降点用起降点，没有就按当前位置算（等价于"原地待命"）。 */
    private GeoPoint homePoint(Drone drone) {
        if (drone.getHomeLat() != null && drone.getHomeLon() != null) {
            return new GeoPoint(drone.getHomeLat(), drone.getHomeLon());
        }
        return currentPosition(drone);
    }

    /** 决策明细的 JSON 快照，便于事后复盘与前端展示。 */
    private String describe(ScoredCandidate sc) {
        try {
            // 可达性字段比基础字段多，Map.of 的 10 对上限不够，用 LinkedHashMap 保序
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("droneCode", sc.drone().getCode());
            detail.put("modelCode", sc.model().getCode());
            detail.put("distanceToPickupM", Math.round(sc.candidate().distanceToPickupM()));
            detail.put("batteryPct", sc.candidate().batteryPct());
            detail.put("activeTaskCount", sc.candidate().activeTaskCount());
            detail.put("usableEnergyWh", sc.candidate().usableEnergyWh());
            detail.put("missionEnergyWh", sc.candidate().missionEnergyWh());
            detail.put("passed", sc.passed());
            detail.put("rejectedReason", sc.rejectedReason() == null ? "" : sc.rejectedReason());
            if (sc.plan() != null) {
                detail.put("chargingStops", sc.plan().stopCount());
                detail.put("reachability", sc.plan().summary());
                detail.put("chargingStopNames", sc.plan().stops().stream()
                        .map(ReachabilityService.ChargingStop::stationName).toList());
                detail.put("tightestMarginWh", Math.round(sc.plan().tightestMarginWh()));
            }
            return objectMapper.writeValueAsString(detail);
        } catch (Exception e) {
            return null;
        }
    }

    private String droneCode(Long droneId) {
        if (droneId == null) {
            return null;
        }
        return droneRepository.findById(droneId).map(Drone::getCode).orElse(null);
    }

    /**
     * 任务号：ST + 日期 + 数据库序列号。
     *
     * <p>与订单号共用 {@code seq_number} 序列，并发派单不会撞号。
     */
    private String nextTaskNo() {
        LocalDate today = LocalDate.now();
        long seq = sequenceService.next();
        return "ST" + today.format(DAY) + String.format("%06d", seq);
    }

    /** 供仿真引擎查询"活动任务"。 */
    @Transactional(readOnly = true)
    public List<DispatchTask> activeTasks() {
        return taskRepository.findByStatusIn(ACTIVE_TASK_STATUSES);
    }

    /** 供仿真引擎查询单条任务。 */
    @Transactional(readOnly = true)
    public Optional<DispatchTask> findTask(Long taskId) {
        return taskRepository.findById(taskId);
    }
}
