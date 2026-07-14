package cn.bugstack.competitoragent.orchestration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OrchestrationDecisionConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(OrchestrationDecisionConfiguration.class)
            .withBean(OrchestratorDecisionProperties.class)
            .withBean(OrchestrationDecisionPromptBuilder.class,
                    () -> mock(OrchestrationDecisionPromptBuilder.class))
            .withBean(OrchestrationDecisionModelInvoker.class,
                    () -> mock(OrchestrationDecisionModelInvoker.class))
            .withBean(OrchestrationDecisionResponseParser.class,
                    () -> mock(OrchestrationDecisionResponseParser.class))
            .withBean(OrchestrationDecisionRetryPromptBuilder.class,
                    () -> mock(OrchestrationDecisionRetryPromptBuilder.class))
            .withBean(RuleBasedOrchestratorDecisionBrain.class,
                    () -> mock(RuleBasedOrchestratorDecisionBrain.class))
            .withBean(OrchestratorFallbackReasonMapper.class)
            .withBean(OrchestrationDecisionService.class);

    @Test
    void shouldCreateSingleRuleSetAndExplicitlyWireBothBrains() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(DecisionPolicyRuleSet.class)).hasSize(1);
            assertThat(context.getBeansOfType(LlmOrchestratorDecisionBrain.class)).hasSize(1);

            DecisionPolicyRuleSet ruleSet = context.getBean(DecisionPolicyRuleSet.class);
            LlmOrchestratorDecisionBrain llmBrain = context.getBean(LlmOrchestratorDecisionBrain.class);
            OrchestrationDecisionService service = context.getBean(OrchestrationDecisionService.class);

            assertThat(ReflectionTestUtils.getField(llmBrain, "normalizedRuleSet")).isSameAs(ruleSet);
            assertThat(ReflectionTestUtils.getField(service, "llmDecisionBrain")).isSameAs(llmBrain);
            assertThat(ReflectionTestUtils.getField(service, "ruleBasedDecisionBrain"))
                    .isSameAs(context.getBean(RuleBasedOrchestratorDecisionBrain.class));
        });
    }
}
