package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.agent.capability.AgentCapabilityRegistry;
import cn.bugstack.competitoragent.event.TaskEventPublisher;
import cn.bugstack.competitoragent.log.AgentLogService;
import cn.bugstack.competitoragent.orchestration.DecisionExecutorAdapter;
import cn.bugstack.competitoragent.orchestration.DecisionPolicyService;
import cn.bugstack.competitoragent.orchestration.OrchestrationDecisionService;
import cn.bugstack.competitoragent.orchestration.OrchestrationTraceService;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.TaskNodeExecutionAttemptRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import cn.bugstack.competitoragent.repository.WorkflowDeadLetterRecordRepository;
import cn.bugstack.competitoragent.task.TaskExecutionLockService;
import cn.bugstack.competitoragent.task.TaskQuotaCoordinator;
import cn.bugstack.competitoragent.task.TaskSnapshotCacheService;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventPublisher;
import cn.bugstack.competitoragent.workflow.runtime.DynamicPlanAppender;
import cn.bugstack.competitoragent.workflow.runtime.RuntimeEventEmitter;
import cn.bugstack.competitoragent.workflow.runtime.RuntimeStateRefresher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

class DagExecutorRuntimeDependencyTest {

    @Test
    void should_depend_on_runtime_capability_registry_instead_of_agent_list() {
        Set<String> fieldNames = Arrays.stream(DagExecutor.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toSet());

        assertTrueContains(fieldNames, "agentCapabilityRegistry");
        assertFalse(fieldNames.contains("agents"),
                "DagExecutor 不应回退为直接持有 List<Agent>");
    }

    @Test
    void should_accept_agent_capability_registry_through_constructor() {
        DagExecutor executor = new DagExecutor(
                mock(TaskNodeRepository.class),
                mock(AnalysisTaskRepository.class),
                mock(AgentCapabilityRegistry.class),
                new ObjectMapper(),
                mock(TaskSnapshotCacheService.class),
                mock(TaskExecutionLockService.class),
                mock(TaskEventPublisher.class),
                mock(AgentLogService.class),
                mock(WorkflowEventPublisher.class),
                mock(TaskNodeExecutionAttemptRepository.class),
                mock(WorkflowDeadLetterRecordRepository.class),
                new RuntimeStateRefresher(
                        mock(AnalysisTaskRepository.class),
                        mock(TaskNodeRepository.class),
                        mock(TaskSnapshotCacheService.class),
                        mock(TaskEventPublisher.class)),
                new RuntimeEventEmitter(
                        mock(TaskEventPublisher.class),
                        mock(AgentLogService.class),
                        new ObjectMapper()),
                new DynamicPlanAppender(
                        mock(AnalysisTaskRepository.class),
                        mock(TaskNodeRepository.class),
                        mock(DynamicTaskGraphService.class),
                        mock(TaskPlanRepository.class),
                        new ObjectMapper(),
                        mock(OrchestrationDecisionService.class),
                        mock(DecisionPolicyService.class),
                        mock(DecisionExecutorAdapter.class),
                        mock(OrchestrationTraceService.class)),
                mock(TaskQuotaCoordinator.class)
        );

        // 只要构造成功，就说明 DagExecutor 的运行时依赖已经稳定在 capability registry 上。
        assertEquals(DagExecutor.class, executor.getClass());
    }

    private void assertTrueContains(Set<String> fieldNames, String expectedField) {
        assertEquals(true, fieldNames.contains(expectedField),
                "DagExecutor 应显式持有 " + expectedField + " 字段");
    }
}
