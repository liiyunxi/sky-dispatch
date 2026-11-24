package com.skydispatch.service;

import com.skydispatch.config.SkyProperties;
import com.skydispatch.domain.entity.DeliveryOrder;
import com.skydispatch.domain.entity.DispatchTask;
import com.skydispatch.domain.entity.Drone;
import com.skydispatch.domain.entity.SimulationRun;
import com.skydispatch.domain.enums.ConflictStatus;
import com.skydispatch.domain.enums.DroneStatus;
import com.skydispatch.domain.enums.OrderStatus;
import com.skydispatch.domain.enums.RunStatus;
import com.skydispatch.domain.enums.TaskStatus;
import com.skydispatch.dto.DashboardDtos;
import com.skydispatch.engine.WindField;
import com.skydispatch.repository.ConflictEventRepository;
import com.skydispatch.repository.DeliveryOrderRepository;
import com.skydispatch.repository.DispatchTaskRepository;
import com.skydispatch.repository.DroneRepository;
import com.skydispatch.repository.SimulationRunRepository;
import com.skydispatch.ws.TelemetryBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 看板服务：把散落在各表里的数据聚合成"一屏能看懂"的指标。
 *
 * <p>两个设计决定值得说明：
 * <ol>
 *   <li><b>不在数据库里写复杂聚合 SQL</b>（除了 db/migration 里那个视图作为备选），
 *       而是在 Java 里算。原因是 MVP 数据量小、指标口径经常变，
 *       放在 Java 里改一行就能上线，不用写迁移脚本。</li>
 *   <li><b>每次现算，不做缓存</b>。数据量小时缓存只会带来"指标不新鲜"的困惑。
 *       等无人机上千架时，再把 KPI 改成"引擎每 N 拍算一次并缓存"。</li>
 * </ol>
 *
 * <p>注意：本类不依赖 {@code SimulationService}，而是直接读运行表，
 * 这样可以避免"引擎 → 看板 → 引擎"的循环依赖。
 */
