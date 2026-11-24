package com.skydispatch.service;

import com.skydispatch.common.BusinessException;
import com.skydispatch.domain.entity.DispatchTask;
import com.skydispatch.domain.entity.Drone;
import com.skydispatch.domain.entity.DeliveryOrder;
import com.skydispatch.domain.enums.DroneStatus;
import com.skydispatch.domain.enums.OrderStatus;
import com.skydispatch.domain.enums.TaskStatus;
import com.skydispatch.dto.CommonDtos;
import com.skydispatch.dto.TaskDtos;
import com.skydispatch.repository.DispatchTaskRepository;
import com.skydispatch.repository.DroneRepository;
import com.skydispatch.repository.FlightRouteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务服务：任务查询、航路点查询、中止任务。
 *
 * <p>任务（{@code dispatch_task}）是"订单 × 无人机 × 航路"的执行实例。
 * 一个订单理论上可以有多条任务（第一次失败、第二次重派），
 * 所以查询订单当前执行情况时要用"最新的一条"。
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final DispatchTaskRepository taskRepository;
    private final DroneRepository droneRepository;
    private final FlightRouteRepository routeRepository;
    private final OrderService orderService;
    private final RouteService routeService;
    private final EventService eventService;

    public TaskService(DispatchTaskRepository taskRepository,
                       DroneRepository droneRepository,
                       FlightRouteRepository routeRepository,
                       OrderService orderService,
                       RouteService routeService,
                       EventService eventService) {
        this.taskRepository = taskRepository;
        this.droneRepository = droneRepository;
        this.routeRepository = routeRepository;
        this.orderService = orderService;
        this.routeService = routeService;
        this.eventService = eventService;
    }

    /** 分页查询任务，可按状态或无人机过滤。 */
    @Transactional(readOnly = true)
    public CommonDtos.PageResponse<TaskDtos.Response> list(TaskStatus status, Long droneId, Pageable pageable) {
        return list(status, null, droneId, pageable);
    }

    /**
     * 分页查询任务（支持按状态集合过滤）。
     *
     * <p>任务指挥台的「飞行阶段」一次代表多个状态（例如"巡航投送" =
     * CRUISING + DELIVERING），走后端筛选才能保证分页总数正确。
     * statuses 非空时优先于单个 status；droneId 与两者都可以叠加。
     */
    @Transactional(readOnly = true)
    public CommonDtos.PageResponse<TaskDtos.Response> list(TaskStatus status, List<TaskStatus> statuses,
                                                           Long droneId, Pageable pageable) {
        boolean hasStatuses = statuses != null && !statuses.isEmpty();
        Page<DispatchTask> page;
        if (droneId != null && hasStatuses) {
            page = taskRepository.findByDroneIdAndStatusIn(droneId, statuses, pageable);
        } else if (droneId != null && status != null) {
            page = taskRepository.findByDroneIdAndStatus(droneId, status, pageable);
        } else if (droneId != null) {
            page = taskRepository.findByDroneId(droneId, pageable);
        } else if (hasStatuses) {
            page = taskRepository.findByStatusIn(statuses, pageable);
        } else if (status != null) {
            page = taskRepository.findByStatus(status, pageable);
        } else {
            page = taskRepository.findAll(pageable);
        }
        List<TaskDtos.Response> content = page.getContent().stream()
                .map(this::toResponse)
                .toList();
        return CommonDtos.PageResponse.of(content, page);
    }

    /**
     * 任务态势统计：各状态任务数 + 活动任务数。
     *
     * <p>返回 map 里补齐全部枚举（缺失状态补 0），前端不需要为"某个阶段是 0"
     * 做额外兜底；活动任务的口径与 {@link TaskStatus#isActive()} 一致。
     */
    @Transactional(readOnly = true)
    public TaskDtos.Stats stats() {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (TaskStatus status : TaskStatus.values()) {
            byStatus.put(status.name(), 0L);
        }
        long total = 0;
        for (Object[] row : taskRepository.countGroupByStatus()) {
            TaskStatus status = (TaskStatus) row[0];
            long count = ((Number) row[1]).longValue();
            byStatus.put(status.name(), count);
            total += count;
        }
        long active = 0;
        for (TaskStatus status : TaskStatus.values()) {
            if (status.isActive()) {
                active += byStatus.getOrDefault(status.name(), 0L);
            }
        }
        return new TaskDtos.Stats(total, active, byStatus);
    }

    /** 任务详情。 */
    @Transactional(readOnly = true)
    public TaskDtos.Response get(Long taskId) {
        return toResponse(require(taskId));
    }

    /** 航路点列表（前端画航迹、回放逐点飞行都要用）。 */
    @Transactional(readOnly = true)
    public List<TaskDtos.WaypointResponse> waypoints(Long taskId) {
        DispatchTask task = require(taskId);
        if (task.getRouteId() == null) {
            return List.of();
        }
        return routeService.waypoints(task.getRouteId()).stream()
                .map(TaskDtos.WaypointResponse::from)
                .toList();
    }

    /** 任务事件时间线。 */
    @Transactional(readOnly = true)
    public List<TaskDtos.EventResponse> events(Long taskId) {
        return eventService.timeline(taskId);
    }

    /**
     * 中止任务。
     *
     * <p>分两种情况，这个区分很重要：
     * <ul>
     *   <li>还没起飞（{@code ASSIGNED}）：订单退回 {@code QUEUED}，可以重新派单。</li>
     *   <li>已经在飞（{@code TAKEOFF/CRUISING/DELIVERING/RETURNING}）：订单判定为
     *       {@code FAILED}。空中改派在真实运营里是不允许的（安全第一），
     *       必须等飞机落地后人工处理。</li>
     * </ul>
     */
    @Transactional
    public TaskDtos.Response abort(Long taskId, String reason) {
        // 与仿真推进串行化：先锁任务行，避免"人工中止"和"本拍推进"互相覆盖状态
        DispatchTask task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> BusinessException.notFound("任务 id=" + taskId));
        if (task.getStatus().isTerminal()) {
            throw BusinessException.illegalState("任务 " + task.getTaskNo() + " 已结束（" + task.getStatus() + "），无法中止");
        }
        boolean airborne = task.getStatus() != TaskStatus.ASSIGNED && task.getStatus() != TaskStatus.PENDING;
        TaskStatus from = task.getStatus();

        task.setStatus(TaskStatus.ABORTED);
        task.setFailReason(reason == null ? "人工中止" : reason);
        task.setFinishedAt(Instant.now());
        if (task.getStartedAt() != null) {
            task.setDurationS(Math.max(1,
                    (int) java.time.Duration.between(task.getStartedAt(), task.getFinishedAt()).toSeconds()));
        }
        taskRepository.save(task);

        // 无人机回到待命（仿真里当作"已就地/已返航"，真实系统会下发给飞控指令）
        Drone drone = droneRepository.findByIdForUpdate(task.getDroneId()).orElse(null);
        if (drone != null) {
            drone.setStatus(DroneStatus.IDLE);
            drone.setSpeedMps(java.math.BigDecimal.ZERO);
            drone.setCurrentTaskId(null);
            droneRepository.save(drone);
        }

        DeliveryOrder order = orderService.requireForUpdate(task.getOrderId());
        order.setFailReason(task.getFailReason());
        orderService.markStatus(order, airborne ? OrderStatus.FAILED : OrderStatus.QUEUED);
        orderService.applyAssignment(order, null);

        eventService.taskStatusChanged(task, from.name() + " → ABORTED", null, null);
        log.info("中止任务: taskNo={}, 原状态={}, 原因={}", task.getTaskNo(), from, task.getFailReason());

        return TaskDtos.Response.from(task, order.getOrderNo(), drone == null ? null : drone.getCode());
    }

    /** 取任务，不存在抛 404。 */
    @Transactional(readOnly = true)
    public DispatchTask require(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> BusinessException.notFound("任务 id=" + taskId));
    }

    /** 实体 → DTO，补上订单号与机身编号这两个便于阅读的冗余字段。 */
    public TaskDtos.Response toResponse(DispatchTask task) {
        String orderNo = orderService.findOrderNo(task.getOrderId());
        String droneCode = droneRepository.findById(task.getDroneId()).map(Drone::getCode).orElse(null);
        return TaskDtos.Response.from(task, orderNo, droneCode);
    }

    /** 任务对应的航路 id。 */
    @Transactional(readOnly = true)
    public Long routeIdOf(Long taskId) {
        return require(taskId).getRouteId();
    }

    /** 供报表：按状态取任务。 */
    @Transactional(readOnly = true)
    public List<DispatchTask> byStatus(List<TaskStatus> statuses) {
        return taskRepository.findByStatusIn(statuses);
    }

    /** 供报表：全部任务。 */
    @Transactional(readOnly = true)
    public List<DispatchTask> all() {
        return taskRepository.findAll();
    }

    /** 航路是否存在（防止脏数据）。 */
    @Transactional(readOnly = true)
    public boolean routeExists(Long routeId) {
        return routeId != null && routeRepository.existsById(routeId);
    }
}
