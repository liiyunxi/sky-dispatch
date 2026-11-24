package com.skydispatch.engine;

import com.skydispatch.common.GeoPoint;
import com.skydispatch.config.SkyProperties;
import com.skydispatch.domain.entity.ChargingStation;
import com.skydispatch.domain.entity.DeliveryOrder;
import com.skydispatch.domain.entity.DispatchTask;
import com.skydispatch.domain.entity.Drone;
import com.skydispatch.domain.entity.DroneModel;
import com.skydispatch.domain.entity.FlightRoute;
import com.skydispatch.domain.enums.DroneStatus;
import com.skydispatch.domain.enums.OrderStatus;
import com.skydispatch.domain.enums.TaskStatus;
import com.skydispatch.repository.ChargingStationRepository;
import com.skydispatch.repository.DispatchTaskRepository;
import com.skydispatch.repository.DroneRepository;
import com.skydispatch.service.EventService;
import com.skydispatch.service.RouteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 无人机的"自主补能"决策（dev 演示默认开启，见 {@code sky.dispatch.relay-charging-enabled}）。
 *
 * <p><b>它解决什么问题</b>：机巢只在起点。一旦订单距离超过机型的往返续航，
 * 派单阶段就会被续航条件淘汰 —— 演示数据里那单 10.5km 的"虹桥→中山公园"，
 * 26 架机没有一架接得了，订单永远躺在待派列表里。有充电站之后，
 * <b>飞机自己判断"这点电飞不完了"，就近落站补能，充满再接着飞</b>，
 * 长单才真正可执行。这也是真实低空物流的运营方式：补能点决定运力半径。
 *
 * <p><b>决策边界</b>：本类只做"要不要补能、去哪儿补、补完接下来飞哪一段"这三件事，
 * 不碰航路算法（改航交给 {@link RouteService} 画直线，与碰撞避让同一套机制），
 * 也不碰电量模型（能耗估算走 {@link EnergyModel}）。
 *
 * <p><b>为什么要记住"补完后飞哪一段"</b>：改航把航路换成了"当前位置 → 充电站"，
 * 原来那条任务航路已被覆盖，所以必须在改航时把"接下来要去送货点还是回机巢"记下来
 * （{@link RelayPlan}），补完能照着重建航路。用内存 Map 即可：这是瞬态运行事件，
 * 任务结束就清除，不值得为此改表结构。
 */
@Service
public class BatteryRelayService {

    private static final Logger log = LoggerFactory.getLogger(BatteryRelayService.class);

    /** 事件类型：无人机自主改航补能（前端事件流里会显示这一条）。 */
    public static final String EVENT_RELAY_DIVERTED = "DRONE_RELAY_DIVERTED";
    /** 事件类型：抵达充电站、开始补能。 */
    public static final String EVENT_RELAY_ARRIVED = "DRONE_RELAY_ARRIVED";
    /** 事件类型：补能完成、恢复飞行。 */
    public static final String EVENT_RELAY_RESUMED = "DRONE_RELAY_RESUMED";

    /** 参与自主补能判断的任务状态（飞行中、尚未落站）。 */
    private static final List<TaskStatus> RELAY_STATUSES = List.of(
            TaskStatus.TAKEOFF, TaskStatus.CRUISING, TaskStatus.RETURNING);

    /** 补能后接下来飞哪一段 */
    private record RelayPlan(
            Long stationId,
            String stationName,
            /** 补能完成后要去的经纬度（送货点或机巢） */
            double resumeLat,
            double resumeLon,
            /** 恢复航路时标记的航段：OUTBOUND=还要送货，RETURNING=只差回家 */
            RerouteRegistry.Leg resumeLeg
    ) {
    }

    private final Map<Long, RelayPlan> plans = new ConcurrentHashMap<>();

    private final ChargingStationRepository stationRepository;
    private final DroneRepository droneRepository;
    private final DispatchTaskRepository taskRepository;
    private final RouteService routeService;
    private final EnergyModel energyModel;
    private final AltitudeLayers altitudeLayers;
    private final ChargingStationRegistry chargingRegistry;
    private final EventService eventService;
    private final RerouteRegistry rerouteRegistry;
    private final SkyProperties properties;

