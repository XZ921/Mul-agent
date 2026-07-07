package cn.bugstack.competitoragent.workflow.coverage;

import cn.bugstack.competitoragent.collection.CollectionExecutionResult;
import cn.bugstack.competitoragent.collection.StructuredContentBlock;
import cn.bugstack.competitoragent.search.EvidenceRepairPlan;
import cn.bugstack.competitoragent.search.EvidenceRepairState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FieldEvidenceCoverageAggregatorTest {

    private final FieldEvidenceCoverageAggregator aggregator = new FieldEvidenceCoverageAggregator();

    @Test
    void shouldMarkFieldPathCompletedWhenEnoughDistinctUrlsCollected() {
        DimensionEvidencePlan plan = DimensionEvidencePlan.builder()
                .competitorName("哔哩哔哩")
                .maxCollectionRounds(2)
                .fieldCoverages(List.of(FieldEvidenceCoverage.builder()
                        .fieldName("coreFeatures")
                        .status(FieldEvidenceCoverageStatus.NOT_STARTED)
                        .minimumAttemptedPaths(1)
                        .minDistinctEvidenceCount(1)
                        .evidencePaths(List.of(CoverageEvidencePath.builder()
                                .pathKey("DOCS_API_GUIDE")
                                .required(true)
                                .build()))
                        .plannedQueries(List.of())
                        .build()))
                .build();

        DimensionEvidencePlan updated = aggregator.applyCollectionResults(plan, List.of(
                CollectionExecutionResult.builder()
                        .success(true)
                        .status("SUCCESS")
                        .resourceLocator("https://open.bilibili.com/doc/4/feb66f99")
                        .sourceUrls(List.of("https://open.bilibili.com/doc/4/feb66f99"))
                        .publicEvidenceRecoveryFieldName("coreFeatures")
                        .publicEvidenceRecoveryEvidencePathKey("DOCS_API_GUIDE")
                        .evidenceRepairPlan(EvidenceRepairPlan.builder()
                                .state(EvidenceRepairState.REPAIR_EVIDENCE_PROMOTED)
                                .promotedUrls(List.of("https://open.bilibili.com/doc/4/feb66f99"))
                                .build())
                        .build()));

        FieldEvidenceCoverage coreFeatures = updated.findField("coreFeatures").orElseThrow();
        assertThat(coreFeatures.getStatus()).isEqualTo(FieldEvidenceCoverageStatus.SUFFICIENT);
        assertThat(coreFeatures.getAttemptedPaths()).containsExactly("DOCS_API_GUIDE");
        assertThat(coreFeatures.getCompletedPaths()).containsExactly("DOCS_API_GUIDE");
        assertThat(coreFeatures.getLastRepairState()).isEqualTo("REPAIR_FIELD_PATH_COMPLETED");
    }

    @Test
    void shouldKeepCoverageGapWhenMinimumAttemptedPathsNotMet() {
        DimensionEvidencePlan plan = DimensionEvidencePlan.builder()
                .competitorName("哔哩哔哩")
                .maxCollectionRounds(2)
                .fieldCoverages(List.of(FieldEvidenceCoverage.builder()
                        .fieldName("pricing")
                        .status(FieldEvidenceCoverageStatus.NOT_STARTED)
                        .minimumAttemptedPaths(2)
                        .minDistinctEvidenceCount(2)
                        .evidencePaths(List.of(
                                CoverageEvidencePath.builder().pathKey("OFFICIAL_PRICING_PAGE").required(true).build(),
                                CoverageEvidencePath.builder().pathKey("DOCS_BILLING_OR_LIMITS").required(true).build()))
                        .build()))
                .build();

        DimensionEvidencePlan updated = aggregator.applyCollectionResults(plan, List.of());

        FieldEvidenceCoverage pricing = updated.findField("pricing").orElseThrow();
        assertThat(pricing.getStatus()).isEqualTo(FieldEvidenceCoverageStatus.EVIDENCE_PATH_COVERAGE_NOT_MET);
        assertThat(pricing.getRecommendedNextAction()).isEqualTo("RECOLLECT_FIELD_EVIDENCE");
    }

    @Test
    void shouldNotCountNavigationShellAsSufficientFieldEvidence() {
        DimensionEvidencePlan plan = DimensionEvidencePlan.builder()
                .competitorName("Bilibili")
                .maxCollectionRounds(2)
                .fieldCoverages(List.of(FieldEvidenceCoverage.builder()
                        .fieldName("coreFeatures")
                        .status(FieldEvidenceCoverageStatus.NOT_STARTED)
                        .minimumAttemptedPaths(1)
                        .minDistinctEvidenceCount(1)
                        .evidencePaths(List.of(CoverageEvidencePath.builder()
                                .pathKey("DOCS_API_GUIDE")
                                .required(true)
                                .build()))
                        .plannedQueries(List.of())
                        .build()))
                .build();

        DimensionEvidencePlan updated = aggregator.applyCollectionResults(plan, List.of(
                CollectionExecutionResult.builder()
                        .success(true)
                        .status("SUCCESS")
                        .resourceLocator("https://open.bilibili.com")
                        .sourceUrls(List.of("https://open.bilibili.com"))
                        .qualitySignals(List.of("NAVIGATION_SHELL_DETECTED", "WEAK_MAIN_CONTENT"))
                        .publicEvidenceRecoveryFieldName("coreFeatures")
                        .publicEvidenceRecoveryEvidencePathKey("DOCS_API_GUIDE")
                        .evidenceRepairPlan(EvidenceRepairPlan.builder()
                                .state(EvidenceRepairState.REPAIR_NOT_REQUIRED)
                                .build())
                        .build()));

        FieldEvidenceCoverage coreFeatures = updated.findField("coreFeatures").orElseThrow();
        assertThat(coreFeatures.getStatus()).isEqualTo(FieldEvidenceCoverageStatus.EVIDENCE_PATH_COVERAGE_NOT_MET);
        assertThat(coreFeatures.getAttemptedPaths()).containsExactly("DOCS_API_GUIDE");
        assertThat(coreFeatures.getCompletedPaths()).isEmpty();
        assertThat(coreFeatures.getSourceUrls()).isEmpty();
        assertThat(coreFeatures.getRecommendedNextAction()).isEqualTo("RECOLLECT_FIELD_EVIDENCE");
    }

    @Test
    void shouldCountSubstantiveFieldEvidenceWhenAuthGateSignalIsOnlyWeakAuditSignal() {
        DimensionEvidencePlan plan = DimensionEvidencePlan.builder()
                .competitorName("Douyin Open Platform")
                .maxCollectionRounds(2)
                .fieldCoverages(List.of(FieldEvidenceCoverage.builder()
                        .fieldName("coreFeatures")
                        .status(FieldEvidenceCoverageStatus.NOT_STARTED)
                        .minimumAttemptedPaths(1)
                        .minDistinctEvidenceCount(1)
                        .evidencePaths(List.of(CoverageEvidencePath.builder()
                                .pathKey("DOCS_API_GUIDE")
                                .required(true)
                                .build()))
                        .plannedQueries(List.of())
                        .build()))
                .build();

        DimensionEvidencePlan updated = aggregator.applyCollectionResults(plan, List.of(
                CollectionExecutionResult.builder()
                        .success(true)
                        .status("SUCCESS")
                        .resourceLocator("https://open.douyin.com/platform/resource/docs/transfer")
                        .content(("Official API documentation with OAuth authorization, login callback, "
                                + "token exchange, SDK guide, permission scopes and public integration steps. ").repeat(4))
                        .sourceUrls(List.of("https://open.douyin.com/platform/resource/docs/transfer"))
                        .qualitySignals(List.of("OFFICIAL_DOMAIN_MATCHED", "AUTH_GATE_WEAK_SIGNAL"))
                        .publicEvidenceRecoveryFieldName("coreFeatures")
                        .publicEvidenceRecoveryEvidencePathKey("DOCS_API_GUIDE")
                        .evidenceRepairPlan(EvidenceRepairPlan.builder()
                                .state(EvidenceRepairState.REPAIR_NOT_REQUIRED)
                                .build())
                        .build()));

        FieldEvidenceCoverage coreFeatures = updated.findField("coreFeatures").orElseThrow();
        assertThat(coreFeatures.getStatus()).isEqualTo(FieldEvidenceCoverageStatus.SUFFICIENT);
        assertThat(coreFeatures.getAttemptedPaths()).containsExactly("DOCS_API_GUIDE");
        assertThat(coreFeatures.getCompletedPaths()).containsExactly("DOCS_API_GUIDE");
        assertThat(coreFeatures.getSourceUrls()).containsExactly("https://open.douyin.com/platform/resource/docs/transfer");
        assertThat(coreFeatures.getRecommendedNextAction()).isEqualTo("ACCEPT_FIELD_EVIDENCE");
    }

    @Test
    void shouldNotCloseFieldWhenSuccessfulResultOnlyContainsThinIntroContent() {
        DimensionEvidencePlan plan = DimensionEvidencePlan.builder()
                .competitorName("Bilibili")
                .maxCollectionRounds(2)
                .fieldCoverages(List.of(FieldEvidenceCoverage.builder()
                        .fieldName("coreFeatures")
                        .status(FieldEvidenceCoverageStatus.NOT_STARTED)
                        .minimumAttemptedPaths(1)
                        .minDistinctEvidenceCount(1)
                        .evidencePaths(List.of(CoverageEvidencePath.builder()
                                .pathKey("DOCS_API_GUIDE")
                                .required(true)
                                .build()))
                        .plannedQueries(List.of())
                        .build()))
                .build();

        DimensionEvidencePlan updated = aggregator.applyCollectionResults(plan, List.of(
                CollectionExecutionResult.builder()
                        .success(true)
                        .status("SUCCESS")
                        .resourceLocator("https://open.bilibili.com/doc/4/feb66f99")
                        .content("简短简介")
                        .sourceUrls(List.of("https://open.bilibili.com/doc/4/feb66f99"))
                        .structuredBlocks(List.of(StructuredContentBlock.builder()
                                .blockType("PARAGRAPH")
                                .content("短内容")
                                .build()))
                        .publicEvidenceRecoveryFieldName("coreFeatures")
                        .publicEvidenceRecoveryEvidencePathKey("DOCS_API_GUIDE")
                        .evidenceRepairPlan(EvidenceRepairPlan.builder()
                                .state(EvidenceRepairState.REPAIR_NOT_REQUIRED)
                                .build())
                        .build()));

        FieldEvidenceCoverage coreFeatures = updated.findField("coreFeatures").orElseThrow();
        assertThat(coreFeatures.getStatus()).isEqualTo(FieldEvidenceCoverageStatus.EVIDENCE_PATH_COVERAGE_NOT_MET);
        assertThat(coreFeatures.getAttemptedPaths()).containsExactly("DOCS_API_GUIDE");
        assertThat(coreFeatures.getCompletedPaths()).isEmpty();
        assertThat(coreFeatures.getSourceUrls()).isEmpty();
        assertThat(coreFeatures.getRecommendedNextAction()).isEqualTo("RECOLLECT_FIELD_EVIDENCE");
    }
}
