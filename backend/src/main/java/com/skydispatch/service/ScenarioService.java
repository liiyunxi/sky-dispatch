package com.skydispatch.service;

import com.skydispatch.common.BusinessException;
import com.skydispatch.dto.CommonDtos;
import com.skydispatch.domain.entity.Drone;
import com.skydispatch.domain.entity.SimulationRun;
import com.skydispatch.domain.enums.ConflictSeverity;
import com.skydispatch.domain.enums.DroneStatus;
import com.skydispatch.domain.enums.Priority;
import com.skydispatch.domain.enums.ZoneShape;
import com.skydispatch.domain.enums.ZoneType;
import com.skydispatch.dto.AirspaceDtos;
import com.skydispatch.dto.OrderDtos;
import com.skydispatch.dto.ScenarioDtos;
import com.skydispatch.engine.FaultInjectionService;
import com.skydispatch.engine.SimulationService;
import com.skydispatch.repository.DroneRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 演示场景编排。
 *
 * <p>原来演示要手工做很多步：先建订单、再等派单、再挑飞机注入故障、再去空域页画禁飞区。
 * 这里把常见演示意图固化成四个"剧本"，一键触发并把影响面写清楚：
 *
 * <ul>
 *   <li>高峰订单：一次灌入 10 个跨城订单，观察排队、批量派单与运力挤压；</li>
 *   <li>故障风暴：对 3 架执行中的无人机分别注入电池压降/通信中断/定位漂移；</li>
 *   <li>临时禁飞：创建 3 分钟（仿真时间）的临时禁飞区，到点自动失效；</li>
 *   <li>补能压力：让 3 架执行中的无人机电量骤降，触发改航补能与排队。</li>
 * </ul>
 *
 * <p>剧本本身不"作弊"：订单走正常创建、故障走正常注入、禁飞区走正常空域台账，
 * 因此事件流、订单链路、基础设施页看到的都是真实发生的数据。
 */
@Service
public class ScenarioService {

    private static final Logger log = LoggerFactory.getLogger(ScenarioService.class);

    private final OrderService orderService;
    private final AirspaceService airspaceService;
    private final DroneRepository droneRepository;
    private final FaultInjectionService faultInjection;
    private final SimulationService simulationService;
    private final EventService eventService;

    public ScenarioService(OrderService orderService,
                           AirspaceService airspaceService,
                           DroneRepository droneRepository,
                           FaultInjectionService faultInjection,
                           SimulationService simulationService,
                           EventService eventService) {
        this.orderService = orderService;
        this.airspaceService = airspaceService;
        this.droneRepository = droneRepository;
        this.faultInjection = faultInjection;
        this.simulationService = simulationService;
        this.eventService = eventService;
    }

    /** 剧本目录。 */
    public List<ScenarioDtos.Info> catalog() {
        return List.of(
                new ScenarioDtos.Info(ScenarioDtos.Key.PEAK_DEMAND, "高峰订单",
                        "一次灌入 10 个跨城订单（上海/北京/深圳/成都），覆盖普通、加急与生鲜优先级。",
                        "创建 10 个订单，自动派单开启时会被立即扫单", "会占用大量运力，可能让后续订单排队"),
                new ScenarioDtos.Info(ScenarioDtos.Key.FAULT_STORM, "故障风暴",
                        "对 3 架执行中的无人机分别注入电池压降、通信中断与定位漂移。",
                        "最多影响 3 架在飞无人机", "会触发返航/补能/任务中止，适合演示异常处置"),
                new ScenarioDtos.Info(ScenarioDtos.Key.TEMP_NO_FLY, "临时禁飞",
                        "在市中心创建半径 1.5km、0~120m 的临时禁飞区，持续 180 仿真秒。",
                        "新增 1 个临时禁飞区", "到点自动失效；期间新规划会绕行"),
                new ScenarioDtos.Info(ScenarioDtos.Key.CHARGING_SURGE, "补能压力",
                        "让 3 架执行中的无人机电量骤降 35%，触发改航补能与充电站排队。",
                        "最多影响 3 架在飞无人机", "会占用充电桩位，适合演示基础设施页的排队与等待")
        );
    }

