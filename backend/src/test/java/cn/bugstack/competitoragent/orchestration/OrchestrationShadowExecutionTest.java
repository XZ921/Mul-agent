package cn.bugstack.competitoragent.orchestration;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrchestrationShadowExecutionTest {

    @Test
    void shouldRejectContradictoryExecutionState() {
        assertThatThrownBy(() -> new OrchestrationShadowExecution(false, true, null, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrchestrationShadowExecution(true, true, "SHADOW_DISABLED", null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldDefensivelyCopySourceUrls() {
        List<String> sourceUrls = new ArrayList<>(List.of("https://example.com/source"));

        OrchestrationShadowExecution execution = OrchestrationShadowExecution.skipped(
                "SHADOW_DISABLED", null, sourceUrls);
        sourceUrls.clear();

        assertThat(execution.sourceUrls()).containsExactly("https://example.com/source");
        assertThatThrownBy(() -> execution.sourceUrls().add("https://example.com/other"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
