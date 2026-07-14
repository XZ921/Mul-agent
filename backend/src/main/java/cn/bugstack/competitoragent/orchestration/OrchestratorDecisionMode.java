package cn.bugstack.competitoragent.orchestration;

/**
 * Orchestrator 决策生成模式。
 * 默认规则模式不调用模型；shadow 只产出对比事实；primary 才把模型候选作为主结果。
 */
public enum OrchestratorDecisionMode {
    RULE_ONLY,
    LLM_SHADOW,
    LLM_PRIMARY
}
