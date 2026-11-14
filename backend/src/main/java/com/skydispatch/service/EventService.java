package com.skydispatch.service;

import com.skydispatch.domain.entity.ConflictEvent;
import com.skydispatch.domain.entity.DeliveryOrder;
import com.skydispatch.domain.entity.DispatchTask;
import com.skydispatch.domain.entity.Drone;
import com.skydispatch.domain.entity.EventLog;
import com.skydispatch.domain.enums.EventSeverity;
import com.skydispatch.dto.CommonDtos;
import com.skydispatch.dto.TaskDtos;
import com.skydispatch.event.SkyEvent;
import com.skydispatch.repository.EventLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 事件服务：统一记账 + 统一推送。
 *
 * <p>整个系统里只有这一个地方写 {@code event_log}，好处是：
 * 事件格式一致、时间线完整、回放时不用东拼西凑。
 *
 * <p>流程：{@code record(...)} → 落库 → 发布 {@link SkyEvent} →
 * {@link com.skydispatch.event.EventBroadcaster} 在事务提交后推 WebSocket。
 */
@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventLogRepository eventLogRepository;
    private final ApplicationEventPublisher eventPublisher;

    public EventService(EventLogRepository eventLogRepository, ApplicationEventPublisher eventPublisher) {
        this.eventLogRepository = eventLogRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 记录一条事件。
     *
     * <p>注意：<b>这里故意不抛异常</b>。事件记账失败不应该让主业务（派单、送达）失败，
     * 那是本末倒置。监控系统里这叫"日志是尽力而为的"。
     */
    @Transactional
    public EventLog record(SkyEvent event) {
        try {
            EventLog entity = EventLog.builder()
                    .runId(event.runId())
                    .taskId(event.taskId())
                    .orderId(event.orderId())
                    .droneId(event.droneId())
                    .eventType(event.eventType())
                    .severity(event.severity() == null ? EventSeverity.INFO : event.severity())
                    .message(trim(event.message(), 500))
                    .payload(trim(event.payload(), 8000))
                    .simTimeS(event.simTimeS())
                    .ts(event.ts())
                    .build();
            EventLog saved = eventLogRepository.save(entity);
            eventPublisher.publishEvent(event);
            return saved;
        } catch (RuntimeException e) {
            log.warn("事件记录失败（不影响主流程）: type={}, message={}, 原因={}",
                    event.eventType(), event.message(), e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 常用事件的语义化封装：让业务代码读起来像在写需求文档
    // ------------------------------------------------------------------

    public void orderCreated(DeliveryOrder order) {
        record(SkyEvent.of("ORDER_CREATED", EventSeverity.INFO,
                        "订单 " + order.getOrderNo() + " 已创建，货物「" + order.getCargoName()
                                + "」" + order.getWeightKg() + "kg，优先级 " + order.getPriority())
                .withOrder(order.getId()));
    }

    public void orderStatusChanged(DeliveryOrder order, String from, String to) {
        record(SkyEvent.of("ORDER_STATUS_CHANGED", EventSeverity.INFO,
                        "订单 " + order.getOrderNo() + " 状态 " + from + " → " + to)
                .withOrder(order.getId())
                .withPayload("{\"from\":\"" + from + "\",\"to\":\"" + to + "\"}"));
    }

    public void taskAssigned(DispatchTask task, Drone drone, String strategy, String reason) {
        record(SkyEvent.of("TASK_ASSIGNED", EventSeverity.INFO,
                        "任务 " + task.getTaskNo() + " 已派给 " + drone.getCode()
                                + "（策略 " + strategy + "，" + reason + "）")
                .withTask(task.getId())
                .withOrder(task.getOrderId())
                .withDrone(drone.getId()));
    }

    public void taskStatusChanged(DispatchTask task, String to, BigDecimal simTimeS, Long runId) {
        record(SkyEvent.of("TASK_STATUS_CHANGED", EventSeverity.INFO,
                        "任务 " + task.getTaskNo() + " 状态 → " + to)
                .withTask(task.getId())
                .withOrder(task.getOrderId())
                .withDrone(task.getDroneId())
                .withRun(runId, simTimeS));
    }

    public void taskCompleted(DispatchTask task, BigDecimal simTimeS, Long runId) {
        record(SkyEvent.of("TASK_COMPLETED", EventSeverity.INFO,
                        "任务 " + task.getTaskNo() + " 已完成，航程 "
                                + task.getActualDistanceM() + "m，耗时 " + task.getDurationS() + "s")
                .withTask(task.getId())
                .withOrder(task.getOrderId())
                .withDrone(task.getDroneId())
                .withRun(runId, simTimeS));
    }

    public void taskFailed(DispatchTask task, String reason, BigDecimal simTimeS, Long runId) {
        record(SkyEvent.of("TASK_FAILED", EventSeverity.ERROR,
                        "任务 " + task.getTaskNo() + " 失败：" + reason)
                .withTask(task.getId())
                .withOrder(task.getOrderId())
                .withDrone(task.getDroneId())
                .withRun(runId, simTimeS));
    }

    public void droneBatteryLow(Drone drone, BigDecimal simTimeS, Long runId) {
        record(SkyEvent.of("DRONE_BATTERY_LOW", EventSeverity.WARN,
                        "无人机 " + drone.getCode() + " 电量低至 " + drone.getBatteryPct() + "%，需要返航")
                .withDrone(drone.getId())
                .withRun(runId, simTimeS));
    }

    public void conflictDetected(ConflictEvent conflict, BigDecimal simTimeS, Long runId) {
        record(SkyEvent.of("CONFLICT_DETECTED", toEventSeverity(conflict.getSeverity()),
                        "检测到冲突[" + conflict.getConflictType() + "]："
                                + trim(conflict.getDetail(), 300))
                .withDrone(conflict.getDroneAId())
                .withTask(conflict.getTaskId())
                .withRun(runId, simTimeS)
                .withPayload(conflict.getDetail()));
    }

    /** 冲突严重度 → 日志严重度。冲突越严重，回放时间线上越显眼。 */
    private EventSeverity toEventSeverity(com.skydispatch.domain.enums.ConflictSeverity severity) {
        if (severity == null) {
            return EventSeverity.WARN;
        }
        return switch (severity) {
            case CRITICAL -> EventSeverity.ERROR;
            case HIGH, MEDIUM -> EventSeverity.WARN;
            case LOW -> EventSeverity.INFO;
        };
    }

    public void simulation(String eventType, String message, Long runId, BigDecimal simTimeS) {
        record(SkyEvent.of(eventType, EventSeverity.INFO, message).withRun(runId, simTimeS));
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** 分页查询事件（可选按任务过滤）。 */
    @Transactional(readOnly = true)
    public CommonDtos.PageResponse<TaskDtos.EventResponse> list(Long taskId, Pageable pageable) {
        var page = taskId == null
                ? eventLogRepository.findAllByOrderByTsDesc(pageable)
                : eventLogRepository.findByTaskId(taskId, pageable);
        return CommonDtos.PageResponse.of(page, TaskDtos.EventResponse::from);
    }

    /** 某个任务的完整时间线（回放的输入）。 */
    @Transactional(readOnly = true)
    public List<TaskDtos.EventResponse> timeline(Long taskId) {
        return eventLogRepository.findByTaskIdOrderByTsAsc(taskId).stream()
                .map(TaskDtos.EventResponse::from)
                .toList();
    }

    /** 全量导出用。 */
    @Transactional(readOnly = true)
    public List<EventLog> recentEvents() {
        return eventLogRepository.findTop200ByOrderByTsDesc();
    }

    private String trim(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
