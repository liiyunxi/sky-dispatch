package com.skydispatch.engine;

import com.skydispatch.common.GeoPoint;
import com.skydispatch.common.GeoUtils;
import com.skydispatch.config.SkyProperties;
import com.skydispatch.domain.entity.AirspaceZone;
import com.skydispatch.domain.entity.ChargingStation;
import com.skydispatch.domain.entity.Drone;
import com.skydispatch.domain.entity.DroneModel;
import com.skydispatch.domain.enums.DroneStatus;
import com.skydispatch.domain.enums.Priority;
import com.skydispatch.domain.enums.ZoneShape;
import com.skydispatch.domain.enums.ZoneType;
import com.skydispatch.dto.CommonDtos;
import com.skydispatch.dto.OrderDtos;
import com.skydispatch.repository.AirspaceZoneRepository;
import com.skydispatch.repository.ChargingStationRepository;
import com.skydispatch.repository.DeliveryOrderRepository;
import com.skydispatch.repository.DroneRepository;
import com.skydispatch.service.AirspaceService;
import com.skydispatch.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Random;

/**
 * 演示订单补给泉（仅在 dev 演示环境开启）。
 *
 * <p><b>为什么需要它</b>：演示数据只有固定 6 单，全部送达后机队无单可飞、看板彻底静止。
 * 本组件在"待派 + 在飞"订单低于目标值时，<b>以一架确实空闲且电量充足的飞机为运力锚点</b>，
 * 生成一张该机型必然能承接的近距离订单，从而维持持续的起降与配送画面。
 *
 * <p><b>"先找运力，再造匹配的单"</b>是关键：若随机造单，很可能又造出一张全机队都
 * 飞不到的远单/重单，重蹈"订单堆积、飞机静止"的覆辙。这里把取货点直接放在锚点的
 * 起降点（空载调机距离为 0）、目的地限制在 1.8~3.8km、重量限制在机型载重的 10%~25%，
 * 对电量 ≥60% 的锚点，往返能耗必然低于可用电量。
 *
 * <p><b>生产环境必须关闭</b>（{@code sky.simulation.demo-replenish-enabled=false}）：
 * 订单只能来自真实客户，系统不能自己给自己下单。
 */
@Service
public class DemoOrderFountain {

    private static final Logger log = LoggerFactory.getLogger(DemoOrderFountain.class);

    /** 未完结（还能产生飞行活动）的订单状态。 */
    private static final List<com.skydispatch.domain.enums.OrderStatus> ACTIVE_ORDER_STATUSES = List.of(
            com.skydispatch.domain.enums.OrderStatus.CREATED,
            com.skydispatch.domain.enums.OrderStatus.QUEUED,
            com.skydispatch.domain.enums.OrderStatus.PLANNING,
            com.skydispatch.domain.enums.OrderStatus.ASSIGNED,
            com.skydispatch.domain.enums.OrderStatus.IN_FLIGHT);

    /**
     * 造单时要避开的障碍物类型。
     *
     * <p>与后端"参与路径规划"的口径保持一致（禁飞区 / 限飞区 / 建筑障碍）。
     */
    private static final List<ZoneType> OBSTACLE_ZONE_TYPES =
            List.of(ZoneType.NO_FLY, ZoneType.RESTRICTED, ZoneType.BUILDING, ZoneType.TERRAIN);

    /** 目的地距离范围（公里）：往返 3.6~7.6km，近到各机型在 60% 电量下都能完成。 */
    private static final double MIN_DISTANCE_KM = 1.8;
    private static final double MAX_DISTANCE_KM = 3.8;

    /**
     * 长航程订单的比例与距离范围（公里）。
     *
     * <p>这类单子<b>刻意超过机型的往返续航</b>，靠"途中自主落到充电站补能"完成 ——
     * 只有造出这样的单，演示里才看得到自主补能。能不能造由
     * {@link ReachabilityService} 做完整可达性分析把关（与派单同一套判据），不满足就退回近单。
     */
    private static final double LONG_ORDER_RATIO = 0.25;
    private static final double LONG_MIN_DISTANCE_KM = 8.0;
    private static final double LONG_MAX_DISTANCE_KM = 14.0;
    /** 货物重量占机型最大载重的比例区间。 */
    private static final double MIN_PAYLOAD_RATIO = 0.10;
    private static final double MAX_PAYLOAD_RATIO = 0.25;
    /** 目的地与圆形禁飞区圆心的最小额外安全距离（米）。 */
    private static final double NO_FLY_BUFFER_M = 300.0;
    /** 为避开禁飞区重新选址的最大尝试次数。 */
    private static final int MAX_LOCATION_TRIES = 8;
    /** 每度纬度对应的公里数（上海一带足够精确）。 */
    private static final double KM_PER_DEGREE_LAT = 111.0;

