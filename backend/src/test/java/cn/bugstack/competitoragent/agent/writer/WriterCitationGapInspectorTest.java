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
                List.of(),
                List.of("产品概述", "市场定位", "目标用户", "核心功能", "价格策略"));

        assertThat(result.severity()).isEqualTo("WARNING");
        assertThat(result.evidenceState()).isEqualTo("MISSING_SOURCE");
        assertThat(result.missingCitationSections()).isEmpty();
        assertThat(result.issueFlags()).contains("WRITER_CITATION_GAP", "WRITER_MISSING_SOURCE", "GENERATED_SECTION_REWRITE_ONLY");
        assertThat(result.gaps()).hasSize(1);
        assertThat(result.gaps().get(0).getTargetSection()).isEqualTo("report_conclusion");
        assertThat(result.gaps().get(0).getEvidenceState()).isEqualTo("MISSING_SOURCE");
        assertThat(result.gaps().get(0).getSeverity()).isEqualTo("WARNING");
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
                List.of("https://www.notion.so/pricing"),
                List.of("产品概述", "市场定位", "目标用户", "核心功能", "价格策略"));

        assertThat(result.severity()).isEqualTo("WARNING");
        assertThat(result.evidenceState()).isEqualTo("PARTIAL_SOURCE");
        assertThat(result.missingCitationSections()).containsExactly("pricing");
        assertThat(result.issueFlags()).contains("WRITER_CITATION_GAP", "OPTIONAL_CITATION_GAP");
        assertThat(result.issueFlags()).doesNotContain("WRITER_MISSING_SOURCE");
        assertThat(result.gaps().get(0).getSeverity()).isEqualTo("WARNING");
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
                List.of("https://www.notion.so/product/ai"),
                List.of("产品概述", "市场定位", "目标用户", "核心功能", "价格策略"));

        assertThat(result.severity()).isEqualTo("WARNING");
        assertThat(result.evidenceState()).isEqualTo("PARTIAL_SOURCE");
        assertThat(result.issueFlags()).contains("WRITER_CITATION_GAP");
        assertThat(result.issueFlags()).doesNotContain("WRITER_MISSING_SOURCE");
        assertThat(result.missingCitationSections()).isEmpty();
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
                List.of("https://www.notion.so/product/ai"),
                List.of("产品概述", "市场定位", "目标用户", "核心功能"));

        assertThat(result.severity()).isEqualTo("NONE");
        assertThat(result.evidenceState()).isEqualTo("FULL_SOURCE");
        assertThat(result.gaps()).isEmpty();
        assertThat(result.missingCitationSections()).isEmpty();
    }

    @Test
    void shouldKeepUnrequestedEnhancementGapsOutOfMissingCitationSections() {
        SectionEvidenceBundle strengthsBundle = SectionEvidenceBundle.builder()
                .stage("WRITE")
                .sectionType("SECTION")
                .sectionKey("strengths")
                .sectionTitle("优势判断")
                .missingFields(List.of("strengthsSummary"))
                .sourceUrls(List.of("https://www.notion.so/product/ai"))
                .issueFlags(List.of("SECTION_EVIDENCE_GAP"))
                .build()
                .normalized();
        SectionEvidenceBundle weaknessesBundle = SectionEvidenceBundle.builder()
                .stage("WRITE")
                .sectionType("SECTION")
                .sectionKey("weaknesses")
                .sectionTitle("短板与风险")
                .missingFields(List.of("weaknessesSummary"))
                .sourceUrls(List.of("https://www.notion.so/product/ai"))
                .issueFlags(List.of("SECTION_EVIDENCE_GAP"))
                .build()
                .normalized();

        WriterCitationGapInspector.InspectionResult result = inspector.inspect(
                "# 竞品报告\n## 优势判断\n需要补充优势引用。\n## 短板与风险\n需要补充短板引用。",
                List.of(strengthsBundle, weaknessesBundle),
                List.of("https://www.notion.so/product/ai"),
                List.of("产品概述", "市场定位", "目标用户", "核心功能", "价格策略"));

        assertThat(result.missingCitationSections()).doesNotContain("strengths", "weaknesses");
        assertThat(result.issueFlags()).contains("WRITER_CITATION_GAP", "OPTIONAL_CITATION_GAP");
        assertThat(result.gaps()).extracting(gap -> gap.getTargetSection()).contains("strengths", "weaknesses");
        assertThat(result.gaps()).allMatch(gap -> "WARNING".equals(gap.getSeverity()));
    }

    @Test
    void shouldTreatGeneratedConclusionGapAsRewriteOnlyWarning() {
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
                "# 竞品报告\n## 报告结论\n建议优先关注知识协作能力。",
                List.of(bundle),
                List.of("https://www.notion.so/product/ai"),
                List.of("产品概述", "市场定位", "目标用户", "核心功能", "价格策略"));

        assertThat(result.severity()).isEqualTo("WARNING");
        assertThat(result.missingCitationSections()).doesNotContain("report_conclusion", "conclusion");
        assertThat(result.issueFlags()).contains("GENERATED_SECTION_REWRITE_ONLY");
        assertThat(result.gaps()).hasSize(1);
        assertThat(result.gaps().get(0).getSeverity()).isEqualTo("WARNING");
        assertThat(result.gaps().get(0).getSourceUrls()).containsExactly("https://www.notion.so/product/ai");
    }
}
