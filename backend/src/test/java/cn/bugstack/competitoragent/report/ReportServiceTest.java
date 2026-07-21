package cn.bugstack.competitoragent.report;

import cn.bugstack.competitoragent.model.dto.ReportResponse;
import cn.bugstack.competitoragent.model.entity.CompetitorKnowledge;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.model.entity.Report;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskWorkflowEvent;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.repository.ReportRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.repository.TaskWorkflowEventRepository;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportServiceTest {

    private final ReportRepository reportRepository = mock(ReportRepository.class);
    private final EvidenceSourceRepository evidenceRepository = mock(EvidenceSourceRepository.class);
    private final CompetitorKnowledgeRepository knowledgeRepository = mock(CompetitorKnowledgeRepository.class);
    private final TaskNodeRepository taskNodeRepository = mock(TaskNodeRepository.class);
    private final TaskWorkflowEventRepository taskWorkflowEventRepository = mock(TaskWorkflowEventRepository.class);
    private final EvidenceQueryService evidenceQueryService = mock(EvidenceQueryService.class);
    private final EvidenceQueryService projectionEvidenceQueryService =
            new EvidenceQueryService(mock(EvidenceSourceRepository.class), new ObjectMapper());
    private final ReportDiagnosisAssembler reportDiagnosisAssembler =
            new ReportDiagnosisAssembler(new ObjectMapper(), new EvidenceQueryService(mock(EvidenceSourceRepository.class), new ObjectMapper()));
    private final ReportService reportService = new ReportService(
            reportRepository,
            evidenceRepository,
            knowledgeRepository,
            taskNodeRepository,
            evidenceQueryService,
            reportDiagnosisAssembler,
            new ObjectMapper()
    );

    @Test
    void report_service_should_rely_on_evidence_projection_not_collection_runtime() {
        Report report = Report.builder()
                .id(7L)
                .taskId(700L)
                .title("证据投影视图")
                .content("# Report")
                .summary("summary")
                .qualityPassed(true)
                .evidenceCount(1)
                .build();
        ReportResponse.EvidenceInfo evidence = new ReportResponse.EvidenceInfo(
                "E700",
                "Docs",
                "https://docs.example.com/report",
                "snippet",
                "Notion AI",
                null,
                "DOCS",
                "SEARCH",
                "docs.example.com",
                "reason",
                null,
                0.91,
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                java.util.Map.of()
        );

        when(reportRepository.findByTaskId(700L)).thenReturn(Optional.of(report));
        when(evidenceQueryService.listTaskEvidence(700L)).thenReturn(List.of(evidence));
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(700L)).thenReturn(List.of());
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(700L)).thenReturn(List.of());

        ReportResponse response = reportService.getReport(700L);

        assertNotNull(response);
        assertEquals(1, response.getEvidences().size());
        verify(evidenceQueryService).listTaskEvidence(700L);
        verify(evidenceRepository, never()).findByTaskIdOrderByEvidenceIdAsc(700L);
    }

    @Test
    void shouldExposeTopLevelSourceUrlsForReportDeliveryPayload() {
        Report report = Report.builder()
                .id(8L)
                .taskId(710L)
                .title("真实冒烟报告")
                .content("# Report")
                .summary("summary")
                .qualityPassed(false)
                .evidenceCount(1)
                .build();
        ReportResponse.EvidenceInfo evidence = new ReportResponse.EvidenceInfo(
                "E710",
                "Notion AI 官方产品页",
                "https://notion.so/product/ai",
                "snippet",
                "Notion AI",
                null,
                "OFFICIAL",
                "DIRECT",
                "notion.so",
                "用户提供入口",
                null,
                0.95,
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                java.util.Map.of()
        );

        when(reportRepository.findByTaskId(710L)).thenReturn(Optional.of(report));
        when(evidenceQueryService.listTaskEvidence(710L)).thenReturn(List.of(evidence));
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(710L)).thenReturn(List.of());
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(710L)).thenReturn(List.of());

        ReportResponse response = reportService.getReport(710L);

        assertEquals(List.of("https://notion.so/product/ai"), response.getSourceUrls());
    }

    @Test
    void shouldMarkStageOneMvpReportAsDegradedReadyWhenScoreIsPassingWithLimitedEvidenceGaps() {
        List<ReportResponse.EvidenceInfo> traceableEvidenceInfos = stageOneTraceableEvidenceInfos();
        Report report = Report.builder()
                .id(711L)
                .taskId(711L)
                .title("阶段1降级交付报告")
                .content("# Report")
                .summary("summary")
                .qualityScore(65)
                .qualityPassed(false)
                .qualityIssues("""
                        [
                          {
                            "type":"missing_evidence",
                            "section":"pricing",
                            "severity":"WARNING",
                            "level":"WARNING",
                            "evidenceBasis":"定价维度仍有证据缺口",
                            "sourceUrls":["https://www.notion.so/pricing"],
                            "suggestion":"补充 pricing 证据"
                          },
                          {
                            "type":"missing_evidence",
                            "section":"strengths",
                            "severity":"WARNING",
                            "level":"WARNING",
                            "evidenceBasis":"优势维度仍有证据缺口",
                            "sourceUrls":["https://www.notion.so/product/ai"],
                            "suggestion":"补充 strengths 证据"
                          },
                          {
                            "type":"missing_evidence",
                            "section":"weaknesses",
                            "severity":"WARNING",
                            "level":"WARNING",
                            "evidenceBasis":"短板维度仍有证据缺口",
                            "sourceUrls":["https://www.notion.so/help"],
                            "suggestion":"补充 weaknesses 证据"
                          }
                        ]
                        """)
                .evidenceCount(traceableEvidenceInfos.size())
                .build();

        when(reportRepository.findByTaskId(711L)).thenReturn(Optional.of(report));
        when(evidenceQueryService.listTaskEvidence(711L)).thenReturn(traceableEvidenceInfos);
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(711L)).thenReturn(List.of());
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(711L)).thenReturn(List.of());

        ReportResponse response = reportService.getReport(711L);

        assertNotNull(response.getDeliverySummary());
        assertEquals(Boolean.TRUE, response.getDeliverySummary().getReadyForDelivery());
        assertEquals("DEGRADED_READY", response.getDeliverySummary().getDeliveryStatus());
        assertTrue(response.getDeliverySummary().getSummary().contains("降级"));
        assertTrue(response.getDeliverySummary().getSummary().contains("人工复核"));
    }

    @Test
    void shouldNotMarkDegradedReadyWhenScoreIsBelowSixtyOrBlockerExists() {
        Report belowFloorReport = Report.builder()
                .id(712L)
                .taskId(712L)
                .title("未达到阶段1门槛")
                .content("# Report")
                .summary("summary")
                .qualityScore(59)
                .qualityPassed(false)
                .qualityIssues("""
                        [
                          {
                            "type":"missing_evidence",
                            "section":"pricing",
                            "severity":"WARNING",
                            "level":"WARNING",
                            "evidenceBasis":"定价维度仍有证据缺口",
                            "sourceUrls":["https://www.notion.so/pricing"],
                            "suggestion":"补充 pricing 证据"
                          }
                        ]
                        """)
                .evidenceCount(0)
                .build();
        Report blockerReport = Report.builder()
                .id(713L)
                .taskId(713L)
                .title("存在阻断诊断的报告")
                .content("# Report")
                .summary("summary")
                .qualityScore(65)
                .qualityPassed(false)
                .qualityIssues("""
                        [
                          {
                            "type":"missing_evidence",
                            "section":"targetUsers",
                            "severity":"ERROR",
                            "level":"BLOCKER",
                            "evidenceBasis":"关键结论缺少可追溯证据",
                            "sourceUrls":["https://www.notion.so/security"],
                            "suggestion":"补充 blocker 证据"
                          }
                        ]
                        """)
                .evidenceCount(0)
                .build();

        when(reportRepository.findByTaskId(712L)).thenReturn(Optional.of(belowFloorReport));
        when(evidenceQueryService.listTaskEvidence(712L)).thenReturn(List.of());
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(712L)).thenReturn(List.of());
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(712L)).thenReturn(List.of());
        when(reportRepository.findByTaskId(713L)).thenReturn(Optional.of(blockerReport));
        when(evidenceQueryService.listTaskEvidence(713L)).thenReturn(List.of());
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(713L)).thenReturn(List.of());
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(713L)).thenReturn(List.of());

        ReportResponse belowFloorResponse = reportService.getReport(712L);
        ReportResponse blockerResponse = reportService.getReport(713L);

        assertEquals(Boolean.FALSE, belowFloorResponse.getDeliverySummary().getReadyForDelivery());
        assertEquals("NEEDS_EVIDENCE", belowFloorResponse.getDeliverySummary().getDeliveryStatus());
        assertEquals(Boolean.FALSE, blockerResponse.getDeliverySummary().getReadyForDelivery());
        assertEquals("BLOCKED", blockerResponse.getDeliverySummary().getDeliveryStatus());
    }

    @Test
    void shouldExposePersistedWriterEvidenceSummaryInReportMainPath() {
        Report report = Report.builder()
                .id(910L)
                .taskId(910L)
                .title("ReportWriting 证据快照")
                .content("# report")
                .summary("summary")
                .evidenceCount(0)
                .writerEvidenceState("MISSING_SOURCE")
                .citationGapSeverity("ERROR")
                .missingCitationSections("[\"report_conclusion\"]")
                .sectionCitationGaps("""
                        [
                          {
                            "targetSection":"report_conclusion",
                            "sectionTitle":"报告结论",
                            "summary":"当前章节暂无可用证据来源",
                            "severity":"ERROR",
                            "evidenceState":"MISSING_SOURCE",
                            "sourceUrls":[],
                            "missingFields":["recommendations"],
                            "suggestedQueries":["report_conclusion recommendations official source"]
                          }
                        ]
                        """)
                .writerIssueFlags("[\"WRITER_CITATION_GAP\",\"WRITER_MISSING_SOURCE\"]")
                .writerSourceUrls("[]")
                .build();
        when(reportRepository.findByTaskId(910L)).thenReturn(Optional.of(report));
        when(evidenceQueryService.listTaskEvidence(910L)).thenReturn(List.of());
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(910L)).thenReturn(List.of());
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(910L)).thenReturn(List.of());

        ReportResponse response = reportService.getReport(910L);

        assertNotNull(response.getWriterEvidenceSummary());
        assertEquals("MISSING_SOURCE", response.getWriterEvidenceSummary().getWriterEvidenceState());
        assertEquals("ERROR", response.getWriterEvidenceSummary().getCitationGapSeverity());
        assertEquals("report_conclusion",
                response.getWriterEvidenceSummary().getSectionCitationGaps().get(0).getTargetSection());
    }

    @Test
    void shouldFallbackToWriterNodeOutputWhenReportHasNoPersistedWriterSnapshot() {
        Report report = Report.builder()
                .id(911L)
                .taskId(911L)
                .title("历史 ReportWriting 报告")
                .content("# report")
                .summary("summary")
                .evidenceCount(0)
                .build();
        TaskNode writerNode = TaskNode.builder()
                .taskId(911L)
                .nodeName("write_report")
                .outputData("""
                        {
                          "writerEvidenceState":"PARTIAL_SOURCE",
                          "citationGapSeverity":"HIGH",
                          "missingCitationSections":["pricing"],
                          "sectionCitationGaps":[
                            {
                              "targetSection":"pricing",
                              "sectionTitle":"定价策略",
                              "summary":"定价章节已有来源但缺逐句引用",
                              "severity":"HIGH",
                              "evidenceState":"PARTIAL_SOURCE",
                              "sourceUrls":["https://www.notion.so/pricing"],
                              "missingFields":["pricingComparison"]
                            }
                          ],
                          "issueFlags":["WRITER_CITATION_GAP"],
                          "sourceUrls":["https://www.notion.so/pricing"]
                        }
                        """)
                .build();

        when(reportRepository.findByTaskId(911L)).thenReturn(Optional.of(report));
        when(evidenceQueryService.listTaskEvidence(911L)).thenReturn(List.of());
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(911L)).thenReturn(List.of());
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(911L)).thenReturn(List.of(writerNode));

        ReportResponse response = reportService.getReport(911L);

        assertEquals("PARTIAL_SOURCE", response.getWriterEvidenceSummary().getWriterEvidenceState());
        assertTrue(response.getSourceUrls().contains("https://www.notion.so/pricing"));
    }

    @Test
    void shouldKeepWriterEvidenceSummaryNullForLegacyWriterOutputWithoutSnapshotFields() {
        Report report = Report.builder()
                .id(912L)
                .taskId(912L)
                .title("旧 Writer 报告")
                .content("# legacy report")
                .summary("旧 Writer 输出")
                .evidenceCount(0)
                .build();
        TaskNode writerNode = TaskNode.builder()
                .taskId(912L)
                .nodeName("write_report")
                .outputData("""
                        {
                          "content":"# legacy report",
                          "summary":"旧 Writer 输出",
                          "sourceUrls":["https://www.notion.so/product/ai"]
                        }
                        """)
                .build();

        when(reportRepository.findByTaskId(912L)).thenReturn(Optional.of(report));
        when(evidenceQueryService.listTaskEvidence(912L)).thenReturn(List.of());
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(912L)).thenReturn(List.of());
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(912L)).thenReturn(List.of(writerNode));

        ReportResponse response = reportService.getReport(912L);

        assertNull(response.getWriterEvidenceSummary());
    }

    @Test
    void shouldIncludeWriterEvidenceSummaryInLegacyMarkdownDownload() {
        Report report = reportWithPersistedWriterEvidenceSnapshot(913L);
        when(reportRepository.findByTaskId(913L)).thenReturn(Optional.of(report));
        when(evidenceQueryService.listTaskEvidence(913L)).thenReturn(List.of());
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(913L)).thenReturn(List.of());
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(913L)).thenReturn(List.of());

        String markdown = new String(reportService.exportMarkdown(913L), StandardCharsets.UTF_8);

        assertTrue(markdown.contains("## 写作证据摘要"));
        assertTrue(markdown.contains("PARTIAL_SOURCE"));
        assertTrue(markdown.contains("定价策略"));
        assertTrue(markdown.contains("https://www.notion.so/pricing"));
    }

    @Test
    void shouldIncludeWriterEvidenceSummaryInLegacyHtmlDownload() {
        Report report = reportWithPersistedWriterEvidenceSnapshot(914L);
        when(reportRepository.findByTaskId(914L)).thenReturn(Optional.of(report));
        when(evidenceQueryService.listTaskEvidence(914L)).thenReturn(List.of());
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(914L)).thenReturn(List.of());
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(914L)).thenReturn(List.of());

        String html = new String(reportService.exportHtml(914L), StandardCharsets.UTF_8);

        assertTrue(html.contains("写作证据摘要"));
        assertTrue(html.contains("PARTIAL_SOURCE"));
        assertTrue(html.contains("定价策略"));
        assertTrue(html.contains("https://www.notion.so/pricing"));
    }

    @Test
    void shouldIncludeOrchestrationDecisionAuditInLegacyMarkdownDownload() throws Exception {
        Report report = minimalReport(915L, "公开 Markdown 决策审计报告");
        TaskWorkflowEvent decisionEvent = persistedV2Event(
                915L,
                "quality_check_final",
                "llm-policy-rejected-rule-fallback"
        );
        stubReportMainPath(report);
        when(taskWorkflowEventRepository.findLatestOrchestrationDecisionEvent(915L))
                .thenReturn(Optional.of(decisionEvent));
        injectTaskWorkflowEventRepositoryIfPresent(reportService);

        String markdown = new String(reportService.exportMarkdown(915L), StandardCharsets.UTF_8);

        assertTrue(markdown.contains("## 协作决策摘要"));
        assertTrue(markdown.contains("od-801-rule-fallback"));
        assertTrue(markdown.contains("RULE_FALLBACK"));
        assertTrue(markdown.contains("CONFIRMATION_REQUIRED"));
        assertTrue(markdown.contains("MARK_WAITING_INTERVENTION"));
        assertTrue(markdown.contains("orch-fixture-trace"));
        assertTrue(markdown.contains("https://docs.example.com/review-gap"));
    }

    @Test
    void shouldIncludeOrchestrationDecisionAuditInLegacyHtmlDownload() throws Exception {
        Report report = minimalReport(916L, "公开 HTML 决策审计报告");
        TaskWorkflowEvent decisionEvent = persistedV2Event(
                916L,
                "quality_check_final",
                "llm-policy-rejected-rule-fallback"
        );
        stubReportMainPath(report);
        when(taskWorkflowEventRepository.findLatestOrchestrationDecisionEvent(916L))
                .thenReturn(Optional.of(decisionEvent));
        injectTaskWorkflowEventRepositoryIfPresent(reportService);

        String html = new String(reportService.exportHtml(916L), StandardCharsets.UTF_8);

        assertTrue(html.contains("协作决策摘要"));
        assertTrue(html.contains("od-801-rule-fallback"));
        assertTrue(html.contains("RULE_FALLBACK"));
        assertTrue(html.contains("CONFIRMATION_REQUIRED"));
        assertTrue(html.contains("MARK_WAITING_INTERVENTION"));
        assertTrue(html.contains("orch-fixture-trace"));
        assertTrue(html.contains("https://docs.example.com/review-gap"));
    }

    @Test
    void shouldExposeLatestOrchestrationDecisionInReportMainPath() throws Exception {
        Report report = Report.builder()
                .id(9L)
                .taskId(720L)
                .title("缂栨帓鍐崇瓥鎶曞奖")
                .content("# Report")
                .summary("summary")
                .qualityPassed(false)
                .evidenceCount(0)
                .build();
        TaskWorkflowEvent decisionEvent = TaskWorkflowEvent.builder()
                .taskId(720L)
                .nodeName("quality_check_final")
                .eventType(WorkflowEventType.ORCHESTRATION_DECISION_RECORDED)
                .payload("""
                        {
                          "decision": {
                            "decisionId": "od-720-review",
                            "triggerNodeName": "quality_check_final",
                            "decisionOrigin": "RULE_FALLBACK",
                            "decisionMetadata": {
                              "modelName": "deepseek-chat",
                              "fallbackUsed": true,
                              "fallbackReason": "LLM_TIMEOUT"
                            },
                            "decisionType": "WAIT_FOR_HUMAN",
                            "actionType": "MANUAL_REVIEW",
                            "targetNode": "quality_check_final",
                            "reason": "终审发现关键信息缺少来源，需要人工确认",
                            "requiresHumanIntervention": true,
                            "requiresConfirmation": false,
                            "evidenceState": "MISSING_SOURCE",
                            "sourceUrls": ["https://docs.example.com/review-gap"]
                          },
                          "policyResult": {
                            "decisionContract": "LEGACY_RULE_SET"
                          }
                        }
                        """)
                .sourceUrls("[\"https://docs.example.com/review-gap\"]")
                .build();

        when(reportRepository.findByTaskId(720L)).thenReturn(Optional.of(report));
        when(evidenceQueryService.listTaskEvidence(720L)).thenReturn(List.of());
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(720L)).thenReturn(List.of());
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(720L)).thenReturn(List.of());
        when(taskWorkflowEventRepository.findLatestOrchestrationDecisionEvent(720L))
                .thenReturn(Optional.of(decisionEvent));
        injectTaskWorkflowEventRepositoryIfPresent(reportService);

        ReportResponse response = reportService.getReport(720L);
        JsonNode payload = new ObjectMapper().valueToTree(response);

        assertEquals("WAIT_FOR_HUMAN", payload.at("/orchestrationDecision/decisionType").asText());
        assertEquals("RULE_FALLBACK", payload.at("/orchestrationDecision/decisionOrigin").asText());
        assertEquals("LEGACY_RULE_SET", payload.at("/orchestrationDecision/decisionContract").asText());
        assertEquals("LLM_TIMEOUT", payload.at("/orchestrationDecision/fallbackReason").asText());
        assertEquals("MISSING_SOURCE", payload.at("/orchestrationDecision/evidenceState").asText());
        assertEquals("quality_check_final", payload.at("/orchestrationDecision/triggerNodeName").asText());
        assertTrue(payload.at("/sourceUrls").toString().contains("https://docs.example.com/review-gap"));
    }

    @Test
    void shouldExposeRepresentativeAndCompleteAuditFromSamePersistedV2Event() throws Exception {
        Report report = minimalReport(801L, "V2 编排审计报告");
        TaskWorkflowEvent decisionEvent = persistedV2Event(
                801L,
                "quality_check_final",
                "llm-policy-rejected-rule-fallback"
        );
        stubReportMainPath(report);
        when(taskWorkflowEventRepository.findLatestOrchestrationDecisionEvent(801L))
                .thenReturn(Optional.of(decisionEvent));
        injectTaskWorkflowEventRepositoryIfPresent(reportService);

        JsonNode payload = new ObjectMapper().valueToTree(reportService.getReport(801L));

        assertEquals("od-801-rule-fallback", payload.at("/orchestrationDecision/decisionId").asText());
        assertEquals("LLM_PRIMARY", payload.at("/orchestrationDecisionAudit/mode").asText());
        assertEquals(2, payload.at("/orchestrationDecisionAudit/attempts").size());
        assertEquals("POLICY_REJECTED",
                payload.at("/orchestrationDecisionAudit/attempts/0/runtimeStatus").asText());
        assertEquals("PARSE_ERROR", payload.at("/orchestrationDecisionAudit/llmFailure/type").asText());
        assertTrue(payload.at("/sourceUrls").toString().contains("https://docs.example.com/checkpoint"));
        assertFalse(payload.at("/sourceUrls").toString().contains("https://untrusted.example.net/outside"));
        verify(taskWorkflowEventRepository).findLatestOrchestrationDecisionEvent(801L);
    }

    @Test
    void shouldKeepShadowOnlyAuditWhenPersistedV2EventHasNoRepresentativeDecision() throws Exception {
        Report report = minimalReport(802L, "Shadow 编排审计报告");
        TaskWorkflowEvent decisionEvent = persistedV2Event(
                802L,
                "quality_check_final",
                "shadow-budget-skipped-without-decision"
        );
        stubReportMainPath(report);
        when(taskWorkflowEventRepository.findLatestOrchestrationDecisionEvent(802L))
                .thenReturn(Optional.of(decisionEvent));
        injectTaskWorkflowEventRepositoryIfPresent(reportService);

        JsonNode payload = new ObjectMapper().valueToTree(reportService.getReport(802L));

        assertTrue(payload.at("/orchestrationDecision").isNull());
        assertEquals("LLM_SHADOW", payload.at("/orchestrationDecisionAudit/mode").asText());
        assertTrue(payload.at("/orchestrationDecisionAudit/shadowExecution/requested").asBoolean());
        assertFalse(payload.at("/orchestrationDecisionAudit/shadowExecution/executed").asBoolean());
        assertEquals("SHADOW_BUDGET_EXHAUSTED",
                payload.at("/orchestrationDecisionAudit/shadowExecution/skippedReason").asText());
        assertTrue(payload.at("/sourceUrls").toString().contains("https://docs.example.com/shadow-context"));
        verify(taskWorkflowEventRepository).findLatestOrchestrationDecisionEvent(802L);
    }

    @Test
    void shouldAggregateCollectorSearchAuditOverview() {
        Report report = Report.builder()
                .id(1L)
                .taskId(100L)
                .title("企业级竞品分析")
                .content("# Report")
                .summary("summary")
                .qualityPassed(true)
                .evidenceCount(0)
                .build();

        TaskNode collectorNode = TaskNode.builder()
                .taskId(100L)
                .nodeName("collect_sources_notion_docs")
                .displayName("采集 Notion Docs")
                .agentType(AgentType.COLLECTOR)
                .status(TaskNodeStatus.SUCCESS)
                .outputData("""
                        {
                          "competitor": "Notion AI",
                          "sourceType": "DOCS",
                          "searchExecutionTrace": {
                            "supplementMethod": "HTTP_FALLBACK",
                            "resumedFromCheckpoint": true,
                            "checkpointSource": "NODE_CONFIG_CHECKPOINT",
                            "degraded": true,
                            "degradationReason": "SEARCH_TIMEOUT_AFTER_SUPPLEMENT",
                            "providerFallbackUsed": true,
                            "fallbackDecision": "USE_HTTP_FALLBACK",
                            "browserBlockedReason": "CAPTCHA",
                            "browserBlockedCount": 1,
                            "recoveryCheckpoint": "BROWSER_SUPPLEMENT_SEARCH",
                            "plannedCandidateCount": 3,
                            "verifiedCandidateCount": 1,
                            "supplementedCandidateCount": 2,
                            "selectedCandidateCount": 2,
                            "selectedUrls": ["https://docs.notion.so", "https://www.notion.so/product/ai"]
                          }
                        }
                        """)
                .executionOrder(1)
                .build();

        when(reportRepository.findByTaskId(100L)).thenReturn(Optional.of(report));
        when(evidenceQueryService.listTaskEvidence(100L)).thenReturn(List.of());
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(100L)).thenReturn(List.of());
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(100L)).thenReturn(List.of(collectorNode));

        ReportResponse response = reportService.getReport(100L);

        assertNotNull(response.getSearchAuditOverview());
        assertEquals(1, response.getSearchAuditOverview().getCollectorNodeCount());
        assertEquals(1, response.getSearchAuditOverview().getTraceRecordedCount());
        assertEquals(1, response.getSearchAuditOverview().getCheckpointRecoveredCount());
        assertEquals(1, response.getSearchAuditOverview().getDegradedCount());
        assertEquals(1, response.getSearchAuditOverview().getProviderFallbackCount());
        assertEquals(1, response.getSearchAuditOverview().getBrowserBlockedCount());
        assertEquals(3, response.getSearchAuditOverview().getPlannedCandidateCount());
        assertEquals(1, response.getSearchAuditOverview().getVerifiedCandidateCount());
        assertEquals(2, response.getSearchAuditOverview().getSupplementedCandidateCount());
        assertEquals(2, response.getSearchAuditOverview().getSelectedCandidateCount());
        assertEquals(1, response.getSearchAuditOverview().getCollectors().size());
        assertTrue(response.getSearchAuditOverview().getCollectors().get(0).getSelectedUrls().contains("https://docs.notion.so"));
        assertNotNull(response.getSearchAuditOverview().getSearchAuditSummary());
    }

    @Test
    void shouldKeepCollectorAuditVisibleWhenTraceMissing() {
        Report report = Report.builder()
                .id(2L)
                .taskId(200L)
                .title("企业级竞品分析")
                .content("# Report")
                .summary("summary")
                .qualityPassed(false)
                .evidenceCount(0)
                .build();

        TaskNode collectorNode = TaskNode.builder()
                .taskId(200L)
                .nodeName("collect_sources_notion_pricing")
                .displayName("采集 Notion Pricing")
                .agentType(AgentType.COLLECTOR)
                .status(TaskNodeStatus.FAILED)
                .nodeConfig("""
                        {
                          "competitorName": "Notion AI",
                          "sourceType": "PRICING"
                        }
                        """)
                .errorMessage("browser unavailable")
                .executionOrder(1)
                .build();

        when(reportRepository.findByTaskId(200L)).thenReturn(Optional.of(report));
        when(evidenceQueryService.listTaskEvidence(200L)).thenReturn(List.of());
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(200L)).thenReturn(List.of());
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(200L)).thenReturn(List.of(collectorNode));

        ReportResponse response = reportService.getReport(200L);

        assertNotNull(response.getSearchAuditOverview());
        assertEquals(1, response.getSearchAuditOverview().getCollectorNodeCount());
        assertEquals(0, response.getSearchAuditOverview().getTraceRecordedCount());
        assertEquals(1, response.getSearchAuditOverview().getCollectors().size());
        assertEquals("Notion AI", response.getSearchAuditOverview().getCollectors().get(0).getCompetitorName());
        assertEquals("PRICING", response.getSearchAuditOverview().getCollectors().get(0).getSourceType());
        assertEquals(TaskNodeStatus.FAILED, response.getSearchAuditOverview().getCollectors().get(0).getNodeStatus());
        assertEquals(Boolean.FALSE, response.getSearchAuditOverview().getCollectors().get(0).getTraceRecorded());
        assertTrue(response.getSearchAuditOverview().getCollectors().get(0).getAuditMessage().contains("未生成结构化搜索轨迹"));
    }

    @Test
    void shouldAggregateEvidenceCoverageOverview() {
        Report report = Report.builder()
                .id(3L)
                .taskId(300L)
                .title("企业级竞品分析")
                .content("# Report")
                .summary("summary")
                .qualityPassed(true)
                .evidenceCount(2)
                .build();

        CompetitorKnowledge knowledge = CompetitorKnowledge.builder()
                .taskId(300L)
                .competitorName("Notion AI")
                .summary("summary")
                .sourceUrls("[\"https://www.notion.so/product/ai\"]")
                .evidenceCoverage("""
                        {
                          "summary": {"status":"TRACEABLE","hasValue":true},
                          "positioning": {"status":"MISSING_EVIDENCE","hasValue":true},
                          "targetUsers": {"status":"EMPTY","hasValue":false},
                          "coreFeatures": {"status":"TRACEABLE","hasValue":true},
                          "pricing": {"status":"MISSING_EVIDENCE","hasValue":true},
                          "strengths": {"status":"TRACEABLE","hasValue":true},
                          "weaknesses": {"status":"EMPTY","hasValue":false}
                        }
                        """)
                .build();

        when(reportRepository.findByTaskId(300L)).thenReturn(Optional.of(report));
        when(evidenceQueryService.listTaskEvidence(300L)).thenReturn(List.of());
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(300L)).thenReturn(List.of(knowledge));
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(300L)).thenReturn(List.of());

        ReportResponse response = reportService.getReport(300L);

        assertNotNull(response.getEvidenceCoverageOverview());
        assertEquals(7, response.getEvidenceCoverageOverview().getTotalFields());
        assertEquals(3, response.getEvidenceCoverageOverview().getTraceableFields());
        assertEquals(2, response.getEvidenceCoverageOverview().getMissingEvidenceFields());
        assertEquals(2, response.getEvidenceCoverageOverview().getEmptyFields());
        assertEquals(7, response.getEvidenceCoverageOverview().getSections().size());
        assertEquals(1, response.getEvidenceCoverageOverview().getCompetitors().size());
        assertTrue(response.getEvidenceCoverageOverview().getCompetitors().get(0).getMissingSections().contains("市场定位"));
        assertTrue(response.getEvidenceCoverageOverview().getCompetitors().get(0).getMissingSections().contains("定价策略"));
    }

    @Test
    void shouldTreatStructuredDirectAsTraceableAndExpandedGapStatesAsMissingCoverage() {
        Report report = Report.builder()
                .id(31L)
                .taskId(310L)
                .title("企业级竞品分析")
                .content("# Report")
                .summary("summary")
                .qualityPassed(true)
                .evidenceCount(1)
                .build();

        CompetitorKnowledge knowledge = CompetitorKnowledge.builder()
                .taskId(310L)
                .competitorName("Notion AI")
                .summary("summary")
                .sourceUrls("[\"https://www.notion.so/product/ai\"]")
                .evidenceCoverage("""
                        {
                          "summary": {"status":"LLM_REFUSED","hasValue":false},
                          "positioning": {"status":"TRACEABLE","hasValue":true},
                          "targetUsers": {"status":"STRUCTURED_BLOCK_DIRECT","hasValue":true},
                          "coreFeatures": {"status":"TRACEABLE","hasValue":true},
                          "pricing": {"status":"EVIDENCE_NOT_COVERING","hasValue":false},
                          "strengths": {"status":"TRACEABLE","hasValue":true},
                          "weaknesses": {"status":"EMPTY","hasValue":false}
                        }
                        """)
                .build();

        when(reportRepository.findByTaskId(310L)).thenReturn(Optional.of(report));
        when(evidenceQueryService.listTaskEvidence(310L)).thenReturn(List.of());
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(310L)).thenReturn(List.of(knowledge));
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(310L)).thenReturn(List.of());

        ReportResponse response = reportService.getReport(310L);

        assertNotNull(response.getEvidenceCoverageOverview());
        assertEquals(7, response.getEvidenceCoverageOverview().getTotalFields());
        assertEquals(4, response.getEvidenceCoverageOverview().getTraceableFields());
        assertEquals(2, response.getEvidenceCoverageOverview().getMissingEvidenceFields());
        assertEquals(1, response.getEvidenceCoverageOverview().getEmptyFields());
        assertTrue(response.getEvidenceCoverageOverview().getCompetitors().get(0).getMissingSections().contains("产品概览"));
        assertTrue(response.getEvidenceCoverageOverview().getCompetitors().get(0).getMissingSections().contains("定价策略"));
    }

    @Test
    void shouldExposeFineGrainedCoverageStatusBreakdownInReportOverview() {
        Report report = Report.builder()
                .id(32L)
                .taskId(320L)
                .title("企业级竞品分析")
                .content("# Report")
                .summary("summary")
                .qualityPassed(true)
                .evidenceCount(1)
                .build();

        CompetitorKnowledge knowledge = CompetitorKnowledge.builder()
                .taskId(320L)
                .competitorName("Notion AI")
                .summary("summary")
                .sourceUrls("[\"https://www.notion.so/product/ai\"]")
                .evidenceCoverage("""
                        {
                          "summary": {"status":"LLM_REFUSED","hasValue":false},
                          "positioning": {"status":"TRACEABLE","hasValue":true},
                          "targetUsers": {"status":"STRUCTURED_BLOCK_DIRECT","hasValue":true},
                          "coreFeatures": {"status":"TRACEABLE","hasValue":true},
                          "pricing": {"status":"EVIDENCE_NOT_COVERING","hasValue":false},
                          "strengths": {"status":"TRACEABLE","hasValue":true},
                          "weaknesses": {"status":"EMPTY","hasValue":false}
                        }
                        """)
                .build();

        when(reportRepository.findByTaskId(320L)).thenReturn(Optional.of(report));
        when(evidenceQueryService.listTaskEvidence(320L)).thenReturn(List.of());
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(320L)).thenReturn(List.of(knowledge));
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(320L)).thenReturn(List.of());

        ReportResponse response = reportService.getReport(320L);

        assertNotNull(response.getEvidenceCoverageOverview());
        assertEquals(7, response.getEvidenceCoverageOverview().getTotalFields());
        assertEquals(4, response.getEvidenceCoverageOverview().getTraceableFields());
        assertEquals(2, response.getEvidenceCoverageOverview().getMissingEvidenceFields());
        assertEquals(1, response.getEvidenceCoverageOverview().getEmptyFields());
        assertEquals(1, response.getEvidenceCoverageOverview().getStatusBreakdown().get("LLM_REFUSED"));
        assertEquals(3, response.getEvidenceCoverageOverview().getStatusBreakdown().get("TRACEABLE"));
        assertEquals(1, response.getEvidenceCoverageOverview().getStatusBreakdown().get("STRUCTURED_BLOCK_DIRECT"));
        assertEquals(1, response.getEvidenceCoverageOverview().getStatusBreakdown().get("EVIDENCE_NOT_COVERING"));
        assertEquals(1, response.getEvidenceCoverageOverview().getStatusBreakdown().get("EMPTY"));
        assertEquals(1, response.getEvidenceCoverageOverview().getSections().stream()
                .filter(section -> "overview".equals(section.getSectionKey()))
                .findFirst()
                .orElseThrow()
                .getStatusBreakdown()
                .get("LLM_REFUSED"));
        assertEquals(1, response.getEvidenceCoverageOverview().getCompetitors().get(0)
                .getStatusBreakdown()
                .get("STRUCTURED_BLOCK_DIRECT"));
    }

    @Test
    void shouldIgnoreSupersededTaskSnapshotWhenBuildingEvidenceCoverageOverview() {
        Report report = Report.builder()
                .id(33L)
                .taskId(330L)
                .title("任务级快照去重")
                .content("# Report")
                .summary("summary")
                .qualityPassed(true)
                .evidenceCount(1)
                .build();

        CompetitorKnowledge staleSnapshot = CompetitorKnowledge.builder()
                .id(1L)
                .taskId(330L)
                .competitorName("Notion AI")
                .snapshotScope("TASK")
                .summary("stale summary")
                .sourceUrls("[\"https://stale.example.com\"]")
                .evidenceCoverage("""
                        {
                          "summary": {"status":"MISSING_EVIDENCE","hasValue":true},
                          "positioning": {"status":"MISSING_EVIDENCE","hasValue":true},
                          "targetUsers": {"status":"MISSING_EVIDENCE","hasValue":true},
                          "coreFeatures": {"status":"TRACEABLE","hasValue":true},
                          "pricing": {"status":"TRACEABLE","hasValue":true},
                          "strengths": {"status":"TRACEABLE","hasValue":true},
                          "weaknesses": {"status":"EMPTY","hasValue":false}
                        }
                        """)
                .build();
        CompetitorKnowledge latestSnapshot = CompetitorKnowledge.builder()
                .id(2L)
                .taskId(330L)
                .competitorName("Notion AI")
                .snapshotScope("TASK")
                .summary("latest summary")
                .sourceUrls("[\"https://latest.example.com\"]")
                .evidenceCoverage("""
                        {
                          "summary": {"status":"TRACEABLE","hasValue":true},
                          "positioning": {"status":"TRACEABLE","hasValue":true},
                          "targetUsers": {"status":"TRACEABLE","hasValue":true},
                          "coreFeatures": {"status":"TRACEABLE","hasValue":true},
                          "pricing": {"status":"TRACEABLE","hasValue":true},
                          "strengths": {"status":"TRACEABLE","hasValue":true},
                          "weaknesses": {"status":"EMPTY","hasValue":false}
                        }
                        """)
                .build();

        when(reportRepository.findByTaskId(330L)).thenReturn(Optional.of(report));
        when(evidenceQueryService.listTaskEvidence(330L)).thenReturn(List.of());
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(330L)).thenReturn(List.of(staleSnapshot, latestSnapshot));
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(330L)).thenReturn(List.of());

        ReportResponse response = reportService.getReport(330L);

        assertNotNull(response.getEvidenceCoverageOverview());
        assertEquals(7, response.getEvidenceCoverageOverview().getTotalFields());
        assertEquals(6, response.getEvidenceCoverageOverview().getTraceableFields());
        assertEquals(0, response.getEvidenceCoverageOverview().getMissingEvidenceFields());
        assertEquals(1, response.getEvidenceCoverageOverview().getEmptyFields());
        assertEquals(1, response.getEvidenceCoverageOverview().getCompetitors().size());
        assertEquals("Notion AI", response.getEvidenceCoverageOverview().getCompetitors().get(0).getCompetitorName());
    }

    @Test
    void shouldExposeTaskRagAuditSummaryToReportResponse() {
        // 报告接口应直接回流任务级检索审计摘要，避免前端再去解析节点原始 outputData。
        Report report = Report.builder()
                .id(31L)
                .taskId(3100L)
                .title("Task RAG 审计")
                .content("# Report")
                .summary("summary")
                .qualityPassed(true)
                .evidenceCount(0)
                .build();
        TaskNode analyzerNode = TaskNode.builder()
                .taskId(3100L)
                .nodeName("analyze_competitors")
                .displayName("分析")
                .agentType(AgentType.ANALYZER)
                .status(TaskNodeStatus.SUCCESS)
                .outputData("""
                        {
                          "taskRagContext": "检索查询：Notion AI pricing\\n缺口说明：公开企业定价页仍不足"
                        }
                        """)
                .executionOrder(2)
                .build();

        when(reportRepository.findByTaskId(3100L)).thenReturn(Optional.of(report));
        when(evidenceQueryService.listTaskEvidence(3100L)).thenReturn(List.of());
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(3100L)).thenReturn(List.of());
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(3100L)).thenReturn(List.of(analyzerNode));

        ReportResponse response = reportService.getReport(3100L);

        assertNotNull(response.getTaskRagAudits());
        assertEquals(1, response.getTaskRagAudits().size());
        assertEquals("analyze_competitors", response.getTaskRagAudits().get(0).getNodeName());
        assertTrue(response.getTaskRagAudits().get(0).getTaskRagContext().contains("公开企业定价页仍不足"));
    }

    @Test
    void shouldHideChunkLevelIndexDetailsFromTaskRagAuditSummary() {
        // Task 5.3.e 要锁定的工作台摘要语义是：
        // 对外返回的 task RAG 审计摘要可以保留检索查询、缺口说明与来源链接，
        // 但不能把切片键、逐条命中片段等底层索引细节直接暴露到报告 / 工作台主路径。
        Report report = Report.builder()
                .id(32L)
                .taskId(3200L)
                .title("Task RAG 工作台摘要")
                .content("# Report")
                .summary("summary")
                .qualityPassed(true)
                .evidenceCount(0)
                .build();
        TaskNode analyzerNode = TaskNode.builder()
                .taskId(3200L)
                .nodeName("analyze_competitors")
                .displayName("分析")
                .agentType(AgentType.ANALYZER)
                .status(TaskNodeStatus.SUCCESS)
                .outputData("""
                        {
                          "taskRagContext": "检索查询：GitHub Copilot enterprise governance\\n检索摘要：[DOMAIN] 领域知识库命中 GitHub Copilot 的企业治理说明。（知识文档：DOMAIN-DOC-009）\\n缺口说明：任务级公开资料不足，当前回退到领域知识召回。\\n来源链接：https://docs.github.com/copilot/enterprise\\n命中片段：\\n1. [E-DOM-001] 领域知识库命中 GitHub Copilot 的企业治理说明。 | 召回层级：DOMAIN | 知识文档：DOMAIN-DOC-009 | 切片键：DOMAIN-DOC-009#CHUNK-001 | 命中原因：DOMAIN_KNOWLEDGE | sourceUrls=https://docs.github.com/copilot/enterprise"
                        }
                        """)
                .executionOrder(2)
                .build();

        when(reportRepository.findByTaskId(3200L)).thenReturn(Optional.of(report));
        when(evidenceQueryService.listTaskEvidence(3200L)).thenReturn(List.of());
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(3200L)).thenReturn(List.of());
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(3200L)).thenReturn(List.of(analyzerNode));

        ReportResponse response = reportService.getReport(3200L);

        assertNotNull(response.getTaskRagAudits());
        assertEquals(1, response.getTaskRagAudits().size());
        assertTrue(response.getTaskRagAudits().get(0).getTaskRagContext().contains("GitHub Copilot enterprise governance"));
        assertTrue(response.getTaskRagAudits().get(0).getTaskRagContext().contains("任务级公开资料不足"));
        assertTrue(response.getTaskRagAudits().get(0).getTaskRagContext().contains("来源链接：https://docs.github.com/copilot/enterprise"));
        assertTrue(response.getTaskRagAudits().get(0).getTaskRagContext().contains("知识文档：DOMAIN-DOC-009"));
        assertTrue(!response.getTaskRagAudits().get(0).getTaskRagContext().contains("命中片段："));
        assertTrue(!response.getTaskRagAudits().get(0).getTaskRagContext().contains("切片键："));
        assertTrue(!response.getTaskRagAudits().get(0).getTaskRagContext().contains("sourceUrls="));
    }

    @Test
    void shouldKeepReusableMemoryAndRuntimeContextSectionsInTaskRagAuditSummary() {
        // Task 5.4.d 要求报告侧也能解释“哪些内容来自可复用记忆，哪些来自当前任务”，
        // 因此对外审计摘要不能再只剩检索查询和缺口说明。
        Report report = Report.builder()
                .id(33L)
                .taskId(3300L)
                .title("记忆复用审计")
                .content("# Report")
                .summary("summary")
                .qualityPassed(true)
                .evidenceCount(0)
                .build();
        TaskNode writerNode = TaskNode.builder()
                .taskId(3300L)
                .nodeName("write_report")
                .displayName("报告撰写")
                .agentType(AgentType.WRITER)
                .status(TaskNodeStatus.SUCCESS)
                .outputData("""
                        {
                          "taskRagContext": "知识上下文\\n检索查询：Notion AI enterprise governance\\n检索摘要：当前任务命中企业治理资料。\\n缺口说明：仍缺企业定价公开证据。\\n来源链接：https://example.com/task-knowledge\\n可复用记忆\\n1. 当前任务已经核实官网定价页缺少企业价卡。 | 记忆层级：SHORT_TERM | 来源对象：MEMORY_SNAPSHOT | 来源节点/对象：collect_sources | versionSource=TASK_RAG@PLAN-22:analysis | invalidationScope=TASK_RERUN | invalidationReason=PLAN_VERSION_CHANGED | reuseReason=同计划版本内可复用，计划重跑后失效 | sourceUrls=https://example.com/notion-ai/pricing\\n任务即时上下文\\n1. collect_sources -> 当前任务已确认需要重点解释企业治理与审计"
                        }
                        """)
                .executionOrder(3)
                .build();

        when(reportRepository.findByTaskId(3300L)).thenReturn(Optional.of(report));
        when(evidenceQueryService.listTaskEvidence(3300L)).thenReturn(List.of());
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(3300L)).thenReturn(List.of());
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(3300L)).thenReturn(List.of(writerNode));

        ReportResponse response = reportService.getReport(3300L);

        assertNotNull(response.getTaskRagAudits());
        assertEquals(1, response.getTaskRagAudits().size());
        String auditSummary = response.getTaskRagAudits().get(0).getTaskRagContext();
        assertTrue(auditSummary.contains("可复用记忆"));
        assertTrue(auditSummary.contains("任务即时上下文"));
        assertTrue(auditSummary.contains("TASK_RERUN"));
        assertTrue(auditSummary.contains("collect_sources"));
        assertTrue(!auditSummary.contains("sourceUrls="));
    }

    @Test
    void shouldExposeExplainableReviewDiagnosisToFrontend() {
        Report report = Report.builder()
                .id(4L)
                .taskId(400L)
                .title("企业级竞品分析")
                .content("# Report")
                .summary("summary")
                .qualityScore(72)
                .qualityPassed(false)
                .qualityIssues("""
                        [
                          {
                            "type":"missing_evidence",
                            "section":"结论",
                            "severity":"ERROR",
                            "level":"BLOCKER",
                            "dimensionCode":"EVIDENCE_TRACEABILITY",
                            "dimensionName":"证据可追溯性",
                            "evidenceBasis":"关键结论缺少可回指的证据编号。",
                            "sourceUrls":["https://docs.notion.so/security"],
                            "suggestion":"补充证据编号或下调判断强度。"
                          }
                        ]
                        """)
                .evidenceCount(1)
                .build();
        EvidenceSource evidenceSource = EvidenceSource.builder()
                .taskId(400L)
                .competitorName("Notion AI")
                .evidenceId("E-400")
                .title("Security Page")
                .url("https://docs.notion.so/security")
                .contentSnippet("security snippet")
                .sourceType("DOCS")
                .build();
        ReportResponse.EvidenceInfo evidenceInfo = new ReportResponse.EvidenceInfo(
                "E-400",
                "Security Page",
                "https://docs.notion.so/security",
                "security snippet",
                "Notion AI",
                null,
                "DOCS",
                "SEARCH",
                "docs.notion.so",
                "命中文档",
                null,
                0.91,
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                java.util.Map.of()
        );

        TaskNode reviewNode = TaskNode.builder()
                .taskId(400L)
                .nodeName("quality_check")
                .status(TaskNodeStatus.SUCCESS)
                .outputData("""
                        {
                          "score": 72,
                          "passed": false,
                          "requiresHumanIntervention": false,
                          "autoRewriteAllowed": true,
                          "summary": "证据可追溯性和结论支撑度存在明显缺口",
                          "dimensions": [
                            {
                              "code":"EVIDENCE_TRACEABILITY",
                              "name":"证据可追溯性",
                              "description":"关键结论必须能回指到稳定来源",
                              "evaluationStandard":"关键结论必须携带可追溯 evidenceId 或来源链接",
                              "score":35,
                              "maxScore":100,
                              "status":"CRITICAL"
                            }
                          ],
                          "diagnoses": [
                            {
                              "dimensionCode":"EVIDENCE_TRACEABILITY",
                              "dimensionName":"证据可追溯性",
                              "type":"missing_evidence",
                              "section":"结论",
                              "severity":"ERROR",
                              "level":"BLOCKER",
                              "title":"关键结论缺少来源引用",
                              "detail":"结论章节中存在无法回指证据的判断。",
                              "evidenceBasis":"关键结论缺少可回指的证据编号。",
                              "sourceUrls":["https://docs.notion.so/security"],
                              "repairSuggestion":"补充证据编号或下调判断强度。"
                            }
                          ],
                          "revisionDirectives": [
                            {
                              "category":"SEARCH_QUALITY",
                              "actionType":"SUPPLEMENT_EVIDENCE",
                              "priority":"HIGH",
                              "targetNode":"collect_sources",
                              "targetSection":"结论",
                              "summary":"补充安全能力的官网证据",
                              "searchFeedback":"当前搜索结果缺少安全专题页面"
                            }
                          ],
                          "issues": [
                            {
                              "type":"missing_evidence",
                              "section":"结论",
                              "severity":"ERROR",
                              "level":"BLOCKER",
                              "dimensionCode":"EVIDENCE_TRACEABILITY",
                              "dimensionName":"证据可追溯性",
                              "evidenceBasis":"关键结论缺少可回指的证据编号。",
                              "sourceUrls":["https://docs.notion.so/security"],
                              "suggestion":"补充证据编号或下调判断强度。"
                            }
                          ]
                        }
                        """)
                .executionOrder(1)
                .build();
        TaskNode writerNode = TaskNode.builder()
                .taskId(400L)
                .nodeName("write_report")
                .agentType(AgentType.WRITER)
                .status(TaskNodeStatus.SUCCESS)
                .outputData("""
                        {
                          "sourceUrls": ["https://docs.notion.so/security"],
                          "evidenceFragments": [
                            {
                              "stage": "WRITE",
                              "competitorName": "Notion AI",
                              "fieldName": "report",
                              "evidenceId": "E-400",
                              "sourceUrl": "https://docs.notion.so/security",
                              "title": "Security Page",
                              "snippet": "security snippet",
                              "issueFlags": ["MISSING_BASIS"]
                            }
                          ]
                        }
                        """)
                .executionOrder(2)
                .build();

        when(reportRepository.findByTaskId(400L)).thenReturn(Optional.of(report));
        when(evidenceQueryService.listTaskEvidence(400L)).thenReturn(List.of(evidenceInfo));
        when(evidenceQueryService.toSectionEvidenceBundleInfo(anyList(), any()))
                .thenAnswer(invocation -> projectionEvidenceQueryService.toSectionEvidenceBundleInfo(
                        invocation.getArgument(0),
                        invocation.getArgument(1)
                ));
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(400L)).thenReturn(List.of());
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(400L)).thenReturn(List.of(reviewNode, writerNode));

        ReportResponse response = reportService.getReport(400L);

        assertNotNull(response.getInitialReview());
        assertEquals(1, response.getInitialReview().getDimensions().size());
        assertEquals("EVIDENCE_TRACEABILITY", response.getInitialReview().getDimensions().get(0).getCode());
        assertEquals(1, response.getInitialReview().getDiagnoses().size());
        assertEquals("BLOCKER", response.getInitialReview().getDiagnoses().get(0).getLevel());
        assertTrue(response.getInitialReview().getDiagnoses().get(0).getRepairSuggestion().contains("证据"));
        assertEquals(1, response.getInitialReview().getRevisionDirectives().size());
        assertEquals("SEARCH_QUALITY", response.getInitialReview().getRevisionDirectives().get(0).getCategory());
        assertEquals("关键结论缺少可回指的证据编号。", response.getQualityIssues().get(0).getEvidenceBasis());
        assertNotNull(response.getReportDiagnosis());
        assertEquals(1, response.getReportDiagnosis().getDiagnosisCount());
        assertEquals(1, response.getReportDiagnosis().getContentEvidences().size());
        assertEquals("E-400", response.getReportDiagnosis().getContentEvidences().get(0).getEvidence().getEvidenceId());
        assertEquals("INITIAL_REVIEW", response.getReportDiagnosis().getSections().get(0).getDiagnoses().get(0).getReviewStage());
        assertEquals(1, response.getReportDiagnosis().getRevisionDirectives().size());
        assertEquals("collect_sources", response.getReportDiagnosis().getRevisionDirectives().get(0).getTargetNode());
    }

    @Test
    void shouldExposeSectionEvidenceBundlesWithConclusionGapDetails() {
        Report report = Report.builder()
                .id(5L)
                .taskId(500L)
                .title("企业级竞品分析")
                .content("# Report")
                .summary("summary")
                .qualityPassed(true)
                .evidenceCount(1)
                .build();
        EvidenceSource evidenceSource = EvidenceSource.builder()
                .taskId(500L)
                .competitorName("Notion AI")
                .evidenceId("E-500")
                .title("Pricing Docs")
                .url("https://docs.notion.so/pricing")
                .contentSnippet("pricing snippet")
                .sourceType("DOCS")
                .build();
        ReportResponse.EvidenceInfo evidenceInfo = new ReportResponse.EvidenceInfo(
                "E-500",
                "Pricing Docs",
                "https://docs.notion.so/pricing",
                "pricing snippet",
                "Notion AI",
                null,
                "DOCS",
                "SEARCH",
                "docs.notion.so",
                "命中文档",
                null,
                0.91,
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                java.util.Map.of()
        );
        TaskNode writerNode = TaskNode.builder()
                .taskId(500L)
                .nodeName("write_report")
                .agentType(AgentType.WRITER)
                .status(TaskNodeStatus.SUCCESS)
                .outputData("""
                        {
                          "sourceUrls": ["https://docs.notion.so/pricing"],
                          "sectionEvidenceBundles": [
                            {
                              "stage": "ANALYZE",
                              "sectionType": "SECTION",
                              "sectionKey": "pricing",
                              "sectionTitle": "定价策略",
                              "gapSummary": "pricing 缺少稳定证据",
                              "missingFields": ["pricingComparison"],
                              "issueFlags": ["SECTION_EVIDENCE_GAP"],
                              "evidenceFragments": [
                                {
                                  "stage": "ANALYZE",
                                  "fieldName": "pricingComparison",
                                  "fieldLabel": "定价策略",
                                  "coverageStatus": "MISSING_EVIDENCE",
                                  "gapComment": "缺少稳定来源"
                                }
                              ]
                            },
                            {
                              "stage": "WRITE",
                              "sectionType": "CONCLUSION",
                              "sectionKey": "report_conclusion",
                              "sectionTitle": "报告结论",
                              "sourceUrls": ["https://docs.notion.so/pricing"],
                              "issueFlags": ["SECTION_EVIDENCE_GAP"],
                              "evidenceFragments": [
                                {
                                  "stage": "WRITE",
                                  "fieldName": "recommendations",
                                  "fieldLabel": "结论建议",
                                  "coverageStatus": "TRACEABLE",
                                  "evidenceId": "E-500",
                                  "sourceUrl": "https://docs.notion.so/pricing",
                                  "title": "Pricing Docs",
                                  "snippet": "pricing snippet"
                                }
                              ]
                            }
                          ]
                        }
                        """)
                .executionOrder(1)
                .build();

        when(reportRepository.findByTaskId(500L)).thenReturn(Optional.of(report));
        when(evidenceQueryService.listTaskEvidence(500L)).thenReturn(List.of(evidenceInfo));
        when(evidenceQueryService.toSectionEvidenceBundleInfo(anyList(), any()))
                .thenAnswer(invocation -> projectionEvidenceQueryService.toSectionEvidenceBundleInfo(
                        invocation.getArgument(0),
                        invocation.getArgument(1)
                ));
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(500L)).thenReturn(List.of());
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(500L)).thenReturn(List.of(writerNode));

        ReportResponse response = reportService.getReport(500L);

        assertEquals(2, response.getSectionEvidenceBundles().size());
        assertEquals("pricing", response.getSectionEvidenceBundles().get(0).getSectionKey());
        assertTrue(response.getSectionEvidenceBundles().get(0).getGapSummary().contains("pricing"));
        assertEquals("CONCLUSION", response.getSectionEvidenceBundles().get(1).getSectionType());
        assertEquals("E-500", response.getSectionEvidenceBundles().get(1).getFields().get(0).getEvidence().getEvidenceId());
    }

    @Test
    void shouldSynthesizeRevisionPlanWhenReviewerOnlyReturnsDiagnosisAndDirectives() {
        Report report = Report.builder()
                .id(6L)
                .taskId(600L)
                .title("企业级竞品分析")
                .content("# Report")
                .summary("summary")
                .qualityPassed(false)
                .evidenceCount(0)
                .build();

        TaskNode reviewNode = TaskNode.builder()
                .taskId(600L)
                .nodeName("quality_check")
                .status(TaskNodeStatus.SUCCESS)
                .outputData("""
                        {
                          "score": 68,
                          "passed": false,
                          "requiresHumanIntervention": false,
                          "autoRewriteAllowed": true,
                          "summary": "先补齐官网证据，再决定是否触发改写。",
                          "diagnoses": [
                            {
                              "dimensionCode":"EVIDENCE_TRACEABILITY",
                              "dimensionName":"证据可追溯性",
                              "type":"missing_evidence",
                              "section":"结论",
                              "severity":"ERROR",
                              "level":"BLOCKER",
                              "title":"关键结论缺少官网引用",
                              "detail":"结论中存在无法回指官网证据的判断。",
                              "repairSuggestion":"补充官网来源，再决定是否改写。"
                            }
                          ],
                          "issues": [
                            {
                              "type":"missing_evidence",
                              "section":"结论",
                              "severity":"ERROR",
                              "level":"BLOCKER",
                              "dimensionCode":"EVIDENCE_TRACEABILITY",
                              "dimensionName":"证据可追溯性",
                              "suggestion":"补充官网来源，再决定是否改写。"
                            }
                          ],
                          "revisionDirectives": [
                            {
                              "category":"SEARCH_QUALITY",
                              "actionType":"SUPPLEMENT_EVIDENCE",
                              "priority":"HIGH",
                              "targetNode":"collect_sources",
                              "targetSection":"结论",
                              "summary":"补充官网安全能力证据",
                              "searchFeedback":"当前搜索结果缺少官网安全专题页面",
                              "expectedOutcome":"让结论可以稳定回指官网来源"
                            }
                          ]
                        }
                        """)
                .executionOrder(1)
                .build();

        when(reportRepository.findByTaskId(600L)).thenReturn(Optional.of(report));
        when(evidenceQueryService.listTaskEvidence(600L)).thenReturn(List.of());
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(600L)).thenReturn(List.of());
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(600L)).thenReturn(List.of(reviewNode));

        ReportResponse response = reportService.getReport(600L);

        assertNotNull(response.getRevisionPlan());
        assertTrue(response.getRevisionPlan().isRewriteRequired());
        assertEquals("先补齐官网证据，再决定是否触发改写。", response.getRevisionPlan().getSummary());
        assertEquals(1, response.getRevisionPlan().getItems().size());
        assertEquals("结论", response.getRevisionPlan().getItems().get(0).getSection());
        assertEquals(1, response.getRevisionPlan().getDirectives().size());
        assertEquals("SUPPLEMENT_EVIDENCE", response.getRevisionPlan().getDirectives().get(0).getActionType());
        assertTrue(response.getRevisionPlan().getRewriteGuidelines().contains("补充官网来源，再决定是否改写。"));
    }
    @Test
    void shouldUpgradeDeliverySummaryWhenStructuredEvidenceGapsAreDiagnosed() {
        Report report = Report.builder()
                .id(7L)
                .taskId(700L)
                .title("企业级竞品分析")
                .content("# Report")
                .summary("summary")
                .qualityPassed(false)
                .evidenceCount(1)
                .build();
        ReportResponse.EvidenceInfo evidenceInfo = new ReportResponse.EvidenceInfo(
                "E-700",
                "Pricing Page",
                "https://www.notion.so/pricing",
                "pricing snippet",
                "Notion AI",
                null,
                "DOCS",
                "SEARCH",
                "www.notion.so",
                "命中定价页",
                null,
                0.71,
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                java.util.Map.of(
                        "qualitySignals", List.of("QUALITY_SIGNAL_FAILED"),
                        "structuredBlocks", List.of(),
                        "failureKind", "STRUCTURED_EXTRACTION_INSUFFICIENT",
                        "qualityScore", 0.18
                )
        );
        TaskNode reviewNode = TaskNode.builder()
                .taskId(700L)
                .nodeName("quality_check")
                .status(TaskNodeStatus.SUCCESS)
                .outputData("""
                        {
                          "score": 62,
                          "passed": false,
                          "summary": "当前报告仍缺结构化证据闭环",
                          "diagnoses": [
                            {
                              "dimensionCode":"SEARCH_QUALITY",
                              "dimensionName":"搜索质量",
                              "type":"missing_structured_evidence",
                              "section":"targetUsers",
                              "severity":"ERROR",
                              "level":"BLOCKER",
                              "title":"结构化证据不足",
                              "detail":"定价结论缺少可复用 structuredBlocks 支撑。",
                              "evidenceBasis":"sourceUrls 已存在，但 structuredBlocks 缺失，qualitySignals 命中失败，evidenceCoverage 仍缺字段。",
                              "evidenceIds":["E-700"],
                              "sourceUrls":["https://www.notion.so/pricing"],
                              "repairSuggestion":"先补齐 structuredBlocks，再回填 coverage 并复核定价结论。"
                            }
                          ],
                          "issues": [
                            {
                              "type":"missing_structured_evidence",
                              "section":"targetUsers",
                              "severity":"ERROR",
                              "level":"BLOCKER",
                              "dimensionCode":"SEARCH_QUALITY",
                              "dimensionName":"搜索质量",
                              "evidenceBasis":"sourceUrls 已存在，但 structuredBlocks 缺失，qualitySignals 命中失败，evidenceCoverage 仍缺字段。",
                              "evidenceIds":["E-700"],
                              "sourceUrls":["https://www.notion.so/pricing"],
                              "suggestion":"先补齐 structuredBlocks，再回填 coverage 并复核定价结论。"
                            }
                          ]
                        }
                        """)
                .executionOrder(1)
                .build();

        when(reportRepository.findByTaskId(700L)).thenReturn(Optional.of(report));
        when(evidenceQueryService.listTaskEvidence(700L)).thenReturn(List.of(evidenceInfo));
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(700L)).thenReturn(List.of());
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(700L)).thenReturn(List.of(reviewNode));

        ReportResponse response = reportService.getReport(700L);

        assertNotNull(response.getDeliverySummary());
        assertTrue(response.getDeliverySummary().getSummary().contains("structuredBlocks"));
        assertTrue(response.getDeliverySummary().getSummary().contains("qualitySignals"));
        assertTrue(response.getDeliverySummary().getSummary().contains("evidenceCoverage"));
        assertTrue(response.getDeliverySummary().getPrimaryIssue().contains("结构化证据"));
        assertTrue(response.getReportDiagnosis().getSections().get(0).getRepairSuggestions().stream()
                .anyMatch(item -> item.contains("structuredBlocks")));
    }

    /**
     * 协作决策只读投影当前仍处于渐进式接入阶段，
     * 测试先按“字段存在就注入，不存在就保持红灯”的方式运行，避免为 Red 阶段额外引入构造器耦合。
     */
    private void injectTaskWorkflowEventRepositoryIfPresent(ReportService target) throws Exception {
        try {
            Field field = ReportService.class.getDeclaredField("taskWorkflowEventRepository");
            field.setAccessible(true);
            field.set(target, taskWorkflowEventRepository);
        } catch (NoSuchFieldException ignored) {
            // Red 阶段允许字段尚未落地；断言会通过缺失投影继续失败。
        }
    }

    /**
     * Task 08 的报告契约必须消费数据库中真实保存的事件 JSON，不能重新手构一份简化 mock。
     * 这里从冻结的 V2 fixture 中按 caseId 取出 payload，确保写侧 schema 变化会直接让报告测试变红。
     */
    private TaskWorkflowEvent persistedV2Event(Long taskId,
                                               String nodeName,
                                               String caseId) throws Exception {
        byte[] fixtureBytes;
        try (var input = getClass().getResourceAsStream(
                "/orchestration/orchestration-trace-v2-fixtures.json")) {
            assertNotNull(input, "V2 trace fixture must exist");
            fixtureBytes = input.readAllBytes();
        }
        JsonNode root = new ObjectMapper().readTree(fixtureBytes);
        for (JsonNode fixtureCase : root.path("cases")) {
            if (caseId.equals(fixtureCase.path("caseId").asText())) {
                JsonNode payload = fixtureCase.path("payload");
                return TaskWorkflowEvent.builder()
                        .taskId(taskId)
                        .nodeName(nodeName)
                        .eventType(WorkflowEventType.ORCHESTRATION_DECISION_RECORDED)
                        .payload(payload.toString())
                        .sourceUrls(payload.path("sourceUrls").toString())
                        .build();
            }
        }
        throw new IllegalArgumentException("unknown V2 trace fixture case: " + caseId);
    }

    private Report minimalReport(Long taskId, String title) {
        return Report.builder()
                .id(taskId)
                .taskId(taskId)
                .title(title)
                .content("# Report")
                .summary("summary")
                .qualityPassed(false)
                .evidenceCount(0)
                .build();
    }

    private void stubReportMainPath(Report report) {
        Long taskId = report.getTaskId();
        when(reportRepository.findByTaskId(taskId)).thenReturn(Optional.of(report));
        when(evidenceQueryService.listTaskEvidence(taskId)).thenReturn(List.of());
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(taskId)).thenReturn(List.of());
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId)).thenReturn(List.of());
    }

    /**
     * 闃舵 1 闄嶇骇棣栨姤鍦ㄦ祴璇曢噷涔熷繀椤诲拰鐢熶骇绾㈢嚎淇濇寔涓€鑷达細
     * 鑷冲皯 5 鏉″彲杩芥函 URL锛屽苟涓旀潵鑷充笉灏戜簬 2 涓綊涓€鍩熷悕銆?
     */
    private List<ReportResponse.EvidenceInfo> stageOneTraceableEvidenceInfos() {
        return List.of(
                traceableEvidence("E711-1", "https://www.notion.so/product/ai", "OFFICIAL", "www.notion.so"),
                traceableEvidence("E711-2", "https://www.notion.so/security", "OFFICIAL", "www.notion.so"),
                traceableEvidence("E711-3", "https://docs.notion.so/ai", "DOCS", "docs.notion.so"),
                traceableEvidence("E711-4", "https://docs.notion.so/admins", "DOCS", "docs.notion.so"),
                traceableEvidence("E711-5", "https://www.g2.com/products/notion-ai/reviews", "REVIEW", "www.g2.com")
        );
    }

    private ReportResponse.EvidenceInfo traceableEvidence(String evidenceId,
                                                          String url,
                                                          String sourceType,
                                                          String domain) {
        return new ReportResponse.EvidenceInfo(
                evidenceId,
                "Traceable source",
                url,
                "snippet",
                "Notion AI",
                null,
                sourceType,
                "SEARCH",
                domain,
                null,
                null,
                0.92,
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                java.util.Map.of()
        );
    }

    private Report reportWithPersistedWriterEvidenceSnapshot(Long taskId) {
        return Report.builder()
                .id(taskId)
                .taskId(taskId)
                .title("ReportWriting 证据下载")
                .content("# report")
                .summary("summary")
                .evidenceCount(0)
                .writerEvidenceState("PARTIAL_SOURCE")
                .citationGapSeverity("HIGH")
                .missingCitationSections("[\"pricing\"]")
                .sectionCitationGaps("""
                        [
                          {
                            "targetSection":"pricing",
                            "sectionTitle":"定价策略",
                            "summary":"定价章节已有来源但缺逐句引用",
                            "severity":"HIGH",
                            "evidenceState":"PARTIAL_SOURCE",
                            "sourceUrls":["https://www.notion.so/pricing"],
                            "missingFields":["pricingComparison"],
                            "suggestedQueries":["Notion AI pricing official source"]
                          }
                        ]
                        """)
                .writerIssueFlags("[\"WRITER_CITATION_GAP\"]")
                .writerSourceUrls("[\"https://www.notion.so/pricing\"]")
                .build();
    }
}
