package cn.bugstack.competitoragent.orchestration;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrchestrationRuntimeStateTest {

    @Test
    void shouldNormalizeCollectionsSectionKeysAndUnsafeValues() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put(" Pricing ", 1);
        counts.put("pricing", 2);
        counts.put(" ", 1);

        OrchestrationRuntimeState state = new OrchestrationRuntimeState(
                -1,
                counts,
                10L,
                -2,
                OrchestrationRuntimeState.CheckpointStateStatus.RESTORED,
                List.of(" https://example.com/a ", "https://example.com/a"));

        assertThat(state.currentDecisionCount()).isZero();
        assertThat(state.nextPlanVersion()).isEqualTo(1);
        assertThat(state.checkpointStateStatus())
                .isEqualTo(OrchestrationRuntimeState.CheckpointStateStatus.UNREADABLE);
        assertThat(state.dynamicBranchCountsBySection())
                .containsOnlyKeys("pricing", OrchestrationRuntimeState.UNSCOPED_SECTION)
                .containsEntry("pricing", 2)
                .containsEntry(OrchestrationRuntimeState.UNSCOPED_SECTION, 1);
        assertThat(state.sourceUrls()).containsExactly("https://example.com/a");
        assertThatThrownBy(() -> state.dynamicBranchCountsBySection().put("other", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldFailClosedWhenCheckpointStatusIsMissing() {
        OrchestrationRuntimeState state = new OrchestrationRuntimeState(0, null, null, 1, null, null);

        assertThat(state.checkpointStateStatus())
                .isEqualTo(OrchestrationRuntimeState.CheckpointStateStatus.UNREADABLE);
        assertThat(state.dynamicBranchCountsBySection()).isEmpty();
        assertThat(state.sourceUrls()).isEmpty();
    }
}