    public BatteryRelayService(ChargingStationRepository stationRepository,
                               DroneRepository droneRepository,
                               DispatchTaskRepository taskRepository,
                               RouteService routeService,
                               EnergyModel energyModel,
                               AltitudeLayers altitudeLayers,
                               ChargingStationRegistry chargingRegistry,
                               EventService eventService,
                               RerouteRegistry rerouteRegistry,
                               SkyProperties properties) {
        this.stationRepository = stationRepository;
        this.droneRepository = droneRepository;
        this.taskRepository = taskRepository;
        this.routeService = routeService;
        this.energyModel = energyModel;
        this.altitudeLayers = altitudeLayers;
        this.chargingRegistry = chargingRegistry;
        this.eventService = eventService;
        this.rerouteRegistry = rerouteRegistry;
        this.properties = properties;
    }

    /** 该任务是否正在"去补能 / 正在补能"（仿真推进靠它区分普通航段与补能航段）。 */
    public boolean isRelaying(Long taskId) {
        return plans.containsKey(taskId);
    }

    /**
     * 清除某任务的补能计划（任务完成/失败/中止时调用）。
     *
     * <p>必须显式清理：这份计划活在内存里、以 taskId 为键，
     * 长期运行如果不清理，失败的补能计划会一直躺着，既是内存泄漏，
     * 也可能让 taskId 被复用时的判断出错。
     */
    public void forget(Long taskId) {
        plans.remove(taskId);
    }

    /** 功能开关。关掉后行为回到旧版：电量不足直接强制返航/中止。 */
    public boolean enabled() {
        return properties.dispatch().relayChargingEnabled();
    }

    // ------------------------------------------------------------------
    // 一、扫描：自己判断"这点电飞不完"
    // ------------------------------------------------------------------

    /**
     * 扫描在飞任务，电量不足以飞完剩余航程时自主改航到最近可达的充电站。
     *
     * <p>判断口径（保守但不过敏）：
     * <pre>
     *   剩余能耗 = 剩余航路能耗（带当前载荷） + 卸货后回家的能耗（未送达时）
     *   触发条件 = 剩余能耗 &gt; 可用电量 × relaySafetyFactor
     * </pre>
     * 系数大于 1 是给"能耗估值误差 + 绕飞 + 悬停"留余量，避免一有偏差就去充电。
     *
     * <p>选站口径：就近 + <b>现在这点电确实飞得到</b>（飞到站的能耗 + 一次降落悬停
     * 必须小于可用电量的 90%）。只"看着近"却飞不到，落不了站反而更糟。
     *
     * @return 本拍自主改航的架数
     */
    @Transactional
    public int scanAndRelay(BigDecimal simTime, Long runId,
                            List<DispatchTask> activeTasks,
                            Map<Long, Drone> drones,
                            Map<Long, FlightRoute> routes,
                            Map<Long, DeliveryOrder> orders) {
        if (!enabled()) {
            return 0;
        }
        List<ChargingStation> stations = stationRepository.findAllByEnabledTrue();
        if (stations.isEmpty()) {
            return 0;
        }
        SkyProperties.Dispatch cfg = properties.dispatch();

        List<DispatchTask> flying = activeTasks.stream()
                .filter(t -> RELAY_STATUSES.contains(t.getStatus()))
                .toList();

        int diverted = 0;
        for (DispatchTask task : flying) {
            if (plans.containsKey(task.getId())) {
                continue;   // 已经在去补能的路上，别重复改航
            }
            Drone drone = drones.get(task.getDroneId());
            if (drone == null || drone.getBatteryPct() == null) {
                continue;
            }
            FlightRoute route = task.getRouteId() == null ? null : routes.get(task.getRouteId());
            if (route == null) {
                continue;
            }
            // 拿本地引用：下面回调/分支里要用，TS 那套收窄在 Java 里对应"局部常量"
            DroneModel model = drone.getModel();
            if (model == null) {
                continue;
            }
            DeliveryOrder order = orders.get(task.getOrderId());
            boolean delivered = order != null && order.getStatus() == OrderStatus.DELIVERED;
            double payload = delivered || order == null ? 0 : order.getWeightKg().doubleValue();

            GeoPoint position = positionOf(drone);
            double routeDistance = route.getDistanceM().doubleValue();
            double flown = task.getActualDistanceM() == null ? 0 : task.getActualDistanceM().doubleValue();
            double remainingLeg = Math.max(0, routeDistance - flown);

            // 卸货后还要飞回机巢：未送达时把这一段也算进"剩余能耗"
            double homeLeg = 0;
            if (!delivered && order != null && drone.getHomeLat() != null && drone.getHomeLon() != null) {
                homeLeg = new GeoPoint(order.getDropoffLat(), order.getDropoffLon())
                        .distanceTo(new GeoPoint(drone.getHomeLat(), drone.getHomeLon()));
            }

            double batteryPct = drone.getBatteryPct().doubleValue();
            double usablePct = Math.max(0, batteryPct - cfg.minBatteryReservePct());
            BigDecimal usableWh = energyModel.usableEnergyWh(model, usablePct);
            if (usableWh.signum() <= 0) {
                continue;   // 已经是"零余量"，强制返航逻辑会接管
            }

            BigDecimal needWh = energyModel.cruiseEnergyWh(model, remainingLeg + homeLeg, payload)
                    .add(energyModel.hoverEnergyWh(model, payload));
            if (needWh.doubleValue() <= usableWh.doubleValue() * cfg.relaySafetyFactor()) {
                continue;   // 这点电够用，不需要补能
            }

            ChargingStation station = pickReachableStation(model, position, payload, usableWh, stations);
            if (station == null) {
                continue;   // 附近没有飞得到的站：交给低电量保护处理（强制返航/中止）
            }

            divert(task, drone, station, order, delivered, position, needWh, usableWh, simTime, runId);
            diverted++;
        }
        return diverted;
    }

