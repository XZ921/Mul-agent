package cn.bugstack.competitoragent.orchestration;

import java.util.List;

/**
 * Orchestrator 决策大脑的统一边界。
 * 调用方负责传入已归一化的上下文；实现只生成候选决策，不调用 Policy、Executor 或 Trace。
 */
public interface OrchestratorDecisionBrain {

    /**
     * 根据稳定的运行期上下文生成已归一化决策。
     * 没有候选决策时返回空列表，禁止返回 null。
     */
    List<OrchestrationDecision> decide(OrchestrationContext normalizedContext);
}
