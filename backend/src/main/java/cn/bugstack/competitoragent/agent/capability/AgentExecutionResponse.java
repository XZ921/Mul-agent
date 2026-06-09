package cn.bugstack.competitoragent.agent.capability;

import cn.bugstack.competitoragent.agent.AgentResult;

/**
 * Agent 能力执行响应。
 * <p>
 * 统一包装后，DagExecutor 不再关心底层能力究竟来自旧 Agent SPI、
 * 还是未来的其他执行适配器，只消费标准化结果。
 */
public record AgentExecutionResponse(AgentResult result) {
}
