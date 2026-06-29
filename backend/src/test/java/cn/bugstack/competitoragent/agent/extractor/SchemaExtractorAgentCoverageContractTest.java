package cn.bugstack.competitoragent.agent.extractor;

import cn.bugstack.competitoragent.workflow.coverage.CoverageBlockingLevel;
import cn.bugstack.competitoragent.workflow.coverage.CoverageContract;
import cn.bugstack.competitoragent.workflow.coverage.CoverageFieldContract;
import cn.bugstack.competitoragent.workflow.coverage.CoverageFieldStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaExtractorAgentCoverageContractTest {

    @Test
    void shouldRenderOutOfScopePricingGuidance() {
        CoverageContract contract = CoverageContract.builder()
                .fields(List.of(CoverageFieldContract.builder()
                        .field("pricing")
                        .status(CoverageFieldStatus.OUT_OF_SCOPE)
                        .blockingLevel(CoverageBlockingLevel.NONE)
                        .overrideReason("能力介绍任务不强检定价")
                        .build()))
                .build();

        String guidance = SchemaExtractorAgent.renderCoverageContractGuidance(contract);

        assertThat(guidance).contains("pricing=OUT_OF_SCOPE");
        assertThat(guidance).contains("能力介绍任务不强检定价");
    }
}
