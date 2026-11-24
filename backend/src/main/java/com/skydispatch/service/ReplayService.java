package com.skydispatch.service;

import com.skydispatch.domain.entity.EventLog;
import com.skydispatch.domain.entity.SimulationRun;
import com.skydispatch.dto.SimulationDtos;
import com.skydispatch.dto.TaskDtos;
import com.skydispatch.engine.SimulationService;
import com.skydispatch.repository.EventLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 运行回放服务：从 event_log 还原一轮仿真的事件时间线。
 *
 * <p>为什么只回放事件而不是轨迹：每拍每机的位置帧属于高频遥测，
 * 当前没有留存（也不该在演示库里无限增长）。事件回放已经足够复盘
 * "哪一单在什么时候被派、什么时候起飞、哪里发生冲突、为什么失败"，
 * 并且能把每条事件继续下钻到订单链路。
 */
@Service
public class ReplayService {

    /** 单轮最多回放的事件数：够覆盖一次演示，又不会把响应撑爆。 */
    private static final int MAX_EVENTS = 2000;

    private final EventLogRepository eventLogRepository;
    private final SimulationService simulationService;

    public ReplayService(EventLogRepository eventLogRepository, SimulationService simulationService) {
        this.eventLogRepository = eventLogRepository;
        this.simulationService = simulationService;
    }

    @Transactional(readOnly = true)
    public SimulationDtos.ReplayResponse replay(Long runId) {
        SimulationRun run = simulationService.require(runId);
        List<EventLog> logs = eventLogRepository.findByRunIdOrderBySimTimeSAscIdAsc(
                runId, PageRequest.of(0, MAX_EVENTS));
        List<TaskDtos.EventResponse> events = logs.stream()
                .map(TaskDtos.EventResponse::from)
                .toList();
        return new SimulationDtos.ReplayResponse(
                run.getId(), run.getName(), run.getStatus(),
                run.getSimTimeS(), run.getTickCount(), events);
    }
}
