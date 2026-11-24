package com.skydispatch.dto;

import com.skydispatch.domain.entity.DeliveryOrder;
import com.skydispatch.domain.enums.OrderStatus;
import com.skydispatch.domain.enums.Priority;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 订单相关的请求/响应模型。
 *
 * <p>刻意把"请求"和"响应"分开：请求里不需要 id/status（那是服务端生成的），
 * 响应里也不应该暴露数据库内部字段。这样接口变更时互不影响。
 */
public final class OrderDtos {

    private OrderDtos() {
    }

    /** 创建订单请求。校验注解会在进 Service 之前生效。 */
    public record CreateRequest(
            @NotBlank(message = "客户名称不能为空") @Size(max = 64) String customerName,
            @Size(max = 32) String customerPhone,
            @NotBlank(message = "货物名称不能为空") @Size(max = 128) String cargoName,
            @NotNull(message = "重量不能为空")
            @DecimalMin(value = "0.01", message = "重量必须大于 0") BigDecimal weightKg,
            /** 缺省按 NORMAL 处理，见 OrderService */
            Priority priority,
            @NotNull(message = "取货点不能为空") @Valid CommonDtos.GeoDto pickup,
            @NotNull(message = "送货点不能为空") @Valid CommonDtos.GeoDto dropoff,
            Instant expectDeliveryAt,
            @Size(max = 500) String remark
    ) {
    }

    /** 订单响应。 */
    public record Response(
            Long id,
            String orderNo,
            String customerName,
            String customerPhone,
            String cargoName,
            BigDecimal weightKg,
            Priority priority,
            OrderStatus status,
            CommonDtos.GeoDto pickup,
            CommonDtos.GeoDto dropoff,
            Instant expectDeliveryAt,
            Long assignedDroneId,
            String failReason,
            String remark,
            Instant createdAt,
            Instant updatedAt
    ) {
        public static Response from(DeliveryOrder o) {
            return new Response(
                    o.getId(), o.getOrderNo(), o.getCustomerName(), o.getCustomerPhone(),
                    o.getCargoName(), o.getWeightKg(), o.getPriority(), o.getStatus(),
                    new CommonDtos.GeoDto(o.getPickupLat(), o.getPickupLon(), o.getPickupAddress()),
                    new CommonDtos.GeoDto(o.getDropoffLat(), o.getDropoffLon(), o.getDropoffAddress()),
                    o.getExpectDeliveryAt(), o.getAssignedDroneId(), o.getFailReason(), o.getRemark(),
                    o.getCreatedAt(), o.getUpdatedAt());
        }
    }

    /**
     * 订单链路追踪：一次返回前端画"全链路"所需的全部数据。
     *
     * <p>为什么不拆成三个接口？链路面板要同时展示时间线、任务执行和派单决策，
     * 三个请求会出现"时间线和任务卡不是同一时刻"的错位；一次读事务返回同一快照更可靠。
     */
    public record TraceResponse(
            Response order,
            /** 该订单的全部执行任务，按 id 倒序（最新一次在前） */
            List<TaskDtos.Response> tasks,
            /** 事件时间线，按 ts 升序 */
            List<TaskDtos.EventResponse> events,
            /** 历次派单的候选评估，按排名升序 */
            List<TaskDtos.DecisionResponse> decisions
    ) {
    }
}
