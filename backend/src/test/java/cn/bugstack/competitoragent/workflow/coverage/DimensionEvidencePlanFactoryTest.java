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
                .resolve("标准版", List.of("产品功能"), List.of("官网", "产品文档"), null);

        DimensionEvidencePlan plan = factory.create("哔哩哔哩", contract, List.of("open.bilibili.com"));

        assertThat(plan.getCompetitorName()).isEqualTo("哔哩哔哩");
        assertThat(plan.getContractVersion()).isEqualTo(contract.getContractVersion());
        assertThat(plan.findField("coreFeatures")).isPresent();
        assertThat(plan.findField("pricing")).isPresent();
        assertThat(plan.findField("pricing").orElseThrow().getMinimumAttemptedPaths()).isEqualTo(2);
        assertThat(plan.findField("pricing").orElseThrow().getPlannedQueries())
                .hasSizeGreaterThanOrEqualTo(5);
        assertThat(plan.getFieldCoverages()).allSatisfy(field -> {
            assertThat(field.getStatus()).isEqualTo(FieldEvidenceCoverageStatus.NOT_STARTED);
            assertThat(field.getAttemptedPaths()).isEmpty();
        });
    }
}