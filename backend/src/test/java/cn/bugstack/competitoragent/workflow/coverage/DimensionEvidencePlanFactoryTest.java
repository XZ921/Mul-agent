package cn.bugstack.competitoragent.workflow.coverage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DimensionEvidencePlanFactoryTest {

    private final DimensionEvidencePlanFactory factory =
            new DimensionEvidencePlanFactory(new FieldEvidenceQueryPlanner());

    @Test
    void shouldCreatePlanOnlyForRequiredFieldsWithEvidencePaths() {
        CoverageContract contract = new CoverageContractResolver(new AnalysisDimensionMappingCatalog())
                .resolve("standard_competitor_report",
                        List.of("\u4ea7\u54c1\u529f\u80fd"),
                        List.of("\u5b98\u7f51", "\u4ea7\u54c1\u6587\u6863"),
                        null);

        DimensionEvidencePlan plan = factory.create("\u54d4\u54e9\u54d4\u54e9", contract, List.of("open.bilibili.com"));

        assertThat(plan.getCompetitorName()).isEqualTo("\u54d4\u54e9\u54d4\u54e9");
        assertThat(plan.getContractVersion()).isEqualTo(contract.getContractVersion());
        assertThat(plan.findField("coreFeatures")).isPresent();
        assertThat(plan.findField("pricing")).isPresent();
        assertThat(plan.findField("pricing").orElseThrow().getMinimumAttemptedPaths()).isEqualTo(1);
        assertThat(plan.findField("pricing").orElseThrow().getPlannedQueries())
                .hasSizeGreaterThanOrEqualTo(5);
        assertThat(plan.getFieldCoverages()).allSatisfy(field -> {
            assertThat(field.getStatus()).isEqualTo(FieldEvidenceCoverageStatus.NOT_STARTED);
            assertThat(field.getAttemptedPaths()).isEmpty();
        });
    }
}