    /**
     * 触发剧本。
     *
     * <p>没有当前运行直接拒绝：剧本要在一条真实的时间轴上发生，
     * 否则临时禁飞区的生效窗口、事件回放都没有归属。
     */
    public ScenarioDtos.Result trigger(ScenarioDtos.Key key) {
        SimulationRun run = simulationService.currentOrNull();
        if (run == null) {
            throw BusinessException.illegalState("请先新建或继续一轮仿真运行，再触发演示场景");
        }
        double simTime = run.getSimTimeS() == null ? 0.0 : run.getSimTimeS().doubleValue();
        String title = catalog().stream()
                .filter(info -> info.key() == key)
                .map(ScenarioDtos.Info::title)
                .findFirst()
                .orElse(key.name());

        List<String> details;
        String message;
        switch (key) {
            case PEAK_DEMAND -> {
                details = createPeakOrders();
                message = details.isEmpty() ? "订单创建失败" : "高峰订单已灌入，观察看板排队与派单";
            }
            case FAULT_STORM -> {
                details = injectFaultStorm();
                message = details.isEmpty()
                        ? "当前没有执行中的无人机；请先派单让飞机起飞后再触发"
                        : "故障风暴已注入，下一拍生效";
            }
            case TEMP_NO_FLY -> {
                details = createTemporaryNoFly(simTime);
                message = "临时禁飞区已生效，180 仿真秒后自动失效";
            }
            case CHARGING_SURGE -> {
                details = injectChargingSurge();
                message = details.isEmpty()
                        ? "当前没有执行中的无人机；请先派单让飞机起飞后再触发"
                        : "补能压力已注入，观察改航补能与站点排队";
            }
            default -> throw BusinessException.invalidParam("未知演示场景: " + key);
        }

        eventService.simulation("SCENARIO_" + key.name(),
                "触发演示场景「" + title + "」：" + message, run.getId(),
                BigDecimal.valueOf(simTime));
        log.info("演示场景触发: key={}, runId={}, simTime={}, details={}", key, run.getId(), simTime, details);
        return new ScenarioDtos.Result(key, title, message, details, simTime, Instant.now());
    }

    // ------------------------------------------------------------------
    // 剧本实现
    // ------------------------------------------------------------------

    private List<String> createPeakOrders() {
        List<DemoOrder> templates = List.of(
                new DemoOrder("演示客户 · 张", "应急药品", 31.2304, 121.4737, "人民广场",
                        31.2397, 121.4998, "陆家嘴", Priority.URGENT, 2.5),
                new DemoOrder("演示客户 · 李", "生鲜餐食", 31.1979, 121.3260, "虹桥枢纽",
                        31.2230, 121.4450, "静安寺", Priority.HIGH, 3.0),
                new DemoOrder("演示客户 · 王", "合同文件", 31.1880, 121.4370, "徐家汇",
                        31.1850, 121.4900, "世博园", Priority.NORMAL, 0.8),
                new DemoOrder("演示客户 · 赵", "精密备件", 39.9087, 116.4601, "国贸",
                        39.9962, 116.4810, "望京", Priority.HIGH, 4.0),
                new DemoOrder("演示客户 · 钱", "医疗样本", 39.9830, 116.3160, "中关村",
                        39.9070, 116.3740, "西单", Priority.URGENT, 1.2),
                new DemoOrder("演示客户 · 孙", "电子元件", 22.5400, 114.0550, "福田 CBD",
                        22.5400, 113.9500, "南山科技园", Priority.NORMAL, 5.0),
                new DemoOrder("演示客户 · 周", "冷链药品", 22.6100, 114.0290, "深圳北",
                        22.5550, 113.8830, "宝安中心", Priority.URGENT, 3.5),
                new DemoOrder("演示客户 · 吴", "火锅食材", 30.6570, 104.0660, "天府广场",
                        30.6590, 104.0810, "春熙路", Priority.HIGH, 4.5),
                new DemoOrder("演示客户 · 郑", "维修工具", 30.5700, 104.0660, "高新区",
                        30.5740, 103.9230, "双流", Priority.NORMAL, 6.0),
                new DemoOrder("演示客户 · 冯", "文创样品", 30.7330, 104.1450, "熊猫基地",
                        30.6300, 104.1410, "成都东站", Priority.LOW, 1.5)
        );

        List<String> codes = new ArrayList<>();
        int index = 0;
        for (DemoOrder template : templates) {
            OrderDtos.CreateRequest request = new OrderDtos.CreateRequest(
                    template.customer(),
                    "1380000" + String.format("%04d", index + 1),
                    template.cargo(),
                    BigDecimal.valueOf(template.weightKg()),
                    template.priority(),
                    new CommonDtos.GeoDto(template.pickupLat(), template.pickupLon(), template.pickupAddress()),
                    new CommonDtos.GeoDto(template.dropoffLat(), template.dropoffLon(), template.dropoffAddress()),
                    Instant.now().plusSeconds(1800 + index * 90L),
                    "演示场景：高峰订单"
            );
            codes.add(orderService.create(request).orderNo());
            index++;
        }
        return List.of("已创建 " + codes.size() + " 个订单",
                String.join("、", codes));
    }

