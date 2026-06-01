package cn.bugstack.competitoragent.report;

import cn.bugstack.competitoragent.model.dto.ReportResponse;
import cn.bugstack.competitoragent.model.entity.CompetitorKnowledge;
import cn.bugstack.competitoragent.model.entity.Report;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.repository.ReportRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportServiceTest {

    private final ReportRepository reportRepository = mock(ReportRepository.class);
    private final EvidenceSourceRepository evidenceRepository = mock(EvidenceSourceRepository.class);
    private final CompetitorKnowledgeRepository knowledgeRepository = mock(CompetitorKnowledgeRepository.class);
    private final TaskNodeRepository taskNodeRepository = mock(TaskNodeRepository.class);
    private final EvidenceQueryService evidenceQueryService = mock(EvidenceQueryService.class);
    private final ReportService reportService = new ReportService(
            reportRepository,
            evidenceRepository,
            knowledgeRepository,
            taskNodeRepository,
            evidenceQueryService,
            new ObjectMapper()
    );

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
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(100L)).thenReturn(List.of());
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
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(200L)).thenReturn(List.of());
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
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(300L)).thenReturn(List.of());
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
}
