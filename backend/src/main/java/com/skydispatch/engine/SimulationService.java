package com.skydispatch.engine;

import com.skydispatch.common.BusinessException;
import com.skydispatch.config.SkyProperties;
import com.skydispatch.domain.entity.SimulationRun;
import com.skydispatch.domain.enums.RunStatus;
import com.skydispatch.dto.SimulationDtos;
import com.skydispatch.repository.SimulationRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 仿真运行的生命周期管理：创建、启动、停止、推进仿真时钟。
 *
 * <p>为什么要单独搞一个"运行"（run）概念？因为它把一次沙盘推演的所有数据
 * （遥测、事件、冲突）都串到同一个 runId 上。没有它，回放时无法区分
 * "这是第一次演练的记录"还是"第二次演练的记录"。
 *
 * <p>同一时刻只允许一个 RUNNING 的运行：仿真引擎是单线程定时器，
 * 并行多个运行会让"当前态势"这个概念失效。要对比多套策略，
 * 应当停掉旧的、起一个新的（V2 再做并行沙盘）。
 */
@Service
public class SimulationService {

    private static final Logger log = LoggerFactory.getLogger(SimulationService.class);

    private final SimulationRunRepository runRepository;
    private final SimulationRuntimeState runtimeState;
    private final ChargingStationRegistry chargingRegistry;
    private final VertiportService vertiports;
    private final StrategicDeconflictionService strategic;
    private final FaultInjectionService faultInjection;
    private final SkyProperties properties;

    public SimulationService(SimulationRunRepository runRepository,
                           SkyProperties properties,
                           SimulationRuntimeState runtimeState,
                           ChargingStationRegistry chargingRegistry,
                           VertiportService vertiports,
                           StrategicDeconflictionService strategic,
                           FaultInjectionService faultInjection) {
        this.runRepository = runRepository;
        this.runtimeState = runtimeState;
        this.chargingRegistry = chargingRegistry;
        this.vertiports = vertiports;
        this.strategic = strategic;
        this.faultInjection = faultInjection;
        this.properties = properties;
    }

    /**
     * 取当前可推进的运行（RUNNING 或 PAUSED）；只有引擎处于激活态且完全没有运行记录时才新建。
     *
     * <p><b>这里是"停止按钮无效"那个 bug 的修复点。</b>
     * 旧实现只要查不到 RUNNING 就无条件新建，于是"停止"后下一拍立刻又冒出一个新运行。
     * 现在把"是否该跑"交给 {@link SimulationRuntimeState} 显式决定：
     * 暂停之后 {@code ensureRunning} 只会返回那个 PAUSED 的运行（供手动单步使用），
     * 绝不会偷偷新建。
     */
    @Transactional
    public SimulationRun ensureRunning() {
        SimulationRun active = activeRunOrNull();
        if (active != null) {
            return active;
        }
        if (!runtimeState.isEngineActive()) {
            return null;
        }
        clearRuntimeState("自动新建运行");
        return createNewRun(new SimulationDtos.StartRequest("默认仿真运行", null, null, null));
    }

    /**
     * 新建一轮运行（计时与拍数从 0 开始），并把引擎切到激活态。
     *
     * <p>语义上与"继续"区分开：想延续当前演练请用 {@link #resumeRun}，
     * 想重新来一轮干净的才用它。
     */
    @Transactional
    public SimulationRun start(SimulationDtos.StartRequest request) {
        stopAllActive("被新的仿真运行替换");
        clearRuntimeState("新建运行");
        runtimeState.resume("新建运行");
        return createNewRun(request);
    }

    /** 暂停推进：运行记录保留为 PAUSED，手动步进仍然可用。 */
    @Transactional
    public SimulationRun pauseRun(Long runId) {
        SimulationRun run = require(runId);
        if (run.getStatus() == RunStatus.RUNNING) {
            run.setStatus(RunStatus.PAUSED);
        }
        runtimeState.pause("用户暂停");
        log.info("暂停仿真运行 id={}（已推进 {} 拍 / {}s）", run.getId(), run.getTickCount(), run.getSimTimeS());
        return run;
    }

    /** 继续推进：把暂停中的运行恢复为 RUNNING，不重置计时。 */
    @Transactional
    public SimulationRun resumeRun(Long runId) {
        SimulationRun run = require(runId);
        if (run.getStatus() == RunStatus.PAUSED || run.getStatus() == RunStatus.CREATED) {
            run.setStatus(RunStatus.RUNNING);
        }
        runtimeState.resume("用户继续");
        log.info("继续仿真运行 id={}（从第 {} 拍 / {}s 接着跑）", run.getId(), run.getTickCount(), run.getSimTimeS());
        return run;
    }

