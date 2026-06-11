package cn.bugstack.competitoragent.controller;

import cn.bugstack.competitoragent.common.ApiResponse;
import cn.bugstack.competitoragent.model.dto.ReportResponse;
import cn.bugstack.competitoragent.model.dto.ReportResponse.EvidenceInfo;
import cn.bugstack.competitoragent.report.EvidenceQueryService;
import cn.bugstack.competitoragent.report.application.ReportQueryFacade;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Tag(name = "Report", description = "View and export competitor analysis reports")
@RestController
@RequestMapping("/api/report")
@RequiredArgsConstructor
public class ReportController {

    private final ReportQueryFacade reportQueryFacade;
    private final EvidenceQueryService evidenceQueryService;

    /**
     * 报告详情接口直接返回交付中心所需的结构化载荷。
     * 前端无需再二次拼接修订计划、问题诊断和证据追溯主链路。
     */
    @GetMapping("/{taskId}")
    @Operation(summary = "Get structured report delivery payload with diagnosis, revision plan, and evidence tracing")
    public ApiResponse<ReportResponse> getReport(
            @Parameter(description = "Task ID", example = "1")
            @PathVariable Long taskId) {
        return ApiResponse.success(reportQueryFacade.getReport(taskId));
    }

    @GetMapping("/{taskId}/evidences")
    @Operation(summary = "List report evidences with source filters")
    public ApiResponse<List<EvidenceInfo>> listEvidences(
            @Parameter(description = "Task ID", example = "1")
            @PathVariable Long taskId,
            @RequestParam(required = false) String competitorName,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String discoveryMethod) {
        return ApiResponse.success(evidenceQueryService.listEvidences(
                taskId,
                competitorName,
                sourceType,
                discoveryMethod
        ));
    }

    @GetMapping("/{taskId}/export")
    @Operation(summary = "Export report as Markdown")
    public ResponseEntity<byte[]> exportMarkdown(
            @Parameter(description = "Task ID", example = "1")
            @PathVariable Long taskId) {
        byte[] markdown = reportQueryFacade.exportMarkdown(taskId);
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
        byte[] html = reportQueryFacade.exportHtml(taskId);
        String filename = URLEncoder.encode("competitor-analysis-report.html", StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + filename)
                .contentType(MediaType.parseMediaType("text/html; charset=UTF-8"))
                .body(html);
    }
}
