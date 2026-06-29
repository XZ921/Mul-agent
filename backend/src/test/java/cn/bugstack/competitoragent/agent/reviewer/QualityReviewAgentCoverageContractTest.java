package cn.bugstack.competitoragent.agent.reviewer;

import cn.bugstack.competitoragent.workflow.coverage.CoverageBlockingLevel;
import cn.bugstack.competitoragent.workflow.coverage.CoverageContract;
import cn.bugstack.competitoragent.workflow.coverage.CoverageFieldContract;
import cn.bugstack.competitoragent.workflow.coverage.CoverageFieldStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QualityReviewAgentCoverageContractTest {

    @Test
    void shouldOnlyBlockRequiredFieldsFromCoverageContract() {
        CoverageContract contract = CoverageContract.builder()
                .taskMode("CAPABILITY_INTRO")
                .contractVersion("coverage-capability_intro-v1")
                .fields(List.of(
                        CoverageFieldContract.builder()
                                .field("coreFeatures")
                                .status(CoverageFieldStatus.REQUIRED)
                                .blockingLevel(CoverageBlockingLevel.BLOCKER)
                                .build(),
                        CoverageFieldContract.builder()
                                .field("pricing")
                                .status(CoverageFieldStatus.OUT_OF_SCOPE)
                                .blockingLevel(CoverageBlockingLevel.NONE)
                                .build()))
                .build();

        List<String> blockers = QualityReviewAgent.resolveCoverageBlockerFields(contract);

        assertThat(blockers).containsExactly("coreFeatures");
        assertThat(blockers).doesNotContain("pricing");
    }
}
