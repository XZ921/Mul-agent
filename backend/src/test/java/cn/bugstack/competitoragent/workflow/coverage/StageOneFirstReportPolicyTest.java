package cn.bugstack.competitoragent.workflow.coverage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StageOneFirstReportPolicyTest {

    @Test
    void shouldTreatOnlyCoreFieldsAsFirstReportBlocking() {
        assertThat(StageOneFirstReportPolicy.isFirstReportCriticalField("summary")).isTrue();
        assertThat(StageOneFirstReportPolicy.isFirstReportCriticalField("positioning")).isTrue();
        assertThat(StageOneFirstReportPolicy.isFirstReportCriticalField("targetUsers")).isTrue();
        assertThat(StageOneFirstReportPolicy.isFirstReportCriticalField("coreFeatures")).isTrue();

        assertThat(StageOneFirstReportPolicy.isFirstReportCriticalField("pricing")).isFalse();
        assertThat(StageOneFirstReportPolicy.isFirstReportCriticalField("strengths")).isFalse();
        assertThat(StageOneFirstReportPolicy.isFirstReportCriticalField("weaknesses")).isFalse();
        assertThat(StageOneFirstReportPolicy.isFirstReportCriticalField("risk")).isFalse();
    }

    @Test
    void shouldNotRequirePricingFamilyForStageOneQuorum() {
        assertThat(StageOneFirstReportPolicy.isQuorumReady(
                List.of("OFFICIAL", "DOCS", "REVIEW"),
                List.of(
                        "https://www.linear.app",
                        "https://www.linear.app/features",
                        "https://linear.app/docs",
                        "https://www.g2.com/products/linear/reviews",
                        "https://www.capterra.com/p/linear"
                ))).isTrue();
    }

    @Test
    void shouldMapReportSectionsThroughTheSameFirstReportPolicy() {
        assertThat(StageOneFirstReportPolicy.fieldForSection("产品概览")).isEqualTo("summary");
        assertThat(StageOneFirstReportPolicy.fieldForSection("市场定位")).isEqualTo("positioning");
        assertThat(StageOneFirstReportPolicy.fieldForSection("目标用户")).isEqualTo("targetUsers");
        assertThat(StageOneFirstReportPolicy.fieldForSection("核心能力")).isEqualTo("coreFeatures");
        assertThat(StageOneFirstReportPolicy.fieldForSection("定价策略")).isEqualTo("pricing");
        assertThat(StageOneFirstReportPolicy.fieldForSection("优势判断")).isEqualTo("strengths");
        assertThat(StageOneFirstReportPolicy.fieldForSection("短板与风险")).isEqualTo("weaknesses");

        assertThat(StageOneFirstReportPolicy.isFirstReportBlockingSection("核心能力")).isTrue();
        assertThat(StageOneFirstReportPolicy.isFirstReportBlockingSection("定价策略")).isFalse();
        assertThat(StageOneFirstReportPolicy.isOptionalEvidenceGapFlag("OPTIONAL_FIELD_DEFERRED")).isTrue();
        assertThat(StageOneFirstReportPolicy.isOptionalEvidenceGapFlag("SECTION_EVIDENCE_GAP")).isFalse();
    }

    @Test
    void shouldNormalizeWwwDomainsWhenCheckingTraceableSourceRedline() {
        assertThat(StageOneFirstReportPolicy.hasEnoughTraceableSources(List.of(
                "https://www.linear.app",
                "https://linear.app/features",
                "https://www.linear.app/docs",
                "https://linear.app/customers",
                "https://www.linear.app/pricing"
        ))).isFalse();

        assertThat(StageOneFirstReportPolicy.hasEnoughTraceableSources(List.of(
                "https://www.linear.app",
                "https://linear.app/features",
                "https://linear.app/docs",
                "https://www.g2.com/products/linear/reviews",
                "https://www.capterra.com/p/linear"
        ))).isTrue();
    }
}
