package com.skydispatch.dto;

import com.skydispatch.domain.entity.SimulationRun;
import com.skydispatch.domain.enums.RunStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 仿真运行相关的请求/响应模型。
 */
public final class SimulationDtos {

    private SimulationDtos() {
    }

    /**
     * 启动仿真。所有字段都可选：不传就用 application.yml 里的默认值。
     *
     * @param name        运行名称，例如 "2026-01-01 高峰压力测试"
     * @param tickMs      每拍真实毫秒数，越小越"实时"
     * @param speedFactor 仿真倍速，4.0 表示 4 倍速推进
     * @param randomSeed  随机种子，固定它可以让每次模拟结果可复现
     */
    public record StartRequest(String name, Integer tickMs, Double speedFactor, Long randomSeed) {
    }

    /** 运行状态响应。 */
    public record RunResponse(
            Long id, String name, RunStatus status, Integer tickMs, BigDecimal speedFactor,
            BigDecimal simTimeS, Long tickCount, Instant startedAt, Instant endedAt
    ) {
        public static RunResponse from(SimulationRun r) {
            return new RunResponse(r.getId(), r.getName(), r.getStatus(), r.getTickMs(), r.getSpeedFactor(),
                    r.getSimTimeS(), r.getTickCount(), r.getStartedAt(), r.getEndedAt());
        }
    }

    /** 手动步进一拍的结果，方便不启动定时器也能测试引擎逻辑。 */
    public record TickResult(
            long stepIndex,
            BigDecimal simTimeS,
            int movedDrones,
            int completedTasks,
            int newConflicts,
            String message
    ) {
    }

    /**
     * 仿真运行时状态。
     *
     * <p>界面靠它渲染「暂停 / 继续」按钮与「自动派单」开关：
     * {@code engineActive=false} 表示定时推进已停（但手动步进仍可用），
     * {@code autoDispatchEnabled=false} 表示派单完全由人控制。
     */
    public record RuntimeState(
            boolean engineActive,
            boolean autoDispatchEnabled,
            long tickMs,
            double speedFactor,
            Long runId,
            String runStatus,
            BigDecimal simTimeS,
            Long tickCount
    ) {
    }
}