    /** 四个演示城市的中心点（用于早晚高峰的"进城/出城"方向偏好）。 */
    private static final double[][] CITY_CENTERS = {
            {31.2304, 121.4737},   // 上海 人民广场
            {39.9087, 116.4074},   // 北京 国贸
            {22.5431, 114.0579},   // 深圳 福田
            {30.5728, 104.0668},   // 成都 天府广场
    };

    // ---- 演示用文案常量（集中定义，不在逻辑里散落裸字符串） ----
    private static final String[] CUSTOMERS = {
            "市第一人民医院", "王先生", "李女士", "晨光便利店", "京东前置仓", "陈先生",
            "张江科技园", "社区卫生中心", "赵女士", "盒马鲜生", "孙先生", "互联网药房",
    };
    private static final String[] CARGOES = {
            "急救药品", "文件快递", "生鲜果蔬", "电子元件", "午餐外卖", "检测样本",
            "日用百货", "疫苗冷藏包", "图书资料", "母婴用品", "咖啡订单", "护理耗材",
    };
    private static final String[] DROP_LABELS = {
            "人民广场站点", "静安配送点", "陆家嘴驿站", "中山公园点", "徐家汇柜台",
            "长宁便民点", "普陀前置仓", "虹口服务站", "浦东驿站", "徐汇网点",
    };

    /** 固定种子的随机源：同一时刻多次运行选址稳定，且不依赖每次新建 Random。 */
    private final Random random = new Random(20261015L);

    private final SkyProperties properties;
    private final DroneRepository droneRepository;
    private final DeliveryOrderRepository orderRepository;
    private final AirspaceZoneRepository zoneRepository;
    private final OrderService orderService;
    private final ReachabilityService reachability;
    private final ChargingStationRepository stationRepository;
    private final EnergyModel energyModel;
    private final FlightTimeEstimator timeEstimator;
    private final AltitudeLayers altitudeLayers;

    public DemoOrderFountain(SkyProperties properties,
                             DroneRepository droneRepository,
                             DeliveryOrderRepository orderRepository,
                             AirspaceZoneRepository zoneRepository,
                             OrderService orderService,
                             ReachabilityService reachability,
                             ChargingStationRepository stationRepository,
                             EnergyModel energyModel,
                             FlightTimeEstimator timeEstimator,
                             AltitudeLayers altitudeLayers) {
        this.properties = properties;
        this.droneRepository = droneRepository;
        this.orderRepository = orderRepository;
        this.zoneRepository = zoneRepository;
        this.orderService = orderService;
        this.reachability = reachability;
        this.stationRepository = stationRepository;
        this.energyModel = energyModel;
        this.timeEstimator = timeEstimator;
        this.altitudeLayers = altitudeLayers;
    }

    /**
     * 若活动订单不足则补单。
     *
     * @return 本次新生成的订单数
     */
    @Transactional
    public int replenishIfNeeded(java.math.BigDecimal simTimeS) {
        SkyProperties.Simulation cfg = properties.simulation();
        if (!cfg.demoReplenishEnabled()) {
            return 0;
        }
        long activeOrders = orderRepository.countByStatusIn(ACTIVE_ORDER_STATUSES);
        long deficit = cfg.targetActiveOrders() - activeOrders;
        if (deficit <= 0) {
            return 0;
        }
        int toCreate = (int) Math.min(deficit, Math.max(1, cfg.replenishBatchSize()));

        // 运力锚点：空闲、电量达标、有起降点坐标的飞机，电量高者优先；本轮不重复使用同一架
        List<Drone> anchors = droneRepository.findAllWithModel().stream()
                .filter(d -> d.getStatus() == DroneStatus.IDLE)
                .filter(d -> d.getBatteryPct() != null
                        && d.getBatteryPct().doubleValue() >= cfg.replenishMinBatteryPct())
                .filter(d -> d.getHomeLat() != null && d.getHomeLon() != null)
                .sorted((a, b) -> b.getBatteryPct().compareTo(a.getBatteryPct()))
                .limit(toCreate)
                .toList();

        double simHour = simHour(simTimeS);
        int created = 0;
        for (Drone anchor : anchors) {
            OrderDtos.CreateRequest request = buildOrderFor(anchor, simHour);
            if (request == null) {
                continue;
            }
            orderService.create(request);
            created++;
        }
        if (created > 0) {
            log.info("演示补给（仿真 {:02.0f}:00 时段）：活动订单 {} < 目标 {}，新生成 {} 单",
                    Math.floor(simHour), activeOrders, cfg.targetActiveOrders(), created);
        }
        return created;
    }

