package com.skydispatch.flow;

import com.skydispatch.common.GeoUtils;
import com.skydispatch.domain.enums.DroneStatus;
import com.skydispatch.domain.enums.OrderStatus;
import com.skydispatch.domain.enums.Priority;
import com.skydispatch.domain.enums.TaskStatus;
import com.skydispatch.dto.CommonDtos;
import com.skydispatch.dto.OrderDtos;
import com.skydispatch.dto.algorithm.AlgorithmDtos;
import com.skydispatch.engine.SimulationEngine;
import com.skydispatch.integration.AlgorithmServiceClient;
import com.skydispatch.repository.DeliveryOrderRepository;
import com.skydispatch.repository.DispatchTaskRepository;
import com.skydispatch.repository.DroneRepository;
import com.skydispatch.repository.FlightWaypointRepository;
import com.skydispatch.repository.TelemetryRepository;
import com.skydispatch.service.DispatchService;
import com.skydispatch.service.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 端到端业务流程测试：下单 → 派单 → 路径规划 → 仿真推进 → 送达 → 归档。
 *
 * <p>它是本仓库最有价值的一个测试，因为它把整条链路串起来验证：
 * 只要业务代码哪里改坏了（状态机、电量、任务推进、遥测落库），这里会立刻红。
 *
 * <p>三个关键测试设计：
 * <ol>
 *   <li><b>用 H2 + dev profile，不依赖 PostgreSQL</b>，任何人 clone 下来都能跑；</li>
 *   <li><b>把算法服务打桩（{@code @MockitoBean}）</b>，让测试不依赖 Python 进程。
 *       算法本身的正确性由 algorithm-service 的 pytest 负责，
 *       这里只需要一个"合法的航路返回值"来驱动 Java 侧流程；</li>
 *   <li><b>关掉定时器、把自动派单周期调到极大</b>，让测试完全确定性——
 *       否则后台线程会偷偷把种子订单也派出去，抢走我们断言用的那架无人机。</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "sky.simulation.enabled=false",                 // 关掉 @Scheduled，测试自己驱动 tick
        "sky.simulation.auto-dispatch-interval-ms=999999999",  // 关掉自动扫单，保证确定性
        "sky.bootstrap.seed-demo-data=true"
})
@ActiveProfiles("dev")
class DispatchFlowIntegrationTest {

    @Autowired
    private OrderService orderService;
    @Autowired
    private DispatchService dispatchService;
    @Autowired
    private SimulationEngine simulationEngine;
    @Autowired
    private DispatchTaskRepository taskRepository;
    @Autowired
    private DeliveryOrderRepository orderRepository;
    @Autowired
    private DroneRepository droneRepository;
    @Autowired
    private FlightWaypointRepository waypointRepository;
    @Autowired
    private TelemetryRepository telemetryRepository;

    /** 算法服务替身。 */
    @MockitoBean
    private AlgorithmServiceClient algorithmClient;

    @Test
    @DisplayName("完整闭环：下单 → 派单 → 航路 → 仿真推进 → 送达 → 返航 → 归档")
    void orderToDelivery() {
        stubAlgorithmService();

        // ---------- 1) 下单 ----------
        OrderDtos.Response order = orderService.create(new OrderDtos.CreateRequest(
                "集成测试医院", "021-00000000", "测试药品", new BigDecimal("2.00"), Priority.URGENT,
                new CommonDtos.GeoDto(31.2304, 121.4737, "人民广场站"),
                new CommonDtos.GeoDto(31.2400, 121.4900, "测试目的地"),
                Instant.now().plusSeconds(3600), "integration-test"));
        assertThat(order.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.orderNo()).startsWith("SO");

        // ---------- 2) 派单 ----------
        var dispatchResult = dispatchService.dispatchOrder(order.id(), "NEAREST_AVAILABLE");
        assertThat(dispatchResult.task()).as("应当成功创建任务").isNotNull();
        assertThat(dispatchResult.strategy()).isEqualTo("NEAREST_AVAILABLE");

        Long taskId = dispatchResult.task().id();
        var task = taskRepository.findById(taskId).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.ASSIGNED);
        assertThat(task.getRouteId()).as("必须已经规划出航路").isNotNull();

        // 航路点已落库（打桩返回 2 个点）
        assertThat(waypointRepository.countByRouteId(task.getRouteId())).isEqualTo(2);

        // 决策明细留痕：能解释"为什么选它"
        var decisions = dispatchService.decisions(order.id());
        assertThat(decisions).isNotEmpty();
        assertThat(decisions).anyMatch(d -> Boolean.TRUE.equals(d.selected()));

