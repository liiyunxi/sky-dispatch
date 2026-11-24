package com.skydispatch.dto;

import com.skydispatch.domain.entity.SimulationRun;
import com.skydispatch.domain.enums.RunStatus;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

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

    /**
     * 运行事件回放。
     *
     * <p>只回放 {@code event_log} 里的事件，不重建每一拍的无人机坐标——
     * 坐标属于高频遥测，当前不做全量留存；事件回放用于复盘"谁在什么时候做了什么"。
     */
    public record ReplayResponse(
            Long runId,
            String runName,
            RunStatus status,
            BigDecimal maxSimTimeS,
            Long tickCount,
            List<TaskDtos.EventResponse> events
    ) {
    }

    /** 可人工注入的故障类型（与 FaultInjectionService.Type 一一对应，不含 NONE）。 */
    public enum ManualFaultType {
        BATTERY_DROP,
        POWER_LOSS,
        COMM_LOSS,
        GPS_DRIFT
    }

    /**
     * 人工故障注入请求。
     *
     * @param droneId        目标无人机
     * @param type           故障类型
     * @param batteryDropPct 仅 BATTERY_DROP 使用；缺省 15%，夹在 1~50
     */
    public record FaultRequest(
            @NotNull(message = "无人机不能为空") Long droneId,
            @NotNull(message = "故障类型不能为空") ManualFaultType type,
            Double batteryDropPct
    ) {
    }

    /** 人工故障注入结果：已排队，下一拍生效。 */
    public record FaultResponse(
            Long droneId,
            String droneCode,
            String type,
            double batteryDropPct,
            String message
    ) {
    }
}
