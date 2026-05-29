package cn.bugstack.competitoragent.agent;

import cn.bugstack.competitoragent.model.enums.AgentType;

/**
 * Agent 统一接口。
 * 每个 Agent 代表 DAG 中一种独立职责，统一消费 AgentContext，统一返回 AgentResult。
 */
public interface Agent {

    /**
     * 返回当前 Agent 的类型标识，供执行器做路由、日志记录与界面展示。
     */
    AgentType getType();

    /**
     * 返回当前 Agent 的名称，主要用于日志和任务节点详情展示。
     */
    String getName();

    /**
     * 执行 Agent 的核心业务逻辑。
     *
     * @param context 执行上下文，包含任务信息、节点配置和共享输出
     * @return 执行结果，包含状态、输出数据和耗时等信息
     */
    AgentResult execute(AgentContext context);
}
