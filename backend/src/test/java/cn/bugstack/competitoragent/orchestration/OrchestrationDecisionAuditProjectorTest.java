package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.model.dto.OrchestrationDecisionAuditSummary;
import cn.bugstack.competitoragent.model.dto.OrchestrationDecisionSummary;
import cn.bugstack.competitoragent.model.entity.TaskWorkflowEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestrationDecisionAuditProjectorTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void shouldProjectFallbackCycleWithPolicyRuntimeShadowAndTypedFailure() throws Exception {
        JsonNode payload = fixturePayload(0);
        TaskWorkflowEvent event = event(801L, payload);

        OrchestrationDecisionAuditSummary audit = OrchestrationDecisionSummaryProjector
                .auditFromWorkflowEvent(event, objectMapper)
                .orElseThrow();

        assertThat(audit.getTraceSchemaVersion()).isEqualTo("ORCHESTRATION_TRACE_V2");
        assertThat(audit.getMode()).isEqualTo("LLM_PRIMARY");
        assertThat(audit.isPolicyFallbackUsed()).isTrue();
        assertThat(audit.getFinalDecisionIds()).containsExactly("od-801-rule-fallback");
        assertThat(audit.getRepresentativeDecision()).satisfies(summary -> {
            assertThat(summary.getDecisionId()).isEqualTo("od-801-rule-fallback");
            assertThat(summary.getDecisionOrigin()).isEqualTo("RULE_FALLBACK");
            assertThat(summary.getPolicyAllowed()).isTrue();
            assertThat(summary.getRuntimeStatus()).isEqualTo("CONFIRMATION_REQUIRED");
            assertThat(summary.isFallbackAttempt()).isTrue();
            assertThat(summary.getMutationType()).isEqualTo("MARK_WAITING_INTERVENTION");
            assertThat(summary.getMutationBranchReason()).isEqualTo("ORCHESTRATOR_DECISION");
        });
        assertThat(audit.getAttempts()).hasSize(2);
        assertThat(audit.getAttempts().get(0)).satisfies(summary -> {
            assertThat(summary.getDecisionId()).isEqualTo("od-801-llm-primary");
            assertThat(summary.getPolicyAllowed()).isFalse();
            assertThat(summary.getPolicyBlockedReasons())
                    .containsExactly("INVALID_DECISION_ACTION_PAIR");
            assertThat(summary.getRuntimeStatus()).isEqualTo("POLICY_REJECTED");
            assertThat(summary.getMutationType()).isEqualTo("NO_MUTATION");
        });
        assertThat(audit.getShadowExecution().isExecuted()).isFalse();
        assertThat(audit.getLlmFailure().getType()).isEqualTo("PARSE_ERROR");
        assertThat(audit.getLlmFailure().getAttempts()).hasSize(2);
        assertThat(audit.getLlmFailure().getAttempts().get(0).getDiscardedSourceUrls())
                .extracting(OrchestrationDecisionAuditSummary.DiscardedSourceSummary::getSourceUrl)
                .containsExactly("https://untrusted.example.net/outside");
        assertThat(audit.getSourceUrls())
                .contains("https://docs.example.com/review-gap", "https://docs.example.com/checkpoint")
                .doesNotContain("https://untrusted.example.net/outside");
    }

    @Test
    void shouldProjectShadowSkippedCycleWithoutRepresentativeDecision() throws Exception {
        TaskWorkflowEvent event = event(802L, fixturePayload(1));

        OrchestrationDecisionAuditSummary audit = OrchestrationDecisionSummaryProjector
                .auditFromWorkflowEvent(event, objectMapper)
                .orElseThrow();

        assertThat(audit.getMode()).isEqualTo("LLM_SHADOW");
        assertThat(audit.getRepresentativeDecision()).isNull();
        assertThat(audit.getAttempts()).isEmpty();
        assertThat(audit.getShadowExecution().isRequested()).isTrue();
        assertThat(audit.getShadowExecution().isExecuted()).isFalse();
        assertThat(audit.getShadowExecution().getSkippedReason())
                .isEqualTo("SHADOW_BUDGET_EXHAUSTED");
        assertThat(audit.getSourceUrls())
                .containsExactly("https://docs.example.com/shadow-context");
        assertThat(OrchestrationDecisionSummaryProjector.fromWorkflowEvent(event, objectMapper))
                .isEmpty();
    }

    @Test
    void shouldProjectSameV2AuditFromDatabaseJsonAndStructuredMap() throws Exception {
        JsonNode payload = fixturePayload(0);
        OrchestrationDecisionAuditSummary fromEvent = OrchestrationDecisionSummaryProjector
                .auditFromWorkflowEvent(event(801L, payload), objectMapper)
                .orElseThrow();
        Map<String, Object> payloadMap = objectMapper.convertValue(
                payload, new TypeReference<Map<String, Object>>() {
                });

        OrchestrationDecisionAuditSummary fromMap = OrchestrationDecisionSummaryProjector
                .auditFromEventPayload(
                        payloadMap,
                        801L,
                        "quality_check_final",
                        objectMapper.convertValue(payload.path("sourceUrls"), new TypeReference<List<String>>() {
                        }),
                        objectMapper)
                .orElseThrow();

        assertThat(fromMap.getTraceSchemaVersion()).isEqualTo(fromEvent.getTraceSchemaVersion());
        assertThat(fromMap.getMode()).isEqualTo(fromEvent.getMode());
        assertThat(fromMap.getRepresentativeDecision()).isEqualTo(fromEvent.getRepresentativeDecision());
        assertThat(fromMap.getAttempts()).isEqualTo(fromEvent.getAttempts());
    }

    @Test
    void shouldFallbackToLegacyRepresentativeWhenV2AuditIsMalformed() {
        TaskWorkflowEvent event = TaskWorkflowEvent.builder()
                .taskId(803L)
                .nodeName("quality_check_final")
                .payload("""
                        {
                          "traceSchemaVersion": "ORCHESTRATION_TRACE_V2",
                          "decision": {
                            "decisionId": "od-legacy-fallback",
                            "decisionType": "NO_ACTION",
                            "actionType": "NO_ACTION"
                          },
                          "audit": {"mode": 42}
                        }
                        """)
                .sourceUrls("[]")
                .build();

        OrchestrationDecisionAuditSummary audit = OrchestrationDecisionSummaryProjector
                .auditFromWorkflowEvent(event, objectMapper)
                .orElseThrow();

        assertThat(audit.getTraceSchemaVersion()).isEqualTo("ORCHESTRATION_TRACE_V1");
        assertThat(audit.getMode()).isNull();
        assertThat(audit.getAttempts()).isEmpty();
        assertThat(audit.getRepresentativeDecision().getDecisionId())
                .isEqualTo("od-legacy-fallback");
    }

    private TaskWorkflowEvent event(Long taskId, JsonNode payload) throws Exception {
        return TaskWorkflowEvent.builder()
                .taskId(taskId)
                .nodeName("quality_check_final")
                .payload(objectMapper.writeValueAsString(payload))
                .sourceUrls(objectMapper.writeValueAsString(payload.path("sourceUrls")))
                .build();
    }

    private JsonNode fixturePayload(int index) throws Exception {
        try (InputStream inputStream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("orchestration/orchestration-trace-v2-fixtures.json")) {
            assertThat(inputStream).isNotNull();
            return objectMapper.readTree(inputStream).path("cases").get(index).path("payload");
        }
    }
}