        // 订单与无人机状态联动
        assertThat(orderRepository.findById(order.id()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.ASSIGNED);
        var assignedDrone = droneRepository.findById(task.getDroneId()).orElseThrow();
        assertThat(assignedDrone.getStatus()).isEqualTo(DroneStatus.ASSIGNED);
        assertThat(assignedDrone.getCurrentTaskId()).isEqualTo(taskId);
        BigDecimal batteryAtStart = assignedDrone.getBatteryPct();

        // ---------- 3) 推进仿真直到任务结束 ----------
        int ticks = 0;
        while (ticks++ < 500) {
            simulationEngine.runOnce();
            if (taskRepository.findById(taskId).orElseThrow().getStatus().isTerminal()) {
                break;
            }
        }
        assertThat(ticks).as("任务应在有限拍内结束，而不是无限循环").isLessThan(500);

        // ---------- 4) 校验终态 ----------
        var finished = taskRepository.findById(taskId).orElseThrow();
        assertThat(finished.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(finished.getDurationS()).as("完成时间必须被记录").isNotNull().isPositive();
        assertThat(finished.getConsumedEnergyWh()).as("能耗必须大于 0").isPositive();
        assertThat(finished.getBatteryEndPct()).isNotNull();
        assertThat(finished.getProgressPct()).isEqualByComparingTo("100.00");

        // 订单已送达
        assertThat(orderRepository.findById(order.id()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.DELIVERED);

        // 无人机已回巢待命，且统计入账
        var droneAfter = droneRepository.findById(task.getDroneId()).orElseThrow();
        assertThat(droneAfter.getStatus()).isEqualTo(DroneStatus.IDLE);
        assertThat(droneAfter.getCurrentTaskId()).isNull();
        assertThat(droneAfter.getBatteryPct()).as("飞行必然耗电").isLessThan(batteryAtStart);
        assertThat(droneAfter.getTotalTasks()).isEqualTo(1);
        assertThat(droneAfter.getTotalDistanceKm()).isPositive();
        assertThat(droneAfter.getCurrentLat()).as("应回到机巢坐标").isEqualTo(droneAfter.getHomeLat());

        // 遥测与事件都已落库（回放与报表的数据基础）
        assertThat(telemetryRepository.findByTaskIdOrderByIdAsc(taskId))
                .as("遥测轨迹不能为空").isNotEmpty();
        assertThat(telemetryRepository.findByTaskIdOrderByIdAsc(taskId).get(0).getStepIndex()).isNotNull();
    }

    @Test
    @DisplayName("载重超出全部机型能力时派单被拒绝，并给出明确原因")
    void rejectsWhenNoDroneCanCarry() {
        stubAlgorithmService();

        OrderDtos.Response heavy = orderService.create(new OrderDtos.CreateRequest(
                "重载测试", null, "超重货物", new BigDecimal("99.00"), Priority.HIGH,
                new CommonDtos.GeoDto(31.2304, 121.4737, "人民广场站"),
                new CommonDtos.GeoDto(31.2400, 121.4900, "测试目的地"),
                null, "overweight"));

        // 1004 = NO_AVAILABLE_DRONE
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> dispatchService.dispatchOrder(heavy.id(), "NEAREST_AVAILABLE")))
                .isInstanceOf(com.skydispatch.common.BusinessException.class)
                .hasMessageContaining("没有满足条件");

        // 失败也要留下决策痕迹，方便运营归因
        var decisions = dispatchService.decisions(heavy.id());
        assertThat(decisions).isNotEmpty();
        assertThat(decisions).allMatch(d -> d.rejectedReason() != null);
        assertThat(decisions.get(0).rejectedReason()).contains("载重不足");
    }

    /**
     * 打桩算法服务：返回一条"起点直连终点"的合法航路。
     * 用真实 haversine 算距离，让后续电量与时长断言有意义。
     */
    private void stubAlgorithmService() {
        when(algorithmClient.baseUrl()).thenReturn("http://stub-algorithm");
        when(algorithmClient.checkConflicts(any())).thenReturn(Optional.empty());
        when(algorithmClient.plan(any())).thenAnswer(invocation -> {
            AlgorithmDtos.PlanPathRequest request = invocation.getArgument(0);
            double distance = GeoUtils.haversineMeters(
                    request.start().lat(), request.start().lon(),
                    request.goal().lat(), request.goal().lon());
            return Optional.of(new AlgorithmDtos.PathPlanResult(
                    true, request.requestId(), "ASTAR", true, "ok", distance,
                    List.of(new AlgorithmDtos.Waypoint(0, request.start().lat(), request.start().lon(),
                                    request.altitudeM(), 0.0),
                            new AlgorithmDtos.Waypoint(1, request.goal().lat(), request.goal().lon(),
                                    request.altitudeM(), distance)),
                    42, 1.5));
        });
    }
}
