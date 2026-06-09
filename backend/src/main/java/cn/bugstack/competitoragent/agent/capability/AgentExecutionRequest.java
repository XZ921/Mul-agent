package cn.bugstack.competitoragent.agent.capability;

import cn.bugstack.competitoragent.agent.AgentContext;

/**
 * Agent 能力执行请求。
 * <p>
 * Phase 1 先只透传编排器已经构造好的 AgentContext，
 * 后续若要扩展更多运行时元数据，可以在不破坏 DagExecutor 调用点的前提下继续演进。
 */
public record AgentExecutionRequest(AgentContext context) {
}
