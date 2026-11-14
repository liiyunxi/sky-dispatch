package com.skydispatch.web;

import com.skydispatch.common.ApiResponse;
import com.skydispatch.dto.AirspaceDtos;
import com.skydispatch.dto.CommonDtos;
import com.skydispatch.service.AirspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 空域接口。
 *
 * <p>新增的禁飞区会**立即影响下一次路径规划**：规划请求会把
 * {@code NO_FLY} / {@code RESTRICTED} 区域作为障碍物传给算法服务。
 * 这是本平台演示"动态空域管制"最直观的方式。
 */
@RestController
@RequestMapping("/api/v1/airspace")
@Tag(name = "空域管理", description = "禁飞区、限飞区、航路走廊的增删查")
public class AirspaceController {

    private final AirspaceService airspaceService;

    public AirspaceController(AirspaceService airspaceService) {
        this.airspaceService = airspaceService;
    }

    @PostMapping("/zones")
    @Operation(summary = "新增空域",
            description = "shapeType=CIRCLE 时填 center + radiusM；shapeType=POLYGON 时填 points（至少 3 个点）")
    public ApiResponse<AirspaceDtos.Response> create(@Valid @RequestBody AirspaceDtos.ZoneRequest request) {
        return ApiResponse.ok(airspaceService.create(request));
    }

    @GetMapping("/zones")
    @Operation(summary = "查询全部空域")
    public ApiResponse<List<AirspaceDtos.Response>> list() {
        return ApiResponse.ok(airspaceService.list());
    }

    @DeleteMapping("/zones/{id}")
    @Operation(summary = "删除空域")
    public ApiResponse<CommonDtos.OperationResult> delete(@PathVariable Long id) {
        airspaceService.delete(id);
        return ApiResponse.ok(CommonDtos.OperationResult.ok("空域已删除"));
    }
}
