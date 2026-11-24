package com.skydispatch.bootstrap;

import com.skydispatch.domain.entity.AirspaceZone;
import com.skydispatch.domain.entity.ChargingStation;
import com.skydispatch.domain.entity.Drone;
import com.skydispatch.domain.entity.DroneModel;
import com.skydispatch.domain.enums.ConflictSeverity;
import com.skydispatch.domain.enums.DroneStatus;
import com.skydispatch.domain.enums.Priority;
import com.skydispatch.domain.enums.ZoneShape;
import com.skydispatch.domain.enums.ZoneType;
import com.skydispatch.dto.CommonDtos;
import com.skydispatch.dto.OrderDtos;
import com.skydispatch.repository.AirspaceZoneRepository;
import com.skydispatch.repository.ChargingStationRepository;
import com.skydispatch.repository.DroneModelRepository;
import com.skydispatch.repository.DroneRepository;
import com.skydispatch.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 演示数据初始化。
 *
 * <p>只在 {@code sky.bootstrap.seed-demo-data=true} 时生效（dev 环境默认开启）。
 * 作用是让"克隆下来就能跑"：启动后立刻有机型、有飞机、有空域、有待办订单，
 * 仿真引擎会自动开始派单送货，打开看板就有画面。
 *
 * <p>为什么不用 SQL 脚本灌 dev 数据？因为 dev profile 用的是 H2 内存库，
 * 每次重启都清空，用 Java 写能保证字段类型、默认值、状态枚举与实体完全一致，
 * 不会出现"SQL 里写了个不存在的枚举值"的问题。真实数据库的种子数据仍在
 * {@code db/migration/V2__seed_data.sql}。
 */
