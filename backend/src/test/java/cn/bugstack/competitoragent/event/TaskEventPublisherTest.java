package cn.bugstack.competitoragent.event;

import cn.bugstack.competitoragent.model.dto.AgentLogResponse;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeControlState;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.task.TaskProgressSnapshot;
import cn.bugstack.competitoragent.workflow.NodeFailureCategory;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskEventPublisherTest {

    @Test
    void shouldPublishStructuredTaskAndNodeEventsIntoRecentBuffer() {
        TaskSseHub sseHub = new TaskSseHub();
        TaskEventPublisher publisher = new TaskEventPublisher(sseHub);

        TaskProgressSnapshot snapshot = TaskProgressSnapshot.builder()
                .taskId(9L)
                .taskStatus("RUNNING")
                .currentStage("信息采集")
                .statusSummary("系统正在等待自动重试")
                .completedNodes(1)
                .totalNodes(6)
                .waitingRetryNodeCount(1)
                .waitingInterventionNodeCount(0)
                .compensatedNodeCount(0)
                .activeNodeNames(List.of("collect_sources_01_01"))
                .updatedAt(LocalDateTime.of(2026, 6, 3, 18, 30, 0))
                .build();
        TaskNode node = TaskNode.builder()
                .taskId(9L)
                .nodeName("collect_sources_01_01")
                .displayName("Notion AI - DOCS采集")
                .agentType(AgentType.COLLECTOR)
                .status(TaskNodeStatus.RUNNING)
                .controlState(TaskNodeControlState.NONE)
                .failureCategory(NodeFailureCategory.TRANSIENT_INFRASTRUCTURE)
                .retryCount(0)
                .executionOrder(0)
                .lastAttemptAt(LocalDateTime.of(2026, 6, 3, 18, 29, 30))
                .nextRetryAt(LocalDateTime.of(2026, 6, 3, 18, 30, 30))
                .build();
        AgentLogResponse logResponse = AgentLogResponse.builder()
                .taskId(9L)
                .agentType(AgentType.COLLECTOR)
                .agentName("CollectorAgent")
                .status(TaskNodeStatus.SUCCESS)
                .reasoningSummary("已完成候选来源选择")
                .outputData("{\"sourceUrls\":[\"https://www.notion.so/product/ai\"]}")
                .build();

        TaskStreamEvent snapshotEvent = publisher.publishTaskSnapshot(snapshot);
        TaskStreamEvent nodeEvent = publisher.publishNodeStatusEvent(9L, node, "NODE_STARTED");
        TaskStreamEvent logEvent = publisher.publishAgentLogEvent(9L, "collect_sources_01_01", logResponse);
        TaskStreamEvent diagnosisEvent = publisher.publishDiagnosisEvent(
                9L,
                "quality_check",
                Map.of("summary", "证据仍需补强", "requiresHumanIntervention", true));

        List<TaskStreamEvent> recentEvents = sseHub.getRecentEvents(9L);
        assertEquals(4, recentEvents.size());
        assertEquals(TaskEventType.TASK_SNAPSHOT, snapshotEvent.getEventType());
        assertEquals(TaskEventType.NODE_STATUS, nodeEvent.getEventType());
        assertEquals(TaskEventType.AGENT_OUTPUT, logEvent.getEventType());
        assertEquals(TaskEventType.DIAGNOSIS, diagnosisEvent.getEventType());
        assertTrue(snapshotEvent.getCursor().startsWith("9-"));
        assertEquals("collect_sources_01_01", nodeEvent.getNodeName());
        assertEquals("RUNNING", nodeEvent.getPayload().get("status"));
        assertEquals("系统正在等待自动重试", snapshotEvent.getPayload().get("statusSummary"));
        assertEquals(1, snapshotEvent.getPayload().get("waitingRetryNodeCount"));
        assertEquals("TRANSIENT_INFRASTRUCTURE", nodeEvent.getPayload().get("failureCategory"));
        assertEquals("collect_sources_01_01", logEvent.getNodeName());
        assertEquals("CollectorAgent", logEvent.getPayload().get("agentName"));
        assertEquals(Boolean.TRUE, diagnosisEvent.getPayload().get("requiresHumanIntervention"));
    }

    @Test
    void shouldAssignMonotonicCursorPerTaskWhenPublishingEvents() {
        TaskSseHub sseHub = new TaskSseHub();
        TaskEventPublisher publisher = new TaskEventPublisher(sseHub);

        TaskStreamEvent first = publisher.publishTaskStatusEvent(12L, AnalysisTaskStatus.PENDING, "等待调度", null);
        TaskStreamEvent second = publisher.publishTaskStatusEvent(12L, AnalysisTaskStatus.RUNNING, "执行中", null);

        assertNotNull(first.getCursor());
        assertNotNull(second.getCursor());
        assertFalse(first.getCursor().equals(second.getCursor()));
        assertTrue(second.getCursor().compareTo(first.getCursor()) > 0);
    }
}
