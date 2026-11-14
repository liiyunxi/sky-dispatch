package com.skydispatch.web;

import com.skydispatch.common.ApiResponse;
import com.skydispatch.domain.enums.ConflictStatus;
import com.skydispatch.dto.DashboardDtos;
import com.skydispatch.integration.AlgorithmServiceClient;
import com.skydispatch.service.ConflictService;
import com.skydispatch.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 看板接口：地图与指标卡片的数据源。
 *
 * <p>同样的数据也会通过 WebSocket 推送（{@code /ws/telemetry}）。
 * 保留 REST 版本的原因：<b>页面首次加载、断线重连、手工刷新都需要"拉"一次</b>，
 * 纯推送架构在这些场景下会很别扭。
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "实时看板", description = "核心指标、无人机快照、冲突列表、算法服务健康状态")
public class DashboardController {

    private final DashboardService dashboardService;
    private final ConflictService conflictService;
    private final AlgorithmServiceClient algorithmClient;

    public DashboardController(DashboardService dashboardService,
                               ConflictService conflictService,
                               AlgorithmServiceClient algorithmClient) {
        this.dashboardService = dashboardService;
        this.conflictService = conflictService;
        this.algorithmClient = algorithmClient;
    }

    @GetMapping("/kpi")
    @Operation(summary = "核心指标", description = "订单/任务/机队/电量/准时率/冲突数，一次返回")
    public ApiResponse<DashboardDtos.Kpi> kpi() {
        return ApiResponse.ok(dashboardService.kpi());
    }

    @GetMapping("/drones")
    @Operation(summary = "无人机实时快照", description = "地图打点用；字段与 WebSocket 的 TelemetryFrame 完全一致")
    public ApiResponse<List<DashboardDtos.DroneFrame>> drones() {
        return ApiResponse.ok(dashboardService.droneFrames());
    }

    @GetMapping("/snapshot")
    @Operation(summary = "全量快照", description = "等价于 WebSocket 连接时收到的 SNAPSHOT 消息")
    public ApiResponse<DashboardDtos.Snapshot> snapshot() {
        return ApiResponse.ok(dashboardService.buildSnapshot());
    }

    @GetMapping("/conflicts")
    @Operation(summary = "冲突列表", description = "status 缺省返回最近 50 条")
    public ApiResponse<List<DashboardDtos.ConflictView>> conflicts(
            @RequestParam(required = false) ConflictStatus status) {
        return ApiResponse.ok(conflictService.listByStatus(status));
    }

    @PostMapping("/conflicts/{id}/resolve")
    @Operation(summary = "标记冲突已处理")
    public ApiResponse<DashboardDtos.ConflictView> resolve(@PathVariable Long id,
                                                          @RequestParam(required = false) String resolution) {
        return ApiResponse.ok(conflictService.resolve(id, resolution == null ? "人工确认解除" : resolution));
    }

    @GetMapping("/algorithm-health")
    @Operation(summary = "算法服务健康状态", description = "确认 Python 侧是否在线，排查 503 时的第一步")
    public ApiResponse<Map<String, Object>> algorithmHealth() {
        boolean enabled = algorithmClient.enabled();
        // 用 HashMap 而不是 Map.of：Map.of 不允许 null 值，算法服务下线时它会直接抛 NPE
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("baseUrl", algorithmClient.baseUrl());
        body.put("enabled", enabled);
        if (enabled) {
            // 仅在启用时探活：离线降级模式下不发无意义的连接，避免接口白等一次连接失败
            var health = algorithmClient.health();
            body.put("available", health.isPresent());
            body.put("detail", health.orElse(null));
        } else {
            // 主动关闭属于预期状态（直线航路降级），不是"故障离线"
            body.put("available", false);
            body.put("detail", null);
        }
        return ApiResponse.ok(body);
    }
}
