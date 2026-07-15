package cn.bugstack.competitoragent.orchestration;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrchestrationDecisionAuditTraceTest {

    @Test
    void shouldCreateImmutableTraceWithoutExecutionOnlyMutationFields() {
        OrchestrationDecisionAuditTrace trace = new OrchestrationDecisionAuditAssembler()
                .assemble(OrchestrationDecisionAuditTestFixtures.fallbackBatch());

        assertThat(trace.traceSchemaVersion()).isEqualTo("ORCHESTRATION_TRACE_V2");
        assertThat(trace.attempts()).hasSize(2);
        assertThat(trace.finalDecisionIds()).containsExactly("od-801-rule-fallback");
        assertThat(trace.sourceUrls())
                .containsExactly(
                        OrchestrationDecisionAuditTestFixtures.TRUSTED_URL,
                        OrchestrationDecisionAuditTestFixtures.CHECKPOINT_URL,
                        OrchestrationDecisionAuditTestFixtures.SHADOW_URL)
                .doesNotContain(OrchestrationDecisionAuditTestFixtures.DISCARDED_URL);
        assertThatThrownBy(() -> trace.attempts().clear())
                .isInstanceOf(UnsupportedOperationException.class);

        assertThat(Arrays.stream(OrchestrationMutationTrace.class.getRecordComponents())
                .map(RecordComponent::getName))
                .doesNotContain("nodeTemplates", "runtimeCommand")
                .contains("sourceUrls");
    }

    @Test
    void shouldKeepDiscardedUrlsInFailureWarningButNotTrustedSources() {
        OrchestrationDecisionAuditTrace trace = new OrchestrationDecisionAuditAssembler()
                .assemble(OrchestrationDecisionAuditTestFixtures.fallbackBatch());

        assertThat(trace.llmFailure()).isNotNull();
        assertThat(trace.llmFailure().sourceUrls())
                .containsExactly(OrchestrationDecisionAuditTestFixtures.TRUSTED_URL);
        assertThat(trace.llmFailure().attempts().get(0).discardedSourceUrls())
                .extracting(OrchestrationDecisionParseResult.DiscardedSourceUrl::sourceUrl)
                .containsExactly(OrchestrationDecisionAuditTestFixtures.DISCARDED_URL);
        assertThat(trace.llmFailure().attempts().get(0).sourceUrls())
                .containsExactly(OrchestrationDecisionAuditTestFixtures.TRUSTED_URL);
    }
}
