package com.skydispatch.service;

import com.skydispatch.common.BusinessException;
import com.skydispatch.domain.entity.ConflictEvent;
import com.skydispatch.domain.entity.DispatchTask;
import com.skydispatch.domain.entity.Drone;
import com.skydispatch.domain.enums.ConflictSeverity;
import com.skydispatch.domain.enums.ConflictStatus;
import com.skydispatch.domain.enums.ConflictType;
import com.skydispatch.domain.enums.TaskStatus;
import com.skydispatch.dto.DashboardDtos;
import com.skydispatch.dto.algorithm.AlgorithmDtos;
import com.skydispatch.integration.AlgorithmServiceClient;
import com.skydispatch.repository.ConflictEventRepository;
import com.skydispatch.repository.DispatchTaskRepository;
import com.skydispatch.repository.DroneRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 冲突检测：调用 Python 算法服务，把结果落库并去重。
 *
 * <p>冲突的三种类型（详见 docs/03-api-contract.md 第 4.3 节）：
 * <ul>
 *   <li>{@code DRONE_DRONE}：两机预测轨迹的最小间距低于安全阈值。低空空域是二维的，
 *       水平间距和垂直（高度层）间距必须同时判断，只满足一个都不算安全。</li>
 *   <li>{@code DRONE_ZONE}：无人机进入禁飞/限飞区。</li>
 *   <li>{@code BATTERY_RISK}：电量过低。</li>
 * </ul>
 *
 * <p><b>去重是本类的关键设计</b>：仿真每 5 拍检查一次，如果每次都把同一对冲突写库，
 * 10 分钟就能产生上万条重复记录，看板会被淹没。这里用内存里的
 * "冲突指纹 + 时间戳"做窗口去重（默认 60 秒内的同指纹只记一次）。
 * 生产环境应当把指纹放到 Redis，做成多实例共享。
 */
@Service
public class ConflictService {

    private static final Logger log = LoggerFactory.getLogger(ConflictService.class);

    /** 参与冲突检测的任务状态。 */
    private static final List<TaskStatus> ACTIVE_STATUSES = List.of(
            TaskStatus.ASSIGNED, TaskStatus.TAKEOFF, TaskStatus.CRUISING,
            TaskStatus.DELIVERING, TaskStatus.RETURNING);

    /** 同一冲突指纹在多久内只记录一次。 */
    private static final long DEDUP_WINDOW_MS = 60_000;

    /** 安全阈值（MVP 固定值，V2 应按空域类型分级配置）。 */
    private static final double MIN_HORIZONTAL_SEPARATION_M = 50.0;
    private static final double MIN_VERTICAL_SEPARATION_M = 15.0;
    private static final double LOOKAHEAD_SECONDS = 60.0;

    private final AlgorithmServiceClient algorithmClient;
    private final AirspaceService airspaceService;
    private final DroneRepository droneRepository;
    private final DispatchTaskRepository taskRepository;
    private final ConflictEventRepository conflictRepository;
    private final EventService eventService;

    /** 冲突指纹 → 上次上报时间。 */
    private final Map<String, Long> recentFingerprints = new ConcurrentHashMap<>();

    public ConflictService(AlgorithmServiceClient algorithmClient,
                           AirspaceService airspaceService,
                           DroneRepository droneRepository,
                           DispatchTaskRepository taskRepository,
                           ConflictEventRepository conflictRepository,
                           EventService eventService) {
        this.algorithmClient = algorithmClient;
        this.airspaceService = airspaceService;
        this.droneRepository = droneRepository;
        this.taskRepository = taskRepository;
        this.conflictRepository = conflictRepository;
        this.eventService = eventService;
    }

    /**
     * 立即执行一次冲突检测。
     *
     * @return 本次<b>新发现</b>的冲突（已去重、已落库）；算法服务不可用时返回空列表
     */
    @Transactional
    public List<ConflictEvent> checkNow(Long runId, BigDecimal simTimeS) {
        List<DispatchTask> activeTasks = taskRepository.findByStatusIn(ACTIVE_STATUSES);
        if (activeTasks.isEmpty()) {
            return List.of();
        }

        // 只检测"有活动任务"的无人机：空闲的飞机停在机巢，不构成空中冲突
        List<AlgorithmDtos.DroneState> states = new ArrayList<>();
        Map<Long, Long> taskByDrone = new ConcurrentHashMap<>();
        for (DispatchTask task : activeTasks) {
            if (taskByDrone.containsKey(task.getDroneId())) {
                continue;
            }
            Drone drone = droneRepository.findById(task.getDroneId()).orElse(null);
            if (drone == null || drone.getCurrentLat() == null || drone.getCurrentLon() == null) {
                continue;
            }
            taskByDrone.put(drone.getId(), task.getId());
            states.add(new AlgorithmDtos.DroneState(
                    drone.getId(), drone.getCode(),
                    drone.getCurrentLat(), drone.getCurrentLon(),
                    drone.getAltitudeM() == null ? 0 : drone.getAltitudeM().doubleValue(),
                    drone.getSpeedMps() == null ? 0 : drone.getSpeedMps().doubleValue(),
                    drone.getHeadingDeg() == null ? 0 : drone.getHeadingDeg().doubleValue(),
                    drone.getBatteryPct() == null ? 0 : drone.getBatteryPct().doubleValue()));
        }
        if (states.isEmpty()) {
            return List.of();
        }

        AlgorithmDtos.ConflictCheckRequest request = new AlgorithmDtos.ConflictCheckRequest(
                Instant.now(), LOOKAHEAD_SECONDS,
                MIN_HORIZONTAL_SEPARATION_M, MIN_VERTICAL_SEPARATION_M,
                states, airspaceService.activeObstacleSpecs());

        AlgorithmDtos.ConflictCheckResult result = algorithmClient.checkConflicts(request).orElse(null);
        if (result == null || result.conflicts() == null) {
            return List.of();
        }

        List<ConflictEvent> fresh = new ArrayList<>();
        for (AlgorithmDtos.ConflictItem item : result.conflicts()) {
            // TTC≈0 表示"当前已经发生"而不是"即将发生"，没有预警价值；
            // 这类记录不再落库和推送，避免前端反复出现 "预计 0.0s 后触发"。
            if (item.timeToConflictS() != null && item.timeToConflictS() <= 0.5) {
                log.debug("忽略 0 秒冲突提醒: {}", item.message());
                continue;
            }
            String fingerprint = fingerprint(item);
            if (isDuplicate(fingerprint)) {
                continue;
            }
            ConflictEvent event = toEntity(item, runId, taskByDrone);
            ConflictEvent saved = conflictRepository.save(event);
            eventService.conflictDetected(saved, simTimeS, runId);
            fresh.add(saved);
            log.warn("新冲突: type={}, severity={}, {}", saved.getConflictType(), saved.getSeverity(), saved.getDetail());
        }
        return fresh;
    }

