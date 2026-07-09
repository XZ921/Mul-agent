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
    void explicitPricingDimensionShouldBeAuditedButNotBlockStageOneFirstReport() {
        CoverageContractResolver resolver = new CoverageContractResolver(new AnalysisDimensionMappingCatalog());

        CoverageContract contract = resolver.resolve(
                null,
                List.of("\u4ea7\u54c1\u529f\u80fd", "\u4ef7\u683c\u7b56\u7565"),
                List.of("\u5b98\u7f51", "\u4ea7\u54c1\u6587\u6863", "\u5b9a\u4ef7\u9875"),
                null);

        CoverageFieldContract pricing = contract.findField("pricing").orElseThrow();
        assertThat(pricing.getStatus()).isEqualTo(CoverageFieldStatus.OPTIONAL);
        assertThat(pricing.getBlockingLevel()).isEqualTo(CoverageBlockingLevel.WARNING);
        assertThat(pricing.getQueryIntents()).contains("OFFICIAL_PRICING");
        assertThat(pricing.getMinimumAttemptedPaths()).isZero();
        assertThat(pricing.getEvidencePaths()).extracting(CoverageEvidencePath::getPathKey)
                .contains("OFFICIAL_PRICING_PAGE", "DOCS_BILLING_OR_LIMITS", "PUBLIC_REVIEW_OR_NEWS");
        assertThat(pathByKey(pricing, "OFFICIAL_PRICING_PAGE").getSourceTypes()).contains("REVIEW", "NEWS");
        assertThat(pathByKey(pricing, "DOCS_BILLING_OR_LIMITS").getSourceTypes()).contains("REVIEW", "NEWS");
        assertThat(pathByKey(pricing, "PUBLIC_REVIEW_OR_NEWS").isRequired()).isTrue();
        assertThat(pricing.getOverrideReason()).contains("\u9636\u6bb51\u589e\u5f3a\u5b57\u6bb5");
    }

    @Test
    void standardTemplateShouldKeepEnhancementFieldsNonBlockingForStageOneFirstReport() {
        CoverageContractResolver resolver = new CoverageContractResolver(new AnalysisDimensionMappingCatalog());

        CoverageContract contract = resolver.resolve(
                "standard_competitor_report",
                List.of("\u5f00\u653e\u5e73\u53f0"),
                List.of("\u5b98\u7f51", "\u4ea7\u54c1\u6587\u6863"),
                null);

        assertThat(contract.getTaskMode()).isEqualTo("STANDARD_COMPETITOR_REPORT");
        assertThat(contract.findField("summary").orElseThrow().getBlockingLevel())
                .isEqualTo(CoverageBlockingLevel.BLOCKER);
        assertThat(contract.findField("coreFeatures").orElseThrow().getBlockingLevel())
                .isEqualTo(CoverageBlockingLevel.BLOCKER);
        assertThat(contract.findField("pricing").orElseThrow().getBlockingLevel())
                .isEqualTo(CoverageBlockingLevel.WARNING);
        assertThat(contract.findField("weaknesses").orElseThrow().getBlockingLevel())
                .isEqualTo(CoverageBlockingLevel.WARNING);
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

        assertThat(pricing.getMinimumAttemptedPaths()).isZero();
        assertThat(pricing.getBlockingLevel()).isEqualTo(CoverageBlockingLevel.WARNING);
        assertThat(pathByKey(pricing, "OFFICIAL_PRICING_PAGE").getSourceTypes()).contains("REVIEW", "NEWS");
        assertThat(pathByKey(pricing, "DOCS_BILLING_OR_LIMITS").getSourceTypes()).contains("REVIEW", "NEWS");
        assertThat(pathByKey(pricing, "PUBLIC_REVIEW_OR_NEWS").isRequired()).isTrue();

        assertThat(strengths.getMinimumAttemptedPaths()).isZero();
        assertThat(strengths.getBlockingLevel()).isEqualTo(CoverageBlockingLevel.WARNING);
        assertThat(pathByKey(strengths, "OFFICIAL_PUBLIC_PROFILE").getSourceTypes()).contains("REVIEW", "NEWS");
        assertThat(pathByKey(strengths, "PUBLIC_REVIEW_OR_NEWS").isRequired()).isTrue();

        assertThat(weaknesses.getMinimumAttemptedPaths()).isZero();
        assertThat(weaknesses.getBlockingLevel()).isEqualTo(CoverageBlockingLevel.WARNING);
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
