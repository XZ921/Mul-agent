package cn.bugstack.competitoragent.workflow.contract;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualityDiagnosisTest {

    @Test
    void shouldNormalizeEvidenceBasisAndFallbackRepairSuggestion() {
        QualityDiagnosis diagnosis = QualityDiagnosis.builder()
                .dimensionCode("CLAIM_SUPPORT")
                .dimensionName("结论支撑度")
                .type("missing_evidence")
                .section("结论")
                .severity("ERROR")
                .level("BLOCKER")
                .detail("关键结论缺少证据引用")
                .evidenceIds(List.of("E001", " ", "E001"))
                .sourceUrls(List.of("https://docs.notion.so", "", "https://docs.notion.so"))
                .build();

        QualityDiagnosis normalized = diagnosis.normalized();

        assertEquals(List.of("E001"), normalized.getEvidenceIds());
        assertEquals(List.of("https://docs.notion.so"), normalized.getSourceUrls());
        assertTrue(normalized.getEvidenceBasis().contains("缺少可回指证据"));
        assertTrue(normalized.getRepairSuggestion().contains("证据"));
    }

    @Test
    void shouldProjectDiagnosisToExplainableQualityIssue() {
        QualityDiagnosis diagnosis = QualityDiagnosis.builder()
                .dimensionCode("EVIDENCE_TRACEABILITY")
                .dimensionName("证据可追溯性")
                .type("unsupported_claim")
                .section("功能对比")
                .severity("WARNING")
                .level("MAJOR")
                .title("功能判断与来源脱节")
                .detail("功能对比章节存在无法回指到来源链接的判断")
                .evidenceBasis("段落中没有显式 [证据：EID] 引用，且上游结构化字段存在缺证据标记。")
                .sourceUrls(List.of("https://docs.notion.so/product"))
                .repairSuggestion("补充对应证据编号，或将判断降级为保守描述。")
                .build();

        QualityIssue issue = diagnosis.toQualityIssue();

        assertEquals("EVIDENCE_TRACEABILITY", issue.getDimensionCode());
        assertEquals("MAJOR", issue.getLevel());
        assertEquals("WARNING", issue.getSeverity());
        assertEquals("补充对应证据编号，或将判断降级为保守描述。", issue.getSuggestion());
        assertTrue(issue.getEvidenceBasis().contains("缺证据"));
        assertEquals(List.of("https://docs.notion.so/product"), issue.getSourceUrls());
    }
}
