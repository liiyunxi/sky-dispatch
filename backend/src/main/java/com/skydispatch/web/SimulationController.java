package com.skydispatch.web;

import com.skydispatch.common.ApiResponse;
import com.skydispatch.common.BusinessException;
import com.skydispatch.config.SkyProperties;
import com.skydispatch.domain.entity.Drone;
import com.skydispatch.domain.enums.DroneStatus;
import com.skydispatch.dto.SimulationDtos;
import com.skydispatch.engine.FaultInjectionService;
import com.skydispatch.engine.SimulationEngine;
import com.skydispatch.engine.SimulationMetrics;
import com.skydispatch.engine.SimulationRuntimeState;
import com.skydispatch.engine.SimulationService;
import com.skydispatch.service.DroneService;
import com.skydispatch.service.ReplayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仿真控制接口。
 *
 * <p>把"跑仿真"和"派单"拆成两个独立开关，是这一版最重要的改动：
 *
 * <ul>
 *   <li><b>暂停 / 继续</b>（{@code engineActive}）：暂停后定时推进真的停下
 *       （手动「步进一拍」仍可用，方便逐拍观察状态机）。</li>
 *   <li><b>自动派单开关</b>（{@code autoDispatchEnabled}）：关掉之后订单只会静静排在那里，
 *       派给谁、什么时候派完全由人决定 —— 这样「立即批量派单」才是一个有意义的动作，
 *       而不是和后台定时任务抢活干。</li>
 * </ul>
 *
 * <p>默认仍是"开箱即用"：应用启动即自动跑、自动派单，直接打开看板就有画面。
 */
@RestController
@RequestMapping("/api/v1/simulation")
@Tag(name = "仿真控制", description = "新建/暂停/继续运行、切换自动派单、手动步进、查看运行时状态")
public class SimulationController {

    private final SimulationService simulationService;
    private final SimulationEngine simulationEngine;
    private final SimulationRuntimeState runtimeState;
    private final FaultInjectionService faultInjection;
    private final DroneService droneService;
    private final SkyProperties properties;
    private final SimulationMetrics metrics;
    private final ReplayService replayService;

    public SimulationController(SimulationService simulationService,
                                SimulationEngine simulationEngine,
                                SimulationRuntimeState runtimeState,
                                FaultInjectionService faultInjection,
                                DroneService droneService,
                                SkyProperties properties,
                                SimulationMetrics metrics,
                                ReplayService replayService) {
        this.simulationService = simulationService;
        this.simulationEngine = simulationEngine;
        this.runtimeState = runtimeState;
        this.faultInjection = faultInjection;
        this.droneService = droneService;
        this.properties = properties;
        this.metrics = metrics;
        this.replayService = replayService;
    }

    @GetMapping("/perf")
    @Operation(summary = "查询仿真单拍耗时",
            description = "返回最近 300 拍的平均/P95/最大耗时（毫秒），用于判断查询放大、锁竞争是否回归")
    public ApiResponse<SimulationMetrics.Snapshot> perf() {
        return ApiResponse.ok(metrics.snapshot());
    }

    @GetMapping("/state")
    @Operation(summary = "查询运行时状态",
            description = "返回引擎是否在推进、自动派单是否开启，以及当前运行的拍数与仿真时间")
    public ApiResponse<SimulationDtos.RuntimeState> state() {
        return ApiResponse.ok(buildState());
    }

    @PostMapping("/auto-dispatch")
    @Operation(summary = "开关自动派单",
            description = "enabled=false 后由人工通过 /dispatch/auto 或订单页派单，演示时更容易观察")
    public ApiResponse<SimulationDtos.RuntimeState> setAutoDispatch(@RequestParam boolean enabled) {
        runtimeState.setAutoDispatch(enabled);
        return ApiResponse.ok(buildState());
    }

    @PostMapping("/pause")
    @Operation(summary = "暂停推进",
            description = "定时推进停止，运行记录保留为 PAUSED；手动「步进一拍」仍然可用")
    public ApiResponse<SimulationDtos.RuntimeState> pause() {
        var run = simulationService.currentOrNull();
        if (run == null) {
            throw BusinessException.illegalState("当前没有可暂停的仿真运行");
        }
        simulationService.pauseRun(run.getId());
        return ApiResponse.ok(buildState());
    }

    @PostMapping("/resume")
    @Operation(summary = "继续推进",
            description = "从暂停处接着跑，不重置拍数与仿真时间；若没有运行则新建一个")
    public ApiResponse<SimulationDtos.RuntimeState> resume() {
        var run = simulationService.currentOrNull();
        if (run == null) {
            simulationService.start(new SimulationDtos.StartRequest("继续仿真（新建）", null, null, null));
        } else {
            simulationService.resumeRun(run.getId());
        }
        return ApiResponse.ok(buildState());
    }

