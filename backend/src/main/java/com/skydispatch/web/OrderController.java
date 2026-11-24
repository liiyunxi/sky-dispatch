package com.skydispatch.web;

import com.skydispatch.common.ApiResponse;
import com.skydispatch.domain.enums.OrderStatus;
import com.skydispatch.domain.enums.Priority;
import com.skydispatch.dto.CommonDtos;
import com.skydispatch.dto.OrderDtos;
import com.skydispatch.dto.TaskDtos;
import com.skydispatch.service.DispatchService;
import com.skydispatch.service.OrderService;
import com.skydispatch.service.OrderTraceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 订单接口。
 *
 * <p>REST 设计约定（全项目一致）：
 * <ul>
 *   <li>资源用复数名词：{@code /orders}；</li>
 *   <li>子动作放在资源之后：{@code /orders/{id}/cancel}、{@code /orders/{id}/dispatch}；</li>
 *   <li>分页统一用 {@code page/size/sort} 查询参数（Spring Data 约定）；</li>
 *   <li>所有响应都包在 {@link ApiResponse} 里。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "订单管理", description = "创建、查询、取消订单，以及针对单个订单派单")
public class OrderController {

    private final OrderService orderService;
    private final DispatchService dispatchService;
    private final OrderTraceService orderTraceService;

    public OrderController(OrderService orderService, DispatchService dispatchService,
                           OrderTraceService orderTraceService) {
        this.orderService = orderService;
        this.dispatchService = dispatchService;
        this.orderTraceService = orderTraceService;
    }

    @PostMapping
    @Operation(summary = "创建订单", description = "订单号自动生成；创建后订单状态为 CREATED，等待派单")
    public ApiResponse<OrderDtos.Response> create(@Valid @RequestBody OrderDtos.CreateRequest request) {
        return ApiResponse.ok(orderService.create(request));
    }

    @GetMapping
    @Operation(summary = "分页查询订单", description = "可按状态、状态集合、优先级过滤")
    public ApiResponse<CommonDtos.PageResponse<OrderDtos.Response>> list(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) List<OrderStatus> statuses,
            @RequestParam(required = false) Priority priority,
            @PageableDefault(size = 20, sort = "id") Pageable pageable) {
        return ApiResponse.ok(orderService.list(status, statuses, priority, pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "订单详情")
    public ApiResponse<OrderDtos.Response> get(@PathVariable Long id) {
        return ApiResponse.ok(orderService.get(id));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "取消订单", description = "仅 CREATED / PLANNING / QUEUED / ASSIGNED 状态可取消")
    public ApiResponse<OrderDtos.Response> cancel(@PathVariable Long id) {
        return ApiResponse.ok(orderService.cancel(id));
    }

    @GetMapping("/{id}/trace")
    @Operation(summary = "订单链路追踪",
            description = "一次返回画全链路所需的订单快照、任务执行、事件时间线与派单决策")
    public ApiResponse<OrderDtos.TraceResponse> trace(@PathVariable Long id) {
        return ApiResponse.ok(orderTraceService.trace(id));
    }

    @PostMapping("/{id}/dispatch")
    @Operation(summary = "为单个订单派单",
            description = "strategy 可选 NEAREST_AVAILABLE / BATTERY_FIRST / LOAD_BALANCE，缺省用配置默认值")
    public ApiResponse<TaskDtos.DispatchResult> dispatch(
            @PathVariable Long id,
            @RequestParam(required = false) String strategy) {
        return ApiResponse.ok(dispatchService.dispatchOrder(id, strategy));
    }
}
