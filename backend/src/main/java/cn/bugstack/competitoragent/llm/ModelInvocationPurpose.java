package cn.bugstack.competitoragent.llm;

/**
 * 模型调用用途，用于在统一网关内区分默认调用、Orchestrator 主调用和 shadow 调用。
 */
public enum ModelInvocationPurpose {
    DEFAULT,
    ORCHESTRATOR_PRIMARY,
    ORCHESTRATOR_SHADOW
}
