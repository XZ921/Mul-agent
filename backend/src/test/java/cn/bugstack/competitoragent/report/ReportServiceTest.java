package cn.bugstack.competitoragent.report;

import cn.bugstack.competitoragent.model.dto.ReportResponse;
import cn.bugstack.competitoragent.model.entity.CompetitorKnowledge;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportServiceTest {

    private final ReportRepository reportRepository = mock(ReportRepository.class);
    private final EvidenceSourceRepository evidenceRepository = mock(EvidenceSourceRepository.class);
    private final CompetitorKnowledgeRepository knowledgeRepository = mock(CompetitorKnowledgeRepository.class);
    private final TaskNodeRepository taskNodeRepository = mock(TaskNodeRepository.class);
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
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(3100L)).thenReturn(List.of());
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
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(3200L)).thenReturn(List.of());
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
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(3300L)).thenReturn(List.of());
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
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(400L)).thenReturn(List.of(evidenceSource));
        when(evidenceQueryService.toEvidenceInfo(evidenceSource)).thenReturn(evidenceInfo);
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
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(500L)).thenReturn(List.of(evidenceSource));
        when(evidenceQueryService.toEvidenceInfo(evidenceSource)).thenReturn(evidenceInfo);
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
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(600L)).thenReturn(List.of());
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
}
