package com.skydispatch.engine;

import com.skydispatch.config.SkyProperties;
import com.skydispatch.domain.entity.ConflictEvent;
import com.skydispatch.domain.entity.SimulationRun;
import com.skydispatch.dto.DashboardDtos;
import com.skydispatch.dto.SimulationDtos;
import com.skydispatch.service.ConflictService;
import com.skydispatch.service.DashboardService;
import com.skydispatch.service.DispatchBatchService;
import com.skydispatch.ws.TelemetryBroadcaster;
import com.skydispatch.ws.WsMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 仿真引擎调度器：定时器 + 并发保护 + 对外广播。
 *
 * <p>它本身<b>不碰数据库事务</b>，只做三件事：
 * <ol>
 *   <li>按 {@code sky.simulation.tick-ms} 定时触发（默认 1 秒一拍）；</li>
 *   <li>保证同一时刻只有一拍在跑（{@link AtomicBoolean} 乐观锁，
 *       手动调用 {@code /simulation/tick} 时不会和定时器打架）；</li>
 *   <li>在事务提交后广播遥测、冲突、KPI，并周期性地自动扫单派单。</li>
 * </ol>
 *
 * <p>真正的"一拍工作单元"在 {@link SimulationTickService} 里，那里才是事务边界。
 * 这种拆分让"计算/写入"和"推送/调度"互不污染，也让每一拍的失败不会连累下一拍。
 *
 * <p>注意 {@code fixedDelay} 与 {@code fixedRate} 的区别：前者是"上一拍结束后再等 N 毫秒"，
 * 后者是"每 N 毫秒起一拍"。仿真必须用 fixedDelay，
 * 否则当某一拍变慢（比如算法服务超时）时会堆积任务，越跑越崩。
 */
@Component
public class SimulationEngine {

    private static final Logger log = LoggerFactory.getLogger(SimulationEngine.class);

    private final SkyProperties properties;
    private final SimulationService simulationService;
    private final SimulationTickService tickService;
    private final SimulationRuntimeState runtimeState;
    private final TelemetryBroadcaster broadcaster;
    private final DashboardService dashboardService;
    private final DispatchBatchService dispatchBatchService;
    private final ConflictService conflictService;
    private final DemoOrderFountain orderFountain;
    private final SimulationMetrics metrics;

    /** 正在推进标志：true 表示上一拍还没结束。 */
    private final AtomicBoolean ticking = new AtomicBoolean(false);

    public SimulationEngine(SkyProperties properties,
                            SimulationService simulationService,
                            SimulationTickService tickService,
                            SimulationRuntimeState runtimeState,
                            TelemetryBroadcaster broadcaster,
                            DashboardService dashboardService,
                            DispatchBatchService dispatchBatchService,
                            ConflictService conflictService,
                            DemoOrderFountain orderFountain,
                            SimulationMetrics metrics) {
        this.properties = properties;
        this.simulationService = simulationService;
        this.tickService = tickService;
        this.runtimeState = runtimeState;
        this.broadcaster = broadcaster;
        this.dashboardService = dashboardService;
        this.dispatchBatchService = dispatchBatchService;
        this.conflictService = conflictService;
        this.orderFountain = orderFountain;
        this.metrics = metrics;
    }

    /** 定时推进。应用启动后立即开始跑（fixedDelay 从第一次执行后开始计时）。 */
    @Scheduled(fixedDelayString = "${sky.simulation.tick-ms:1000}", initialDelayString = "3000")
    public void scheduledTick() {
        if (!properties.simulation().enabled()) {
            return;
        }
        // 暂停之后这里必须直接返回：否则"暂停"只会停一拍，下一拍又跑起来
        // （旧版本更糟 —— 它会重建一个运行，表现为"停止按钮完全无效"）
        if (!runtimeState.isEngineActive()) {
            return;
        }
        try {
            runOnce();
        } catch (Exception e) {
            // 单拍失败不能让定时器停掉：记录后等下一拍继续
            log.error("仿真 tick 执行失败，将在下一拍重试: {}", e.getMessage(), e);
        }
    }

    /**
     * 执行一拍（定时器与手动接口共用）。
     *
     * <p>手动步进在"暂停"状态下同样可用：此时 {@code ensureRunning} 会返回那个 PAUSED 的运行，
     * 于是可以一拍一拍地观察状态机推进 —— 这正是教学演示最需要的操作方式。
     *
     * @return 结果摘要；若上一拍仍在执行、或当前没有任何运行可推进，返回带说明的结果
     */
    public SimulationDtos.TickResult runOnce() {
        if (!ticking.compareAndSet(false, true)) {
            return new SimulationDtos.TickResult(0, BigDecimal.ZERO, 0, 0, 0,
                    "上一拍仍在执行，本次步进已跳过");
        }
        try {
            SimulationRun run = simulationService.ensureRunning();
            if (run == null) {
                return new SimulationDtos.TickResult(0, BigDecimal.ZERO, 0, 0, 0,
                        "当前没有可推进的仿真运行，请先点击「继续仿真」或「新建运行」");
            }
            long tickStartNanos = System.nanoTime();
            TickOutcome outcome = tickService.tick(run);
            long tickMs = (System.nanoTime() - tickStartNanos) / 1_000_000L;
            metrics.record(tickMs);
            if (tickMs >= 150) {
                // 慢拍告警：正常情况下单拍应在几十毫秒内；持续偏高通常是查询放大或锁竞争
                log.warn("[仿真] 单拍耗时 {}ms（第 {} 拍，{} 架在飞），请检查查询/锁竞争",
                        tickMs, outcome.stepIndex(), outcome.movedDrones());
            }
            publish(outcome);
            // Python 冲突检测放在 tick 事务提交之后：HTTP 最长 10s，
            // 不能让外部调用占着仿真事务和数据库连接。
            checkAlgorithmConflicts(outcome, run);
            autoDispatch(outcome);
            replenishDemoOrders(outcome);
            return new SimulationDtos.TickResult(
                    outcome.stepIndex(), outcome.simTimeS(),
                    outcome.movedDrones(), outcome.completedTasks(), outcome.newConflicts().size(),
                    "第 " + outcome.stepIndex() + " 拍完成");
        } finally {
            ticking.set(false);
        }
    }

