package cn.bugstack.competitoragent.report;

import cn.bugstack.competitoragent.model.dto.OrchestrationDecisionAuditSummary;
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
        assertTrue(markdown.contains("LLM_PRIMARY"));
        assertTrue(markdown.contains("RULE_FALLBACK"));
        assertTrue(markdown.contains("orch-export-trace"));
        assertTrue(markdown.contains("LEGACY_RULE_SET"));
        assertTrue(markdown.contains("INVALID_DECISION_ACTION_PAIR"));
        assertTrue(markdown.contains("CONFIRMATION_REQUIRED"));
        assertTrue(markdown.contains("MARK_WAITING_INTERVENTION"));
        assertTrue(markdown.contains("ORCHESTRATOR_DECISION"));
        assertTrue(markdown.contains("PARSE_ERROR"));
        assertTrue(markdown.contains("https://docs.example.com/checkpoint"));

        ReportExportRenderer.RenderedExportPackage htmlPackage = service.createExportPackage(42L, "HTML");
        String html = new String(htmlPackage.content(), StandardCharsets.UTF_8);
        assertTrue(html.contains("协作决策摘要"));
        assertTrue(html.contains("WAIT_FOR_HUMAN"));
        assertTrue(html.contains("MISSING_SOURCE"));
        assertTrue(html.contains("LLM_PRIMARY"));
        assertTrue(html.contains("RULE_FALLBACK"));
        assertTrue(html.contains("orch-export-trace"));
        assertTrue(html.contains("CONFIRMATION_REQUIRED"));
        assertTrue(html.contains("&lt;script&gt;blocked&lt;/script&gt;"));
        assertTrue(!html.contains("<script>blocked</script>"));

        ReportExportRenderer.RenderedExportPackage jsonPackage = service.createExportPackage(42L, "JSON");
        JsonNode jsonNode = objectMapper.readTree(jsonPackage.content());
        assertEquals("WAIT_FOR_HUMAN", jsonNode.path("orchestrationDecision").path("decisionType").asText());
        assertEquals("MISSING_SOURCE", jsonNode.path("orchestrationDecision").path("evidenceState").asText());
        assertEquals("RULE_FALLBACK",
                jsonNode.path("orchestrationDecision").path("decisionOrigin").asText());
        assertEquals("LEGACY_RULE_SET",
                jsonNode.path("orchestrationDecision").path("decisionContract").asText());
        assertEquals("deepseek-chat",
                jsonNode.path("orchestrationDecision").path("modelName").asText());
        assertEquals("orch-export-trace",
                jsonNode.path("orchestrationDecision").path("aiAuditTraceId").asText());
        assertEquals("CONFIRMATION_REQUIRED",
                jsonNode.path("orchestrationDecision").path("runtimeStatus").asText());
        assertEquals("MARK_WAITING_INTERVENTION",
                jsonNode.path("orchestrationDecision").path("mutationType").asText());
        assertEquals("https://docs.example.com/review-gap",
                jsonNode.path("orchestrationDecision").path("sourceUrls").get(0).asText());
        assertEquals("LLM_PRIMARY", jsonNode.path("orchestrationDecisionAudit").path("mode").asText());
        assertEquals("PARSE_ERROR",
                jsonNode.path("orchestrationDecisionAudit").path("llmFailure").path("type").asText());
        assertTrue(jsonNode.toString().contains("https://docs.example.com/checkpoint"));
        assertTrue(!jsonNode.path("sourceUrls").toString().contains("https://untrusted.example.net/outside"));
        assertTrue(!jsonNode.has("rawPrompt"));
        assertTrue(!jsonNode.has("rawResponse"));
        assertTrue(!jsonNode.has("nodeTemplates"));
        assertTrue(!jsonNode.has("runtimeCommand"));
        assertTrue(!jsonNode.has("apiKey"));
        assertTrue(!jsonNode.has("exceptionMessage"));
    }

    @Test
    void shouldRenderShadowOnlyAuditWithoutInventingRepresentativeDecision() throws Exception {
        OrchestrationDecisionAuditSummary audit = OrchestrationDecisionAuditSummary.builder()
                .traceSchemaVersion("ORCHESTRATION_TRACE_V2")
                .mode("LLM_SHADOW")
                .shadowExecution(OrchestrationDecisionAuditSummary.ShadowExecutionSummary.builder()
                        .requested(true)
                        .executed(false)
                        .skippedReason("SHADOW_BUDGET_EXHAUSTED")
                        .sourceUrls(List.of("https://docs.example.com/shadow-context"))
                        .build())
                .sourceUrls(List.of("https://docs.example.com/shadow-context"))
                .build()
                .normalized();
        ReportResponse report = ReportResponse.builder()
                .id(2L)
                .taskId(43L)
                .title("Shadow-only 导出验证")
                .content("# Shadow Report")
                .summary("summary")
                .qualityPassed(false)
                .evidenceCount(0)
                .sourceUrls(List.of("https://docs.example.com/shadow-context"))
                .orchestrationDecision(null)
                .orchestrationDecisionAudit(audit)
                .build();
        when(reportService.getReport(43L)).thenReturn(report);
        when(reportExportRecordRepository.findTopByTaskIdOrderByExportVersionDesc(43L))
                .thenReturn(Optional.empty());
        when(reportExportRecordRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ExportPackageService service = new ExportPackageService(
                reportExportRecordRepository, reportService, objectMapper);

        String markdown = new String(
                service.createExportPackage(43L, "MARKDOWN").content(), StandardCharsets.UTF_8);
        String html = new String(
                service.createExportPackage(43L, "HTML").content(), StandardCharsets.UTF_8);
        JsonNode json = objectMapper.readTree(service.createExportPackage(43L, "JSON").content());

        assertTrue(markdown.contains("当前周期没有产生代表决策"));
        assertTrue(markdown.contains("LLM_SHADOW"));
        assertTrue(markdown.contains("SHADOW_BUDGET_EXHAUSTED"));
        assertTrue(html.contains("当前周期没有产生代表决策"));
        assertTrue(html.contains("SHADOW_BUDGET_EXHAUSTED"));
        assertTrue(json.path("orchestrationDecision").isNull());
        assertEquals("LLM_SHADOW", json.path("orchestrationDecisionAudit").path("mode").asText());
        assertEquals("SHADOW_BUDGET_EXHAUSTED",
                json.path("orchestrationDecisionAudit").path("shadowExecution").path("skippedReason").asText());
    }

    private ReportResponse buildReportResponse() throws Exception {
        OrchestrationDecisionSummary representative = OrchestrationDecisionSummary.builder()
                .decisionId("od-42-review")
                .taskId(42L)
                .triggerNodeName("quality_check_final")
                .decisionType("WAIT_FOR_HUMAN")
                .actionType("MANUAL_REVIEW")
                .decisionOrigin("RULE_FALLBACK")
                .modelName("deepseek-chat")
                .temperature(0.0)
                .promptHash("sha256:prompt-2")
                .llmResponseHash("sha256:response-2")
                .aiAuditTraceId("orch-export-trace")
                .parseRetryCount(1)
                .fallbackUsed(true)
                .fallbackReason("PARSE_ERROR:INVALID_DECISION_ACTION_PAIR")
                .shadowExecuted(false)
                .targetNode("quality_check_final")
                .reason("终审前仍缺少最终人工确认。")
                .requiresHumanIntervention(true)
                .requiresConfirmation(false)
                .policyAllowed(true)
                .normalizedAction("MANUAL_ONLY")
                .runtimeStatus("CONFIRMATION_REQUIRED")
                .fallbackAttempt(true)
                .mutationType("MARK_WAITING_INTERVENTION")
                .mutationBranchReason("ORCHESTRATOR_DECISION")
                .mutationDynamicAction("MANUAL_ONLY")
                .evidenceState("MISSING_SOURCE")
                .sourceUrls(List.of("https://docs.example.com/review-gap"))
                .build()
                .normalized();
        OrchestrationDecisionSummary rejectedAttempt = OrchestrationDecisionSummary.builder()
                .decisionId("od-42-llm-primary")
                .taskId(42L)
                .triggerNodeName("quality_check_final")
                .decisionType("REWRITE_ONLY")
                .actionType("SUPPLEMENT_EVIDENCE")
                .decisionOrigin("LLM_PRIMARY")
                .reason("补充来源后改写。")
                .policyAllowed(false)
                .policyBlockedReasons(List.of("INVALID_DECISION_ACTION_PAIR", "<script>blocked</script>"))
                .normalizedAction("MANUAL_ONLY")
                .runtimeStatus("POLICY_REJECTED")
                .mutationType("NO_MUTATION")
                .mutationBranchReason("POLICY_REJECTED")
                .mutationDynamicAction("NO_ACTION")
                .evidenceState("MISSING_SOURCE")
                .sourceUrls(List.of("https://docs.example.com/review-gap"))
                .build()
                .normalized();
        OrchestrationDecisionAuditSummary audit = OrchestrationDecisionAuditSummary.builder()
                .traceSchemaVersion("ORCHESTRATION_TRACE_V2")
                .mode("LLM_PRIMARY")
                .representativeDecision(representative)
                .coordinatorDecisions(List.of(rejectedAttempt))
                .attempts(List.of(rejectedAttempt, representative))
                .finalDecisionIds(List.of("od-42-review"))
                .policyFallbackUsed(true)
                .runtimeState(OrchestrationDecisionAuditSummary.RuntimeStateSummary.builder()
                        .currentDecisionCount(1)
                        .dynamicBranchCountsBySection(java.util.Map.of("quality", 1))
                        .currentPlanVersionId(31L)
                        .nextPlanVersion(3)
                        .checkpointStateStatus("RESTORED")
                        .sourceUrls(List.of("https://docs.example.com/checkpoint"))
                        .build())
                .shadowExecution(OrchestrationDecisionAuditSummary.ShadowExecutionSummary.builder()
                        .requested(false)
                        .executed(false)
                        .sourceUrls(List.of("https://docs.example.com/review-gap"))
                        .build())
                .llmFailure(OrchestrationDecisionAuditSummary.FailureSummary.builder()
                        .type("PARSE_ERROR")
                        .parseRetryCount(1)
                        .attempts(List.of(OrchestrationDecisionAuditSummary.FailureAttemptSummary.builder()
                                .attemptNumber(1)
                                .promptHash("sha256:prompt-1")
                                .llmResponseHash("sha256:response-1")
                                .issues(List.of(OrchestrationDecisionAuditSummary.ParseIssueSummary.builder()
                                        .decisionIndex(0)
                                        .code("INVALID_DECISION_ACTION_PAIR")
                                        .fieldName("decisionType/actionType")
                                        .sourceUrls(List.of("https://docs.example.com/review-gap"))
                                        .build()))
                                .discardedSourceUrls(List.of(
                                        OrchestrationDecisionAuditSummary.DiscardedSourceSummary.builder()
                                                .decisionIndex(0)
                                                .sourceUrl("https://untrusted.example.net/outside")
                                                .code("SOURCE_URL_OUTSIDE_CONTEXT")
                                                .sourceUrls(List.of("https://docs.example.com/review-gap"))
                                                .build()))
                                .sourceUrls(List.of("https://docs.example.com/review-gap"))
                                .build()))
                        .sourceUrls(List.of("https://docs.example.com/review-gap"))
                        .build())
                .sourceUrls(List.of(
                        "https://docs.example.com/review-gap",
                        "https://docs.example.com/checkpoint"))
                .build()
                .normalized();
        ReportResponse response = ReportResponse.builder()
                .id(1L)
                .taskId(42L)
                .title("协作决策导出验证")
                .content("# Report")
                .summary("summary")
                .qualityPassed(false)
                .qualityScore(80)
                .evidenceCount(1)
                .sourceUrls(List.of("https://docs.example.com/review-gap"))
                .orchestrationDecision(representative)
                .orchestrationDecisionAudit(audit)
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
        return response;
    }
}
