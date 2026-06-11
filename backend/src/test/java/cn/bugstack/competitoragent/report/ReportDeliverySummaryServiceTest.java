package cn.bugstack.competitoragent.report;

import cn.bugstack.competitoragent.model.dto.ReportResponse;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.model.entity.Report;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.repository.ReportRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.workflow.contract.QualityDiagnosis;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 5.7.c 交付中心主路径摘要测试。
 * <p>
 * 这个测试只覆盖当前子任务的完成标志：
 * 1. 报告响应直接暴露可交付性摘要，而不是让前端先拼原始诊断对象；
 * 2. 报告响应直接暴露证据入口摘要，便于用户先找到最关键的来源；
 * 3. 报告响应直接暴露审计摘要，便于主路径先解释检索与任务审计背景。
 */
class ReportDeliverySummaryServiceTest {

    private final ReportRepository reportRepository = mock(ReportRepository.class);
    private final EvidenceSourceRepository evidenceRepository = mock(EvidenceSourceRepository.class);
    private final CompetitorKnowledgeRepository knowledgeRepository = mock(CompetitorKnowledgeRepository.class);
    private final TaskNodeRepository taskNodeRepository = mock(TaskNodeRepository.class);
    private final EvidenceQueryService evidenceQueryService = mock(EvidenceQueryService.class);
    private final ReportDiagnosisAssembler reportDiagnosisAssembler = mock(ReportDiagnosisAssembler.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldExposeDeliveryCenterSummaryFieldsForDefaultReportPath() throws Exception {
        ReportService reportService = instantiateReportService();
        String sourceUrl = "https://docs.notion.so/security";

        Report report = Report.builder()
                .id(71L)
                .taskId(700L)
                .title("企业级竞品分析报告")
                .content("# Report")
                .summary("当前版本已经形成结论，但仍需补齐关键证据。")
                .qualityScore(72)
                .qualityPassed(false)
                .evidenceCount(1)
                .build();
        EvidenceSource evidence = EvidenceSource.builder()
                .taskId(700L)
                .competitorName("Notion AI")
                .evidenceId("E-700")
                .title("Notion 安全文档")
                .url(sourceUrl)
                .contentSnippet("Notion 提供企业安全与权限治理说明。")
                .sourceType("DOCS")
                .collectedAt(LocalDateTime.of(2026, 6, 8, 16, 10, 0))
                .build();
        ReportResponse.EvidenceInfo evidenceInfo = new ReportResponse.EvidenceInfo(
                "E-700",
                "Notion 安全文档",
                sourceUrl,
                "Notion 提供企业安全与权限治理说明。",
                "Notion AI",
                LocalDateTime.of(2026, 6, 8, 16, 10, 0),
                "DOCS",
                "SEARCH",
                "docs.notion.so",
                "命中官方安全说明页",
                "2026-06-01",
                0.94,
                true,
                "官方域名已校验",
                "Notion AI security",
                "bing",
                1,
                "trace-700",
                "与关键结论直接相关",
                "SELECTED",
                List.of("security"),
                java.util.Map.of("collector", "mock")
        );
        TaskNode collectorNode = TaskNode.builder()
                .taskId(700L)
                .nodeName("collect_sources_notion_security")
                .displayName("采集安全资料")
                .agentType(AgentType.COLLECTOR)
                .status(TaskNodeStatus.SUCCESS)
                .outputData("""
                        {
                          "competitor": "Notion AI",
                          "sourceType": "DOCS",
                          "searchExecutionTrace": {
                            "plannedCandidateCount": 2,
                            "verifiedCandidateCount": 1,
                            "supplementedCandidateCount": 0,
                            "selectedCandidateCount": 1,
                            "selectedUrls": ["https://docs.notion.so/security"]
                          }
                        }
                        """)
                .executionOrder(1)
                .build();
        TaskNode analyzerNode = TaskNode.builder()
                .taskId(700L)
                .nodeName("analyze_competitors")
                .displayName("分析")
                .agentType(AgentType.ANALYZER)
                .status(TaskNodeStatus.SUCCESS)
                .outputData("""
                        {
                          "taskRagContext": "检索查询：Notion AI security\\n缺口说明：当前仍缺企业权限治理专题证据"
                        }
                        """)
                .executionOrder(2)
                .build();
        TaskNode finalReviewNode = TaskNode.builder()
                .taskId(700L)
                .nodeName("quality_check_final")
                .displayName("终审")
                .agentType(AgentType.REVIEWER)
                .status(TaskNodeStatus.SUCCESS)
                .outputData("""
                        {
                          "score": 72,
                          "passed": false,
                          "summary": "当前不可直接交付，仍需补齐关键证据。",
                          "revisionPlan": {
                            "rewriteRequired": true,
                            "summary": "先补齐官方安全证据，再决定是否重写结论。",
                            "items": [
                              {
                                "type": "MISSING_EVIDENCE",
                                "section": "结论",
                                "severity": "ERROR",
                                "suggestion": "先补齐官方安全证据，再决定是否重写结论。"
                              }
                            ],
                            "rewriteGuidelines": [
                              "先补齐官方安全证据，再决定是否重写结论。"
                            ]
                          }
                        }
                        """)
                .executionOrder(3)
                .build();
        ReportResponse.ReportDiagnosisInfo reportDiagnosis = ReportResponse.ReportDiagnosisInfo.builder()
                .diagnosisCount(2)
                .blockerCount(1)
                .evidenceGapCount(1)
                .sourceUrls(List.of(sourceUrl))
                .sections(List.of(ReportResponse.DiagnosisSection.builder()
                        .section("结论")
                        .evidenceInsufficient(true)
                        .sourceUrls(List.of(sourceUrl))
                        .repairSuggestions(List.of("先补齐官方安全证据，再决定是否重写结论。"))
                        .diagnoses(List.of(ReportResponse.DiagnosisItem.builder()
                                .reviewStage("FINAL_REVIEW")
                                .diagnosis(QualityDiagnosis.builder()
                                        .section("结论")
                                        .level("BLOCKER")
                                        .title("关键结论缺少来源引用")
                                        .sourceUrls(List.of(sourceUrl))
                                        .repairSuggestion("先补齐官方安全证据，再决定是否重写结论。")
                                        .build()
                                        .normalized())
                                .evidenceReferences(List.of())
                                .build()))
                        .build()))
                .nextActions(List.of(new ReportResponse.ReviewNextAction(
                        "前往补充证据",
                        "先补齐官方安全证据，再决定是否重写结论。",
                        "SUPPLEMENT_EVIDENCE",
                        "collect_sources_notion_security",
                        "HIGH"
                )))
                .build();

        when(reportRepository.findByTaskId(700L)).thenReturn(Optional.of(report));
        when(evidenceQueryService.listTaskEvidence(700L)).thenReturn(List.of(evidenceInfo));
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(700L)).thenReturn(List.of());
        when(taskNodeRepository.findByTaskIdOrderByExecutionOrderAsc(700L))
                .thenReturn(List.of(collectorNode, analyzerNode, finalReviewNode));
        when(reportDiagnosisAssembler.assemble(anyList(), anyList(), any(), any(), any(), anyList()))
                .thenReturn(reportDiagnosis);

        ReportResponse response = reportService.getReport(700L);

        Object deliverySummary = readField(response, "deliverySummary");
        assertNotNull(deliverySummary);
        assertEquals(Boolean.FALSE, readField(deliverySummary, "readyForDelivery"));
        assertEquals("BLOCKED", readField(deliverySummary, "deliveryStatus"));
        assertTrue(String.valueOf(readField(deliverySummary, "summary")).contains("1 个阻塞问题"));
        assertTrue(String.valueOf(readField(deliverySummary, "primaryIssue")).contains("关键结论缺少来源引用"));
        assertTrue(String.valueOf(readField(deliverySummary, "recommendedAction")).contains("先补齐官方安全证据"));

        Object evidenceEntryPoint = readField(response, "evidenceEntryPoint");
        assertNotNull(evidenceEntryPoint);
        assertEquals("E-700", readField(evidenceEntryPoint, "evidenceId"));
        assertEquals(sourceUrl, readField(evidenceEntryPoint, "url"));
        assertTrue(String.valueOf(readField(evidenceEntryPoint, "summary")).contains("Notion 安全文档"));

        Object auditSummary = readField(response, "auditSummary");
        assertNotNull(auditSummary);
        assertTrue(String.valueOf(readField(auditSummary, "searchAuditSummary")).contains("采集节点 1 个"));
        assertTrue(String.valueOf(readField(auditSummary, "taskRagAuditSummary")).contains("检索查询：Notion AI security"));
        assertTrue(String.valueOf(readField(auditSummary, "summary")).contains("采集节点 1 个"));
    }

    /**
     * ReportService 在当前仓库里仍处于快速演进期，
     * 这里通过反射兼容构造器签名变化，避免测试因为非目标字段演进直接失焦。
     */
    private ReportService instantiateReportService() throws Exception {
        for (Constructor<?> constructor : ReportService.class.getDeclaredConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length == 7) {
                return (ReportService) constructor.newInstance(
                        reportRepository,
                        evidenceRepository,
                        knowledgeRepository,
                        taskNodeRepository,
                        evidenceQueryService,
                        reportDiagnosisAssembler,
                        objectMapper
                );
            }
        }
        fail("ReportService 应提供当前报告摘要测试可用的构造器");
        return null;
    }

    /**
     * 使用反射读取字段，确保 Red 阶段能清晰看到“摘要字段尚未建模”的失败信息。
     */
    private Object readField(Object target, String fieldName) throws Exception {
        if (target == null) {
            fail("目标对象为空，无法读取字段 " + fieldName);
        }
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (NoSuchFieldException exception) {
            fail(target.getClass().getSimpleName() + " 应声明字段 " + fieldName);
            return null;
        }
    }
}
