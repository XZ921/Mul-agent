package cn.bugstack.competitoragent.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrchestrationDecisionAuditAssemblerTest {

    private final OrchestrationDecisionAuditAssembler assembler =
            new OrchestrationDecisionAuditAssembler();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void shouldSelectLastFinalAttemptAndPreserveFallbackAssociation() {
        OrchestrationRuntimeDecisionBatch batch = OrchestrationDecisionAuditTestFixtures.fallbackBatch();

        OrchestrationDecisionAuditTrace trace = assembler.assemble(batch);
        OrchestrationRuntimeDecisionTrace representative = assembler
                .selectRepresentativeAttempt(batch)
                .orElseThrow();

        assertThat(representative.decision().getDecisionId()).isEqualTo("od-801-rule-fallback");
        assertThat(representative.fallbackAttempt()).isTrue();
        assertThat(representative.runtimeStatus())
                .isEqualTo(OrchestrationRuntimeDecision.CONFIRMATION_REQUIRED);
        assertThat(trace.policyFallbackUsed()).isTrue();
        assertThat(trace.attempts())
                .extracting(item -> item.decision().getDecisionId())
                .containsExactly("od-801-llm-primary", "od-801-rule-fallback");
        assertThat(trace.shadowDecisions()).singleElement()
                .extracting(OrchestrationDecision::getDecisionOrigin)
                .isEqualTo(OrchestrationDecisionOrigin.LLM_SHADOW);
    }

    @Test
    void shouldSelectLastAttemptWhenNoFinalDecisionAndReturnEmptyForShadowOnly() {
        OrchestrationRuntimeDecisionBatch fallbackBatch =
                OrchestrationDecisionAuditTestFixtures.fallbackBatch();
        OrchestrationRuntimeDecisionBatch noFinal = new OrchestrationRuntimeDecisionBatch(
                fallbackBatch.coordinatorOutcome(),
                fallbackBatch.runtimeState(),
                fallbackBatch.attempts(),
                java.util.List.of(),
                fallbackBatch.policyFallbackUsed(),
                fallbackBatch.sourceUrls());

        assertThat(assembler.selectRepresentativeAttempt(noFinal))
                .get()
                .extracting(item -> item.decision().getDecisionId())
                .isEqualTo("od-801-rule-fallback");
        assertThat(assembler.selectRepresentativeAttempt(
                OrchestrationDecisionAuditTestFixtures.shadowSkippedBatch()))
                .isEmpty();
    }

    @Test
    void shouldRejectNullBatch() {
        assertThatThrownBy(() -> assembler.assemble(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> assembler.selectRepresentativeAttempt(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldMatchCanonicalFallbackFixtureAtFrozenContractBoundaries() throws Exception {
        JsonNode actual = objectMapper.valueToTree(assembler.assemble(
                OrchestrationDecisionAuditTestFixtures.fallbackBatch()));
        JsonNode expected = loadCanonicalFallbackAudit();

        assertThat(actual.path("traceSchemaVersion").asText())
                .isEqualTo(expected.path("traceSchemaVersion").asText());
        assertThat(actual.path("mode").asText()).isEqualTo(expected.path("mode").asText());
        assertThat(actual.path("attempts")).hasSameSizeAs(expected.path("attempts"));
        assertThat(actual.path("finalDecisionIds"))
                .extracting(JsonNode::asText)
                .containsExactly(expected.path("finalDecisionIds").get(0).asText());
        assertThat(actual.path("runtimeState").path("checkpointStateStatus").asText())
                .isEqualTo(expected.path("runtimeState").path("checkpointStateStatus").asText());
        assertThat(actual.path("llmFailure").path("attempts")).hasSize(2);
    }

    private JsonNode loadCanonicalFallbackAudit() throws Exception {
        try (InputStream inputStream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("orchestration/orchestration-trace-v2-fixtures.json")) {
            assertThat(inputStream).isNotNull();
            return objectMapper.readTree(inputStream).path("cases").get(0).path("payload").path("audit");
        }
    }
}
