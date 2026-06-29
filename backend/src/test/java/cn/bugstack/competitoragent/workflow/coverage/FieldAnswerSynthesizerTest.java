package cn.bugstack.competitoragent.workflow.coverage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FieldAnswerSynthesizerTest {

    private final FieldAnswerSynthesizer synthesizer = new FieldAnswerSynthesizer();

    @Test
    void shouldSynthesizeConfirmedFreeConclusionWithReasoningSteps() {
        FieldAnswerConclusion conclusion = synthesizer.synthesize(
                "pricing",
                "CONFIRMED_FREE",
                List.of("https://open.example.com/docs/billing"),
                List.of(
                        "OFFICIAL_PRICING_PAGE 未发现独立定价页",
                        "DOCS_BILLING_OR_LIMITS 命中文档说明免费开放",
                        "PUBLIC_WEB_CONFIRMATION 未发现冲突证据"),
                List.of());

        assertThat(conclusion.getField()).isEqualTo("pricing");
        assertThat(conclusion.getCoverageStatus()).isEqualTo("CONFIRMED_FREE");
        assertThat(conclusion.getAnswerValue()).contains("免费");
        assertThat(conclusion.getSourceUrls()).containsExactly("https://open.example.com/docs/billing");
        assertThat(conclusion.getReasoningSteps()).hasSize(3);
        assertThat(conclusion.getRecommendedNextAction()).isEqualTo("ACCEPT_CONCLUSION");
    }

    @Test
    void shouldNotBorrowCoverageFromOtherFieldsWhenSameSourceIsReused() {
        FieldAnswerConclusion conclusion = synthesizer.synthesize(
                "pricing",
                "EVIDENCE_PATH_COVERAGE_NOT_MET",
                List.of("https://open.example.com/docs/api"),
                List.of(
                        "同一文档已支撑 coreFeatures 的 API 能力说明",
                        "pricing 字段的 DOCS_BILLING_OR_LIMITS 路径未完成"),
                List.of());

        assertThat(conclusion.getField()).isEqualTo("pricing");
        assertThat(conclusion.getAnswerValue()).doesNotContain("免费");
        assertThat(conclusion.getRecommendedNextAction()).isEqualTo("REPAIR_WITH_TAVILY");
    }
}