    /**
     * 选一个"现在这点电飞得到"的最近充电站。
     *
     * @return 可落站的站点；都不满足返回 null
     */
    private ChargingStation pickReachableStation(DroneModel model, GeoPoint position, double payload,
                                                 BigDecimal usableWh,
                                                 List<ChargingStation> stations) {
        ChargingStation best = null;
        double bestScore = Double.MAX_VALUE;
        double budget = usableWh.doubleValue() * 0.9;
        for (ChargingStation station : stations) {
            if (station.getLat() == null || station.getLon() == null) {
                continue;
            }
            double distance = position.distanceTo(new GeoPoint(station.getLat(), station.getLon()));
            // 飞得到的判据：飞到站的巡航能耗 + 一次降落悬停，必须落在可用电量预算内
            double reachWh = energyModel.cruiseEnergyWh(model, distance, payload).doubleValue()
                    + energyModel.hoverEnergyWh(model, payload).doubleValue();
            if (reachWh > budget) {
                continue;
            }
            // 有空桩优先；满站按排队长度加惩罚，避免所有飞机都往同一个热门站挤
            int capacity = chargingRegistry.capacity(station);
            int occupancy = chargingRegistry.occupancy(station.getId());
            int waiting = chargingRegistry.queueLength(station.getId());
            double score = distance + (occupancy < capacity ? 0 : 2000.0 + waiting * 500.0);
            if (score < bestScore) {
                best = station;
                bestScore = score;
            }
        }
        return best;
    }

