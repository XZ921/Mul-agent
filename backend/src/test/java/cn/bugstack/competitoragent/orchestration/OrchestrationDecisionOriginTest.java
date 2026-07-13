package cn.bugstack.competitoragent.orchestration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestrationDecisionOriginTest {

    @Test
    void shouldRouteLlmOriginsToLlmActionMatrixContract() {
        assertThat(OrchestrationDecisionOrigin.LLM_PRIMARY.decisionContract())
                .isEqualTo("LLM_ACTION_MATRIX");
        assertThat(OrchestrationDecisionOrigin.LLM_SHADOW.decisionContract())
                .isEqualTo("LLM_ACTION_MATRIX");
        assertThat(OrchestrationDecisionOrigin.LLM_PRIMARY.usesLlmActionMatrix()).isTrue();
        assertThat(OrchestrationDecisionOrigin.LLM_SHADOW.usesLlmActionMatrix()).isTrue();
    }

    @Test
    void shouldRouteRuleAndLegacyOriginsToLegacyRuleSetContract() {
        assertThat(OrchestrationDecisionOrigin.RULE_ONLY.decisionContract())
                .isEqualTo("LEGACY_RULE_SET");
        assertThat(OrchestrationDecisionOrigin.RULE_FALLBACK.decisionContract())
                .isEqualTo("LEGACY_RULE_SET");
        assertThat(OrchestrationDecisionOrigin.LEGACY_ADAPTER.decisionContract())
                .isEqualTo("LEGACY_RULE_SET");
        assertThat(OrchestrationDecisionOrigin.RULE_ONLY.usesLlmActionMatrix()).isFalse();
        assertThat(OrchestrationDecisionOrigin.RULE_FALLBACK.usesLlmActionMatrix()).isFalse();
        assertThat(OrchestrationDecisionOrigin.LEGACY_ADAPTER.usesLlmActionMatrix()).isFalse();
    }

    @Test
    void shouldUseOneConservativeDefaultForMissingOrInvalidOrigin() {
        assertThat(OrchestrationDecisionOrigin.defaultOrigin())
                .isEqualTo(OrchestrationDecisionOrigin.LEGACY_ADAPTER);
        assertThat(OrchestrationDecisionOrigin.fromValue(null))
                .isEqualTo(OrchestrationDecisionOrigin.defaultOrigin());
        assertThat(OrchestrationDecisionOrigin.fromValue("unknown-origin"))
                .isEqualTo(OrchestrationDecisionOrigin.defaultOrigin());
    }
}
