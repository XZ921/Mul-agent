package cn.bugstack.competitoragent.report;

import cn.bugstack.competitoragent.model.dto.ReportResponse;
import cn.bugstack.competitoragent.model.dto.ReportResponse.DiagnosisSection;
import cn.bugstack.competitoragent.model.dto.ReportResponse.EvidenceCoverageOverview;
import cn.bugstack.competitoragent.model.dto.ReportResponse.EvidenceInfo;
import cn.bugstack.competitoragent.model.dto.ReportResponse.QualityIssue;
import cn.bugstack.competitoragent.model.dto.ReportResponse.ReviewCheckpoint;
import cn.bugstack.competitoragent.model.dto.ReportResponse.ReviewNextAction;
import cn.bugstack.competitoragent.model.dto.ReportResponse.RevisionDirectiveInfo;
import cn.bugstack.competitoragent.model.dto.ReportResponse.SectionEvidenceCoverage;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.workflow.contract.QualityDiagnosis;
import cn.bugstack.competitoragent.workflow.contract.RevisionDirective;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ReportDiagnosisAssemblerTest {

    private final EvidenceQueryService evidenceQueryService =
            new EvidenceQueryService(mock(cn.bugstack.competitoragent.repository.EvidenceSourceRepository.class), new ObjectMapper());
    private final ReportDiagnosisAssembler assembler = new ReportDiagnosisAssembler(new ObjectMapper(), evidenceQueryService);

    @Test
    void shouldAssembleDiagnosisSectionsWithLinkedEvidenceAndWriterFragments() {
        List<EvidenceInfo> evidences = List.of(new EvidenceInfo(
                "E-001",
                "Notion AI Docs",
                "https://docs.notion.so/ai",
                "docs snippet",
                "Notion AI",
                LocalDateTime.now(),
                "DOCS",
                "SEARCH",
                "docs.notion.so",
                "命中文档首页",
                "2026-05-20",
                0.93,
                true,
                "docs 信号命中",
                "Notion AI docs",
                "bing",
                1,
                "trace-001",
                "验证通过",
                "SELECTED",
                List.of("docs"),
                java.util.Map.of("verified", true)
        ));
        ReviewCheckpoint initialReview = ReviewCheckpoint.builder()
                .nodeName("quality_check")
                .nodeStatus(TaskNodeStatus.SUCCESS)
                .score(72)
                .passed(false)
                .diagnoses(List.of(QualityDiagnosis.builder()
                        .dimensionCode("EVIDENCE_TRACEABILITY")
                        .dimensionName("证据可追溯性")
                        .type("missing_evidence")
                        .section("结论")
                        .severity("ERROR")
                        .level("BLOCKER")
                        .title("关键结论缺少来源引用")
                        .detail("结论章节中存在无法回指证据的判断。")
                        .evidenceBasis("关键结论缺少可回指的证据编号。")
                        .evidenceIds(List.of("E-001"))
                        .sourceUrls(List.of("https://docs.notion.so/ai"))
                        .repairSuggestion("补充证据编号并降低结论强度。")
                        .build().normalized()))
                .nextActions(List.of(new ReviewNextAction(
                        "补充结论证据",
                        "先补充结论引用，再决定是否改写",
                        "RERUN_FROM_NODE",
                        "collect_sources_web",
                        "HIGH"
                )))
                .revisionDirectives(List.of(RevisionDirective.builder()
                        .category("SEARCH_QUALITY")
                        .actionType("SUPPLEMENT_EVIDENCE")
                        .priority("HIGH")
                        .targetNode("collect_sources")
                        .targetSection("结论")
                        .summary("补充结论证据")
                        .searchFeedback("当前搜索结果缺少稳定官网来源")
                        .build().normalized()))
                .build();
        EvidenceCoverageOverview coverageOverview = EvidenceCoverageOverview.builder()
                .sections(List.of(SectionEvidenceCoverage.builder()
                        .sectionKey("positioning")
                        .sectionTitle("市场定位")
                        .totalFields(1)
                        .traceableFields(0)
                        .missingEvidenceFields(1)
                        .emptyFields(0)
                        .missingFields(List.of("positioning"))
                        .build()))
                .build();
        TaskNode writerNode = TaskNode.builder()
                .nodeName("write_report")
                .agentType(AgentType.WRITER)
                .status(TaskNodeStatus.SUCCESS)
                .outputData("""
                        {
                          "sourceUrls": ["https://docs.notion.so/ai"],
                          "evidenceFragments": [
                            {
                              "stage": "WRITE",
                              "competitorName": "Notion AI",
                              "fieldName": "report",
                              "evidenceId": "E-001",
                              "sourceUrl": "https://docs.notion.so/ai",
                              "title": "Notion AI Docs",
                              "snippet": "docs snippet",
                              "issueFlags": ["MISSING_BASIS"]
                            }
                          ]
                        }
                        """)
                .build();

        ReportResponse.ReportDiagnosisInfo diagnosis = assembler.assemble(
                evidences,
                List.of(),
                initialReview,
                null,
                coverageOverview,
                List.of(writerNode)
        );

        assertNotNull(diagnosis);
        assertEquals(1, diagnosis.getDiagnosisCount());
        assertEquals(1, diagnosis.getBlockerCount());
        assertTrue(diagnosis.getSourceUrls().contains("https://docs.notion.so/ai"));
        assertEquals(1, diagnosis.getContentEvidences().size());
        assertEquals("E-001", diagnosis.getContentEvidences().get(0).getEvidence().getEvidenceId());
        assertEquals(1, diagnosis.getNextActions().size());
        assertEquals(1, diagnosis.getRevisionDirectives().size());
        assertEquals("SEARCH_QUALITY", diagnosis.getRevisionDirectives().get(0).getCategory());
        assertEquals("collect_sources", diagnosis.getRevisionDirectives().get(0).getTargetNode());
        DiagnosisSection conclusion = diagnosis.getSections().stream()
                .filter(section -> "结论".equals(section.getSection()))
                .findFirst()
                .orElseThrow();
        assertTrue(conclusion.getEvidenceInsufficient());
        assertTrue(conclusion.getRepairSuggestions().contains("补充证据编号并降低结论强度。"));
        assertEquals("INITIAL_REVIEW", conclusion.getDiagnoses().get(0).getReviewStage());
        assertEquals("E-001", conclusion.getDiagnoses().get(0).getEvidenceReferences().get(0).getEvidenceId());
    }

    @Test
    void shouldFallbackToReportQualityIssuesWhenReviewDiagnosesMissing() {
        List<EvidenceInfo> evidences = List.of(new EvidenceInfo(
                "E-009",
                "Security Page",
                "https://www.notion.so/security",
                "security snippet",
                "Notion AI",
                LocalDateTime.now(),
                "OFFICIAL",
                "DIRECT",
                "www.notion.so",
                "官网直连",
                null,
                0.88,
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
        ));
        List<QualityIssue> issues = List.of(new QualityIssue(
                "missing_evidence",
                "结论",
                "ERROR",
                "BLOCKER",
                "EVIDENCE_TRACEABILITY",
                "证据可追溯性",
                "关键结论缺少可回指的证据编号。",
                List.of("E-009"),
                List.of("https://www.notion.so/security"),
                "补充证据或下调判断强度。"
        ));

        ReportResponse.ReportDiagnosisInfo diagnosis = assembler.assemble(
                evidences,
                issues,
                null,
                null,
                null,
                List.of()
        );

        assertNotNull(diagnosis);
        assertEquals(1, diagnosis.getDiagnosisCount());
        assertEquals(1, diagnosis.getContentEvidences().size());
        assertEquals("E-009", diagnosis.getContentEvidences().get(0).getEvidenceId());
        DiagnosisSection section = diagnosis.getSections().stream()
                .filter(item -> "结论".equals(item.getSection()))
                .findFirst()
                .orElseThrow();
        assertTrue(section.getEvidenceInsufficient());
        assertEquals("REPORT", section.getDiagnoses().get(0).getReviewStage());
        assertEquals("https://www.notion.so/security", section.getDiagnoses().get(0).getEvidenceReferences().get(0).getUrl());
    }

    @Test
    void shouldBackfillDirectiveSourceUrlsFromMatchingDiagnosisSection() {
        List<EvidenceInfo> evidences = List.of(new EvidenceInfo(
                "E-010",
                "Security Page",
                "https://www.notion.so/security",
                "security snippet",
                "Notion AI",
                LocalDateTime.now(),
                "OFFICIAL",
                "DIRECT",
                "www.notion.so",
                "官网直连",
                null,
                0.88,
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
        ));
        ReviewCheckpoint initialReview = ReviewCheckpoint.builder()
                .nodeName("quality_check")
                .nodeStatus(TaskNodeStatus.SUCCESS)
                .score(70)
                .passed(false)
                .diagnoses(List.of(QualityDiagnosis.builder()
                        .dimensionCode("EVIDENCE_TRACEABILITY")
                        .dimensionName("证据可追溯性")
                        .type("missing_evidence")
                        .section("结论")
                        .severity("ERROR")
                        .level("BLOCKER")
                        .title("关键结论缺少来源引用")
                        .detail("结论章节中存在无法回指证据的判断。")
                        .evidenceIds(List.of("E-010"))
                        .sourceUrls(List.of("https://www.notion.so/security"))
                        .repairSuggestion("补充官网来源。")
                        .build().normalized()))
                .revisionDirectives(List.of(RevisionDirective.builder()
                        .category("SEARCH_QUALITY")
                        .actionType("SUPPLEMENT_EVIDENCE")
                        .priority("HIGH")
                        .targetNode("collect_sources")
                        .targetSection("结论")
                        .summary("补充安全能力证据")
                        .searchFeedback("当前搜索结果缺少官网安全页面")
                        .build().normalized()))
                .build();

        ReportResponse.ReportDiagnosisInfo diagnosis = assembler.assemble(
                evidences,
                List.of(),
                initialReview,
                null,
                null,
                List.of()
        );

        assertNotNull(diagnosis);
        assertEquals(1, diagnosis.getRevisionDirectives().size());
        assertEquals(List.of("https://www.notion.so/security"), diagnosis.getRevisionDirectives().get(0).getSourceUrls());
    }

    @Test
    void shouldTreatMissingStructuredEvidenceAsEvidenceInsufficientDiagnosis() {
        List<EvidenceInfo> evidences = List.of(new EvidenceInfo(
                "E-011",
                "Pricing Page",
                "https://www.notion.so/pricing",
                "pricing snippet",
                "Notion AI",
                LocalDateTime.now(),
                "DOCS",
                "SEARCH",
                "www.notion.so",
                "命中定价页",
                null,
                0.66,
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
                        "qualityScore", 0.22
                )
        ));
        ReviewCheckpoint initialReview = ReviewCheckpoint.builder()
                .nodeName("quality_check")
                .nodeStatus(TaskNodeStatus.SUCCESS)
                .score(69)
                .passed(false)
                .diagnoses(List.of(QualityDiagnosis.builder()
                        .dimensionCode("SEARCH_QUALITY")
                        .dimensionName("搜索质量")
                        .type("missing_structured_evidence")
                        .section("定价对比")
                        .severity("ERROR")
                        .level("BLOCKER")
                        .title("结构化证据不足")
                        .detail("定价结论缺少稳定结构化块支撑")
                        .evidenceBasis("structuredBlocks 缺失，qualitySignals 显示质量门槛未达标。")
                        .evidenceIds(List.of("E-011"))
                        .sourceUrls(List.of("https://www.notion.so/pricing"))
                        .repairSuggestion("先补齐可用 structuredBlocks，再决定是否保留当前定价结论。")
                        .build().normalized()))
                .build();

        ReportResponse.ReportDiagnosisInfo diagnosis = assembler.assemble(
                evidences,
                List.of(),
                initialReview,
                null,
                null,
                List.of()
        );

        assertNotNull(diagnosis);
        assertEquals(1, diagnosis.getEvidenceGapCount());
        DiagnosisSection section = diagnosis.getSections().stream()
                .filter(item -> "定价对比".equals(item.getSection()))
                .findFirst()
                .orElseThrow();
        assertTrue(section.getEvidenceInsufficient());
        assertTrue(section.getRepairSuggestions().stream()
                .anyMatch(item -> item.contains("structuredBlocks") || item.contains("结构化")));
    }
}
