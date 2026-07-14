package cn.bugstack.competitoragent.orchestration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrchestratorDecisionModeTest {

    @Test
    void shouldExposeExactlyThreeExplicitModes() {
        assertThat(OrchestratorDecisionMode.values()).containsExactly(
                OrchestratorDecisionMode.RULE_ONLY,
                OrchestratorDecisionMode.LLM_SHADOW,
                OrchestratorDecisionMode.LLM_PRIMARY);
    }

    @Test
    void shouldUseSpringBindingForCaseInsensitiveModeAndRejectUnknownValue() {
        OrchestratorDecisionProperties bound = bind("llm_shadow");

        assertThat(bound.getMode()).isEqualTo(OrchestratorDecisionMode.LLM_SHADOW);
        assertThatThrownBy(() -> bind("automatic"))
                .isInstanceOf(BindException.class);
    }

    private OrchestratorDecisionProperties bind(String mode) {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "orchestration.decision.mode", mode));
        return new Binder(source)
                .bind("orchestration.decision", OrchestratorDecisionProperties.class)
                .orElseThrow(() -> new IllegalStateException("decision properties 绑定结果为空"));
    }
}
