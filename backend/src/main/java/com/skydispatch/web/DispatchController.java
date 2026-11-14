package com.skydispatch.web;

import com.skydispatch.common.ApiResponse;
import com.skydispatch.dispatch.DispatchStrategyFactory;
import com.skydispatch.domain.enums.DispatchStrategyType;
import com.skydispatch.dto.TaskDtos;
import com.skydispatch.service.DispatchBatchService;
import com.skydispatch.service.DispatchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 调度接口。
 *
 * <p>这是最能体现"策略可插拔"的入口：同一批订单，换个 {@code strategy} 参数，
 * 派单结果就不同。调用 {@code /dispatch/decisions} 可以看到每种策略的打分与淘汰原因，
 * 非常适合做课堂演示或对比实验。
 */
@RestController
@RequestMapping("/api/v1/dispatch")
@Tag(name = "调度派单", description = "单订单派单、批量派单、调度决策明细")
public class DispatchController {

    private final DispatchService dispatchService;
    private final DispatchBatchService dispatchBatchService;
    private final DispatchStrategyFactory strategyFactory;

    public DispatchController(DispatchService dispatchService,
                              DispatchBatchService dispatchBatchService,
                              DispatchStrategyFactory strategyFactory) {
        this.dispatchService = dispatchService;
        this.dispatchBatchService = dispatchBatchService;
        this.strategyFactory = strategyFactory;
    }

    @PostMapping("/orders/{orderId}")
    @Operation(summary = "为指定订单派单")
    public ApiResponse<TaskDtos.DispatchResult> dispatchOrder(
            @PathVariable Long orderId,
            @RequestParam(required = false) String strategy) {
        return ApiResponse.ok(dispatchService.dispatchOrder(orderId, strategy));
    }

    @PostMapping("/auto")
    @Operation(summary = "批量派单",
            description = "按「优先级高 → 创建时间早」扫描待派订单，最多处理 limit 单；单个失败不影响其余")
    public ApiResponse<List<TaskDtos.DispatchResult>> dispatchAuto(
            @RequestParam(required = false) String strategy,
            @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(dispatchBatchService.dispatchPending(strategy, limit));
    }

    @GetMapping("/decisions")
    @Operation(summary = "查询调度决策明细",
            description = "返回每架候选无人机的得分、排名、是否被选中，以及未入选的具体原因")
    public ApiResponse<List<TaskDtos.DecisionResponse>> decisions(@RequestParam Long orderId) {
        return ApiResponse.ok(dispatchService.decisions(orderId));
    }

    @GetMapping("/candidates")
    @Operation(summary = "搜罗可承接订单的无人机（派单前预览）",
            description = "按订单重量与往返能耗筛选，只返回载重够、电量够返回的入围无人机（按策略排序），不展示不合格候选")
    public ApiResponse<TaskDtos.CandidatePreview> candidates(
            @RequestParam Long orderId,
            @RequestParam(required = false) String strategy) {
        return ApiResponse.ok(dispatchService.previewCandidates(orderId, strategy));
    }

    @GetMapping("/strategies")
    @Operation(summary = "列出可用派单策略")
    public ApiResponse<List<DispatchStrategyType>> strategies() {
        return ApiResponse.ok(strategyFactory.availableTypes());
    }
}
