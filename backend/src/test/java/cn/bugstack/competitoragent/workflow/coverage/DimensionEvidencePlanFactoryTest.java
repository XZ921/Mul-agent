package cn.bugstack.competitoragent.workflow.coverage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DimensionEvidencePlanFactoryTest {

    private final DimensionEvidencePlanFactory factory =
            new DimensionEvidencePlanFactory(new FieldEvidenceQueryPlanner());

    @Test
    void shouldCreatePlanOnlyForCriticalFieldsWhenEnhancementScopeIsNotExplicit() {
        CoverageContract contract = new CoverageContractResolver(new AnalysisDimensionMappingCatalog())
                .resolve("standard_competitor_report",
                        List.of("\u4ea7\u54c1\u529f\u80fd", "\u5b9a\u4ef7", "\u98ce\u9669"),
                        List.of("\u5b98\u7f51", "\u4ea7\u54c1\u6587\u6863"),
                        null);

        DimensionEvidencePlan plan = factory.create(
                "\u54d4\u54e9\u54d4\u54e9",
                contract,
                List.of("open.bilibili.com"),
                List.of()
        );

        assertThat(plan.getCompetitorName()).isEqualTo("\u54d4\u54e9\u54d4\u54e9");
        assertThat(plan.getContractVersion()).isEqualTo(contract.getContractVersion());
        assertThat(plan.findField("coreFeatures")).isPresent();
        assertThat(plan.findField("pricing")).isEmpty();
        assertThat(plan.findField("weaknesses")).isEmpty();
        assertThat(plan.getFieldCoverages()).allSatisfy(field -> {
            assertThat(field.getStatus()).isEqualTo(FieldEvidenceCoverageStatus.NOT_STARTED);
            assertThat(field.getAttemptedPaths()).isEmpty();
        });
    }

    @Test
    void shouldMarkOnlyStageOneFirstReportFieldsAsCriticalWhenEnhancementScopeIsExplicit() {
        CoverageContract contract = new CoverageContractResolver(new AnalysisDimensionMappingCatalog())
                .resolve("standard_competitor_report",
                        List.of("\u4ea7\u54c1\u529f\u80fd", "\u5b9a\u4ef7", "\u98ce\u9669"),
                        List.of("\u5b98\u7f51", "\u4ea7\u54c1\u6587\u6863", "\u516c\u5f00\u6d4b\u8bc4"),
                        null);

        DimensionEvidencePlan plan = factory.create(
                "\u54d4\u54e9\u54d4\u54e9",
                contract,
                List.of("open.bilibili.com"),
                List.of("\u5b9a\u4ef7\u9875", "\u516c\u5f00\u6d4b\u8bc4")
        );

        assertThat(plan.findField("coreFeatures").orElseThrow().getCriticalForFirstReport()).isTrue();
        assertThat(plan.findField("pricing").orElseThrow().getCriticalForFirstReport()).isFalse();
        assertThat(plan.findField("weaknesses").orElseThrow().getCriticalForFirstReport()).isFalse();
        assertThat(plan.findField("pricing").orElseThrow().getPlannedQueries())
                .allSatisfy(query -> assertThat(query.getCriticalForFirstReport()).isFalse());
        assertThat(plan.findField("weaknesses").orElseThrow().getPlannedQueries())
                .allSatisfy(query -> assertThat(query.getCriticalForFirstReport()).isFalse());
    }
}
