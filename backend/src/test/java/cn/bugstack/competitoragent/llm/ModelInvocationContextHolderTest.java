package cn.bugstack.competitoragent.llm;

import cn.bugstack.competitoragent.governance.GovernanceDefaults;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelInvocationContextHolderTest {

    @AfterEach
    void tearDown() {
        ModelInvocationContextHolder.clear();
    }

    @Test
    void shouldKeepLegacyDefaults() {
        ModelInvocationContextHolder.set(1L, "writer", "trace-1");

        ModelInvocationContextHolder.ModelInvocationContext context = ModelInvocationContextHolder.get();

        assertThat(context.purpose()).isEqualTo(ModelInvocationPurpose.DEFAULT);
        assertThat(context.quotaKey()).isEqualTo(GovernanceDefaults.MODEL_DAILY_BUDGET_KEY);
        assertThat(context.requireActiveQuota()).isFalse();
        assertThat(context.organizationQuotaReserved()).isFalse();
    }

    @Test
    void shouldRestoreOuterContextAfterNestedShadowScope() {
        ModelInvocationContextHolder.set(1L, "writer", "outer-trace");
        ModelInvocationContextHolder.ModelInvocationContext outer = ModelInvocationContextHolder.get();

        ModelInvocationContextHolder.ModelInvocationContext observed =
                ModelInvocationContextHolder.withContext(
                        2L,
                        "reviewer",
                        "shadow-trace",
                        ModelInvocationPurpose.ORCHESTRATOR_SHADOW,
                        "ORCHESTRATOR_SHADOW",
                        true,
                        false,
                        ModelInvocationContextHolder::get);

        assertThat(observed.purpose()).isEqualTo(ModelInvocationPurpose.ORCHESTRATOR_SHADOW);
        assertThat(observed.quotaKey()).isEqualTo("ORCHESTRATOR_SHADOW");
        assertThat(observed.requireActiveQuota()).isTrue();
        assertThat(ModelInvocationContextHolder.get()).isSameAs(outer);
    }

    @Test
    void shouldClearScopeAfterExceptionWhenNoOuterContextExists() {
        assertThatThrownBy(() -> ModelInvocationContextHolder.withContext(
                2L, "reviewer", "trace", () -> {
                    throw new IllegalStateException("expected");
                })).isInstanceOf(IllegalStateException.class);

        assertThat(ModelInvocationContextHolder.get()).isNull();
    }
}
