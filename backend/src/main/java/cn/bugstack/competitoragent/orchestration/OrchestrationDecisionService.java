package cn.bugstack.competitoragent.orchestration;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orchestrator 决策入口，统一负责原始上下文归一化并委托规则大脑生成决策。
 * 业务规则只由 {@link RuleBasedOrchestratorDecisionBrain} 持有，本服务不复制或改写决策结果。
 */
@Service
@RequiredArgsConstructor
public class OrchestrationDecisionService {

    private final RuleBasedOrchestratorDecisionBrain ruleBasedDecisionBrain;

    /**
     * 空上下文直接返回空列表；非空上下文归一化一次后原样返回规则大脑的结果。
     */
    public List<OrchestrationDecision> decide(OrchestrationContext rawContext) {
        if (rawContext == null) {
            return List.of();
        }
        return ruleBasedDecisionBrain.decide(rawContext.normalized());
    }
}