    @PostMapping("/runs")
    @Operation(summary = "新建一轮仿真",
            description = "结束当前运行、计时与拍数从 0 重新开始。想延续当前演练请用 /simulation/resume")
    public ApiResponse<SimulationDtos.RunResponse> start(
            @RequestBody(required = false) SimulationDtos.StartRequest request) {
        return ApiResponse.ok(simulationService.toResponse(simulationService.start(request)));
    }

    @PostMapping("/runs/{id}/stop")
    @Operation(summary = "结束运行", description = "运行置为 STOPPED 且不可继续；之后需要新建一轮或调用 resume")
    public ApiResponse<SimulationDtos.RunResponse> stop(@PathVariable Long id) {
        return ApiResponse.ok(simulationService.toResponse(simulationService.stop(id)));
    }

    @GetMapping("/runs/{id}")
    @Operation(summary = "查询指定运行")
    public ApiResponse<SimulationDtos.RunResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(simulationService.toResponse(simulationService.require(id)));
    }

    @GetMapping("/runs/current")
    @Operation(summary = "查询当前运行（RUNNING 或 PAUSED）", description = "没有运行时 data 为 null")
    public ApiResponse<SimulationDtos.RunResponse> current() {
        var run = simulationService.currentOrNull();
        return ApiResponse.ok(run == null ? null : simulationService.toResponse(run));
    }

    @GetMapping("/runs/{id}/replay")
    @Operation(summary = "运行事件回放",
            description = "按仿真时间顺序返回该轮的事件（最多 2000 条），用于复盘派单、起飞、冲突、故障与失败")
    public ApiResponse<SimulationDtos.ReplayResponse> replay(@PathVariable Long id) {
        return ApiResponse.ok(replayService.replay(id));
    }

    @PostMapping("/tick")
    @Operation(summary = "手动步进一拍",
            description = "教学演示用：不依赖定时器手工推进一个时间片，暂停状态下同样可用")
    public ApiResponse<SimulationDtos.TickResult> tick() {
        return ApiResponse.ok(simulationEngine.runOnce());
    }

    @PostMapping("/fault")
    @Operation(summary = "人工注入飞行故障",
            description = "指定无人机与故障类型（电池压降/动力失效/通信中断/定位漂移），下一拍生效，便于演示精确复现")
    public ApiResponse<SimulationDtos.FaultResponse> injectFault(
            @Valid @RequestBody SimulationDtos.FaultRequest request) {
        Drone drone = droneService.require(request.droneId());
        boolean airborne = drone.getStatus() == DroneStatus.ASSIGNED
                || drone.getStatus() == DroneStatus.IN_FLIGHT
                || drone.getStatus() == DroneStatus.RETURNING;
        if (!airborne || drone.getCurrentTaskId() == null) {
            throw BusinessException.illegalState(
                    "无人机 " + drone.getCode() + " 当前没有执行中的飞行任务，无法注入飞行故障");
        }
        FaultInjectionService.Type type = FaultInjectionService.Type.valueOf(request.type().name());
        FaultInjectionService.ManualFault scheduled = faultInjection.injectManual(
                drone.getId(), type, request.batteryDropPct());
        String message = switch (type) {
            case BATTERY_DROP -> String.format("已排队：下一拍让 %s 电池压降 %.1f%%",
                    drone.getCode(), scheduled.batteryDropPct());
            case POWER_LOSS -> String.format("已排队：下一拍 %s 动力系统失效", drone.getCode());
            case COMM_LOSS -> String.format("已排队：下一拍 %s 通信中断", drone.getCode());
            case GPS_DRIFT -> String.format("已排队：下一拍 %s 定位漂移", drone.getCode());
            default -> "已排队";
        };
        return ApiResponse.ok(new SimulationDtos.FaultResponse(
                drone.getId(), drone.getCode(), type.name(), scheduled.batteryDropPct(), message));
    }

    private SimulationDtos.RuntimeState buildState() {
        var run = simulationService.currentOrNull();
        return new SimulationDtos.RuntimeState(
                runtimeState.isEngineActive(),
                runtimeState.isAutoDispatchEnabled(),
                properties.simulation().tickMs(),
                properties.simulation().speedFactor(),
                run == null ? null : run.getId(),
                run == null ? null : run.getStatus().name(),
                run == null ? null : run.getSimTimeS(),
                run == null ? null : run.getTickCount());
    }
}
