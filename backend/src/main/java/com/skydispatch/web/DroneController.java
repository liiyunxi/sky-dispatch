package com.skydispatch.web;

import com.skydispatch.common.ApiResponse;
import com.skydispatch.domain.enums.DroneStatus;
import com.skydispatch.dto.DroneDtos;
import com.skydispatch.service.DroneService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 无人机接口（台账 + 状态）。
 */
@RestController
@RequestMapping("/api/v1/drones")
@Tag(name = "无人机管理", description = "注册无人机、查询机队、手动调整状态")
public class DroneController {

    private final DroneService droneService;

    public DroneController(DroneService droneService) {
        this.droneService = droneService;
    }

    @PostMapping
    @Operation(summary = "注册无人机", description = "型号用业务编码引用，例如 SKY-X4")
    public ApiResponse<DroneDtos.Response> register(@Valid @RequestBody DroneDtos.CreateRequest request) {
        return ApiResponse.ok(droneService.register(request));
    }

    @GetMapping
    @Operation(summary = "查询无人机列表", description = "可按状态过滤，如只查空闲的 ?status=IDLE")
    public ApiResponse<List<DroneDtos.Response>> list(@RequestParam(required = false) DroneStatus status) {
        return ApiResponse.ok(droneService.list(status));
    }

    @GetMapping("/{id}")
    @Operation(summary = "无人机详情")
    public ApiResponse<DroneDtos.Response> get(@PathVariable Long id) {
        return ApiResponse.ok(droneService.get(id));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "调整无人机状态", description = "用于模拟充电完成、送修、下线等运营动作")
    public ApiResponse<DroneDtos.Response> updateStatus(@PathVariable Long id,
                                                       @Valid @RequestBody DroneDtos.StatusRequest request) {
        return ApiResponse.ok(droneService.updateStatus(id, request.status()));
    }
}
