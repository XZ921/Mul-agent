package cn.bugstack.competitoragent.integration;

import cn.bugstack.competitoragent.workflow.coverage.AnalysisDimensionMappingCatalog;
import cn.bugstack.competitoragent.workflow.coverage.CoverageBlockingLevel;
import cn.bugstack.competitoragent.workflow.coverage.CoverageContract;
import cn.bugstack.competitoragent.workflow.coverage.CoverageContractResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Task66CoverageContractRegressionTest {

    private final CoverageContractResolver resolver = new CoverageContractResolver(new AnalysisDimensionMappingCatalog());

    @Test
    void task66CapabilityIntroShouldNotBlockOnPricingOrWeaknesses() {
        CoverageContract contract = resolver.resolve(
                null,
                List.of("开放平台", "开发者生态", "产品功能"),
                List.of("官网", "产品文档"),
                null);

        assertThat(contract.findField("coreFeatures").orElseThrow().getBlockingLevel())
                .isEqualTo(CoverageBlockingLevel.BLOCKER);
        assertThat(contract.findField("pricing").orElseThrow().getBlockingLevel())
                .isEqualTo(CoverageBlockingLevel.NONE);
        assertThat(contract.findField("weaknesses").orElseThrow().getBlockingLevel())
                .isEqualTo(CoverageBlockingLevel.NONE);
    }

    @Test
    void standardReportShouldStillBlockOnPricingAndWeaknesses() {
        CoverageContract contract = resolver.resolve(
                "STANDARD_COMPETITOR_REPORT",
                List.of("开放平台", "开发者生态", "产品功能"),
                List.of("官网", "产品文档"),
                null);

        assertThat(contract.findField("pricing").orElseThrow().getBlockingLevel())
                .isEqualTo(CoverageBlockingLevel.BLOCKER);
        assertThat(contract.findField("weaknesses").orElseThrow().getBlockingLevel())
                .isEqualTo(CoverageBlockingLevel.BLOCKER);
    }
}
