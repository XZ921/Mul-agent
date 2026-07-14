package cn.bugstack.competitoragent.orchestration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 阶段二 Orchestrator 决策对象的 composition root。
 */
@Configuration
public class OrchestrationDecisionConfiguration {

    /**
     * 阶段二 DecisionPolicyRuleSet 的唯一 owner。
     * 未来改为 YAML 或数据库配置时只替换本 Bean 的构造来源，禁止调用方另行创建默认规则集。
     */
    @Bean
    public DecisionPolicyRuleSet orchestrationDecisionRuleSet() {
        return DecisionPolicyRuleSet.builder().build().normalized();
    }

    /**
     * LLM Brain 保持纯 POJO，由 composition root 显式装配，避免通过 @Primary 猜测 Brain 实现。
     */
    @Bean
    public LlmOrchestratorDecisionBrain llmOrchestratorDecisionBrain(
            OrchestrationDecisionPromptBuilder promptBuilder,
            OrchestrationDecisionModelInvoker modelInvoker,
            OrchestrationDecisionResponseParser parser,
            OrchestrationDecisionRetryPromptBuilder retryPromptBuilder,
            DecisionPolicyRuleSet ruleSet,
            OrchestratorDecisionProperties properties) {
        return new LlmOrchestratorDecisionBrain(
                promptBuilder,
                modelInvoker,
                parser,
                retryPromptBuilder,
                ruleSet,
                properties);
    }
}
