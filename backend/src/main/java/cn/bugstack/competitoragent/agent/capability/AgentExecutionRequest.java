package cn.bugstack.competitoragent.agent.capability;

import cn.bugstack.competitoragent.agent.AgentContext;

/**
 * Agent 能力执行请求。
 * <p>
 * Phase 1 先只透传编排器已经构造好的 AgentContext，
 * 用统一请求壳把运行时合同固定住，避免 DagExecutor 直接依赖具体 Agent 实现签名。
 */
public record AgentExecutionRequest(AgentContext context) {
}