    /** 结束运行（不可继续，需要新建一轮）。 */
    @Transactional
    public SimulationRun stop(Long runId) {
        SimulationRun run = require(runId);
        if (run.getStatus() == RunStatus.RUNNING || run.getStatus() == RunStatus.PAUSED) {
            run.setStatus(RunStatus.STOPPED);
            run.setEndedAt(Instant.now());
            log.info("结束仿真运行 id={}，共推进 {} 拍 / {}s", run.getId(), run.getTickCount(), run.getSimTimeS());
        }
        runtimeState.pause("用户结束运行");
        return run;
    }

    /** 推进仿真时钟一拍。由引擎每个 tick 调用。 */
    @Transactional
    public SimulationRun advance(SimulationRun run, BigDecimal deltaSeconds) {
        run.setSimTimeS(run.getSimTimeS().add(deltaSeconds));
        run.setTickCount(run.getTickCount() + 1);
        return runRepository.save(run);
    }

    /** 当前可推进的运行（RUNNING 优先，其次是暂停中的），可能为空。 */
    @Transactional(readOnly = true)
    public SimulationRun currentOrNull() {
        return activeRunOrNull();
    }

    /** 查询指定运行。 */
    @Transactional(readOnly = true)
    public SimulationRun require(Long runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> BusinessException.notFound("仿真运行 id=" + runId));
    }

    /** 转 DTO。 */
    public SimulationDtos.RunResponse toResponse(SimulationRun run) {
        return SimulationDtos.RunResponse.from(run);
    }

    /** RUNNING 优先；没有 RUNNING 就返回最近一个 PAUSED（用于暂停状态下的手动单步）。 */
    private SimulationRun activeRunOrNull() {
        return runRepository.findFirstByStatusOrderByIdDesc(RunStatus.RUNNING)
                .orElseGet(() -> runRepository.findFirstByStatusOrderByIdDesc(RunStatus.PAUSED)
                        .orElse(null));
    }

    /** 建一条新的运行记录。 */
    private SimulationRun createNewRun(SimulationDtos.StartRequest request) {
        SimulationRun run = SimulationRun.builder()
                .name(request != null && request.name() != null ? request.name() : "仿真运行 " + Instant.now())
                .status(RunStatus.RUNNING)
                .tickMs(request != null && request.tickMs() != null
                        ? request.tickMs() : (int) properties.simulation().tickMs())
                .speedFactor(BigDecimal.valueOf(request != null && request.speedFactor() != null
                        ? request.speedFactor() : properties.simulation().speedFactor()))
                .randomSeed(request == null ? null : request.randomSeed())
                .simTimeS(BigDecimal.ZERO)
                .tickCount(0L)
                .config(describeConfig())
                .startedAt(Instant.now())
                .build();

        SimulationRun saved = runRepository.save(run);
        log.info("启动仿真运行: id={}, name={}, tick={}ms, 倍速={}x",
                saved.getId(), saved.getName(), saved.getTickMs(), saved.getSpeedFactor());
        return saved;
    }

    /**
     * 清空本次演练的内存运行态。
     *
     * <p>这些状态不落库：充电桩占用/排队、机巢放行节奏与单机周转、
     * 战略时隙预约、故障持续状态。如果新建运行时不清理，上一次演练的
     * "桩位被占、时隙被订、通信还断着"会残留到新运行，表现为新运行一开始就莫名排队。
     */
    private void clearRuntimeState(String reason) {
        chargingRegistry.clearAll();
        vertiports.clearAll();
        strategic.clearAll();
        faultInjection.clearAll();
        log.info("已清空仿真运行态（{}）：充电占用/排队、机巢周转、战略时隙、故障状态", reason);
    }

    private void stopAllActive(String reason) {
        List<SimulationRun> running = runRepository.findByStatusIn(List.of(RunStatus.RUNNING, RunStatus.PAUSED));
        for (SimulationRun run : running) {
            run.setStatus(RunStatus.STOPPED);
            run.setEndedAt(Instant.now());
            runRepository.save(run);
            log.info("自动结束运行 id={}（{}）", run.getId(), reason);
        }
    }

    private String describeConfig() {
        return String.format(
                "{\"tickMs\":%d,\"speedFactor\":%.1f,\"conflictCheckEveryTicks\":%d,"
                        + "\"autoDispatchIntervalMs\":%d,\"defaultStrategy\":\"%s\",\"algorithmEnabled\":%b}",
                properties.simulation().tickMs(),
                properties.simulation().speedFactor(),
                properties.simulation().conflictCheckEveryTicks(),
                properties.simulation().autoDispatchIntervalMs(),
                properties.dispatch().defaultStrategy(),
                properties.algorithm().enabled());
    }
}
