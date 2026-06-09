package cn.bugstack.competitoragent.agent.capability;

/**
 * Agent 能力接口。
 * <p>
 * 这里把运行时真正依赖的“可执行能力”从旧的 Agent SPI 上再包一层，
 * 让编排器只面向稳定的执行契约，而不是直接耦合 Spring 注入进来的 Agent 列表。
 */
public interface AgentCapability {

    /**
     * 执行一次 Agent 能力调用，并返回统一包装后的执行结果。
     */
    AgentExecutionResponse execute(AgentExecutionRequest request);
}
