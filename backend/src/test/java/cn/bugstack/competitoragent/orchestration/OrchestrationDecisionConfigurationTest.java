package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.governance.OrganizationQuotaPolicy;
import cn.bugstack.competitoragent.llm.BudgetGuard;
import cn.bugstack.competitoragent.llm.ModelGateway;
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
            .withBean(OrchestrationDecisionService.class)
            .withBean(DecisionPolicyService.class, () -> mock(DecisionPolicyService.class))
            .withBean(DecisionExecutorAdapter.class, () -> mock(DecisionExecutorAdapter.class))
            .withBean(OrchestrationRuntimeStateService.class,
                    () -> mock(OrchestrationRuntimeStateService.class))
            .withBean(OrchestrationRuntimeDecisionService.class);

    @Test
    void shouldCreateSingleRuleSetAndExplicitlyWireBothBrains() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(DecisionPolicyRuleSet.class)).hasSize(1);
            assertThat(context.getBeansOfType(LlmOrchestratorDecisionBrain.class)).hasSize(1);

            DecisionPolicyRuleSet ruleSet = context.getBean(DecisionPolicyRuleSet.class);
            LlmOrchestratorDecisionBrain llmBrain = context.getBean(LlmOrchestratorDecisionBrain.class);
            OrchestrationDecisionService service = context.getBean(OrchestrationDecisionService.class);
            OrchestrationRuntimeDecisionService runtimeService =
                    context.getBean(OrchestrationRuntimeDecisionService.class);

            assertThat(ReflectionTestUtils.getField(llmBrain, "normalizedRuleSet")).isSameAs(ruleSet);
            assertThat(ReflectionTestUtils.getField(service, "llmDecisionBrain")).isSameAs(llmBrain);
            assertThat(ReflectionTestUtils.getField(service, "ruleBasedDecisionBrain"))
                    .isSameAs(context.getBean(RuleBasedOrchestratorDecisionBrain.class));
            assertThat(ReflectionTestUtils.getField(runtimeService, "ruleSet")).isSameAs(ruleSet);
        });
    }

    @Test
    void shouldCreateShadowBudgetGateThroughProductionConstructor() {
        new ApplicationContextRunner()
                .withUserConfiguration(OrchestrationDecisionConfiguration.class)
                .withBean(OrchestratorDecisionProperties.class)
                .withBean(BudgetGuard.class, () -> mock(BudgetGuard.class))
                .withBean(OrganizationQuotaPolicy.class, () -> mock(OrganizationQuotaPolicy.class))
                .withBean(ModelGateway.class, () -> mock(ModelGateway.class))
                .withBean(OrchestrationShadowBudgetGate.class)
                .withBean(OrchestrationDecisionModelInvoker.class)
                .withBean(OrchestrationDecisionPromptBuilder.class,
                        () -> mock(OrchestrationDecisionPromptBuilder.class))
                .withBean(OrchestrationDecisionResponseParser.class,
                        () -> mock(OrchestrationDecisionResponseParser.class))
                .withBean(OrchestrationDecisionRetryPromptBuilder.class,
                        () -> mock(OrchestrationDecisionRetryPromptBuilder.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(OrchestrationShadowBudgetGate.class)).hasSize(1);
                    assertThat(context.getBeansOfType(OrchestrationDecisionModelInvoker.class)).hasSize(1);
                    assertThat(context.getBeansOfType(LlmOrchestratorDecisionBrain.class)).hasSize(1);

                    OrchestrationShadowBudgetGate gate = context.getBean(OrchestrationShadowBudgetGate.class);
                    assertThat(ReflectionTestUtils.getField(gate, "budgetGuard"))
                            .isSameAs(context.getBean(BudgetGuard.class));
                    assertThat(ReflectionTestUtils.getField(gate, "organizationQuotaPolicy"))
                            .isSameAs(context.getBean(OrganizationQuotaPolicy.class));
                    assertThat(ReflectionTestUtils.getField(gate, "properties"))
                            .isSameAs(context.getBean(OrchestratorDecisionProperties.class));
                    assertThat(ReflectionTestUtils.getField(
                            context.getBean(OrchestrationDecisionModelInvoker.class),
                            "shadowBudgetGate"))
                            .isSameAs(gate);
                });
    }
}
