package com.skydispatch.domain.entity;

import com.skydispatch.domain.enums.OrderStatus;
import com.skydispatch.domain.enums.Priority;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 配送订单，对应表 {@code delivery_order}。
 *
 * <p>建模取舍：取送两端都用"地址文本 + 经纬度"两列，地址给人看、经纬度给算法算，
 * 不用地址解析服务也能跑通；{@code assignedDroneId} 只存 id，
 * 让订单表不依赖 drone 表，历史订单不会因为无人机删除/改型号而受影响。
 */
@Entity
@Table(name = "delivery_order")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryOrder {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 业务单号，如 SO20260101001；对外接口用它做幂等键。 */
    @Column(name = "order_no", nullable = false, unique = true, length = 40)
    private String orderNo;

    /** 客户名称。 */
    @Column(name = "customer_name", nullable = false, length = 64)
    private String customerName;

    /** 客户电话，可为空（如内部调拨单）。 */
    @Column(name = "customer_phone", length = 32)
    private String customerPhone;

    /** 货物名称。 */
    @Column(name = "cargo_name", nullable = false, length = 128)
    private String cargoName;

    /** 货物重量(kg)，派单时要与型号最大载重比较。 */
    @Column(name = "weight_kg", nullable = false)
    private BigDecimal weightKg;

    /** 优先级，排队出队顺序的依据之一。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 10)
    private Priority priority;

    /** 订单状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status;

    /** 取货地址（人类可读）。 */
    @Column(name = "pickup_address", length = 255)
    private String pickupAddress;

    /** 取货点纬度，航路规划起点。 */
    @Column(name = "pickup_lat", nullable = false)
    private Double pickupLat;

    /** 取货点经度。 */
    @Column(name = "pickup_lon", nullable = false)
    private Double pickupLon;

    /** 送达地址（人类可读）。 */
    @Column(name = "dropoff_address", length = 255)
    private String dropoffAddress;

    /** 送达点纬度，航路规划终点。 */
    @Column(name = "dropoff_lat", nullable = false)
    private Double dropoffLat;

    /** 送达点经度。 */
    @Column(name = "dropoff_lon", nullable = false)
    private Double dropoffLon;

    /** 期望送达时间，用于准时率统计与 DEADLINE_RISK 冲突判定。 */
    @Column(name = "expect_delivery_at")
    private Instant expectDeliveryAt;

    /**
     * 实际送达时间：订单转为 DELIVERED 时写入。
     *
     * <p>准时率必须用"送达时间"而不是"任务完成时间"（后者含返航），
     * 否则承诺的是送货、统计的却是回机巢，口径不一致。
     */
    @Column(name = "delivered_at")
    private Instant deliveredAt;

    /** 已分配的无人机 id（只存 id，不做对象关联）。 */
    @Column(name = "assigned_drone_id")
    private Long assignedDroneId;

    /** 失败原因，成功订单为空。 */
    @Column(name = "fail_reason", length = 255)
    private String failReason;

    /** 备注。 */
    @Column(name = "remark", length = 500)
    private String remark;

    /**
     * 乐观锁版本号。
     *
     * <p>订单会被仿真线程（起飞/送达）和 HTTP 线程（派单/取消）同时修改，
     * 没有版本号时后提交的更新会静默覆盖前一个，造成"订单已送达又被改回配送中"。
     * 加 @Version 后冲突方会抛乐观锁异常并回滚，业务层可选择重试。
     */
    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;

    /** 创建时间，仅写入一次。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 更新时间，由 Hibernate 自动刷新。 */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
