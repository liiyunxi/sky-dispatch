package com.skydispatch.service;

import com.skydispatch.domain.entity.DispatchTask;
import com.skydispatch.domain.entity.Drone;
import com.skydispatch.domain.entity.EventLog;
import com.skydispatch.domain.entity.Telemetry;
import com.skydispatch.repository.DroneRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 报表导出服务：把遥测、事件、任务清单导成 CSV。
 *
 * <p>为什么先做 CSV 而不是 Excel？因为 CSV 是"最小可用交付物"：
 * 任何工具（Excel、pandas、Power BI）都能直接打开，实现成本几乎为零。
 * 等业务方提出"要合并单元格、要图表"时再上 POI/EasyExcel。
 *
 * <p>两点工程细节：
 * <ol>
 *   <li>文件开头加 UTF-8 BOM（{@code \uFEFF}），否则 Excel 打开中文会乱码，
 *       这是国内项目最常见的踩坑点之一。</li>
 *   <li>所有字段都做 CSV 转义（含逗号、引号、换行时用双引号包裹），
 *       否则客户名称里一个逗号就会把整张表的列错位。</li>
 * </ol>
 */
@Service
public class ReportService {

    /** UTF-8 BOM，让 Excel 正确识别中文编码。 */
    private static final String BOM = "\uFEFF";

    private final TelemetryService telemetryService;
    private final EventService eventService;
    private final TaskService taskService;
    private final OrderService orderService;
    private final DroneRepository droneRepository;

    public ReportService(TelemetryService telemetryService,
                         EventService eventService,
                         TaskService taskService,
                         OrderService orderService,
                         DroneRepository droneRepository) {
        this.telemetryService = telemetryService;
        this.eventService = eventService;
        this.taskService = taskService;
        this.orderService = orderService;
        this.droneRepository = droneRepository;
    }

    /** 某任务的遥测轨迹 CSV（回放与论文画图都用它）。 */
    @Transactional(readOnly = true)
    public String telemetryCsv(Long taskId) {
        List<Telemetry> rows = telemetryService.byTask(taskId);
        StringBuilder sb = new StringBuilder(BOM);
        sb.append("id,taskId,droneId,ts,simTimeS,stepIndex,lat,lon,altitudeM,speedMps,headingDeg,batteryPct,progressPct\n");
        for (Telemetry t : rows) {
            sb.append(t.getId()).append(',')
                    .append(nullSafe(t.getTaskId())).append(',')
                    .append(nullSafe(t.getDroneId())).append(',')
                    .append(nullSafe(t.getTs())).append(',')
                    .append(nullSafe(t.getSimTimeS())).append(',')
                    .append(nullSafe(t.getStepIndex())).append(',')
                    .append(nullSafe(t.getLat())).append(',')
                    .append(nullSafe(t.getLon())).append(',')
                    .append(nullSafe(t.getAltitudeM())).append(',')
                    .append(nullSafe(t.getSpeedMps())).append(',')
                    .append(nullSafe(t.getHeadingDeg())).append(',')
                    .append(nullSafe(t.getBatteryPct())).append(',')
                    .append(nullSafe(t.getProgressPct())).append('\n');
        }
        return sb.toString();
    }

    /** 事件时间线 CSV。传 taskId 则只导该任务。 */
    @Transactional(readOnly = true)
    public String eventsCsv(Long taskId) {
        List<EventLog> rows = taskId == null
                ? eventService.recentEvents()
                : eventService.timeline(taskId).stream().map(this::toEntity).toList();
        StringBuilder sb = new StringBuilder(BOM);
        sb.append("id,ts,simTimeS,taskId,orderId,droneId,eventType,severity,message\n");
        for (EventLog e : rows) {
            sb.append(nullSafe(e.getId())).append(',')
                    .append(nullSafe(e.getTs())).append(',')
                    .append(nullSafe(e.getSimTimeS())).append(',')
                    .append(nullSafe(e.getTaskId())).append(',')
                    .append(nullSafe(e.getOrderId())).append(',')
                    .append(nullSafe(e.getDroneId())).append(',')
                    .append(escape(e.getEventType())).append(',')
                    .append(nullSafe(e.getSeverity())).append(',')
                    .append(escape(e.getMessage())).append('\n');
        }
        return sb.toString();
    }

    /** 任务清单 CSV（含订单号、机身编号、耗时、能耗）。 */
    @Transactional(readOnly = true)
    public String tasksCsv() {
        List<DispatchTask> tasks = taskService.all();
        Map<Long, String> droneCodes = droneRepository.findAll().stream()
                .collect(Collectors.toMap(Drone::getId, Drone::getCode, (a, b) -> a));
        StringBuilder sb = new StringBuilder(BOM);
        sb.append("id,taskNo,orderNo,droneCode,strategy,status,progressPct,")
                .append("plannedDistanceM,actualDistanceM,plannedEnergyWh,consumedEnergyWh,")
                .append("batteryStartPct,batteryEndPct,assignedAt,startedAt,finishedAt,durationS,failReason\n");
        for (DispatchTask t : tasks) {
            sb.append(nullSafe(t.getId())).append(',')
                    .append(escape(t.getTaskNo())).append(',')
                    .append(escape(orderService.findOrderNo(t.getOrderId()))).append(',')
                    .append(escape(droneCodes.get(t.getDroneId()))).append(',')
                    .append(escape(t.getStrategy())).append(',')
                    .append(nullSafe(t.getStatus())).append(',')
                    .append(nullSafe(t.getProgressPct())).append(',')
                    .append(nullSafe(t.getPlannedDistanceM())).append(',')
                    .append(nullSafe(t.getActualDistanceM())).append(',')
                    .append(nullSafe(t.getPlannedEnergyWh())).append(',')
                    .append(nullSafe(t.getConsumedEnergyWh())).append(',')
                    .append(nullSafe(t.getBatteryStartPct())).append(',')
                    .append(nullSafe(t.getBatteryEndPct())).append(',')
                    .append(nullSafe(t.getAssignedAt())).append(',')
                    .append(nullSafe(t.getStartedAt())).append(',')
                    .append(nullSafe(t.getFinishedAt())).append(',')
                    .append(nullSafe(t.getDurationS())).append(',')
                    .append(escape(t.getFailReason())).append('\n');
        }
        return sb.toString();
    }

    /** 事件 DTO 反查实体（导出需要原始字段）。 */
    private EventLog toEntity(com.skydispatch.dto.TaskDtos.EventResponse dto) {
        return EventLog.builder()
                .id(dto.id()).taskId(dto.taskId()).orderId(dto.orderId()).droneId(dto.droneId())
                .eventType(dto.eventType()).severity(dto.severity()).message(dto.message())
                .payload(dto.payload()).simTimeS(dto.simTimeS()).ts(dto.ts())
                .build();
    }

    private String nullSafe(Object value) {
        return value == null ? "" : value.toString();
    }

    /** CSV 字段转义：含逗号/引号/换行时用双引号包裹，内部引号翻倍。 */
    private String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        boolean needQuote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needQuote) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