    private List<String> injectFaultStorm() {
        List<Drone> active = activeDrones();
        if (active.isEmpty()) {
            return List.of();
        }
        FaultInjectionService.Type[] types = {
                FaultInjectionService.Type.BATTERY_DROP,
                FaultInjectionService.Type.COMM_LOSS,
                FaultInjectionService.Type.GPS_DRIFT
        };
        List<String> details = new ArrayList<>();
        int count = Math.min(active.size(), types.length);
        for (int i = 0; i < count; i++) {
            Drone drone = active.get(i);
            FaultInjectionService.Type type = types[i];
            faultInjection.injectManual(drone.getId(), type, type == FaultInjectionService.Type.BATTERY_DROP ? 25.0 : null);
            details.add(drone.getCode() + " → " + type);
        }
        return details;
    }

    private List<String> injectChargingSurge() {
        List<Drone> active = activeDrones();
        if (active.isEmpty()) {
            return List.of();
        }
        List<String> details = new ArrayList<>();
        int count = Math.min(active.size(), 3);
        for (int i = 0; i < count; i++) {
            Drone drone = active.get(i);
            faultInjection.injectManual(drone.getId(), FaultInjectionService.Type.BATTERY_DROP, 35.0);
            details.add(drone.getCode() + " 电量骤降 35%");
        }
        return details;
    }

    private List<String> createTemporaryNoFly(double simTime) {
        long from = Math.round(simTime);
        long to = from + 180;
        AirspaceDtos.Response zone = airspaceService.create(new AirspaceDtos.ZoneRequest(
                null,
                "临时禁飞区 · 演示",
                ZoneType.NO_FLY,
                ZoneShape.CIRCLE,
                new CommonDtos.GeoDto(31.2350, 121.4800, "演示临时禁飞区"),
                BigDecimal.valueOf(1500),
                null,
                BigDecimal.ZERO,
                BigDecimal.valueOf(120),
                ConflictSeverity.CRITICAL,
                true,
                null,
                null,
                from,
                to,
                "演示场景：临时管制 180 仿真秒，到点自动失效"
        ));
        return List.of(
                "已创建 " + zone.code() + "（半径 1500m，高度 0~120m）",
                "生效窗口：T+" + from + "s ~ T+" + to + "s",
                "到点后自动失效，不参与后续路径规划"
        );
    }

    /** 当前在执行中的无人机（有任务、且处于已派/飞行/返航）。 */
    private List<Drone> activeDrones() {
        return droneRepository.findAll().stream()
                .filter(drone -> drone.getCurrentTaskId() != null)
                .filter(drone -> drone.getStatus() == DroneStatus.ASSIGNED
                        || drone.getStatus() == DroneStatus.IN_FLIGHT
                        || drone.getStatus() == DroneStatus.RETURNING)
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .limit(3)
                .toList();
    }

    private record DemoOrder(
            String customer,
            String cargo,
            double pickupLat,
            double pickupLon,
            String pickupAddress,
            double dropoffLat,
            double dropoffLon,
            String dropoffAddress,
            Priority priority,
            double weightKg
    ) {
    }
}
