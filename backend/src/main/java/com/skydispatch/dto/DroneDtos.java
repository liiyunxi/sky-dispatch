package com.skydispatch.dto;

import com.skydispatch.domain.entity.Drone;
import com.skydispatch.domain.enums.DroneStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 无人机相关的请求/响应模型。
 */
public final class DroneDtos {

    private DroneDtos() {
    }

    /** 注册无人机。型号用业务编码（如 SKY-X4）引用，比传数字 id 更友好。 */
    public record CreateRequest(
            /**
             * 机身编号，**可留空**。
             *
             * <p>编号本质上是系统的事：它必须唯一、必须符合命名规范、注册后不该再改，
             * 让操作员现场"编一个"只会带来两件事——起名困难，以及撞号报错。
             * 所以留空时由后端自动生成（见 {@code DroneService#nextDroneCode}）；
             * 确有命名规范要求的运维仍可显式传入。
             */
            @Size(max = 32) String code,
            @NotBlank(message = "型号编码不能为空") @Size(max = 32) String modelCode,
            @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal batteryPct,
            @NotNull(message = "起降点不能为空") @Valid CommonDtos.GeoDto home
    ) {
    }

    /** 手动调整状态（充电完成、送修、下线等）。 */
    public record StatusRequest(@NotNull(message = "状态不能为空") DroneStatus status) {
    }

    /** 无人机响应，包含机型能力，前端不用再查一次型号表。 */
    public record Response(
            Long id,
            String code,
            String modelCode,
            String modelName,
            BigDecimal maxPayloadKg,
            BigDecimal cruiseSpeedMps,
            Integer maxFlightMin,
            BigDecimal batteryCapacityWh,
            BigDecimal energyPerKmWh,
            DroneStatus status,
            BigDecimal batteryPct,
            Double currentLat,
            Double currentLon,
            BigDecimal altitudeM,
            BigDecimal speedMps,
            BigDecimal headingDeg,
            Double homeLat,
            Double homeLon,
            Long currentTaskId,
            BigDecimal totalFlightMin,
            BigDecimal totalDistanceKm,
            Integer totalTasks,
            Instant lastHeartbeatAt
    ) {
        /**
         * 注意：这里会访问懒加载的 {@code model}，
         * 必须在事务范围内调用（Service 层方法上有 @Transactional）。
         */
        public static Response from(Drone d) {
            return new Response(
                    d.getId(), d.getCode(),
                    d.getModel().getCode(), d.getModel().getName(),
                    d.getModel().getMaxPayloadKg(), d.getModel().getCruiseSpeedMps(),
                    d.getModel().getMaxFlightMin(), d.getModel().getBatteryCapacityWh(),
                    d.getModel().getEnergyPerKmWh(),
                    d.getStatus(), d.getBatteryPct(),
                    d.getCurrentLat(), d.getCurrentLon(), d.getAltitudeM(),
                    d.getSpeedMps(), d.getHeadingDeg(),
                    d.getHomeLat(), d.getHomeLon(), d.getCurrentTaskId(),
                    d.getTotalFlightMin(), d.getTotalDistanceKm(), d.getTotalTasks(),
                    d.getLastHeartbeatAt());
        }
    }
}
