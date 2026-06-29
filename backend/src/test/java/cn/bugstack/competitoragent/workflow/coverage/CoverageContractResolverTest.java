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
                List.of("开放平台", "开发者生态", "产品功能"),
                List.of("官网", "产品文档"),
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
                List.of("定价策略"),
                List.of("官网"),
                null);

        CoverageFieldContract pricing = contract.findField("pricing").orElseThrow();
        assertThat(pricing.getStatus()).isEqualTo(CoverageFieldStatus.REQUIRED);
        assertThat(pricing.getBlockingLevel()).isEqualTo(CoverageBlockingLevel.BLOCKER);
        assertThat(pricing.getQueryIntents()).contains("OFFICIAL_PRICING");
        assertThat(pricing.getMinimumAttemptedPaths()).isGreaterThanOrEqualTo(2);
        assertThat(pricing.getEvidencePaths()).extracting(CoverageEvidencePath::getPathKey)
                .contains("OFFICIAL_PRICING_PAGE", "DOCS_BILLING_OR_LIMITS");
        assertThat(pricing.getOverrideReason()).contains("显式维度");
    }

    @Test
    void explicitStandardTemplateShouldRequireWeaknessesAndPricing() {
        CoverageContractResolver resolver = new CoverageContractResolver(new AnalysisDimensionMappingCatalog());

        CoverageContract contract = resolver.resolve(
                "标准版",
                List.of("开放平台"),
                List.of("官网", "产品文档"),
                null);

        assertThat(contract.getTaskMode()).isEqualTo("STANDARD_COMPETITOR_REPORT");
        assertThat(contract.findField("pricing").orElseThrow().getStatus()).isEqualTo(CoverageFieldStatus.REQUIRED);
        assertThat(contract.findField("weaknesses").orElseThrow().getStatus()).isEqualTo(CoverageFieldStatus.REQUIRED);
    }
}
