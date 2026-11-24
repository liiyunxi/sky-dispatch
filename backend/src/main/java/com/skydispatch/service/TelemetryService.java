package com.skydispatch.service;

import com.skydispatch.domain.entity.DispatchTask;
import com.skydispatch.domain.entity.Drone;
import com.skydispatch.domain.entity.SimulationRun;
import com.skydispatch.domain.entity.Telemetry;
import com.skydispatch.engine.TelemetryObservationService;
import com.skydispatch.repository.TelemetryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 遥测服务：把每一拍的飞行状态落库，供回放与报表使用。
 *
 * <p>遥测是"写多读少"的时序数据，本平台有两个处理策略：
 * <ol>
 *   <li><b>降采样落库</b>：引擎不要求每拍都写库（默认每 2 拍写一次），
 *       但 WebSocket 是每拍都推。看板要流畅，回放不需要毫秒级精度。</li>
 *   <li><b>实时状态不查遥测表</b>：无人机当前位置直接读 {@code drone} 表，
 *       遥测表只用于历史轨迹。否则"取最新一条"会把时序表变成热表。</li>
 * </ol>
 */
@Service
public class TelemetryService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryService.class);

    private final TelemetryRepository telemetryRepository;
    private final TelemetryObservationService observation;

    public TelemetryService(TelemetryRepository telemetryRepository,
                            TelemetryObservationService observation) {
        this.telemetryRepository = telemetryRepository;
        this.observation = observation;
    }

    /** 记录一拍遥测（观测值：含噪声/丢包，通信中断时整拍缺失）。 */
    @Transactional
    public void record(SimulationRun run, DispatchTask task, Drone drone, BigDecimal simTimeS,
                       long stepIndex, BigDecimal progressPct) {
        double simTime = simTimeS == null ? 0 : simTimeS.doubleValue();
        TelemetryObservationService.Observation observed =
                observation.observeForRecord(drone, simTime, stepIndex);
        if (observed == null) {
            return;   // 丢包/通信中断：这一拍没有可落库的遥测
        }
        Telemetry telemetry = Telemetry.builder()
                .runId(run == null ? null : run.getId())
                .taskId(task == null ? null : task.getId())
                .droneId(drone.getId())
                .ts(java.time.Instant.now())
                .simTimeS(simTimeS)
                .stepIndex(stepIndex)
                .lat(observed.lat())
                .lon(observed.lon())
                .altitudeM(BigDecimal.valueOf(observed.altitudeM()).setScale(2, java.math.RoundingMode.HALF_UP))
                .speedMps(BigDecimal.valueOf(observed.speedMps()).setScale(2, java.math.RoundingMode.HALF_UP))
                .headingDeg(BigDecimal.valueOf(observed.headingDeg()).setScale(2, java.math.RoundingMode.HALF_UP))
                .batteryPct(BigDecimal.valueOf(observed.batteryPct()).setScale(2, java.math.RoundingMode.HALF_UP))
                .progressPct(progressPct)
                .build();
        telemetryRepository.save(telemetry);
    }

    /** 某任务的完整轨迹（按写入顺序）。 */
    @Transactional(readOnly = true)
    public List<Telemetry> byTask(Long taskId) {
        return telemetryRepository.findByTaskIdOrderByIdAsc(taskId);
    }

    /** 某次运行的最近若干条遥测。 */
    @Transactional(readOnly = true)
    public List<Telemetry> recentByRun(Long runId) {
        return telemetryRepository.findTop200ByRunIdOrderByIdDesc(runId);
    }
}