@Component
@ConditionalOnProperty(name = "sky.bootstrap.seed-demo-data", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final DroneModelRepository modelRepository;
    private final DroneRepository droneRepository;
    private final AirspaceZoneRepository zoneRepository;
    private final ChargingStationRepository stationRepository;
    private final OrderService orderService;

    public DemoDataSeeder(DroneModelRepository modelRepository,
                          DroneRepository droneRepository,
                          AirspaceZoneRepository zoneRepository,
                          ChargingStationRepository stationRepository,
                          OrderService orderService) {
        this.modelRepository = modelRepository;
        this.droneRepository = droneRepository;
        this.zoneRepository = zoneRepository;
        this.stationRepository = stationRepository;
        this.orderService = orderService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (modelRepository.count() > 0) {
            log.info("检测到已有数据（型号 {} 条），跳过演示数据初始化", modelRepository.count());
            return;
        }
        log.info("开始初始化演示数据...");
        seedModels();
        seedDrones();
        seedZones();
        seedStations();
        seedOrders();
        log.info("演示数据初始化完成：型号 {} 种、无人机 {} 架（覆盖上海/北京/深圳/成都）、空域 {} 个、充电站 {} 座、待派订单 {} 单。"
                        + "仿真引擎将在数秒内自动开始派单，多个城市会同时起飞。",
                modelRepository.count(), droneRepository.count(),
                zoneRepository.count(), stationRepository.count(), orderService.findDispatchable().size());
    }

    /**
     * 三种机型：轻量高频、中型长航程、重载慢速。
     *
     * <p><b>参数必须自洽</b>：{@code batteryCapacityWh ≈ energyPerKmWh × cruiseSpeed × maxFlightMin / 60}。
     * 例如 SKY-X4：42 Wh/km × (15 m/s × 35 min = 31.5km) ≈ 1323 → 取 1300 Wh。
     * 如果电池容量装不下标称续航，仿真的续航校验就会和"标称参数"互相打架，
     * 运营看到的机型能力表也就不可信了——这类"数据自洽性"问题在真实项目里非常常见。
     */
    private void seedModels() {
        modelRepository.saveAll(List.of(
                DroneModel.builder().code("SKY-X4").name("天巡 X4 轻量配送机")
                        .maxPayloadKg(new BigDecimal("5.00")).cruiseSpeedMps(new BigDecimal("15.00"))
                        .maxFlightMin(35).batteryCapacityWh(new BigDecimal("1300.00"))
                        .energyPerKmWh(new BigDecimal("42.000")).hoverPowerW(new BigDecimal("900.00"))
                        .maxAltitudeM(120).enabled(true).remark("小件高频，主力机型").build(),
                DroneModel.builder().code("SKY-M8").name("天巡 M8 中型配送机")
                        .maxPayloadKg(new BigDecimal("15.00")).cruiseSpeedMps(new BigDecimal("18.00"))
                        .maxFlightMin(45).batteryCapacityWh(new BigDecimal("2800.00"))
                        .energyPerKmWh(new BigDecimal("58.000")).hoverPowerW(new BigDecimal("1600.00"))
                        .maxAltitudeM(150).enabled(true).remark("中件长航程").build(),
                DroneModel.builder().code("SKY-H2").name("天巡 H2 重载配送机")
                        .maxPayloadKg(new BigDecimal("30.00")).cruiseSpeedMps(new BigDecimal("12.00"))
                        .maxFlightMin(30).batteryCapacityWh(new BigDecimal("2000.00"))
                        .energyPerKmWh(new BigDecimal("95.000")).hoverPowerW(new BigDecimal("2800.00"))
                        .maxAltitudeM(180).enabled(true).remark("重载，速度慢").build()));
    }

    /**
     * 无人机分布在<b>四个城市集群</b>的多个起降点，让演示覆盖全国而不是只有上海。
     *
     * <p>编号按城市分段，便于一眼看出归属：0xx = 上海、1xx = 北京、2xx = 深圳、3xx = 成都。
     * 每个城市留出足够空闲运力：演示时最常见的困惑是"点了派单却没反应"，
     * 真实原因往往只是"这个站点附近的飞机恰好全在飞"。
     */
    private void seedDrones() {
        DroneModel x4 = modelRepository.findByCode("SKY-X4").orElseThrow();
        DroneModel m8 = modelRepository.findByCode("SKY-M8").orElseThrow();
        DroneModel h2 = modelRepository.findByCode("SKY-H2").orElseThrow();

        // ---- 上海：虹桥 / 人民广场 / 浦东 三个起降点，11 架 ----
        // 虹桥配送站（距虹桥禁飞区圆心约 3.6km > 半径 2.5km）
        droneRepository.saveAll(List.of(
                buildDrone("UAV-001", x4, 100.0, 31.1900, 121.3000),
                buildDrone("UAV-002", x4, 96.5, 31.1900, 121.3000),
                buildDrone("UAV-003", x4, 88.0, 31.1900, 121.3000),
                // 人民广场站：主力站点，放 6 架中型机
                buildDrone("UAV-004", m8, 100.0, 31.2304, 121.4737),
                buildDrone("UAV-005", m8, 74.2, 31.2304, 121.4737),
                buildDrone("UAV-006", m8, 55.0, 31.2304, 121.4737),
                buildDrone("UAV-009", m8, 100.0, 31.2304, 121.4737),
                buildDrone("UAV-010", m8, 92.0, 31.2304, 121.4737),
                buildDrone("UAV-011", m8, 85.0, 31.2304, 121.4737),
                // 浦东站（距浦东禁飞区圆心约 3.9km > 半径 3km）
                buildDrone("UAV-007", h2, 100.0, 31.1700, 121.7800),
                buildDrone("UAV-008", h2, 62.3, 31.1700, 121.7800)));

        // ---- 北京：国贸 / 中关村 两个起降点 ----
        droneRepository.saveAll(List.of(
                buildDrone("UAV-101", x4, 100.0, 39.9087, 116.4074),
                buildDrone("UAV-102", x4, 92.0, 39.9087, 116.4074),
                buildDrone("UAV-103", m8, 97.0, 39.9087, 116.4074),
                buildDrone("UAV-104", m8, 84.0, 39.9836, 116.3164),
                buildDrone("UAV-105", h2, 91.0, 39.9836, 116.3164)));

        // ---- 深圳：福田 / 南山 两个起降点 ----
        droneRepository.saveAll(List.of(
                buildDrone("UAV-201", x4, 99.0, 22.5410, 114.0579),
                buildDrone("UAV-202", x4, 90.5, 22.5410, 114.0579),
                buildDrone("UAV-203", m8, 96.0, 22.5410, 114.0579),
                buildDrone("UAV-204", m8, 82.5, 22.5333, 113.9300),
                buildDrone("UAV-205", h2, 88.0, 22.5333, 113.9300)));

        // ---- 成都：天府广场 / 高新区 两个起降点 ----
        droneRepository.saveAll(List.of(
                buildDrone("UAV-301", x4, 100.0, 30.6570, 104.0657),
                buildDrone("UAV-302", x4, 89.0, 30.6570, 104.0657),
                buildDrone("UAV-303", m8, 95.0, 30.6570, 104.0657),
                buildDrone("UAV-304", m8, 81.0, 30.5736, 104.0668),
                buildDrone("UAV-305", h2, 86.5, 30.5736, 104.0668)));
    }

    private Drone buildDrone(String code, DroneModel model, double battery, double lat, double lon) {
        return Drone.builder()
                .code(code).model(model).status(DroneStatus.IDLE)
                .batteryPct(BigDecimal.valueOf(battery))
                .currentLat(lat).currentLon(lon)
                .altitudeM(BigDecimal.ZERO).speedMps(BigDecimal.ZERO).headingDeg(BigDecimal.ZERO)
                .homeLat(lat).homeLon(lon)
                .totalFlightMin(BigDecimal.ZERO).totalDistanceKm(BigDecimal.ZERO).totalTasks(0)
                .version(0L)
                .build();
    }

    /** 各城市空域：机场净空禁飞区 + 一条推荐走廊，供全国演示。 */
    private void seedZones() {
        zoneRepository.saveAll(List.of(
                // ---- 上海 ----
                AirspaceZone.builder().code("Z-NF-001").name("虹桥机场净空禁飞区")
                        .zoneType(ZoneType.NO_FLY).shapeType(ZoneShape.CIRCLE)
                        .centerLat(31.1979).centerLon(121.3363).radiusM(new BigDecimal("2500.00"))
                        .minAltitudeM(BigDecimal.ZERO).maxAltitudeM(new BigDecimal("300"))
                        .severity(ConflictSeverity.CRITICAL).enabled(true)
                        .remark("半径 2.5km 内禁止无人机活动").build(),
                AirspaceZone.builder().code("Z-NF-002").name("浦东机场净空禁飞区")
                        .zoneType(ZoneType.NO_FLY).shapeType(ZoneShape.CIRCLE)
                        .centerLat(31.1443).centerLon(121.8083).radiusM(new BigDecimal("3000.00"))
                        .minAltitudeM(BigDecimal.ZERO).maxAltitudeM(new BigDecimal("300"))
                        .severity(ConflictSeverity.CRITICAL).enabled(true)
                        .remark("半径 3km 内禁止无人机活动").build(),
                AirspaceZone.builder().code("Z-RS-001").name("陆家嘴限飞区")
                        .zoneType(ZoneType.RESTRICTED).shapeType(ZoneShape.POLYGON)
                        .polygonWkt("POLYGON((121.4900 31.2300, 121.5150 31.2300, 121.5150 31.2500, 121.4900 31.2500, 121.4900 31.2300))")
                        .minAltitudeM(BigDecimal.ZERO).maxAltitudeM(new BigDecimal("60"))
                        .severity(ConflictSeverity.HIGH).enabled(true)
                        .remark("限高 60m，需报备").build(),
                AirspaceZone.builder().code("Z-CR-001").name("中环低空物流走廊")
                        .zoneType(ZoneType.CORRIDOR).shapeType(ZoneShape.POLYGON)
                        .polygonWkt("POLYGON((121.3800 31.2100, 121.4600 31.2100, 121.4600 31.2250, 121.3800 31.2250, 121.3800 31.2100))")
                        .minAltitudeM(new BigDecimal("60")).maxAltitudeM(new BigDecimal("120"))
                        .severity(ConflictSeverity.LOW).enabled(true)
                        .remark("推荐航路，限高 120m").build(),

                // ---- 北京 ----
                AirspaceZone.builder().code("Z-NF-101").name("首都机场净空禁飞区")
                        .zoneType(ZoneType.NO_FLY).shapeType(ZoneShape.CIRCLE)
                        .centerLat(40.0799).centerLon(116.6031).radiusM(new BigDecimal("3500.00"))
                        .minAltitudeM(BigDecimal.ZERO).maxAltitudeM(new BigDecimal("300"))
                        .severity(ConflictSeverity.CRITICAL).enabled(true)
                        .remark("半径 3.5km 内禁止无人机活动").build(),
                AirspaceZone.builder().code("Z-CR-101").name("长安街低空物流走廊")
                        .zoneType(ZoneType.CORRIDOR).shapeType(ZoneShape.POLYGON)
                        .polygonWkt("POLYGON((116.3500 39.9050, 116.4600 39.9050, 116.4600 39.9130, 116.3500 39.9130, 116.3500 39.9050))")
                        .minAltitudeM(new BigDecimal("60")).maxAltitudeM(new BigDecimal("120"))
                        .severity(ConflictSeverity.LOW).enabled(true)
                        .remark("推荐航路，限高 120m").build(),

                // ---- 深圳 ----
                AirspaceZone.builder().code("Z-NF-201").name("宝安机场净空禁飞区")
                        .zoneType(ZoneType.NO_FLY).shapeType(ZoneShape.CIRCLE)
                        .centerLat(22.6393).centerLon(113.8107).radiusM(new BigDecimal("3000.00"))
                        .minAltitudeM(BigDecimal.ZERO).maxAltitudeM(new BigDecimal("300"))
                        .severity(ConflictSeverity.CRITICAL).enabled(true)
                        .remark("半径 3km 内禁止无人机活动").build(),
                AirspaceZone.builder().code("Z-CR-201").name("深南大道物流走廊")
                        .zoneType(ZoneType.CORRIDOR).shapeType(ZoneShape.POLYGON)
                        .polygonWkt("POLYGON((113.9300 22.5380, 114.0600 22.5380, 114.0600 22.5440, 113.9300 22.5440, 113.9300 22.5380))")
                        .minAltitudeM(new BigDecimal("60")).maxAltitudeM(new BigDecimal("120"))
                        .severity(ConflictSeverity.LOW).enabled(true)
                        .remark("推荐航路，限高 120m").build(),

                // ---- 成都 ----
                AirspaceZone.builder().code("Z-NF-301").name("双流机场净空禁飞区")
                        .zoneType(ZoneType.NO_FLY).shapeType(ZoneShape.CIRCLE)
                        .centerLat(30.5785).centerLon(103.9471).radiusM(new BigDecimal("3000.00"))
                        .minAltitudeM(BigDecimal.ZERO).maxAltitudeM(new BigDecimal("300"))
                        .severity(ConflictSeverity.CRITICAL).enabled(true)
                        .remark("半径 3km 内禁止无人机活动").build(),
                AirspaceZone.builder().code("Z-CR-301").name("天府大道物流走廊")
                        .zoneType(ZoneType.CORRIDOR).shapeType(ZoneShape.POLYGON)
                        .polygonWkt("POLYGON((104.0500 30.5600, 104.0800 30.5600, 104.0800 30.5680, 104.0500 30.5680, 104.0500 30.5600))")
                        .minAltitudeM(new BigDecimal("60")).maxAltitudeM(new BigDecimal("120"))
                        .severity(ConflictSeverity.LOW).enabled(true)
                        .remark("推荐航路，限高 120m").build(),

                // ---- 建筑障碍（高楼）：让 A* 不能简单走两点直线，必须绕开楼群 ----
                // 高度用 maxAltitudeM 表达（楼高），无人机可以飞过楼顶，但在楼高以下必须绕行。
                // 位置都刻意选在各城起降点与常见目的地之间，但不压住任何订单端点。
                AirspaceZone.builder().code("Z-BLD-001").name("徐家汇超高层建筑群")
                        .zoneType(ZoneType.BUILDING).shapeType(ZoneShape.POLYGON)
                        .polygonWkt("POLYGON((121.4330 31.1910, 121.4430 31.1910, 121.4430 31.1990, 121.4330 31.1990, 121.4330 31.1910))")
                        .minAltitudeM(BigDecimal.ZERO).maxAltitudeM(new BigDecimal("180"))
                        .severity(ConflictSeverity.HIGH).enabled(true)
                        .remark("180m 以下禁止穿越，需从楼顶以上或两侧绕行").build(),
                AirspaceZone.builder().code("Z-BLD-101").name("国贸 CBD 楼群")
                        .zoneType(ZoneType.BUILDING).shapeType(ZoneShape.POLYGON)
                        .polygonWkt("POLYGON((116.4370 39.9060, 116.4490 39.9060, 116.4490 39.9160, 116.4370 39.9160, 116.4370 39.9060))")
                        .minAltitudeM(BigDecimal.ZERO).maxAltitudeM(new BigDecimal("200"))
                        .severity(ConflictSeverity.HIGH).enabled(true)
                        .remark("200m 以下禁止穿越，国贸高楼群").build(),
                AirspaceZone.builder().code("Z-BLD-201").name("平安金融中心及周边楼群")
                        .zoneType(ZoneType.BUILDING).shapeType(ZoneShape.POLYGON)
                        .polygonWkt("POLYGON((114.0440 22.5330, 114.0560 22.5330, 114.0560 22.5400, 114.0440 22.5400, 114.0440 22.5330))")
                        .minAltitudeM(BigDecimal.ZERO).maxAltitudeM(new BigDecimal("200"))
                        .severity(ConflictSeverity.HIGH).enabled(true)
                        .remark("200m 以下禁止穿越，福田 CBD 楼群").build(),
                AirspaceZone.builder().code("Z-BLD-301").name("高新区超高层楼群")
                        .zoneType(ZoneType.BUILDING).shapeType(ZoneShape.POLYGON)
                        .polygonWkt("POLYGON((104.0800 30.5860, 104.0900 30.5860, 104.0900 30.5940, 104.0800 30.5940, 104.0800 30.5860))")
                        .minAltitudeM(BigDecimal.ZERO).maxAltitudeM(new BigDecimal("160"))
                        .severity(ConflictSeverity.HIGH).enabled(true)
                        .remark("160m 以下禁止穿越，高新楼群").build(),

                // ---- 地形障碍（山体）：底部贴地、顶部为山高，低空必须绕行或爬升到山顶以上 ----
                AirspaceZone.builder().code("Z-TRN-001").name("佘山山体")
                        .zoneType(ZoneType.TERRAIN).shapeType(ZoneShape.CIRCLE)
                        .centerLat(31.0960).centerLon(121.1900).radiusM(new BigDecimal("1500"))
                        .minAltitudeM(BigDecimal.ZERO).maxAltitudeM(new BigDecimal("60"))
                        .severity(ConflictSeverity.HIGH).enabled(true)
                        .remark("山体顶高 60m，以下不可穿越").build(),
                AirspaceZone.builder().code("Z-TRN-101").name("西山山体")
                        .zoneType(ZoneType.TERRAIN).shapeType(ZoneShape.CIRCLE)
                        .centerLat(39.9900).centerLon(116.1900).radiusM(new BigDecimal("1800"))
                        .minAltitudeM(BigDecimal.ZERO).maxAltitudeM(new BigDecimal("80"))
                        .severity(ConflictSeverity.HIGH).enabled(true)
                        .remark("山体顶高 80m，以下不可穿越").build(),
                AirspaceZone.builder().code("Z-TRN-201").name("梧桐山山体")
                        .zoneType(ZoneType.TERRAIN).shapeType(ZoneShape.CIRCLE)
                        .centerLat(22.5800).centerLon(114.2100).radiusM(new BigDecimal("2000"))
                        .minAltitudeM(BigDecimal.ZERO).maxAltitudeM(new BigDecimal("90"))
                        .severity(ConflictSeverity.HIGH).enabled(true)
                        .remark("山体顶高 90m，以下不可穿越").build(),
                AirspaceZone.builder().code("Z-TRN-301").name("龙泉山山体")
                        .zoneType(ZoneType.TERRAIN).shapeType(ZoneShape.CIRCLE)
                        .centerLat(30.5500).centerLon(104.2800).radiusM(new BigDecimal("1800"))
                        .minAltitudeM(BigDecimal.ZERO).maxAltitudeM(new BigDecimal("70"))
                        .severity(ConflictSeverity.HIGH).enabled(true)
                        .remark("山体顶高 70m，以下不可穿越").build(),

                // ---- 临时管制：带仿真时间窗，到点自动生效/解除 ----
                AirspaceZone.builder().code("Z-TMP-001").name("虹桥临时管制区")
                        .zoneType(ZoneType.RESTRICTED).shapeType(ZoneShape.CIRCLE)
                        .centerLat(31.2050).centerLon(121.3350).radiusM(new BigDecimal("900"))
                        .minAltitudeM(BigDecimal.ZERO).maxAltitudeM(new BigDecimal("120"))
                        .severity(ConflictSeverity.HIGH).enabled(true)
                        .activeFromSimSec(1800L).activeToSimSec(2700L)
                        .remark("仿真 1800~2700s 临时管制（时间窗）").build()));
    }

    /**
     * 充电站：全国省会 + 四个演示城市的加密站点。
     *
     * <p><b>为什么是"全国通用"而不是只给四个城市布点</b>：补能网络是公共资源，
     * 任何一架无人机飞到哪里都能落站，不该按城市/运营商切分。演示里只布四城的话，
     * 地图上就只有四个城市的 ⚡ 标记，用户看到的是"补能点很少的地方性网络"，
     * 而真实低空物流拼的正是<b>全国补能覆盖</b> —— 覆盖到哪，运力半径就到哪。
     *
     * <p>所以这里按<b>省会城市清单</b>批量生成站点（每个省会 1 座），
     * 再在四个演示城市加密到 3 座（长航路走廊上各一座，保证演示订单补得到）。
     */
    private void seedStations() {
        List<ChargingStation> stations = new ArrayList<>(CAPITAL_STATIONS.stream()
                .map(city -> ChargingStation.builder()
                        .code("CS-" + city[0] + "-01")
                        .name(city[2] + "补能站")
                        .city(city[2])
                        .lat(Double.parseDouble(city[1]))
                        .lon(Double.parseDouble(city[3]))
                        .chargeSlots(2)
                        .enabled(true)
                        .remark("省会通用补能点，2 桩")
                        .build())
                .toList());

        // 演示城市加密：长航路走廊上各补 2 座，保证"飞不完就近落站"真的落得下
        stations.addAll(List.of(
                ChargingStation.builder().code("CS-SH-02").name("虹桥枢纽补能站").city("上海")
                        .lat(31.1950).lon(121.3400).chargeSlots(4).enabled(true)
                        .remark("虹桥方向长单的中继点，4 桩").build(),
                ChargingStation.builder().code("CS-SH-03").name("张江补能站").city("上海")
                        .lat(31.2050).lon(121.5900).chargeSlots(3).enabled(true)
                        .remark("浦东方向补能点，3 桩").build(),
                ChargingStation.builder().code("CS-BJ-02").name("五棵松补能站").city("北京")
                        .lat(39.9080).lon(116.2800).chargeSlots(2).enabled(true).remark("西线补能点，2 桩").build(),
                ChargingStation.builder().code("CS-SZ-02").name("南山补能站").city("深圳")
                        .lat(22.5300).lon(113.9300).chargeSlots(2).enabled(true).remark("西线补能点，2 桩").build(),
                ChargingStation.builder().code("CS-CD-02").name("高新区补能站").city("成都")
                        .lat(30.5600).lon(104.0680).chargeSlots(2).enabled(true).remark("南线补能点，2 桩").build()));

        stationRepository.saveAll(stations);
    }

    /**
     * 省会城市清单：{代码后缀, 纬度, 城市名, 经度}。
     *
     * <p>覆盖全部省/自治区/直辖市 + 港澳台，这样"全国补能网络"在地图上是完整的 ——
     * 用户点名要的福州（福建）、台北（台湾）也在其中。
     */
    private static final List<String[]> CAPITAL_STATIONS = List.of(
            new String[] { "BJ", "39.9042", "北京", "116.4074" },
            new String[] { "TJ", "39.3434", "天津", "117.3616" },
            new String[] { "SJZ", "38.0428", "石家庄", "114.5149" },
            new String[] { "TY", "37.8706", "太原", "112.5489" },
            new String[] { "HHHT", "40.8426", "呼和浩特", "111.7492" },
            new String[] { "SY", "41.8057", "沈阳", "123.4315" },
            new String[] { "CC", "43.8171", "长春", "125.3235" },
            new String[] { "HRB", "45.8038", "哈尔滨", "126.5349" },
            new String[] { "SH", "31.2304", "上海", "121.4737" },
            new String[] { "NJ", "32.0603", "南京", "118.7969" },
            new String[] { "HZ", "30.2741", "杭州", "120.1551" },
            new String[] { "HF", "31.8206", "合肥", "117.2272" },
            new String[] { "FZ", "26.0745", "福州", "119.2965" },
            new String[] { "NC", "28.6820", "南昌", "115.8579" },
            new String[] { "JN", "36.6512", "济南", "117.1201" },
            new String[] { "ZZ", "34.7466", "郑州", "113.6254" },
            new String[] { "WH", "30.5928", "武汉", "114.3055" },
            new String[] { "CS", "28.2282", "长沙", "112.9388" },
            new String[] { "GZ", "23.1291", "广州", "113.2644" },
            new String[] { "NN", "22.8170", "南宁", "108.3665" },
            new String[] { "HAIKOU", "20.0442", "海口", "110.1999" },
            new String[] { "CQ", "29.5630", "重庆", "106.5516" },
            new String[] { "CD", "30.5728", "成都", "104.0668" },
            new String[] { "GY", "26.6470", "贵阳", "106.6302" },
            new String[] { "KM", "24.8801", "昆明", "102.8329" },
            new String[] { "LS", "29.6520", "拉萨", "91.1721" },
            new String[] { "XA", "34.3416", "西安", "108.9398" },
            new String[] { "LZ", "36.0611", "兰州", "103.8343" },
            new String[] { "XN", "36.6171", "西宁", "101.7782" },
            new String[] { "YC", "38.4872", "银川", "106.2309" },
            new String[] { "WLMQ", "43.8256", "乌鲁木齐", "87.6168" },
            new String[] { "TPE", "25.0330", "台北", "121.5654" },
            new String[] { "HKG", "22.3193", "香港", "114.1694" },
            new String[] { "MO", "22.1987", "澳门", "113.5439" },
            new String[] { "SZ", "22.5431", "深圳", "114.0579" });

    /**
     * 12 张待办订单，覆盖<b>四个城市</b>，距离刻意做梯度且每单都有机型能承接：
     * 上海 6 单（2.6~2.8km 近单、4~12km 中短单）、北京 / 深圳 / 成都各 2 单。
     * 想让看板一开始就体现"全国运营"，而不是只有上海在动，所以在每个城市都放了首单，
     * 仿真启动后四个城市会同时起飞。
     */
    private void seedOrders() {
        Instant now = Instant.now();
        List<OrderDtos.CreateRequest> requests = List.of(
                // ---- 上海 ----
                order("上海市第一人民医院", "021-60000001", "急救血浆", "3.00", Priority.URGENT,
                        31.1900, 121.3000, "虹桥配送站", 31.2080, 121.3650, "北新泾医疗点",
                        now.plus(30, ChronoUnit.MINUTES), "高优先级急救"),
                order("王先生", "13800000002", "生鲜包裹", "1.80", Priority.NORMAL,
                        31.1900, 121.3000, "虹桥配送站", 31.2210, 121.4040, "中山公园",
                        now.plus(2, ChronoUnit.HOURS), null),
                order("京东仓储", "021-60000003", "电子元件", "8.50", Priority.HIGH,
                        31.2304, 121.4737, "人民广场站", 31.2050, 121.5990, "张江高科技园",
                        now.plus(90, ChronoUnit.MINUTES), "需防震"),
                order("李女士", "13800000004", "文件快递", "0.40", Priority.LOW,
                        31.2304, 121.4737, "人民广场站", 31.2230, 121.4450, "静安寺",
                        now.plus(4, ChronoUnit.HOURS), null),
                order("浦东医疗仓", "021-60000005", "检测试剂", "10.00", Priority.HIGH,
                        31.1820, 121.7650, "川沙起降点", 31.1950, 121.7550, "川沙南侧配送点",
                        now.plus(3, ChronoUnit.HOURS), "重载短驳（送货点在浦东机场禁飞圈外）"),
                order("陈先生", "13800000006", "午餐外卖", "2.10", Priority.NORMAL,
                        31.2304, 121.4737, "人民广场站", 31.2390, 121.5000, "陆家嘴环路",
                        now.plus(45, ChronoUnit.MINUTES), "穿越限飞区边缘"),

                // ---- 北京 ----
                order("北京协和医院", "010-60000101", "急救药品", "2.50", Priority.URGENT,
                        39.9087, 116.4074, "国贸起降点", 39.9337, 116.4551, "三里屯社区站",
                        now.plus(40, ChronoUnit.MINUTES), "高优先级急救"),
                order("中关村在线", "010-60000102", "电子元件", "4.20", Priority.NORMAL,
                        39.9836, 116.3164, "中关村起降点", 39.9926, 116.3376, "五道口配送点",
                        now.plus(2, ChronoUnit.HOURS), null),

                // ---- 深圳 ----
                order("深圳书城", "0755-60000201", "图书资料", "1.60", Priority.NORMAL,
                        22.5410, 114.0579, "福田起降点", 22.5470, 114.0870, "华强北配送点",
                        now.plus(2, ChronoUnit.HOURS), null),
                order("南山科技园", "0755-60000202", "检测样本", "3.30", Priority.HIGH,
                        22.5333, 113.9300, "南山起降点", 22.5100, 113.9350, "深圳湾医疗点",
                        now.plus(70, ChronoUnit.MINUTES), "样本需恒温"),

                // ---- 成都 ----
                order("春熙路商圈", "028-60000301", "生鲜果蔬", "2.20", Priority.NORMAL,
                        30.6570, 104.0657, "天府广场起降点", 30.6580, 104.0810, "春熙路配送点",
                        now.plus(2, ChronoUnit.HOURS), null),
                order("成都高新区", "028-60000302", "应急药品", "5.00", Priority.HIGH,
                        30.5736, 104.0668, "高新区起降点", 30.5600, 104.0670, "世纪城社区站",
                        now.plus(80, ChronoUnit.MINUTES), null));
        requests.forEach(orderService::create);
    }

    private OrderDtos.CreateRequest order(String customer, String phone, String cargo, String weight,
                                          Priority priority,
                                          double pickupLat, double pickupLon, String pickupAddress,
                                          double dropoffLat, double dropoffLon, String dropoffAddress,
                                          Instant expectAt, String remark) {
        return new OrderDtos.CreateRequest(
                customer, phone, cargo, new BigDecimal(weight), priority,
                new CommonDtos.GeoDto(pickupLat, pickupLon, pickupAddress),
                new CommonDtos.GeoDto(dropoffLat, dropoffLon, dropoffAddress),
                expectAt, remark);
    }
}
