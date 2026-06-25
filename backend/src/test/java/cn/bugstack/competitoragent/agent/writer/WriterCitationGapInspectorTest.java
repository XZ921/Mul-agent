package cn.bugstack.competitoragent.agent.writer;

import cn.bugstack.competitoragent.workflow.contract.SectionEvidenceBundle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WriterCitationGapInspectorTest {

    private final WriterCitationGapInspector inspector = new WriterCitationGapInspector();

    @Test
    void shouldExposeMissingSourceSectionGapFromWriterBundles() {
        SectionEvidenceBundle bundle = SectionEvidenceBundle.builder()
                .stage("WRITE")
                .sectionType("CONCLUSION")
                .sectionKey("report_conclusion")
                .sectionTitle("报告结论")
                .missingFields(List.of("recommendations"))
                .sourceUrls(List.of())
                .issueFlags(List.of("SECTION_EVIDENCE_GAP"))
                .build()
                .normalized();

        WriterCitationGapInspector.InspectionResult result = inspector.inspect(
                "# 竞品报告\n## 建议\n建议推进连接器生态。",
                List.of(bundle),
                List.of());

        assertThat(result.severity()).isEqualTo("ERROR");
        assertThat(result.evidenceState()).isEqualTo("MISSING_SOURCE");
        assertThat(result.missingCitationSections()).containsExactly("report_conclusion");
        assertThat(result.issueFlags()).contains("WRITER_CITATION_GAP", "WRITER_MISSING_SOURCE");
        assertThat(result.gaps()).hasSize(1);
        assertThat(result.gaps().get(0).getTargetSection()).isEqualTo("report_conclusion");
        assertThat(result.gaps().get(0).getEvidenceState()).isEqualTo("MISSING_SOURCE");
    }

    @Test
    void shouldExposeSourceBackedCitationGapWithoutPretendingCitationAgent() {
        SectionEvidenceBundle bundle = SectionEvidenceBundle.builder()
                .stage("WRITE")
                .sectionType("SECTION")
                .sectionKey("pricing")
                .sectionTitle("定价策略")
                .missingFields(List.of("pricingComparison"))
                .sourceUrls(List.of("https://www.notion.so/pricing"))
                .issueFlags(List.of("SECTION_EVIDENCE_GAP"))
                .build()
                .normalized();

        WriterCitationGapInspector.InspectionResult result = inspector.inspect(
                "# 竞品报告\n## 定价策略\n定价信息需要补充逐句引用。",
                List.of(bundle),
                List.of("https://www.notion.so/pricing"));

        assertThat(result.severity()).isEqualTo("HIGH");
        assertThat(result.evidenceState()).isEqualTo("PARTIAL_SOURCE");
        assertThat(result.issueFlags()).contains("WRITER_CITATION_GAP");
        assertThat(result.issueFlags()).doesNotContain("WRITER_MISSING_SOURCE");
        assertThat(result.gaps().get(0).getSourceUrls()).containsExactly("https://www.notion.so/pricing");
        assertThat(result.gaps().get(0).getSuggestedQueries()).contains("pricing official citation evidence");
    }

    @Test
    void shouldTreatSectionWithoutOwnUrlsAsPartialSourceWhenWriterHasGlobalSources() {
        SectionEvidenceBundle bundle = SectionEvidenceBundle.builder()
                .stage("WRITE")
                .sectionType("SECTION")
                .sectionKey("recommendations")
                .sectionTitle("行动建议")
                .missingFields(List.of("recommendations"))
                .sourceUrls(List.of())
                .issueFlags(List.of("SECTION_EVIDENCE_GAP"))
                .build()
                .normalized();

        WriterCitationGapInspector.InspectionResult result = inspector.inspect(
                "# 竞品报告\n## 行动建议\n建议需要补充逐句引用。",
                List.of(bundle),
                List.of("https://www.notion.so/product/ai"));

        assertThat(result.severity()).isEqualTo("HIGH");
        assertThat(result.evidenceState()).isEqualTo("PARTIAL_SOURCE");
        assertThat(result.issueFlags()).contains("WRITER_CITATION_GAP");
        assertThat(result.issueFlags()).doesNotContain("WRITER_MISSING_SOURCE");
        assertThat(result.gaps().get(0).getEvidenceState()).isEqualTo("PARTIAL_SOURCE");
        assertThat(result.gaps().get(0).getSourceUrls()).containsExactly("https://www.notion.so/product/ai");
    }

    @Test
    void shouldReturnNoGapWhenSectionBundlesHaveSourcesAndNoMissingFields() {
        SectionEvidenceBundle bundle = SectionEvidenceBundle.builder()
                .stage("WRITE")
                .sectionType("SECTION")
                .sectionKey("features")
                .sectionTitle("产品功能")
                .fieldNames(List.of("featureComparison"))
                .sourceUrls(List.of("https://www.notion.so/product/ai"))
                .build()
                .normalized();

        WriterCitationGapInspector.InspectionResult result = inspector.inspect(
                "# 竞品报告\n## 产品功能\nNotion AI 提供工作区 AI 能力 [证据：E001]。",
                List.of(bundle),
                List.of("https://www.notion.so/product/ai"));

        assertThat(result.severity()).isEqualTo("NONE");
        assertThat(result.evidenceState()).isEqualTo("FULL_SOURCE");
        assertThat(result.gaps()).isEmpty();
        assertThat(result.missingCitationSections()).isEmpty();
    }
}
