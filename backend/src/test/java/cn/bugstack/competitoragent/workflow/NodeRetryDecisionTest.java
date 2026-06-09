package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeRetryDecisionTest {

    @Test
    void shouldClassifyTransientTimeoutAsRetryableBeforeMaxRetries() {
        TaskNode node = TaskNode.builder()
                .taskId(1L)
                .nodeName("collect_sources_web")
                .displayName("collect_sources_web")
                .agentType(AgentType.COLLECTOR)
                .retryable(true)
                .maxRetries(3)
                .retryCount(1)
                .status(TaskNodeStatus.RUNNING)
                .build();

        NodeRetryDecision decision = NodeRetryDecision.evaluate(node, "HTTP timeout while collecting source page");

        assertEquals(NodeFailureCategory.TRANSIENT_INFRASTRUCTURE, decision.getFailureCategory());
        assertEquals(TaskNodeStatus.WAITING_RETRY, decision.getNextStatus());
        assertEquals(2, decision.getNextRetryCount());
        assertTrue(decision.isRetryPlanned());
        assertTrue(decision.getUserReadableSummary().contains("重试"));
    }

    @Test
    void shouldMoveRetryableFailureToDlqAfterRetryBudgetExhausted() {
        TaskNode node = TaskNode.builder()
                .taskId(2L)
                .nodeName("collect_sources_docs")
                .displayName("collect_sources_docs")
                .agentType(AgentType.COLLECTOR)
                .retryable(true)
                .maxRetries(2)
                .retryCount(2)
                .status(TaskNodeStatus.RUNNING)
                .build();

        NodeRetryDecision decision = NodeRetryDecision.evaluate(node, "Rate limit exceeded by remote provider");

        assertEquals(NodeFailureCategory.TRANSIENT_INFRASTRUCTURE, decision.getFailureCategory());
        assertEquals(TaskNodeStatus.WAITING_INTERVENTION, decision.getNextStatus());
        assertEquals(2, decision.getNextRetryCount());
        assertTrue(decision.shouldEnterDlq());
        assertTrue(decision.getUserReadableSummary().contains("人工"));
    }

    @Test
    void shouldClassifyBadRequestAsPermanentFailureWithoutRetry() {
        TaskNode node = TaskNode.builder()
                .taskId(3L)
                .nodeName("extract_schema")
                .displayName("extract_schema")
                .agentType(AgentType.EXTRACTOR)
                .retryable(true)
                .maxRetries(3)
                .retryCount(0)
                .status(TaskNodeStatus.RUNNING)
                .build();

        NodeRetryDecision decision = NodeRetryDecision.evaluate(node, "Invalid input schema, request payload malformed");

        assertEquals(NodeFailureCategory.PERMANENT_BUSINESS, decision.getFailureCategory());
        assertEquals(TaskNodeStatus.FAILED, decision.getNextStatus());
        assertEquals(0, decision.getNextRetryCount());
        assertTrue(decision.isTerminalFailure());
    }

    @Test
    void shouldRequireManualInterventionForPermissionAndPolicyErrors() {
        TaskNode node = TaskNode.builder()
                .taskId(4L)
                .nodeName("write_report")
                .displayName("write_report")
                .agentType(AgentType.WRITER)
                .retryable(true)
                .maxRetries(3)
                .retryCount(0)
                .status(TaskNodeStatus.RUNNING)
                .build();

        NodeRetryDecision decision = NodeRetryDecision.evaluate(node, "Permission denied while writing protected artifact");

        assertEquals(NodeFailureCategory.MANUAL_INTERVENTION_REQUIRED, decision.getFailureCategory());
        assertEquals(TaskNodeStatus.WAITING_INTERVENTION, decision.getNextStatus());
        assertTrue(decision.requiresManualIntervention());
        assertTrue(decision.shouldEnterDlq());
    }
}