    /** 演示时钟从 08:00 开始，按仿真秒推进到 24 小时制。 */
    private double simHour(java.math.BigDecimal simTimeS) {
        double seconds = simTimeS == null ? 0 : simTimeS.doubleValue();
        return 8.0 + (seconds / 3600.0) % 24.0;
    }

    /** 为指定锚点飞机构造一张它必然能承接的订单；选址多次仍落在障碍物则返回 null。 */
    private OrderDtos.CreateRequest buildOrderFor(Drone anchor, double simHour) {
        double homeLat = anchor.getHomeLat();
        double homeLon = anchor.getHomeLon();

        double maxPayload = anchor.getModel().getMaxPayloadKg().doubleValue();
        double ratio = MIN_PAYLOAD_RATIO + random.nextDouble() * (MAX_PAYLOAD_RATIO - MIN_PAYLOAD_RATIO);
        double weight = Math.max(0.3, Math.round(maxPayload * ratio * 10.0) / 10.0);

        /*
         * 为什么掺长单：如果永远只造 2~4km 的近单，"自主落到充电站补能"在演示里永远看不到。
         * 早/晚高峰把长单比例略调高，模拟"跨区急件"；能否造出由可达性分析把关。
         */
        boolean peak = isPeakHour(simHour);
        boolean wantLong = random.nextDouble() < (peak ? 0.30 : LONG_ORDER_RATIO);
        double[] drop = wantLong ? pickLongDropoff(anchor, weight) : null;
        if (drop == null) {
            // 时空分布：早高峰往城市中心送（居住区 → CBD），晚高峰反向（CBD → 居住区）
            drop = pickSpatialDropoff(anchor, simHour);
        }
        if (drop == null) {
            drop = pickDropoff(homeLat, homeLon);
        }
        if (drop == null) {
            return null;
        }

        Priority priority = pickPriority(simHour);
        String customer = CUSTOMERS[random.nextInt(CUSTOMERS.length)];
        String cargo = CARGOES[random.nextInt(CARGOES.length)];
        String dropLabel = DROP_LABELS[random.nextInt(DROP_LABELS.length)];

        // 期望送达时间与 ETA 同口径：飞行时间 + 自动派单扫描间隔 + 地面时隙/周转，再乘优先级松弛
        double distanceM = GeoUtils.haversineMeters(homeLat, homeLon, drop[0], drop[1]);
        double bearing = GeoUtils.bearingDegrees(homeLat, homeLon, drop[0], drop[1]);
        int etaSeconds = timeEstimator.estimate(distanceM, anchor.getModel(),
                altitudeLayers.forDrone(anchor), bearing).seconds();
        double speedFactor = Math.max(1.0, properties.simulation().speedFactor());
        double dispatchLatencySim = properties.simulation().autoDispatchIntervalMs() / 1000.0 * speedFactor;
        double promisedSimSeconds = etaSeconds + dispatchLatencySim + 30.0;
        double slack = switch (priority) {
            case URGENT -> 1.35;
            case HIGH -> 1.50;
            case NORMAL -> 1.80;
            case LOW -> 2.20;
        };
        long wallSeconds = Math.max(45L,
                Math.round(promisedSimSeconds / speedFactor * slack));

        return new OrderDtos.CreateRequest(
                customer,
                null,
                cargo,
                BigDecimal.valueOf(weight).setScale(1, RoundingMode.HALF_UP),
                priority,
                new CommonDtos.GeoDto(homeLat, homeLon, "无人机起降点"),
                new CommonDtos.GeoDto(drop[0], drop[1], dropLabel),
                Instant.now().plusSeconds(wallSeconds),
                String.format("演示系统自动补给订单（%s%s）", slotLabel(simHour),
                        wantLong ? "·长航程·途中自主补能" : ""));
    }