    /** 未处理的冲突列表（封顶 200 条，避免长期运行后一次响应拖回上万条记录）。 */
    @Transactional(readOnly = true)
    public List<DashboardDtos.ConflictView> listByStatus(ConflictStatus status) {
        List<ConflictEvent> events = status == null
                ? conflictRepository.findTop50ByOrderByDetectedAtDesc()
                : conflictRepository.findTop200ByStatusOrderByDetectedAtDesc(status);
        return events.stream().map(e -> DashboardDtos.ConflictView.from(e, e.getDetail())).toList();
    }

    /** 人工标记冲突已处理。 */
    @Transactional
    public DashboardDtos.ConflictView resolve(Long id, String resolution) {
        ConflictEvent event = conflictRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("冲突 id=" + id));
        event.setStatus(ConflictStatus.RESOLVED);
        event.setResolution(resolution);
        event.setResolvedAt(Instant.now());
        return DashboardDtos.ConflictView.from(event, event.getDetail());
    }

    /** 统计未处理冲突数（KPI 用）。 */
    @Transactional(readOnly = true)
    public long openCount() {
        return conflictRepository.countByStatus(ConflictStatus.OPEN);
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 冲突指纹：同一对飞机（或同一区域）的同一类冲突视为同一条。 */
    private String fingerprint(AlgorithmDtos.ConflictItem item) {
        Long a = item.droneAId();
        Long b = item.droneBId();
        // 机-机冲突与顺序无关，统一按 id 大小排序，避免 (1,2) 与 (2,1) 被当成两条
        String pair = (b == null) ? String.valueOf(a)
                : (a <= b ? a + "-" + b : b + "-" + a);
        return item.conflictType() + ":" + pair + ":" + item.zoneId();
    }

    private boolean isDuplicate(String fingerprint) {
        long now = System.currentTimeMillis();
        // 顺手清理过期条目，避免内存无限增长
        recentFingerprints.entrySet().removeIf(e -> now - e.getValue() > DEDUP_WINDOW_MS);
        Long last = recentFingerprints.get(fingerprint);
        if (last != null && now - last <= DEDUP_WINDOW_MS) {
            return true;
        }
        recentFingerprints.put(fingerprint, now);
        return false;
    }

    private ConflictEvent toEntity(AlgorithmDtos.ConflictItem item, Long runId, Map<Long, Long> taskByDrone) {
        return ConflictEvent.builder()
                .runId(runId)
                .taskId(item.droneAId() == null ? null : taskByDrone.get(item.droneAId()))
                .droneAId(item.droneAId())
                .droneBId(item.droneBId())
                .zoneId(resolveZoneId(item.zoneId()))
                .conflictType(parseType(item.conflictType()))
                .severity(parseSeverity(item.severity()))
                .status(ConflictStatus.OPEN)
                .horizontalDistanceM(scale(item.horizontalDistanceM()))
                .verticalDistanceM(scale(item.verticalDistanceM()))
                .closingSpeedMps(scale(item.closingSpeedMps()))
                .timeToConflictS(scale(item.timeToConflictS()))
                .lat(item.lat())
                .lon(item.lon())
                .detectedAt(Instant.now())
                .detail(item.message())
                .build();
    }

    /**
     * 算法服务返回的 zoneId 是空域编码（如 Z-NF-001），这里转成数据库主键。
     * 转不了就置空——冲突记录本身仍然有价值，不该因为外键解析失败而丢弃。
     */
    private Long resolveZoneId(String zoneCode) {
        if (zoneCode == null || zoneCode.isBlank()) {
            return null;
        }
        return airspaceService.idByCode(zoneCode).orElse(null);
    }

    private ConflictType parseType(String raw) {
        try {
            return ConflictType.valueOf(raw);
        } catch (RuntimeException e) {
            log.warn("未知冲突类型 {}，按 DRONE_DRONE 处理", raw);
            return ConflictType.DRONE_DRONE;
        }
    }

    private ConflictSeverity parseSeverity(String raw) {
        try {
            return ConflictSeverity.valueOf(raw);
        } catch (RuntimeException e) {
            return ConflictSeverity.MEDIUM;
        }
    }

    private BigDecimal scale(Double value) {
        return value == null ? null : BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }
}
