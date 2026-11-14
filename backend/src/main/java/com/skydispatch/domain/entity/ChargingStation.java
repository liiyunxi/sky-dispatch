package com.skydispatch.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * 充电站（无人机中途补能的落脚点），对应表 {@code charging_station}。
 *
 * <p><b>为什么需要它</b>：机巢（home）只能服务"回到起点"的场景。一旦订单的距离
 * 超过机型的往返续航，任务就永远派不出去 —— 演示数据里那单 10.5km 的虹桥→中山公园
 * 就是这样，26 架机全部被续航条件淘汰。有充电站之后，无人机可以"飞一段、补一段"，
 * 这类订单才真正可执行。
 *
 * <p>建模取舍：站点的位置、桩位数是静态台账（选点要报备、桩位是物理资源），
 * 所以这里只存基础信息；"当前有没有空桩""排队多久"属于调度状态，等真要做排队模型时再加。
 */
@Entity
@Table(name = "charging_station")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChargingStation {

    /** 主键，对应 BIGSERIAL。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 站点编码，如 CS-SH-01；业务上的唯一标识。 */
    @Column(name = "code", nullable = false, unique = true, length = 32)
    private String code;

    /** 站点名称，如「虹桥枢纽补能站」。 */
    @Column(name = "name", nullable = false, length = 64)
    private String name;

    /** 所在城市，用于看板分组与筛选。 */
    @Column(name = "city", nullable = false, length = 32)
    private String city;

    /** 纬度。 */
    @Column(name = "lat", nullable = false)
    private Double lat;

    /** 经度。 */
    @Column(name = "lon", nullable = false)
    private Double lon;

    /** 可同时补能的桩位数（展示用，MVP 不做排队模型）。 */
    @Column(name = "charge_slots", nullable = false)
    private Integer chargeSlots;

    /** 是否启用；停用的站点不参与自主补能选点。 */
    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    /** 备注。 */
    @Column(name = "remark", length = 200)
    private String remark;

    /** 创建时间，仅写入一次。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 更新时间，由 Hibernate 自动刷新。 */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
