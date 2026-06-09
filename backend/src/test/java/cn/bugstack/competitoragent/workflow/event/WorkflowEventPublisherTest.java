package cn.bugstack.competitoragent.workflow.event;

import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WorkflowEventPublisherTest {

    @Test
    void shouldStageUnifiedWorkflowEventForFailedNode() {
        WorkflowEventOutboxService outboxService = mock(WorkflowEventOutboxService.class);
        WorkflowEventPublisher publisher = new WorkflowEventPublisher(outboxService, new ObjectMapper());

        TaskNode node = TaskNode.builder()
                .taskId(18L)
                .nodeName("collect_sources_web")
                .displayName("collect_sources_web")
                .agentType(AgentType.COLLECTOR)
                .status(TaskNodeStatus.FAILED)
                .planVersionId(9L)
                .branchKey("root/review-2")
                .retryCount(2)
                .errorMessage("collector timeout")
                .build();

        publisher.publishNodeFailed(node, List.of("https://docs.example.com"));

        ArgumentCaptor<WorkflowEvent> eventCaptor = ArgumentCaptor.forClass(WorkflowEvent.class);
        verify(outboxService).stage(eventCaptor.capture());

        WorkflowEvent event = eventCaptor.getValue();
        assertThat(event.getEventType()).isEqualTo(WorkflowEventType.NODE_FAILED);
        assertThat(event.getTaskId()).isEqualTo(18L);
        assertThat(event.getNodeName()).isEqualTo("collect_sources_web");
        assertThat(event.getPlanVersionId()).isEqualTo(9L);
        assertThat(event.getBranchKey()).isEqualTo("root/review-2");
        assertThat(event.getSourceUrls()).containsExactly("https://docs.example.com");
        assertThat(event.getPayload()).containsEntry("status", "FAILED");
        assertThat(event.getPayload()).containsEntry("planVersionId", 9L);
        assertThat(event.getPayload()).containsEntry("branchKey", "root/review-2");
        assertThat(event.getPayload()).containsEntry("retryCount", 2);
        assertThat(event.getPayload()).containsEntry("errorMessage", "collector timeout");
    }
}
