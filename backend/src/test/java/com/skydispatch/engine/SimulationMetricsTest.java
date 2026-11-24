package com.skydispatch.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 单拍耗时指标：P95 口径与 300 拍滚动窗口。 */
class SimulationMetricsTest {

    @Test
    @DisplayName("P95 取排序后第 95 百分位（100 拍 → 第 95 个值）")
    void p95OfHundredTicks() {
        SimulationMetrics metrics = new SimulationMetrics();
        for (int i = 1; i <= 100; i++) {
            metrics.record(i);
        }
        SimulationMetrics.Snapshot snapshot = metrics.snapshot();
        assertThat(snapshot.ticks()).isEqualTo(100);
        assertThat(snapshot.avgMs()).isEqualTo(50.5);
        assertThat(snapshot.p95Ms()).isEqualTo(95);
        assertThat(snapshot.maxMs()).isEqualTo(100);
        assertThat(snapshot.lastMs()).isEqualTo(100);
    }

    @Test
    @DisplayName("只统计最近 300 拍，累计拍数持续增长")
    void rollingWindow() {
        SimulationMetrics metrics = new SimulationMetrics();
        for (int i = 1; i <= 350; i++) {
            metrics.record(i);
        }
        SimulationMetrics.Snapshot snapshot = metrics.snapshot();
        assertThat(snapshot.ticks()).isEqualTo(350);
        // 最近 300 拍是 51..350：平均 200.5，P95 为窗口内第 285 个值 = 335
        assertThat(snapshot.avgMs()).isEqualTo(200.5);
        assertThat(snapshot.p95Ms()).isEqualTo(335);
        assertThat(snapshot.maxMs()).isEqualTo(350);
        assertThat(snapshot.lastMs()).isEqualTo(350);
    }

    @Test
    @DisplayName("没有采样时返回全零，不抛异常")
    void emptySnapshot() {
        SimulationMetrics.Snapshot snapshot = new SimulationMetrics().snapshot();
        assertThat(snapshot.ticks()).isZero();
        assertThat(snapshot.avgMs()).isZero();
        assertThat(snapshot.p95Ms()).isZero();
        assertThat(snapshot.maxMs()).isZero();
        assertThat(snapshot.lastMs()).isZero();
    }
}
