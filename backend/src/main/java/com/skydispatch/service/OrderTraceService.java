package com.skydispatch.service;

import com.skydispatch.domain.entity.DeliveryOrder;
import com.skydispatch.domain.entity.DispatchDecision;
import com.skydispatch.domain.entity.DispatchTask;
import com.skydispatch.domain.entity.Drone;
import com.skydispatch.domain.entity.EventLog;
import com.skydispatch.dto.OrderDtos;
import com.skydispatch.dto.TaskDtos;
import com.skydispatch.repository.DispatchDecisionRepository;
import com.skydispatch.repository.DispatchTaskRepository;
import com.skydispatch.repository.DroneRepository;
import com.skydispatch.repository.EventLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 订单链路追踪。
 *
 * <p>一条订单从下单到送达（或失败）会横跨三张表：
 * 任务表记录"谁飞的、飞了多久"，事件表记录"按时间发生了什么"，
 * 决策表记录"当时为什么选这架"。这个服务把三张表按订单号聚合，
 * 用<b>一次只读事务</b>返回同一时刻的快照——拆成多个接口会让时间线和任务卡错位。
 */
@Service
public class OrderTraceService {

    private final DispatchTaskRepository taskRepository;
    private final EventLogRepository eventRepository;
    private final DispatchDecisionRepository decisionRepository;
    private final DroneRepository droneRepository;
    private final OrderService orderService;

    public OrderTraceService(DispatchTaskRepository taskRepository,
                             EventLogRepository eventRepository,
                             DispatchDecisionRepository decisionRepository,
                             DroneRepository droneRepository,
                             OrderService orderService) {
        this.taskRepository = taskRepository;
        this.eventRepository = eventRepository;
        this.decisionRepository = decisionRepository;
        this.droneRepository = droneRepository;
        this.orderService = orderService;
    }

    /**
     * 聚合订单全链路。
     *
     * <p>查询顺序有讲究：先取任务和决策，把涉及的无人机 id 合并去重后<b>一次</b>查出机身编号，
     * 避免每条任务 / 每条决策各查一次（N+1）。事件表已冗余 order_id，直接按订单查即可。
     */
    @Transactional(readOnly = true)
    public OrderDtos.TraceResponse trace(Long orderId) {
        DeliveryOrder order = orderService.require(orderId);
        List<DispatchTask> tasks = taskRepository.findByOrderIdOrderByIdDesc(orderId);
        List<EventLog> events = eventRepository.findByOrderIdOrderByTsAsc(orderId);
        List<DispatchDecision> decisions = decisionRepository.findByOrderIdOrderByRankNoAsc(orderId).stream()
                // NULL 排名（被淘汰）在不同数据库里排序不一致，这里统一沉底，保证接口语义稳定
                .sorted(Comparator.comparing((DispatchDecision d) -> d.getRankNo() == null)
                        .thenComparing(d -> d.getRankNo() == null ? Integer.MAX_VALUE : d.getRankNo())
                        .thenComparing(DispatchDecision::getId))
                .toList();

        Map<Long, String> droneCodes = loadDroneCodes(tasks, decisions, order.getAssignedDroneId());

        List<TaskDtos.Response> taskDtos = tasks.stream()
                .map(t -> TaskDtos.Response.from(t, order.getOrderNo(), droneCodes.get(t.getDroneId())))
                .toList();
        List<TaskDtos.DecisionResponse> decisionDtos = decisions.stream()
                .map(d -> TaskDtos.DecisionResponse.from(d, droneCodes.get(d.getDroneId())))
                .toList();
        List<TaskDtos.EventResponse> eventDtos = events.stream()
                .map(TaskDtos.EventResponse::from)
                .toList();

        return new OrderDtos.TraceResponse(OrderDtos.Response.from(order), taskDtos, eventDtos, decisionDtos);
    }

    /** 收集任务与决策里出现过的无人机 id，一次查询拿回编号。 */
    private Map<Long, String> loadDroneCodes(List<DispatchTask> tasks,
                                             List<DispatchDecision> decisions,
                                             Long assignedDroneId) {
        Set<Long> ids = new LinkedHashSet<>();
        tasks.forEach(t -> addIfPresent(ids, t.getDroneId()));
        decisions.forEach(d -> addIfPresent(ids, d.getDroneId()));
        addIfPresent(ids, assignedDroneId);

        Map<Long, String> codes = new HashMap<>();
        if (ids.isEmpty()) {
            return codes;
        }
        for (Drone drone : droneRepository.findAllById(ids)) {
            codes.put(drone.getId(), drone.getCode());
        }
        return codes;
    }

    private void addIfPresent(Set<Long> ids, Long id) {
        if (id != null) {
            ids.add(id);
        }
    }
}
