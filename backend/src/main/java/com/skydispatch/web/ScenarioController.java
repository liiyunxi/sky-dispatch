package com.skydispatch.web;

import com.skydispatch.common.ApiResponse;
import com.skydispatch.common.BusinessException;
import com.skydispatch.dto.ScenarioDtos;
import com.skydispatch.service.ScenarioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * 演示场景编排接口。
 *
 * <p>场景列表由后端下发（文案与影响面跟实现放在一起，前端不重复维护）；
 * 触发动作走正常业务路径，因此事件回放与链路追踪能看到真实数据。
 */
@RestController
@RequestMapping("/api/v1/scenarios")
@Tag(name = "演示场景", description = "高峰订单、故障风暴、临时禁飞、补能压力等一键剧本")
public class ScenarioController {

    private final ScenarioService scenarioService;

    public ScenarioController(ScenarioService scenarioService) {
        this.scenarioService = scenarioService;
    }

    @GetMapping
    @Operation(summary = "场景目录", description = "返回可一键触发的演示剧本及影响面说明")
    public ApiResponse<List<ScenarioDtos.Info>> catalog() {
        return ApiResponse.ok(scenarioService.catalog());
    }

    @PostMapping("/{key}")
    @Operation(summary = "触发演示场景", description = "按业务路径真实创建订单/故障/临时禁飞，并记录 SCENARIO 事件")
    public ApiResponse<ScenarioDtos.Result> trigger(@PathVariable String key) {
        ScenarioDtos.Key scenarioKey;
        try {
            scenarioKey = ScenarioDtos.Key.valueOf(key.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw BusinessException.invalidParam("未知演示场景: " + key);
        }
        return ApiResponse.ok(scenarioService.trigger(scenarioKey));
    }
}
