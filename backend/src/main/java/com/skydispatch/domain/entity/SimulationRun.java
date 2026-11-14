package com.skydispatch.domain.entity;

import com.skydispatch.domain.enums.RunStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 仿真运行（一次"沙盘推演"），对应表 {@code simulation_run}。
 *
 * <p>为什么需要这张表：仿真不是无状态计算，暂停/恢复/回放都要求"进度"可持久化。
 * 因此把仿真时钟（simTimeS、tickCount）、倍速和随机种子落库，
 * 其中 {@code randomSeed} 尤其关键——固定种子才能让一次推演可复现。
 */
@Entity
@Table(name = "simulation_run")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SimulationRun {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 运行名称，便于在列表里区分不同实验。 */
    @Column(name = "name", nullable = false, length = 128)
    private String name;

    /** 运行状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RunStatus status;

    /** 每 tick 对应的真实毫秒数。 */
    @Column(name = "tick_ms", nullable = false)
    private Integer tickMs;

    /** 仿真倍速，如 10 表示 1 真实秒推进 10 仿真秒。 */
    @Column(name = "speed_factor", nullable = false)
    private BigDecimal speedFactor;

    /** 随机种子，保证同一实验可复现。 */
    @Column(name = "random_seed")
    private Long randomSeed;

    /** 已推进的仿真秒数，暂停/恢复靠它续跑。 */
    @Column(name = "sim_time_s", nullable = false)
    private BigDecimal simTimeS;

    /** 已推进的 tick 次数。 */
    @Column(name = "tick_count", nullable = false)
    private Long tickCount;

    /** 运行参数 JSON，长度 8000 对应 SQL 的 VARCHAR(8000)。 */
    @Column(name = "config", length = 8000)
    private String config;

    /** 实际开始时间。 */
    @Column(name = "started_at")
    private Instant startedAt;

    /** 实际结束时间。 */
    @Column(name = "ended_at")
    private Instant endedAt;

    /** 创建时间，仅写入一次。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 更新时间。 */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
