package cn.bugstack.competitoragent.workflow.coverage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisDimensionMappingCatalogTest {

    private final AnalysisDimensionMappingCatalog catalog = new AnalysisDimensionMappingCatalog();

    @Test
    void shouldMapPricingDimensionToPricingFieldAndEvidencePaths() {
        List<AnalysisDimensionMapping> mappings = catalog.resolve(
                List.of("定价策略", "商业化模式"),
                List.of("官网", "产品文档"));

        assertThat(mappings).anySatisfy(mapping -> {
            assertThat(mapping.getDimensionKey()).isEqualTo("PRICING_ANALYSIS");
            assertThat(mapping.getTargetFields()).contains("pricing");
            assertThat(mapping.getEvidencePathKeys()).contains("OFFICIAL_PRICING_PAGE", "DOCS_BILLING_OR_LIMITS");
            assertThat(mapping.getSourceTypes()).contains("PRICING", "DOCS", "OFFICIAL");
            assertThat(mapping.getReason()).contains("显式分析维度");
        });
    }

    @Test
    void shouldNotRequireWeaknessWhenOnlyOfficialScopeAndCapabilityIntroDimensions() {
        List<AnalysisDimensionMapping> mappings = catalog.resolve(
                List.of("开放平台", "开发者生态", "产品功能"),
                List.of("官网", "产品文档"));

        assertThat(mappings)
                .noneMatch(mapping -> mapping.getTargetFields().contains("weaknesses")
                        && mapping.isRequiredByDefault());
    }

    @Test
    void everyFieldMappingShouldIncludeThirdPartySourceType() {
        List<AnalysisDimensionMapping> mappings = catalog.resolve(
                List.of("开放平台", "开发者生态", "产品功能"),
                List.of("官网"));

        AnalysisDimensionMapping capability = mappings.stream()
                .filter(mapping -> "CAPABILITY_INTRO".equals(mapping.getDimensionKey()))
                .findFirst()
                .orElseThrow();

        // 第三方准入与字段语义无关：正向能力介绍字段也允许测评、新闻、开放网络等补充来源。
        assertThat(capability.getSourceTypes())
                .as("正向字段也应允许第三方来源，官方只是权重")
                .anySatisfy(type -> assertThat(type).isIn("REVIEW", "NEWS", "OPEN_WEB"));
        assertThat(capability.getEvidencePathKeys())
                .anySatisfy(pathKey -> assertThat(pathKey).contains("PUBLIC_REVIEW_OR_NEWS"));
    }
}
