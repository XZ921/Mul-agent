package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.llm.ModelChatOptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrchestratorDecisionPropertiesTest {

    @Test
    void shouldExposeConservativeLlmDefaults() {
        OrchestratorDecisionProperties properties = new OrchestratorDecisionProperties();

        properties.validate();

        assertThat(properties.getMode()).isEqualTo(OrchestratorDecisionMode.RULE_ONLY);
        assertThat(properties.isFallbackToRule()).isTrue();
        assertThat(properties.getModelTemperature()).isZero();
        assertThat(properties.getLlmTimeoutMs()).isEqualTo(4000L);
        assertThat(properties.getMaxParseRetries()).isEqualTo(1);
        assertThat(properties.getExecutorThreads()).isEqualTo(2);
        assertThat(properties.getExecutorQueueCapacity()).isEqualTo(16);
        assertThat(properties.getShadow().isEnabled()).isFalse();
        assertThat(properties.getShadow().getIsolatedBudgetKey()).isEqualTo("ORCHESTRATOR_SHADOW");
        assertThat(properties.getShadow().isRequireActiveQuota()).isTrue();
    }

    @Test
    void shouldRejectInvalidTemperature() {
        for (double value : new double[]{-0.01d, Double.NaN, Double.POSITIVE_INFINITY}) {
            OrchestratorDecisionProperties properties = new OrchestratorDecisionProperties();
            properties.setModelTemperature(value);

            assertThatThrownBy(properties::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("modelTemperature");
        }
    }

    @Test
    void shouldRejectInvalidRequestScopedModelOptions() {
        assertThatThrownBy(() -> new ModelChatOptions(-0.01d, 4000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("temperature");
        assertThatThrownBy(() -> new ModelChatOptions(Double.NaN, 4000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("temperature");
        assertThatThrownBy(() -> new ModelChatOptions(0.0d, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeoutMillis");
    }

    @Test
    void shouldRejectInvalidTimeoutRetryAndExecutorBounds() {
        assertInvalid(properties -> properties.setLlmTimeoutMs(0L), "llmTimeoutMs");
        assertInvalid(properties -> properties.setMaxParseRetries(-1), "maxParseRetries");
        assertInvalid(properties -> properties.setMaxParseRetries(2), "maxParseRetries");
        assertInvalid(properties -> properties.setExecutorThreads(0), "executorThreads");
        assertInvalid(properties -> properties.setExecutorQueueCapacity(0), "executorQueueCapacity");
    }

    @Test
    void shouldRejectMissingModeShadowAndBudgetKey() {
        assertInvalid(properties -> properties.setMode(null), "mode");
        assertInvalid(properties -> properties.setShadow(null), "shadow");
        assertInvalid(properties -> properties.getShadow().setIsolatedBudgetKey("  "), "isolatedBudgetKey");
    }

    private void assertInvalid(java.util.function.Consumer<OrchestratorDecisionProperties> mutation,
                               String fieldName) {
        OrchestratorDecisionProperties properties = new OrchestratorDecisionProperties();
        mutation.accept(properties);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(fieldName);
    }
}
