package cn.bugstack.competitoragent.orchestration;

/** Orchestrator LLM 候选生成阶段的稳定失败分类。 */
public enum LlmOrchestratorFailureType {
    LLM_TIMEOUT,
    LLM_ERROR,
    PARSE_ERROR
}