    /** 早高峰 06:30~10:30，晚高峰 16:30~20:30。 */
    private boolean isPeakHour(double hour) {
        double h = ((hour % 24) + 24) % 24;
        return (h >= 6.5 && h < 10.5) || (h >= 16.5 && h < 20.5);
    }

    private String slotLabel(double hour) {
        double h = ((hour % 24) + 24) % 24;
        if (h >= 6.5 && h < 10.5) {
            return "早高峰";
        }
        if (h >= 16.5 && h < 20.5) {
            return "晚高峰";
        }
        if (h >= 10.5 && h < 16.5) {
            return "日间平峰";
        }
        return "夜间";
    }

    /**
     * 造一张"必须中途补能才能完成"的长单；不可行时返回 null（调用方退回近单）。
     *
     * <p>判据与派单完全一致：{@link ReachabilityService} 必须给出"可行且确实需要经停"的方案，
     * 这张单才造得出来 —— 既保证派得出去、飞得完，也保证演示里真能看到补能动作。
     */
    private double[] pickLongDropoff(Drone anchor, double weightKg) {
        if (!properties.dispatch().relayChargingEnabled()) {
            return null;
        }
        GeoPoint home = new GeoPoint(anchor.getHomeLat(), anchor.getHomeLon());
        DroneModel model = anchor.getModel();
        double battery = anchor.getBatteryPct() == null ? 0 : anchor.getBatteryPct().doubleValue();
        double usablePct = Math.max(0, battery - properties.dispatch().minBatteryReservePct());
        BigDecimal usableWh = energyModel.usableEnergyWh(model, usablePct);
        List<ChargingStation> stations = stationRepository.findAllByEnabledTrue();

        for (int attempt = 0; attempt < MAX_LOCATION_TRIES; attempt++) {
            double distanceKm = LONG_MIN_DISTANCE_KM
                    + random.nextDouble() * (LONG_MAX_DISTANCE_KM - LONG_MIN_DISTANCE_KM);
            double[] candidate = offsetPoint(home.lat(), home.lon(), distanceKm, random.nextDouble() * 360);
            if (isInsideAnyObstacle(candidate[0], candidate[1], activeObstacles())) {
                continue;
            }
            GeoPoint dropoff = new GeoPoint(candidate[0], candidate[1]);
            // 取货点就是机巢：调机段为 0；必须"可达且需要补能"才算长单
            ReachabilityService.Plan plan = reachability.analyze(
                    model, home, home, dropoff, home, weightKg, usableWh, stations);
            if (plan.feasible() && plan.needsCharging()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 在 home 周围随机选一个目的地，并避开<b>所有</b>生效障碍物；多次失败返回 null。
     *
     * <p>早期只避让圆形禁飞区，后来加入"建筑障碍"（多边形）后就不够了：
     * 万一目的地落在楼里，A* 会规划失败、这张单永远派不出去，还会把"活动订单数"顶住导致不再补单。
     */
    private double[] pickDropoff(double homeLat, double homeLon) {
        List<AirspaceZone> obstacles = activeObstacles();

        for (int attempt = 0; attempt < MAX_LOCATION_TRIES; attempt++) {
            double distanceKm = MIN_DISTANCE_KM
                    + random.nextDouble() * (MAX_DISTANCE_KM - MIN_DISTANCE_KM);
            double bearing = random.nextDouble() * 2 * Math.PI;
            double[] candidate = offsetPoint(homeLat, homeLon, distanceKm, bearing);
            if (isInsideAnyObstacle(candidate[0], candidate[1], obstacles)) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    /** 当前启用中的障碍物（禁飞区 / 限飞区 / 建筑障碍）。 */
    private List<AirspaceZone> activeObstacles() {
        return zoneRepository.findAll().stream()
                .filter(z -> Boolean.TRUE.equals(z.getEnabled()))
                .filter(z -> OBSTACLE_ZONE_TYPES.contains(z.getZoneType()))
                .toList();
    }

    /**
     * 从某点按给定方位与距离推出新坐标（等距圆柱近似，城市尺度足够精确）。
     *
     * @param distanceKm 距离（公里）
     * @param bearingRad 方位角（弧度，0 = 正东，逆时针为北）
     * @return {@code [lat, lon]}，保留 6 位小数
     */
    private double[] offsetPoint(double lat, double lon, double distanceKm, double bearingRad) {
        double dLat = Math.cos(bearingRad) * distanceKm / KM_PER_DEGREE_LAT;
        double kmPerDegreeLon = KM_PER_DEGREE_LAT * Math.cos(Math.toRadians(lat));
        double dLon = Math.sin(bearingRad) * distanceKm / Math.max(1.0, kmPerDegreeLon);
        return new double[] { round6(lat + dLat), round6(lon + dLon) };
    }

    /** 点是否落在任一障碍物内（圆用半径+余量，多边形用射线法+边界余量）。 */
    private boolean isInsideAnyObstacle(double lat, double lon, List<AirspaceZone> zones) {
        GeoPoint candidate = new GeoPoint(lat, lon);
        for (AirspaceZone zone : zones) {
            if (zone.getShapeType() == ZoneShape.CIRCLE) {
                if (zone.getCenterLat() == null || zone.getCenterLon() == null || zone.getRadiusM() == null) {
                    continue;
                }
                double distance = candidate.distanceTo(
                        new GeoPoint(zone.getCenterLat(), zone.getCenterLon()));
                if (distance < zone.getRadiusM().doubleValue() + NO_FLY_BUFFER_M) {
                    return true;
                }
                continue;
            }
            List<GeoPoint> polygon = AirspaceService.parsePolygonWkt(zone.getPolygonWkt());
            if (polygon.size() < 3) {
                continue;
            }
            if (GeoUtils.pointInPolygon(lat, lon, polygon)) {
                return true;
            }
            for (int i = 1; i < polygon.size(); i++) {
                if (GeoUtils.distanceToSegmentMeters(candidate, polygon.get(i - 1), polygon.get(i))
                        < NO_FLY_BUFFER_M) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 时空热点选址：早高峰从居住区飞向城市中心，晚高峰反向。
     *
     * <p>只按随机角度造单会让看板上的订单方向均匀铺开，看不出"潮汐需求"。
     * 这里用城市中心与机巢的相对方位确定主方向，再叠加 ±25° 抖动与随机距离，
     * 既保留随机性，又能看出早高峰"进城多"、晚高峰"出城多"。
     */
    private double[] pickSpatialDropoff(Drone anchor, double simHour) {
        double hour = ((simHour % 24) + 24) % 24;
        boolean morning = hour >= 6.5 && hour < 10.5;
        boolean evening = hour >= 16.5 && hour < 20.5;
        if (!morning && !evening) {
            return null;
        }
        double homeLat = anchor.getHomeLat();
        double homeLon = anchor.getHomeLon();
        double[] center = nearestCityCenter(homeLat, homeLon);
        if (center == null) {
            return null;
        }
        double bearing = morning
                ? GeoUtils.bearingDegrees(homeLat, homeLon, center[0], center[1])
                : GeoUtils.bearingDegrees(center[0], center[1], homeLat, homeLon);
        List<AirspaceZone> obstacles = activeObstacles();
        for (int attempt = 0; attempt < MAX_LOCATION_TRIES; attempt++) {
            double distanceKm = 2.2 + random.nextDouble() * 4.5;
            double jittered = bearing + (random.nextDouble() - 0.5) * 50.0;
            double[] candidate = offsetPoint(homeLat, homeLon, distanceKm, Math.toRadians(jittered));
            if (!isInsideAnyObstacle(candidate[0], candidate[1], obstacles)) {
                return candidate;
            }
        }
        return null;
    }

    /** 最近的城市中心；离四个演示城市都太远时返回 null（不在热点区域就不做方向偏置）。 */
    private double[] nearestCityCenter(double lat, double lon) {
        double[] best = null;
        double bestDistance = Double.MAX_VALUE;
        for (double[] center : CITY_CENTERS) {
            double distance = GeoUtils.haversineMeters(lat, lon, center[0], center[1]);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = center;
            }
        }
        return bestDistance <= 60_000 ? best : null;
    }

    /** 优先级分布：高峰时段急救/高优先级比例更高，模拟真实潮汐需求。 */
    private Priority pickPriority(double simHour) {
        boolean peak = isPeakHour(simHour);
        int roll = random.nextInt(100);
        if (roll < (peak ? 13 : 8)) {
            return Priority.URGENT;
        }
        if (roll < (peak ? 38 : 28)) {
            return Priority.HIGH;
        }
        if (roll < 85) {
            return Priority.NORMAL;
        }
        return Priority.LOW;
    }

    private double round6(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).doubleValue();
    }
}
