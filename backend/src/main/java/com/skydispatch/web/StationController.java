package com.skydispatch.web;

import com.skydispatch.common.ApiResponse;
import com.skydispatch.dto.ChargingDtos;
import com.skydispatch.repository.ChargingStationRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 充电站接口。
 *
 * <p>只读：演示环境默认只提供查询，站点是"选点要报备"的静态资源，
 * 让界面随手新增/删除反而不符合业务。前端把它画在态势地图与立体视图上，
 * 让运营看得见"飞机中途能落在哪儿"。
 */
@RestController
@RequestMapping("/api/v1/stations")
@Tag(name = "充电站", description = "无人机中途补能站点的查询")
public class StationController {

    private final ChargingStationRepository stationRepository;

    public StationController(ChargingStationRepository stationRepository) {
        this.stationRepository = stationRepository;
    }

    @GetMapping
    @Operation(summary = "查询全部启用的充电站")
    public ApiResponse<List<ChargingDtos.Station>> list() {
        return ApiResponse.ok(stationRepository.findAllByEnabledTrue().stream()
                .map(ChargingDtos.Station::from)
                .toList());
    }
}
