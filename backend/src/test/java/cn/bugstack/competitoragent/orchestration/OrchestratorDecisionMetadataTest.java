package cn.bugstack.competitoragent.orchestration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestratorDecisionMetadataTest {

    @Test
    void shouldNormalizeFallbackMetadataWithoutLosingAuditFields() {
        OrchestratorDecisionMetadata metadata = OrchestratorDecisionMetadata.builder()
                .modelName(" deepseek-chat ")
                .temperature(0.0d)
                .promptHash(" sha256:prompt ")
                .llmResponseHash(" sha256:response ")
                .aiAuditTraceId(" orch-12345678-1234-1234-1234-123456789012 ")
                .parseRetryCount(-1)
                .fallbackUsed(false)
                .fallbackReason(" LLM_TIMEOUT ")
                .build()
                .normalized(OrchestrationDecisionOrigin.RULE_FALLBACK);

        assertThat(metadata.getModelName()).isEqualTo("deepseek-chat");
        assertThat(metadata.getTemperature()).isZero();
        assertThat(metadata.getPromptHash()).isEqualTo("sha256:prompt");
        assertThat(metadata.getLlmResponseHash()).isEqualTo("sha256:response");
        assertThat(metadata.getAiAuditTraceId()).isEqualTo("orch-12345678-1234-1234-1234-123456789012");
        assertThat(metadata.getParseRetryCount()).isZero();
        assertThat(metadata.isFallbackUsed()).isTrue();
        assertThat(metadata.getFallbackReason()).isEqualTo("LLM_TIMEOUT");
    }

    @Test
    void shouldNormalizeContradictoryShadowExecutionState() {
        OrchestratorDecisionMetadata metadata = OrchestratorDecisionMetadata.builder()
                .shadowExecuted(true)
                .shadowSkippedReason(" SHADOW_BUDGET_EXHAUSTED ")
                .build()
                .normalized(OrchestrationDecisionOrigin.LLM_SHADOW);

        assertThat(metadata.getShadowExecuted()).isFalse();
        assertThat(metadata.getShadowSkippedReason()).isEqualTo("SHADOW_BUDGET_EXHAUSTED");
    }

    @Test
    void shouldDropBlankAiAuditTraceId() {
        OrchestratorDecisionMetadata metadata = OrchestratorDecisionMetadata.builder()
                .aiAuditTraceId("  ")
                .build()
                .normalized(OrchestrationDecisionOrigin.LLM_PRIMARY);

        assertThat(metadata.getAiAuditTraceId()).isNull();
    }
}