@Service
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    private final DeliveryOrderRepository orderRepository;
    private final DispatchTaskRepository taskRepository;
    private final DroneRepository droneRepository;
    private final ConflictEventRepository conflictRepository;
    private final SimulationRunRepository runRepository;
    private final TelemetryBroadcaster broadcaster;
    private final SkyProperties properties;
    private final WindField windField;

    public DashboardService(DeliveryOrderRepository orderRepository,
                            DispatchTaskRepository taskRepository,
                            DroneRepository droneRepository,
                            ConflictEventRepository conflictRepository,
                            SimulationRunRepository runRepository,
                            TelemetryBroadcaster broadcaster,
                            SkyProperties properties,
                            WindField windField) {
        this.orderRepository = orderRepository;
        this.taskRepository = taskRepository;
        this.droneRepository = droneRepository;
        this.conflictRepository = conflictRepository;
        this.runRepository = runRepository;
        this.broadcaster = broadcaster;
        this.properties = properties;
        this.windField = windField;
    }

    /**
     * 当前风场读数（按巡航高度取值，供 HUD 展示）。
     *
     * <p>只做展示用，不参与任何计算：真正影响飞行的是 {@link WindField#adjust}。
     */
    public DashboardDtos.WindView windView(BigDecimal simTimeS) {
        double t = simTimeS == null ? 0.0 : simTimeS.doubleValue();
        WindField.Vector vector = windField.vectorAt(t, properties.flight().defaultAltitudeM());
        return new DashboardDtos.WindView(
                windField.enabled(),
                BigDecimal.valueOf(vector.speedMps()).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(vector.fromDirectionDeg()).setScale(1, RoundingMode.HALF_UP));
    }

    /** 核心指标。 */
    @Transactional(readOnly = true)
    public DashboardDtos.Kpi kpi() {
        // 一次 GROUP BY 拿到全部状态计数，替代原来 6 条 countByStatus（订单量越大差距越明显）
        Map<OrderStatus, Long> orderCounts = new java.util.EnumMap<>(OrderStatus.class);
        for (Object[] row : orderRepository.countGroupByStatus()) {
            orderCounts.put((OrderStatus) row[0], ((Number) row[1]).longValue());
        }
        long totalOrders = orderCounts.values().stream().mapToLong(Long::longValue).sum();
        long pending = sum(orderCounts, OrderStatus.CREATED, OrderStatus.PLANNING, OrderStatus.QUEUED);
        long inFlight = sum(orderCounts, OrderStatus.ASSIGNED, OrderStatus.IN_FLIGHT);
        long delivered = sum(orderCounts, OrderStatus.DELIVERED);
        long failed = sum(orderCounts, OrderStatus.FAILED);

        long totalDrones = droneRepository.count();
        long activeDrones = droneRepository.countByStatusIn(List.of(
                DroneStatus.ASSIGNED, DroneStatus.IN_FLIGHT, DroneStatus.RETURNING));
        Double avgBattery = droneRepository.averageBatteryPct();
        double avgBatteryPct = avgBattery == null ? 0 : avgBattery;

        long totalTasks = taskRepository.count();
        long completedTasks = taskRepository.countByStatus(TaskStatus.COMPLETED);
        // 平均耗时 / 准时率全部交给数据库聚合，避免把全部已完成任务和全部订单读进内存
        Double avgDuration = taskRepository.averageDurationByStatus(TaskStatus.COMPLETED);
        Double avgDeliverySeconds = avgDuration == null ? null : round(avgDuration, 2);
        long completedWithOrder = taskRepository.countByStatusWithOrder(TaskStatus.COMPLETED);
        Double onTimeRate = completedWithOrder == 0 ? null
                : round((double) taskRepository.countOnTimeByStatus(TaskStatus.COMPLETED) / completedWithOrder, 4);

        SimulationRun run = runRepository.findFirstByStatusOrderByIdDesc(RunStatus.RUNNING).orElse(null);

        return new DashboardDtos.Kpi(
                totalOrders,
                pending,
                inFlight,
                delivered,
                failed,
                totalTasks,
                completedTasks,
                activeDrones,
                totalDrones,
                round(avgBatteryPct, 2),
                avgDeliverySeconds,
                onTimeRate,
                conflictRepository.countByStatus(ConflictStatus.OPEN),
                run == null ? BigDecimal.ZERO : run.getSimTimeS(),
                run == null ? 0L : run.getTickCount(),
                run == null ? RunStatus.STOPPED.name() : run.getStatus().name(),
                broadcaster.onlineCount());
    }

    /** 累加多个状态的订单数。 */
    private long sum(Map<OrderStatus, Long> counts, OrderStatus... statuses) {
        long total = 0;
        for (OrderStatus status : statuses) {
            total += counts.getOrDefault(status, 0L);
        }
        return total;
    }

    /** 全部无人机的一帧快照（地图打点用）。 */
    @Transactional(readOnly = true)
    public List<DashboardDtos.DroneFrame> droneFrames() {
        // join fetch 机型：避免每一帧都触发一次 model 懒加载
        return buildFrames(droneRepository.findAllWithModel());
    }

    /** 由给定无人机集合构建遥测帧。 */
    public List<DashboardDtos.DroneFrame> buildFrames(List<Drone> drones) {
        // 只按当前任务 id 批量取任务/订单，而不是把全表读进内存。
        // 仿真一拍调用一次，订单/任务累积后这个差别是数量级的。
        java.util.Set<Long> taskIds = drones.stream()
                .map(Drone::getCurrentTaskId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, DispatchTask> tasks = new java.util.HashMap<>();
        if (!taskIds.isEmpty()) {
            taskRepository.findAllById(taskIds).forEach(t -> tasks.put(t.getId(), t));
        }
        java.util.Set<Long> orderIds = tasks.values().stream()
                .map(DispatchTask::getOrderId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, DeliveryOrder> orders = new java.util.HashMap<>();
        if (!orderIds.isEmpty()) {
            orderRepository.findAllById(orderIds).forEach(o -> orders.put(o.getId(), o));
        }

        List<DashboardDtos.DroneFrame> frames = new ArrayList<>(drones.size());
        Instant now = Instant.now();
        for (Drone drone : drones) {
            DispatchTask task = drone.getCurrentTaskId() == null ? null : tasks.get(drone.getCurrentTaskId());
            DeliveryOrder order = task == null ? null : orders.get(task.getOrderId());
            frames.add(new DashboardDtos.DroneFrame(
                    drone.getId(), drone.getCode(), modelCodeOf(drone),
                    task == null ? null : task.getId(),
                    order == null ? null : order.getOrderNo(),
                    drone.getStatus(),
                    drone.getCurrentLat(), drone.getCurrentLon(),
                    drone.getAltitudeM(), drone.getSpeedMps(), drone.getHeadingDeg(),
                    drone.getBatteryPct(),
                    task == null ? null : task.getProgressPct(),
                    task == null ? null : task.getCurrentWaypointSeq(),
                    drone.getLastHeartbeatAt() == null ? now : drone.getLastHeartbeatAt()));
        }
        return frames;
    }

    /**
     * 取机型代码（遥测帧里要带给前端做外形渲染）。
     *
     * <p>型号是 LAZY 关联，正常路径下调用方都在事务里（{@code droneFrames()} 与仿真单拍都是），
     * 这里仍然兜住"事务外访问"的异常：宁可这一帧退回通用外形，也不能让整段遥测抛出去把看板打挂。
     */
    private String modelCodeOf(Drone drone) {
        try {
            return drone.getModel() == null ? null : drone.getModel().getCode();
        } catch (RuntimeException e) {
            log.debug("取机型代码失败（可能不在事务上下文），本帧退回通用外形：{}", e.getMessage());
            return null;
        }
    }

    /** WebSocket 首帧用的全量快照。 */
    public DashboardDtos.Snapshot buildSnapshot() {
        SimulationRun run = runRepository.findFirstByStatusOrderByIdDesc(RunStatus.RUNNING).orElse(null);
        BigDecimal simTime = run == null ? BigDecimal.ZERO : run.getSimTimeS();
        return new DashboardDtos.Snapshot(
                simTime,
                run == null ? RunStatus.STOPPED.name() : run.getStatus().name(),
                run == null ? 0L : run.getTickCount(),
                droneFrames(),
                kpi(),
                topOpenConflicts(),
                orderPins(),
                windView(simTime));
    }

    /**
     * 地图用的订单地理标记：全部未完结订单 + 最近若干已送达订单。
     *
     * <p>已送达的目的地保留几条（绿色），运营才能在地图上直观确认"飞机确实到达了目的地"；
     * 失败 / 取消的不画，避免地图堆无效标记。总量封顶，防止长期运行后标记过载。
     */
    private static final int MAX_ORDER_PINS = 24;
    private static final int RECENT_DELIVERED_PINS = 6;

    /** 地图只关心未完结订单，不需要把历史订单全量读出来排序。 */
    private static final List<OrderStatus> ACTIVE_ORDER_STATUSES = List.of(
            OrderStatus.CREATED, OrderStatus.PLANNING, OrderStatus.QUEUED,
            OrderStatus.ASSIGNED, OrderStatus.IN_FLIGHT);

    @Transactional(readOnly = true)
    public List<DashboardDtos.OrderPin> orderPins() {
        // 改成"未完结订单（按 id 倒序）+ 最近 6 条已送达"，两条带索引的限量查询
        List<DashboardDtos.OrderPin> result = new ArrayList<>(MAX_ORDER_PINS);
        int activeLimit = MAX_ORDER_PINS - RECENT_DELIVERED_PINS;
        for (DeliveryOrder order : orderRepository.findByStatusInOrderByIdDesc(ACTIVE_ORDER_STATUSES)) {
            if (result.size() >= activeLimit) {
                break;
            }
            result.add(pin(order));
        }
        for (DeliveryOrder order : orderRepository.findTop6ByStatusOrderByIdDesc(OrderStatus.DELIVERED)) {
            if (result.size() >= MAX_ORDER_PINS) {
                break;
            }
            result.add(pin(order));
        }
        return result;
    }

    private DashboardDtos.OrderPin pin(DeliveryOrder order) {
        return new DashboardDtos.OrderPin(
                order.getId(), order.getOrderNo(), order.getStatus(), order.getWeightKg(),
                order.getPickupLat(), order.getPickupLon(), order.getDropoffLat(), order.getDropoffLon(),
                order.getAssignedDroneId());
    }

    /** 最近的未处理冲突（快照里只带 10 条，避免首帧过大）。 */
    private List<DashboardDtos.ConflictView> topOpenConflicts() {
        return conflictRepository.findTop10ByStatusOrderByDetectedAtDesc(ConflictStatus.OPEN).stream()
                .map(e -> DashboardDtos.ConflictView.from(e, e.getDetail()))
                .toList();
    }

    /** 暴露默认栅格参数给前端画图例用。 */
    public double defaultGridSizeM() {
        return properties.flight().gridSizeM();
    }

    private double round(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }
}
