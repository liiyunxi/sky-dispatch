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
 * 机巢 / 起降点资源台账（对应表 {@code vertiport_site}）。
 *
 * <p><b>为什么需要独立台账</b>：机巢容量过去只有一份全局配置（所有站点统一 4 坪位、
 * 统一 45s 周转）。真实运营里机场、园区、社区起降点的物理条件不同，
 * "虹桥 6 个坪位、社区 2 个坪位"必须能单独配置，否则调度模型会系统性高估/低估吞吐。
 *
 * <p>站点以"无人机 home 坐标（保留 5 位小数）"为业务键，与 {@code VertiportService}
 * 运行态的 siteKey 完全一致，避免出现"配置了一套站点、调度认的是另一套坐标"的割裂。
 */
@Entity
@Table(name = "vertiport_site")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VertiportSite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 业务键：home 坐标格式化后的字符串，如 "31.23040,121.47370"。 */
    @Column(name = "site_key", nullable = false, unique = true, length = 64)
    private String siteKey;

    /** 站点编码，如 VP-001。 */
    @Column(name = "code", nullable = false, unique = true, length = 32)
    private String code;

    /** 站点名称，如「起降点 VP-001」。 */
    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "lat", nullable = false)
    private Double lat;

    @Column(name = "lon", nullable = false)
    private Double lon;

    /** 停机位/起降坪数量（可单独配置）。 */
    @Column(name = "slots", nullable = false)
    @Builder.Default
    private Integer slots = 4;

    /** 降落后地面周转时间（仿真秒，可单独配置）。 */
    @Column(name = "turnaround_seconds", nullable = false)
    @Builder.Default
    private Double turnaroundSeconds = 45.0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
