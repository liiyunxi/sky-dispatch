package com.skydispatch.web;

import com.skydispatch.common.ApiResponse;
import com.skydispatch.dto.InfrastructureDtos;
import com.skydispatch.service.InfrastructureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 基础设施接口：充电站排队、机巢容量、战略时隙。
 *
 * <p>只读总览 + 一个机巢配置修改入口。站点台账（充电站）本身不做增删，
 * 避免把"选点要报备"的物理资源变成页面上的随手操作。
 */
@RestController
@RequestMapping("/api/v1/infrastructure")
@Tag(name = "基础设施", description = "充电站排队、机巢容量与战略时隙查询")
public class InfrastructureController {

    private final InfrastructureService infrastructureService;

    public InfrastructureController(InfrastructureService infrastructureService) {
        this.infrastructureService = infrastructureService;
    }

    @GetMapping
    @Operation(summary = "基础设施总览",
            description = "一次返回充电站桩位/排队/预计等待、机巢坪位/周转/待飞、战略时隙占用")
    public ApiResponse<InfrastructureDtos.Overview> overview() {
        return ApiResponse.ok(infrastructureService.overview());
    }

    @GetMapping("/slots")
    @Operation(summary = "战略时隙快照", description = "只读：当前被占用的走廊扇区 + 高度层 + 时隙及占用任务")
    public ApiResponse<InfrastructureDtos.SlotSnapshot> slots() {
        return ApiResponse.ok(infrastructureService.slotSnapshot());
    }

    @PatchMapping("/vertiports/{id}")
    @Operation(summary = "修改机巢坪位/周转", description = "不同站点可单独配置坪位数与地面周转时间")
    public ApiResponse<InfrastructureDtos.VertiportView> updateVertiport(
            @PathVariable Long id,
            @RequestBody InfrastructureDtos.VertiportUpdate request) {
        return ApiResponse.ok(infrastructureService.updateVertiport(id, request));
    }
}