    /** 事务外的对外广播。 */
    private void publish(TickOutcome outcome) {
        broadcaster.broadcast(WsMessage.telemetry(new DashboardDtos.TelemetryPayload(
                outcome.simTimeS(), outcome.stepIndex(), outcome.frames(),
                dashboardService.windView(outcome.simTimeS()))));

        List<ConflictEvent> conflicts = outcome.newConflicts();
        for (ConflictEvent conflict : conflicts) {
            broadcaster.broadcast(WsMessage.conflict(
                    DashboardDtos.ConflictView.from(conflict, conflict.getDetail())));
        }
        if (!conflicts.isEmpty()) {
            log.warn("本拍新增 {} 条冲突，已推送前端", conflicts.size());
        }

        int kpiEvery = Math.max(1, properties.simulation().kpiBroadcastEveryTicks());
        if (outcome.stepIndex() % kpiEvery == 0) {
            broadcaster.broadcast(WsMessage.kpi(dashboardService.kpi()));
        }
    }

    /**
     * 算法服务（Python）的补充冲突检测。
     *
     * <p>只在 tick 事务提交后调用，因此不会把 10s 的 HTTP 超时算进仿真事务。
     * 本地 Java 检测已经发现冲突时跳过，避免同一拍重复告警。
     */
    private void checkAlgorithmConflicts(TickOutcome outcome, SimulationRun run) {
        if (!properties.algorithm().enabled()) {
            return;
        }
        int every = Math.max(1, properties.simulation().conflictCheckEveryTicks());
        if (outcome.stepIndex() % every != 0 || !outcome.newConflicts().isEmpty()) {
            return;
        }
        try {
            List<ConflictEvent> conflicts = conflictService.checkNow(run.getId(), outcome.simTimeS());
            for (ConflictEvent conflict : conflicts) {
                broadcaster.broadcast(WsMessage.conflict(
                        DashboardDtos.ConflictView.from(conflict, conflict.getDetail())));
            }
        } catch (Exception e) {
            // 算法服务不可用不能影响仿真主流程
            log.warn("算法服务冲突检测失败（不影响仿真推进）: {}", e.getMessage());
        }
    }

    /**
     * 周期性自动派单。
     *
     * <p>为什么用"每 N 拍"而不是独立定时器？因为仿真可能被加速（4 倍速），
     * 独立定时器会让"派单节奏"和"飞行节奏"脱钩。
     * 用 tick 计数能让仿真加速时派单也跟着加速，整体时序保持一致。
     */
    private void autoDispatch(TickOutcome outcome) {
        // 关掉自动派单后，派单完全由人控制 —— 演示时才能看清"派单到底做了什么"
        if (!runtimeState.isAutoDispatchEnabled()) {
            return;
        }
        long tickMs = Math.max(1, properties.simulation().tickMs());
        long everyTicks = Math.max(1, properties.simulation().autoDispatchIntervalMs() / tickMs);
        if (outcome.stepIndex() % everyTicks != 0) {
            return;
        }
        try {
            var results = dispatchBatchService.dispatchPending(properties.dispatch().defaultStrategy(), 3);
            long success = results.stream().filter(r -> r.task() != null).count();
            if (!results.isEmpty()) {
                log.info("自动派单: 尝试 {} 单，成功 {} 单", results.size(), success);
            }
        } catch (Exception e) {
            log.warn("自动派单失败（不影响仿真推进）: {}", e.getMessage());
        }
    }

    /**
     * 演示订单补给（dev 环境）。
     *
     * <p>与 {@link #autoDispatch} 独立：即使运营关掉了"自动派单"想手动操作，
     * 队列里仍应有新订单可派。补单器内部自带开关与"按可用运力造单"的判断。
     * 节奏与自动派单一致（每个调度周期检查一次），但每次最多补 2 单，不会瞬间灌满。
     */
    private void replenishDemoOrders(TickOutcome outcome) {
        long tickMs = Math.max(1, properties.simulation().tickMs());
        long everyTicks = Math.max(1, properties.simulation().autoDispatchIntervalMs() / tickMs);
        if (outcome.stepIndex() % everyTicks != 0) {
            return;
        }
        try {
            orderFountain.replenishIfNeeded(outcome.simTimeS());
        } catch (Exception e) {
            log.warn("演示订单补给失败（不影响仿真推进）: {}", e.getMessage());
        }
    }

    /** 供接口层判断引擎是否已启用（配置层面）。 */
    public boolean isEnabled() {
        return properties.simulation().enabled();
    }

    /** 供接口层判断当前是否有拍在跑。 */
    public boolean isTicking() {
        return ticking.get();
    }
}
