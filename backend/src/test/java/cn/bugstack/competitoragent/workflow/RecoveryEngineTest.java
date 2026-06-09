package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryEngineTest {

    private final RecoveryEngine recoveryEngine = new RecoveryEngine();

    @Test
    void shouldSuggestRetryDispatchForWaitingRetryNode() {
        TaskNode node = TaskNode.builder()
                .taskId(10L)
                .nodeName("collect_sources_web")
                .displayName("collect_sources_web")
                .agentType(AgentType.COLLECTOR)
                .status(TaskNodeStatus.WAITING_RETRY)
                .retryCount(1)
                .maxRetries(3)
                .build();

        RecoveryCommand command = recoveryEngine.decideNextAction(node, List.of(), null);

        assertEquals(RecoveryCommand.ActionType.REQUEUE_NODE, command.getActionType());
        assertEquals(TaskNodeStatus.READY, command.getTargetStatus());
        assertTrue(command.getReason().contains("重试"));
    }

    @Test
    void shouldSuggestManualResumeForWaitingInterventionNodeWithoutDlqOverride() {
        TaskNode node = TaskNode.builder()
                .taskId(11L)
                .nodeName("quality_check")
                .displayName("quality_check")
                .agentType(AgentType.REVIEWER)
                .status(TaskNodeStatus.WAITING_INTERVENTION)
                .build();

        RecoveryCommand command = recoveryEngine.decideNextAction(node, List.of(), null);

        assertEquals(RecoveryCommand.ActionType.AWAIT_MANUAL_INTERVENTION, command.getActionType());
        assertEquals(TaskNodeStatus.WAITING_INTERVENTION, command.getTargetStatus());
    }

    @Test
    void shouldSuggestCompensationWhenFailureAlreadyMarkedAsCompensatable() {
        TaskNode node = TaskNode.builder()
                .taskId(12L)
                .nodeName("rewrite_report")
                .displayName("rewrite_report")
                .agentType(AgentType.WRITER)
                .status(TaskNodeStatus.FAILED)
                .build();

        RecoveryCommand command = recoveryEngine.decideNextAction(
                node,
                List.of(),
                NodeFailureCategory.COMPENSATABLE
        );

        assertEquals(RecoveryCommand.ActionType.EXECUTE_COMPENSATION, command.getActionType());
        assertEquals(TaskNodeStatus.COMPENSATED, command.getTargetStatus());
        assertTrue(command.getReason().contains("补偿"));
    }

    @Test
    void shouldSuggestFailFinalizationForPermanentFailure() {
        TaskNode node = TaskNode.builder()
                .taskId(13L)
                .nodeName("extract_schema")
                .displayName("extract_schema")
                .agentType(AgentType.EXTRACTOR)
                .status(TaskNodeStatus.FAILED)
                .build();

        RecoveryCommand command = recoveryEngine.decideNextAction(
                node,
                List.of(),
                NodeFailureCategory.PERMANENT_BUSINESS
        );

        assertEquals(RecoveryCommand.ActionType.FINALIZE_FAILURE, command.getActionType());
        assertEquals(TaskNodeStatus.FAILED, command.getTargetStatus());
    }
}
