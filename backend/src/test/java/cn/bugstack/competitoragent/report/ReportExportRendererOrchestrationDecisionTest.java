package cn.bugstack.competitoragent.report;

import cn.bugstack.competitoragent.model.dto.OrchestrationDecisionSummary;
import cn.bugstack.competitoragent.model.dto.ReportResponse;
import cn.bugstack.competitoragent.model.entity.ReportExportRecord;
import cn.bugstack.competitoragent.repository.ReportExportRecordRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportExportRendererOrchestrationDecisionTest {

    private final ReportExportRecordRepository reportExportRecordRepository = mock(ReportExportRecordRepository.class);
    private final ReportService reportService = mock(ReportService.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void shouldRenderOrchestrationDecisionSummaryAcrossFormalExportFormats() throws Exception {
        when(reportService.getReport(42L)).thenReturn(buildReportResponse());
        when(reportExportRecordRepository.findTopByTaskIdOrderByExportVersionDesc(42L)).thenReturn(Optional.empty());
        when(reportExportRecordRepository.save(any())).thenAnswer(invocation -> {
            ReportExportRecord record = invocation.getArgument(0);
            record.setId(101L);
            record.setCreatedAt(LocalDateTime.of(2026, 6, 26, 18, 30));
            record.setUpdatedAt(LocalDateTime.of(2026, 6, 26, 18, 30));
            return record;
        });

        ExportPackageService service = new ExportPackageService(
                reportExportRecordRepository,
                reportService,
                objectMapper
        );

        ReportExportRenderer.RenderedExportPackage markdownPackage = service.createExportPackage(42L, "MARKDOWN");
        String markdown = new String(markdownPackage.content(), StandardCharsets.UTF_8);
        assertTrue(markdown.contains("## 协作决策摘要"));
        assertTrue(markdown.contains("WAIT_FOR_HUMAN"));
        assertTrue(markdown.contains("MISSING_SOURCE"));

        ReportExportRenderer.RenderedExportPackage htmlPackage = service.createExportPackage(42L, "HTML");
        String html = new String(htmlPackage.content(), StandardCharsets.UTF_8);
        assertTrue(html.contains("协作决策摘要"));
        assertTrue(html.contains("WAIT_FOR_HUMAN"));
        assertTrue(html.contains("MISSING_SOURCE"));

        ReportExportRenderer.RenderedExportPackage jsonPackage = service.createExportPackage(42L, "JSON");
        JsonNode jsonNode = objectMapper.readTree(jsonPackage.content());
        assertEquals("WAIT_FOR_HUMAN", jsonNode.path("orchestrationDecision").path("decisionType").asText());
        assertEquals("MISSING_SOURCE", jsonNode.path("orchestrationDecision").path("evidenceState").asText());
        assertEquals("https://docs.example.com/review-gap",
                jsonNode.path("orchestrationDecision").path("sourceUrls").get(0).asText());
    }

    private ReportResponse buildReportResponse() {
        return ReportResponse.builder()
                .id(1L)
                .taskId(42L)
                .title("协作决策导出验证")
                .content("# Report")
                .summary("summary")
                .qualityPassed(false)
                .qualityScore(80)
                .evidenceCount(1)
                .sourceUrls(List.of("https://docs.example.com/review-gap"))
                .orchestrationDecision(OrchestrationDecisionSummary.builder()
                        .decisionId("od-42-review")
                        .taskId(42L)
                        .triggerNodeName("quality_check_final")
                        .decisionType("WAIT_FOR_HUMAN")
                        .actionType("MANUAL_REVIEW")
                        .targetNode("quality_check_final")
                        .reason("终审前仍缺少最终人工确认。")
                        .requiresHumanIntervention(true)
                        .requiresConfirmation(false)
                        .evidenceState("MISSING_SOURCE")
                        .sourceUrls(List.of("https://docs.example.com/review-gap"))
                        .build()
                        .normalized())
                .deliverySummary(ReportResponse.DeliverySummaryInfo.builder()
                        .readyForDelivery(false)
                        .deliveryStatus("BLOCKED")
                        .summary("当前报告暂不允许正式交付。")
                        .primaryIssue("仍需人工确认最终结论。")
                        .recommendedAction("先完成人工确认，再继续导出。")
                        .blockerCount(1)
                        .evidenceGapCount(0)
                        .sourceUrls(List.of("https://docs.example.com/review-gap"))
                        .build())
                .auditSummary(ReportResponse.AuditSummaryInfo.builder()
                        .summary("当前审计链路已记录最新交付阻塞信息。")
                        .searchAuditSummary("collector trace ready")
                        .taskRagAuditSummary("task rag ready")
                        .sourceUrls(List.of("https://docs.example.com/review-gap"))
                        .build())
                .evidenceEntryPoint(ReportResponse.EvidenceEntryPointInfo.builder()
                        .summary("请优先核对终审补证入口。")
                        .title("终审补证入口")
                        .url("https://docs.example.com/review-gap")
                        .sourceType("DOCS")
                        .sourceUrls(List.of("https://docs.example.com/review-gap"))
                        .build())
                .evidences(List.of(new ReportResponse.EvidenceInfo(
                        "E-42",
                        "Review Gap Doc",
                        "https://docs.example.com/review-gap",
                        "snippet",
                        "Notion AI",
                        LocalDateTime.of(2026, 6, 26, 18, 0),
                        "DOCS",
                        "SEARCH",
                        "docs.example.com",
                        "reason",
                        "2026-06-26",
                        0.91,
                        true,
                        "verified",
                        "query",
                        "bing",
                        1,
                        "trace-42",
                        "selection",
                        "SELECTED",
                        List.of("review"),
                        java.util.Map.of())))
                .build();
    }
}