    /** 执行改航：把航路换成"当前位置 → 充电站"，并记住补完能后要去哪一段。 */
    private void divert(DispatchTask task, Drone drone, ChargingStation station, DeliveryOrder order,
                        boolean delivered, GeoPoint position, BigDecimal needWh, BigDecimal usableWh,
                        BigDecimal simTime, Long runId) {
        GeoPoint target = new GeoPoint(station.getLat(), station.getLon());
        // 改航保持本机原有高度层，避免中途换层制造新的穿越冲突
        routeService.replaceWithStraightLine(task.getRouteId(), position, target,
                altitudeLayers.forDrone(drone));
        task.setActualDistanceM(BigDecimal.ZERO);
        task.setCurrentWaypointSeq(0);
        taskRepository.save(task);

        // 航段标记：去程段按 OUTBOUND（送达后回程会重建成"送货点→机巢"直线），
        // 返航段按 RETURNING（新折线方向就是"当前位置→站"，须正向飞而不是反向）
        RerouteRegistry.Leg leg = delivered ? RerouteRegistry.Leg.RETURNING : RerouteRegistry.Leg.OUTBOUND;
        rerouteRegistry.mark(task.getId(), leg);

        double resumeLat = delivered
                ? (drone.getHomeLat() == null ? position.lat() : drone.getHomeLat())
                : (order == null ? position.lat() : order.getDropoffLat());
        double resumeLon = delivered
                ? (drone.getHomeLon() == null ? position.lon() : drone.getHomeLon())
                : (order == null ? position.lon() : order.getDropoffLon());
        plans.put(task.getId(), new RelayPlan(
                station.getId(), station.getName(), resumeLat, resumeLon, leg));

        String message = String.format(
                "无人机 %s 自主判断电量不足以飞完剩余航程（约需 %.0fWh，可用 %.0fWh），"
                        + "已改航至「%s」补能，充满后继续%s",
                drone.getCode(), needWh.doubleValue(), usableWh.doubleValue(), station.getName(),
                delivered ? "返航" : "配送");
        eventService.simulation(EVENT_RELAY_DIVERTED, message, runId, simTime);
        log.info("[自主补能] {}", message);
    }

    // ------------------------------------------------------------------
    // 二、到站补能
    // ------------------------------------------------------------------

    /** 抵达充电站：飞机落到桩上，任务进入 CHARGING。 */
    @Transactional
    public void beginCharging(DispatchTask task, Drone drone, BigDecimal simTime, Long runId) {
        RelayPlan plan = plans.get(task.getId());
        if (plan == null) {
            return;
        }
        task.setStatus(TaskStatus.CHARGING);
        taskRepository.save(task);

        drone.setStatus(DroneStatus.CHARGING);
        drone.setSpeedMps(BigDecimal.ZERO);
        drone.setAltitudeM(BigDecimal.ZERO);
        drone.setLastHeartbeatAt(java.time.Instant.now());
        droneRepository.save(drone);

        // 桩位是有限资源：有空格才能充，满了就排队（排队期间仍占用站点等待位）
        ChargingStation station = stationRepository.findById(plan.stationId()).orElse(null);
        int capacity = chargingRegistry.capacity(station);
        boolean acquired = chargingRegistry.tryAcquire(plan.stationId(), drone.getId(), capacity);
        boolean lowBatteryPriority = false;
        if (!acquired) {
            double battery = drone.getBatteryPct() == null ? 0 : drone.getBatteryPct().doubleValue();
            lowBatteryPriority = battery <= properties.dispatch().minBatteryReservePct() + 10;
            if (lowBatteryPriority) {
                chargingRegistry.enqueueFirst(plan.stationId(), drone.getId());
            } else {
                chargingRegistry.enqueue(plan.stationId(), drone.getId());
            }
        }
        String message = acquired
                ? String.format("无人机 %s 已降落「%s」，开始补能（当前 %s%%，桩位 %d/%d）",
                        drone.getCode(), plan.stationName(), drone.getBatteryPct(),
                        chargingRegistry.occupancy(plan.stationId()), capacity)
                : String.format("无人机 %s 抵达「%s」，桩位已满（%d/%d），进入排队第 %d 位%s",
                        drone.getCode(), plan.stationName(),
                        chargingRegistry.occupancy(plan.stationId()), capacity,
                        chargingRegistry.queuePosition(drone.getId()),
                        lowBatteryPriority ? "（低电优先）" : "");
        eventService.simulation(EVENT_RELAY_ARRIVED, message, runId, simTime);
        log.info("[自主补能] {}", message);
    }

