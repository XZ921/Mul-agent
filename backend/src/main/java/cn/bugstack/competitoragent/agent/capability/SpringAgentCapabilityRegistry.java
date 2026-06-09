package cn.bugstack.competitoragent.agent.capability;

import cn.bugstack.competitoragent.agent.Agent;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.model.enums.AgentType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 Spring 注入 Agent 列表的能力注册表适配器。
 * <p>
 * 这里保持实现尽量薄，只负责把既有 Agent SPI 折叠成 DagExecutor 可消费的能力表，
 * 不在注册表层引入新的业务分支，避免运行时行为在重构过程中发生漂移。
 */
@Slf4j
@Component
public class SpringAgentCapabilityRegistry implements AgentCapabilityRegistry {

    private final Map<AgentType, AgentCapability> capabilities;

    public SpringAgentCapabilityRegistry(List<Agent> agents) {
        this.capabilities = buildCapabilities(agents);
    }

    @Override
    public AgentCapability resolve(AgentType agentType) {
        return capabilities.get(agentType);
    }

    /**
     * 统一把 Spring 托管的 Agent 列表折叠为 AgentType -> AgentCapability 映射。
     * <p>
     * 若存在重复类型，这里保留最后注册的实现并记录告警，
     * 便于后续在架构治理阶段继续收敛重复实现。
     */
    private Map<AgentType, AgentCapability> buildCapabilities(List<Agent> agents) {
        Map<AgentType, AgentCapability> registry = new EnumMap<>(AgentType.class);
        for (Agent agent : agents) {
            AgentCapability previous = registry.put(agent.getType(), request -> {
                AgentResult result = agent.execute(request.context());
                return new AgentExecutionResponse(result);
            });
            if (previous != null) {
                log.warn("duplicate agent capability registered, agentType={}", agent.getType());
            }
        }
        log.info("agent capability registry initialized: {}", registry.keySet());
        return registry;
    }
}
