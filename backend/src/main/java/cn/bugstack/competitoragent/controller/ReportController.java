package cn.bugstack.competitoragent.controller;

import cn.bugstack.competitoragent.common.ApiResponse;
import cn.bugstack.competitoragent.model.dto.ReportResponse;
import cn.bugstack.competitoragent.report.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Tag(name = "Report", description = "View and export competitor analysis reports")
@RestController
@RequestMapping("/api/report")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @GetMapping("/{taskId}")
    @Operation(summary = "Get report detail")
    public ApiResponse<ReportResponse> getReport(
            @Parameter(description = "Task ID", example = "1")
            @PathVariable Long taskId) {
        return ApiResponse.success(reportService.getReport(taskId));
    }

    @GetMapping("/{taskId}/export")
    @Operation(summary = "Export report as Markdown")
    public ResponseEntity<byte[]> exportMarkdown(
            @Parameter(description = "Task ID", example = "1")
            @PathVariable Long taskId) {
        byte[] markdown = reportService.exportMarkdown(taskId);
        String filename = URLEncoder.encode("competitor-analysis-report.md", StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + filename)
                .contentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"))
                .body(markdown);
    }

    @GetMapping("/{taskId}/export/html")
    @Operation(summary = "Export report as HTML")
    public ResponseEntity<byte[]> exportHtml(
            @Parameter(description = "Task ID", example = "1")
            @PathVariable Long taskId) {
        byte[] html = reportService.exportHtml(taskId);
        String filename = URLEncoder.encode("competitor-analysis-report.html", StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + filename)
                .contentType(MediaType.parseMediaType("text/html; charset=UTF-8"))
                .body(html);
    }
}
