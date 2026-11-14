package com.skydispatch.web;

import com.skydispatch.common.ApiResponse;
import com.skydispatch.domain.enums.TaskStatus;
import com.skydispatch.dto.CommonDtos;
import com.skydispatch.dto.TaskDtos;
import com.skydispatch.service.TaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 * 任务接口。
 *
 * <p>任务 = 订单 × 无人机 × 航路 的执行实例。回放功能依赖两个接口：
 * {@code /tasks/{id}/waypoints}（航迹几何）与 {@code /tasks/{id}/events}（时间线）。
 */
@RestController
@RequestMapping("/api/v1/tasks")
@Tag(name = "任务管理", description = "任务查询、航路点、事件时间线、中止任务")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping
    @Operation(summary = "分页查询任务", description = "可按状态或无人机过滤")
    public ApiResponse<CommonDtos.PageResponse<TaskDtos.Response>> list(
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) Long droneId,
            @PageableDefault(size = 20, sort = "id") Pageable pageable) {
        return ApiResponse.ok(taskService.list(status, droneId, pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "任务详情")
    public ApiResponse<TaskDtos.Response> get(@PathVariable Long id) {
        return ApiResponse.ok(taskService.get(id));
    }

    @GetMapping("/{id}/waypoints")
    @Operation(summary = "航路点列表", description = "按 seq 升序，含累计距离与 ETA，用于地图画线与回放")
    public ApiResponse<List<TaskDtos.WaypointResponse>> waypoints(@PathVariable Long id) {
        return ApiResponse.ok(taskService.waypoints(id));
    }

    @GetMapping("/{id}/events")
    @Operation(summary = "任务事件时间线", description = "按时间升序，回放与故障归因的主要依据")
    public ApiResponse<List<TaskDtos.EventResponse>> events(@PathVariable Long id) {
        return ApiResponse.ok(taskService.events(id));
    }

    @PostMapping("/{id}/abort")
    @Operation(summary = "中止任务",
            description = "未起飞的任务中止后订单退回 QUEUED 可重新派单；已在飞的任务中止则订单置为 FAILED")
    public ApiResponse<TaskDtos.Response> abort(@PathVariable Long id,
                                               @RequestBody(required = false) TaskDtos.AbortRequest request) {
        String reason = request == null ? null : request.reason();
        return ApiResponse.ok(taskService.abort(id, reason));
    }
}
