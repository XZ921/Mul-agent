package cn.bugstack.competitoragent.workflow.coverage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CoverageContractResolverTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldSerializeCoverageContractWithOverrideReason() throws Exception {
        CoverageContract contract = CoverageContract.builder()
                .taskMode("CAPABILITY_INTRO")
                .contractVersion("task-66-plan-v1")
                .source("PLANNER")
                .fields(List.of(CoverageFieldContract.builder()
                        .field("pricing")
                        .status(CoverageFieldStatus.OUT_OF_SCOPE)
                        .blockingLevel(CoverageBlockingLevel.NONE)
                        .targetEvidenceTypes(List.of("PRICING_BLOCK"))
                        .queryIntents(List.of())
                        .minDistinctEvidenceCount(0)
                        .allowOfficialOnly(true)
                        .overrideReason("taskMode=CAPABILITY_INTRO")
                        .build()))
                .build();

        String json = objectMapper.writeValueAsString(contract);
        CoverageContract restored = objectMapper.readValue(json, CoverageContract.class);

        assertThat(restored.getTaskMode()).isEqualTo("CAPABILITY_INTRO");
        assertThat(restored.findField("pricing")).isPresent();
        assertThat(restored.findField("pricing").orElseThrow().getBlockingLevel())
                .isEqualTo(CoverageBlockingLevel.NONE);
        assertThat(restored.findField("pricing").orElseThrow().getOverrideReason())
                .isEqualTo("taskMode=CAPABILITY_INTRO");
    }

    @Test
    void shouldBuildCapabilityIntroContractForTask66Dimensions() {
        CoverageContractResolver resolver = new CoverageContractResolver(new AnalysisDimensionMappingCatalog());

        CoverageContract contract = resolver.resolve(
                null,
                List.of("\u5f00\u653e\u5e73\u53f0", "\u5f00\u53d1\u8005\u751f\u6001", "\u4ea7\u54c1\u529f\u80fd"),
                List.of("\u5b98\u7f51", "\u4ea7\u54c1\u6587\u6863"),
                null);

        assertThat(contract.getTaskMode()).isEqualTo("CAPABILITY_INTRO");
        assertThat(contract.findField("coreFeatures").orElseThrow().getStatus())
                .isEqualTo(CoverageFieldStatus.REQUIRED);
        assertThat(contract.findField("pricing").orElseThrow().getBlockingLevel())
                .isEqualTo(CoverageBlockingLevel.NONE);
        assertThat(contract.findField("weaknesses").orElseThrow().getStatus())
                .isIn(CoverageFieldStatus.OUT_OF_SCOPE, CoverageFieldStatus.OPTIONAL);
    }

    @Test
    void explicitPricingDimensionShouldOverrideOfficialOnlyScope() {
        CoverageContractResolver resolver = new CoverageContractResolver(new AnalysisDimensionMappingCatalog());

        CoverageContract contract = resolver.resolve(
                null,
                List.of("\u5b9a\u4ef7"),
                List.of("\u5b98\u7f51"),
                null);

        CoverageFieldContract pricing = contract.findField("pricing").orElseThrow();
        assertThat(pricing.getStatus()).isEqualTo(CoverageFieldStatus.REQUIRED);
        assertThat(pricing.getBlockingLevel()).isEqualTo(CoverageBlockingLevel.BLOCKER);
        assertThat(pricing.getQueryIntents()).contains("OFFICIAL_PRICING");
        assertThat(pricing.getMinimumAttemptedPaths()).isEqualTo(1);
        assertThat(pricing.getEvidencePaths()).extracting(CoverageEvidencePath::getPathKey)
                .contains("OFFICIAL_PRICING_PAGE", "DOCS_BILLING_OR_LIMITS", "PUBLIC_REVIEW_OR_NEWS");
        assertThat(pathByKey(pricing, "OFFICIAL_PRICING_PAGE").getSourceTypes()).contains("REVIEW", "NEWS");
        assertThat(pathByKey(pricing, "DOCS_BILLING_OR_LIMITS").getSourceTypes()).contains("REVIEW", "NEWS");
        assertThat(pathByKey(pricing, "PUBLIC_REVIEW_OR_NEWS").isRequired()).isTrue();
        assertThat(pricing.getOverrideReason()).contains("\u663e\u5f0f\u7ef4\u5ea6");
    }

    @Test
    void explicitStandardTemplateShouldRequireWeaknessesAndPricing() {
        CoverageContractResolver resolver = new CoverageContractResolver(new AnalysisDimensionMappingCatalog());

        CoverageContract contract = resolver.resolve(
                "standard_competitor_report",
                List.of("\u5f00\u653e\u5e73\u53f0"),
                List.of("\u5b98\u7f51", "\u4ea7\u54c1\u6587\u6863"),
                null);

        assertThat(contract.getTaskMode()).isEqualTo("STANDARD_COMPETITOR_REPORT");
        assertThat(contract.findField("pricing").orElseThrow().getStatus()).isEqualTo(CoverageFieldStatus.REQUIRED);
        assertThat(contract.findField("weaknesses").orElseThrow().getStatus()).isEqualTo(CoverageFieldStatus.REQUIRED);
    }

    @Test
    void standardTemplateShouldRequireThirdPartyEvidenceForPricingStrengthsAndWeaknesses() {
        CoverageContractResolver resolver = new CoverageContractResolver(new AnalysisDimensionMappingCatalog());

        CoverageContract contract = resolver.resolve(
                "standard_competitor_report",
                List.of("\u4ea7\u54c1\u529f\u80fd"),
                List.of("\u5b98\u7f51", "\u4ea7\u54c1\u6587\u6863"),
                null);

        CoverageFieldContract pricing = contract.findField("pricing").orElseThrow();
        CoverageFieldContract strengths = contract.findField("strengths").orElseThrow();
        CoverageFieldContract weaknesses = contract.findField("weaknesses").orElseThrow();
        CoverageFieldContract summary = contract.findField("summary").orElseThrow();
        CoverageFieldContract coreFeatures = contract.findField("coreFeatures").orElseThrow();

        assertThat(pricing.getMinimumAttemptedPaths()).isEqualTo(1);
        assertThat(pathByKey(pricing, "OFFICIAL_PRICING_PAGE").getSourceTypes()).contains("REVIEW", "NEWS");
        assertThat(pathByKey(pricing, "DOCS_BILLING_OR_LIMITS").getSourceTypes()).contains("REVIEW", "NEWS");
        assertThat(pathByKey(pricing, "PUBLIC_REVIEW_OR_NEWS").isRequired()).isTrue();

        assertThat(strengths.getMinimumAttemptedPaths()).isEqualTo(1);
        assertThat(pathByKey(strengths, "OFFICIAL_PUBLIC_PROFILE").getSourceTypes()).contains("REVIEW", "NEWS");
        assertThat(pathByKey(strengths, "PUBLIC_REVIEW_OR_NEWS").isRequired()).isTrue();

        assertThat(weaknesses.getMinimumAttemptedPaths()).isEqualTo(1);
        assertThat(pathByKey(weaknesses, "TERMS_OR_SERVICE_AGREEMENT").getSourceTypes()).contains("REVIEW", "NEWS");
        assertThat(pathByKey(weaknesses, "PUBLIC_REVIEW_OR_NEWS").isRequired()).isTrue();

        assertThat(pathByKey(summary, "OFFICIAL_PUBLIC_PROFILE").getSourceTypes())
                .containsExactly("OFFICIAL", "DOCS");
        assertThat(pathByKey(coreFeatures, "DOCS_API_GUIDE").getSourceTypes())
                .containsExactly("DOCS", "OFFICIAL");
    }

    private CoverageEvidencePath pathByKey(CoverageFieldContract field, String pathKey) {
        return field.getEvidencePaths().stream()
                .filter(path -> pathKey.equals(path.getPathKey()))
                .findFirst()
                .orElseThrow();
    }
}