    /**
     * 推进一拍补能。
     *
     * @return true 表示本拍已充满并恢复了飞行（航路已重建）
     */
    @Transactional
    public boolean advanceCharging(DispatchTask task, Drone drone, double simDeltaSeconds,
                                   BigDecimal simTime, Long runId) {
        RelayPlan plan = plans.get(task.getId());
        if (plan == null) {
            return false;
        }
        ChargingStation station = stationRepository.findById(plan.stationId()).orElse(null);
        int capacity = chargingRegistry.capacity(station);

        // 排队中：本拍不充电，只尝试叫号；排到了再开始充
        if (chargingRegistry.isQueued(drone.getId())) {
            int promoted = chargingRegistry.promote(plan.stationId(), capacity);
            if (!chargingRegistry.isCharging(drone.getId())) {
                droneRepository.save(drone);
                return false;
            }
            String queuedMessage = String.format("无人机 %s 轮到补能（当前 %s%%，等待队列推进 %d 架）",
                    drone.getCode(), drone.getBatteryPct(), promoted);
            eventService.simulation(EVENT_RELAY_ARRIVED, queuedMessage, runId, simTime);
            log.info("[自主补能] {}", queuedMessage);
        }

        SkyProperties.Dispatch cfg = properties.dispatch();
        double battery = drone.getBatteryPct() == null ? 0 : drone.getBatteryPct().doubleValue();
        // 充电曲线：高 SOC 进入恒压段，功率衰减
        double gain = properties.simulation().rechargeRatePctPerSimSec()
                * chargingRegistry.powerFactor(battery) * simDeltaSeconds;
        double next = Math.min(cfg.relayResumePct(), battery + gain);
        drone.setBatteryPct(BigDecimal.valueOf(next).setScale(2, RoundingMode.HALF_UP));

        if (next < cfg.relayResumePct() - 1e-6) {
            droneRepository.save(drone);
            return false;
        }

        // 充满：释放桩位，重建"充电站 → 接下来那一段的终点"的航路，然后继续飞
        chargingRegistry.release(drone.getId());
        GeoPoint from = station == null || station.getLat() == null
                ? positionOf(drone)
                : new GeoPoint(station.getLat(), station.getLon());
        GeoPoint to = new GeoPoint(plan.resumeLat(), plan.resumeLon());
        routeService.replaceWithStraightLine(task.getRouteId(), from, to,
                altitudeLayers.forDrone(drone));
        task.setActualDistanceM(BigDecimal.ZERO);
        task.setCurrentWaypointSeq(0);
        task.setStatus(plan.resumeLeg() == RerouteRegistry.Leg.RETURNING
                ? TaskStatus.RETURNING : TaskStatus.CRUISING);
        taskRepository.save(task);

        drone.setStatus(DroneStatus.IN_FLIGHT);
        drone.setCurrentLat(from.lat());
        drone.setCurrentLon(from.lon());
        drone.setSpeedMps(BigDecimal.ZERO);
        drone.setAltitudeM(BigDecimal.ZERO);
        drone.setLastHeartbeatAt(java.time.Instant.now());
        droneRepository.save(drone);

        rerouteRegistry.mark(task.getId(), plan.resumeLeg());
        plans.remove(task.getId());

        String message = String.format("无人机 %s 补能完成（%s%%），恢复%s",
                drone.getCode(), drone.getBatteryPct(),
                plan.resumeLeg() == RerouteRegistry.Leg.RETURNING ? "返航" : "配送");
        eventService.simulation(EVENT_RELAY_RESUMED, message, runId, simTime);
        log.info("[自主补能] {}", message);
        return true;
    }

    /** 无人机当前位置：优先实时坐标，没有则回落到机巢。 */
    private GeoPoint positionOf(Drone drone) {
        if (drone.getCurrentLat() != null && drone.getCurrentLon() != null) {
            return new GeoPoint(drone.getCurrentLat(), drone.getCurrentLon());
        }
        if (drone.getHomeLat() != null && drone.getHomeLon() != null) {
            return new GeoPoint(drone.getHomeLat(), drone.getHomeLon());
        }
        return new GeoPoint(0, 0);
    }
}
