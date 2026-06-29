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
}
