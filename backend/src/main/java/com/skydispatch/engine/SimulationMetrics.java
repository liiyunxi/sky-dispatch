package com.skydispatch.engine;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 仿真单拍耗时指标（最近 300 拍）。
 *
 * <p>性能优化不能只看"感觉快了"：这里保留平均、P95、最大值与最近一拍，
 * 慢拍（≥150ms）同时会打 WARN 日志。指标只存在内存，重启清零，
 * 不写数据库以免"为了监控反而增加每拍写库开销"。
 */
@Component
public class SimulationMetrics {

    private static final int WINDOW = 300;

    private final Deque<Long> tickMillis = new ConcurrentLinkedDeque<>();
    private final AtomicLong totalTicks = new AtomicLong();

    public void record(long millis) {
        totalTicks.incrementAndGet();
        tickMillis.addLast(Math.max(0, millis));
        while (tickMillis.size() > WINDOW) {
            tickMillis.pollFirst();
        }
    }

    /** 指标快照：ticks 为累计拍数，窗口统计只覆盖最近 300 拍。 */
    public record Snapshot(long ticks, double avgMs, long p95Ms, long maxMs, long lastMs) {
    }

    public Snapshot snapshot() {
        List<Long> values = new ArrayList<>(tickMillis);
        if (values.isEmpty()) {
            return new Snapshot(totalTicks.get(), 0, 0, 0, 0);
        }
        long[] sorted = values.stream().mapToLong(Long::longValue).sorted().toArray();
        double avg = Arrays.stream(sorted).average().orElse(0);
        long p95 = sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * 0.95) - 1)];
        long max = sorted[sorted.length - 1];
        long last = values.get(values.size() - 1);
        return new Snapshot(totalTicks.get(),
                Math.round(avg * 100.0) / 100.0, p95, max, last);
    }
}
