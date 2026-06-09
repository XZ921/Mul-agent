package cn.bugstack.competitoragent.agent.capability;

import cn.bugstack.competitoragent.model.enums.AgentType;

/**
 * Agent 能力注册表。
 * <p>
 * DagExecutor 只需要根据 AgentType 找到对应执行能力，
 * 因此这里收敛成最小查询接口，避免运行时继续持有 List<Agent> 并自行建表。
 */
public interface AgentCapabilityRegistry {

    /**
     * 根据 Agent 类型解析对应能力。
     */
    AgentCapability resolve(AgentType agentType);
}
