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

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 调度决策明细（回答"为什么选了这架无人机"），对应表 {@code dispatch_decision}。
 *
 * <p>建模取舍：
 * <ul>
 *   <li>一次派单会写入多行：每架候选机一行，{@code selected} 标记最终选中者，
 *       {@code rankNo} 记录排序名次。这样"落选原因"也能被查询和解释。</li>
 *   <li>{@code orderId} 故意不建外键：决策是审计留痕，订单被删除后仍应保留，
 *       否则复盘时会出现"选了谁无从考证"。</li>
 *   <li>这张表只追加不修改，所以只有 createdAt，没有 updatedAt。</li>
 * </ul>
 */
@Entity
@Table(name = "dispatch_decision")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DispatchDecision {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 被派单的订单 id；不设外键，允许订单删除后留痕。 */
    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /** 所属仿真运行 id（实时派单时可为空）。 */
    @Column(name = "run_id")
    private Long runId;

    /** 本次使用的派单策略名。 */
    @Column(name = "strategy", nullable = false, length = 32)
    private String strategy;

    /** 候选无人机 id。 */
    @Column(name = "drone_id")
    private Long droneId;

    /** 综合得分，越小越优先。 */
    @Column(name = "score")
    private BigDecimal score;

    /** 排名名次，从 1 开始。 */
    @Column(name = "rank_no")
    private Integer rankNo;

    /** 是否最终被选中。 */
    @Column(name = "selected", nullable = false)
    private Boolean selected;

    /** 落选原因（载重不足 / 电量不够 / 忙碌 …）。 */
    @Column(name = "rejected_reason", length = 255)
    private String rejectedReason;

    /** 各因子分项 JSON；长度 8000 对应 SQL 的 VARCHAR(8000)，用于解释得分构成。 */
    @Column(name = "detail", length = 8000)
    private String detail;

    /** 创建时间，由 Hibernate 在 insert 时写入；只追加不修改，故无 updatedAt。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
