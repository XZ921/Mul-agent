package cn.bugstack.competitoragent.workflow.coverage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FieldEvidenceQueryPlannerTest {

    private final FieldEvidenceQueryPlanner planner = new FieldEvidenceQueryPlanner();

    @Test
    void shouldPlanMultipleSemanticQueriesForCoreFeatureDocsPath() {
        CoverageFieldContract field = CoverageFieldContract.builder()
                .field("coreFeatures")
                .status(CoverageFieldStatus.REQUIRED)
                .evidencePaths(List.of(CoverageEvidencePath.builder()
                        .pathKey("DOCS_API_GUIDE")
                        .sourceTypes(List.of("DOCS", "OFFICIAL"))
                        .queryIntents(List.of("API_DOCS", "SDK_GUIDE"))
                        .expectedSignals(List.of("DEVELOPER_DOCS_BLOCK", "FEATURE_BLOCK"))
                        .required(true)
                        .build()))
                .minimumAttemptedPaths(1)
                .minDistinctEvidenceCount(2)
                .build();

        List<FieldEvidenceQuery> queries = planner.plan(
                "哔哩哔哩",
                field,
                List.of("open.bilibili.com"));

        assertThat(queries).hasSizeGreaterThanOrEqualTo(4);
        assertThat(queries).extracting(FieldEvidenceQuery::getFieldName).containsOnly("coreFeatures");
        assertThat(queries).extracting(FieldEvidenceQuery::getEvidencePathKey).containsOnly("DOCS_API_GUIDE");
        assertThat(queries).extracting(FieldEvidenceQuery::getQueryIntent)
                .contains("API_DOCS", "SDK_GUIDE");
        assertThat(queries).extracting(FieldEvidenceQuery::getQuery)
                .anySatisfy(query -> assertThat(query).contains("开放平台").contains("API").contains("官方文档"))
                .anySatisfy(query -> assertThat(query).contains("SDK").contains("接入"))
                .anySatisfy(query -> assertThat(query).contains("评测").contains("用户反馈"));
        assertThat(queries)
                .filteredOn(query -> !"OPEN_WEB".equals(query.getSourceType()))
                .allSatisfy(query -> assertThat(query.getIncludeDomains()).contains("open.bilibili.com"));
        assertThat(queries)
                .filteredOn(query -> "OPEN_WEB".equals(query.getSourceType()))
                .allSatisfy(query -> assertThat(query.getIncludeDomains()).isEmpty());
        assertThat(queries).allSatisfy(query -> {
            assertThat(query.getReason()).isNotBlank();
            assertThat(query.getQueryFingerprint()).isNotBlank();
        });
    }

    @Test
    void shouldPlanPricingQueriesAcrossOfficialBillingAndTermsPaths() {
        CoverageFieldContract pricing = CoverageFieldContract.builder()
                .field("pricing")
                .status(CoverageFieldStatus.REQUIRED)
                .evidencePaths(List.of(
                        CoverageEvidencePath.builder()
                                .pathKey("OFFICIAL_PRICING_PAGE")
                                .sourceTypes(List.of("PRICING", "OFFICIAL"))
                                .queryIntents(List.of("OFFICIAL_PRICING"))
                                .expectedSignals(List.of("PRICING_BLOCK"))
                                .required(true)
                                .build(),
                        CoverageEvidencePath.builder()
                                .pathKey("DOCS_BILLING_OR_LIMITS")
                                .sourceTypes(List.of("DOCS", "OFFICIAL"))
                                .queryIntents(List.of("DOCS_BILLING", "TERMS_BILLING"))
                                .expectedSignals(List.of("PRICING_BLOCK", "LIMITATION_OR_POLICY_BLOCK"))
                                .required(true)
                                .build()))
                .minimumAttemptedPaths(2)
                .minDistinctEvidenceCount(2)
                .build();

        List<FieldEvidenceQuery> queries = planner.plan(
                "哔哩哔哩",
                pricing,
                List.of("open.bilibili.com", "bilibili.com"));

        assertThat(queries).hasSizeGreaterThanOrEqualTo(4);
        assertThat(queries).extracting(FieldEvidenceQuery::getFieldName).containsOnly("pricing");
        assertThat(queries).extracting(FieldEvidenceQuery::getEvidencePathKey)
                .contains("OFFICIAL_PRICING_PAGE", "DOCS_BILLING_OR_LIMITS");
        assertThat(queries).extracting(FieldEvidenceQuery::getQueryIntent)
                .contains("OFFICIAL_PRICING", "DOCS_BILLING", "TERMS_BILLING");
        assertThat(queries).extracting(FieldEvidenceQuery::getQuery)
                .anySatisfy(query -> assertThat(query).contains("定价").contains("收费"))
                .anySatisfy(query -> assertThat(query).contains("API").contains("计费"))
                .anySatisfy(query -> assertThat(query).contains("服务协议").contains("条款"));
        assertThat(queries).allSatisfy(query -> {
            assertThat(query.getReason()).contains("字段 pricing");
            assertThat(query.getQueryFingerprint()).isNotBlank();
        });
    }

    @Test
    void shouldPlanBestEffortThirdPartyQueriesForNonRequiredReviewPath() {
        CoverageFieldContract weaknesses = CoverageFieldContract.builder()
                .field("weaknesses")
                .status(CoverageFieldStatus.REQUIRED)
                .evidencePaths(List.of(
                        CoverageEvidencePath.builder()
                                .pathKey("OFFICIAL_PUBLIC_PROFILE")
                                .sourceTypes(List.of("OFFICIAL"))
                                .queryIntents(List.of("FIELD_EVIDENCE"))
                                .expectedSignals(List.of("PROFILE_BLOCK"))
                                .required(true)
                                .build(),
                        CoverageEvidencePath.builder()
                                .pathKey("PUBLIC_REVIEW_OR_NEWS")
                                .sourceTypes(List.of("REVIEW", "NEWS"))
                                .queryIntents(List.of("THIRD_PARTY_REVIEW"))
                                .expectedSignals(List.of("PUBLIC_RISK_BLOCK"))
                                .required(false)
                                .build()))
                .minimumAttemptedPaths(1)
                .minDistinctEvidenceCount(2)
                .build();

        List<FieldEvidenceQuery> queries = planner.plan(
                "抖音开放平台",
                weaknesses,
                List.of("open.douyin.com"));

        assertThat(queries)
                .filteredOn(query -> "PUBLIC_REVIEW_OR_NEWS".equals(query.getEvidencePathKey()))
                .isNotEmpty()
                .allSatisfy(query -> {
                    assertThat(query.getIncludeDomains()).isEmpty();
                    assertThat(query.getQuery()).contains("抖音开放平台");
                    assertThat(query.getReason()).contains("PUBLIC_REVIEW_OR_NEWS");
                });
    }
}
