package cn.bugstack.competitoragent.agent.capability;

import cn.bugstack.competitoragent.agent.Agent;
import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SpringAgentCapabilityRegistryTest {

    @Test
    void should_resolve_agent_type_through_runtime_capability() {
        SpringAgentCapabilityRegistry registry =
                new SpringAgentCapabilityRegistry(List.of(new DemoCollectorAgent()));

        AgentExecutionResponse response = registry.resolve(AgentType.COLLECTOR)
                .execute(new AgentExecutionRequest(AgentContext.builder()
                        .taskId(1L)
                        .taskName("runtime-test")
                        .currentNodeName("collect")
                        .build()));

        assertEquals(TaskNodeStatus.SUCCESS, response.result().getStatus());
        assertEquals("collector-output", response.result().getOutputData());
    }

    @Test
    void should_keep_null_return_semantics_when_capability_is_missing() {
        SpringAgentCapabilityRegistry registry =
                new SpringAgentCapabilityRegistry(List.of(new DemoCollectorAgent()));

        AgentCapability capability = registry.resolve(AgentType.REVIEWER);

        // Phase 1 明确保持兼容语义：缺能力时返回 null，由 DagExecutor 收口为节点失败。
        assertNull(capability);
    }

    @Test
    void should_wrap_existing_agent_spi_without_exposing_agent_list() {
        SpringAgentCapabilityRegistry registry =
                new SpringAgentCapabilityRegistry(List.of(new DemoCollectorAgent()));

        AgentCapability capability = registry.resolve(AgentType.COLLECTOR);

        assertNotNull(capability);
    }

    /**
     * 这里保留一个最小 Agent 假实现，用来证明 runtime registry 只关心 AgentType -> AgentCapability 映射，
     * 而不要求测试直接接触 DagExecutor 或其他编排层依赖。
     */
    private static final class DemoCollectorAgent implements Agent {

        @Override
        public AgentType getType() {
            return AgentType.COLLECTOR;
        }

        @Override
        public String getName() {
            return "demo-collector";
        }

        @Override
        public AgentResult execute(AgentContext context) {
            return AgentResult.builder()
                    .status(TaskNodeStatus.SUCCESS)
                    .outputData("collector-output")
                    .outputSummary("collector-success")
                    .build();
        }
    }
}
