package com.skydispatch.web;

import com.skydispatch.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * 报表导出接口（CSV）。
 *
 * <p>这几个接口<b>不套 {@code ApiResponse}</b>，而是直接返回文件字节流 —— 这是特例。
 * 判断标准很简单：给程序调用的接口用统一包装，给"人下载文件"用的接口返回原生格式。
 *
 * <p>文件名放在 {@code Content-Disposition} 里，浏览器点击后直接下载。
 * CSV 内容自带 UTF-8 BOM，Excel 打开不会中文乱码。
 */
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "报表导出", description = "遥测轨迹、事件时间线、任务清单的 CSV 导出")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/tasks/{taskId}/telemetry.csv")
    @Operation(summary = "导出任务遥测 CSV", description = "逐点经纬度、电量、进度，可直接用于画飞行轨迹图")
    public ResponseEntity<byte[]> telemetryCsv(@PathVariable Long taskId) {
        return csv(reportService.telemetryCsv(taskId), "telemetry-task-" + taskId + ".csv");
    }

    @GetMapping("/events.csv")
    @Operation(summary = "导出事件时间线 CSV", description = "传 taskId 只导该任务，不传则导最近 200 条")
    public ResponseEntity<byte[]> eventsCsv(@RequestParam(required = false) Long taskId) {
        return csv(reportService.eventsCsv(taskId), "events" + (taskId == null ? "" : "-task-" + taskId) + ".csv");
    }

    @GetMapping("/tasks.csv")
    @Operation(summary = "导出任务清单 CSV", description = "含航程、能耗、电量、耗时，适合做策略效果对比")
    public ResponseEntity<byte[]> tasksCsv() {
        return csv(reportService.tasksCsv(), "tasks.csv");
    }

    private ResponseEntity<byte[]> csv(String content, String filename) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(bytes);
    }
}
