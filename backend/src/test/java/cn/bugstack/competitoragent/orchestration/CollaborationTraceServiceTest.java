package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.model.entity.TaskWorkflowEvent;
import cn.bugstack.competitoragent.workflow.event.WorkflowEvent;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventOutboxService;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CollaborationTraceServiceTest {

    private final WorkflowEventOutboxService workflowEventOutboxService = mock(WorkflowEventOutboxService.class);
    private final CollaborationTraceService service = new CollaborationTraceService(workflowEventOutboxService);

    @Test
    void shouldRecordPlanAndCheckpointEventsWithSourceUrls() {
        when(workflowEventOutboxService.stage(any(WorkflowEvent.class))).thenAnswer(invocation -> {
            WorkflowEvent workflowEvent = invocation.getArgument(0);
            return TaskWorkflowEvent.builder()
                    .eventId(workflowEvent.getEventId())
                    .taskId(workflowEvent.getTaskId())
                    .nodeName(workflowEvent.getNodeName())
                    .planVersionId(workflowEvent.getPlanVersionId())
                    .branchKey(workflowEvent.getBranchKey())
                    .eventType(workflowEvent.getEventType())
                    .topic("task-workflow-events")
                    .tag(workflowEvent.getEventType() == WorkflowEventType.COLLABORATION_PLAN_RECORDED
                            ? "collaboration_plan_recorded"
                            : "collaboration_checkpoint_updated")
                    .payload(workflowEvent.getPayload().toString())
                    .sourceUrls(workflowEvent.getSourceUrls().toString())
                    .build();
        });
        CollaborationGoal goal = CollaborationGoal.builder()
                .goalId("cg-task-88")
                .taskId(88L)
                .subject("企业级 RAG 知识库竞品分析")
                .sourceUrls(List.of("https://www.notion.so"))
                .build()
                .normalized();
        CollaborationPlan plan = CollaborationPlan.builder()
                .planId("cp-task-88-v1")
                .goalId("cg-task-88")
                .taskId(88L)
                .planningMode("ORCHESTRATOR_FIRST")
                .sourceUrls(List.of("https://www.notion.so"))
                .build()
                .normalized();
        InitialPlanReview review = InitialPlanReview.builder()
                .reviewId("ipr-cp-task-88-v1")
                .planId("cp-task-88-v1")
                .allowed(true)
                .mappedWorkflowTemplate("STANDARD_COMPETITOR_ANALYSIS_V1")
                .sourceUrls(List.of("https://www.notion.so"))
                .build()
                .normalized();
        CollaborationCheckpoint checkpoint = CollaborationCheckpoint.builder()
                .checkpointId("cc-cp-task-88-v1-phase-1")
                .taskId(88L)
                .goalId("cg-task-88")
                .planId("cp-task-88-v1")
                .lastReviewId("ipr-cp-task-88-v1")
                .phase("PLAN_REVIEW")
                .sourceUrls(List.of("https://www.notion.so"))
                .build()
                .normalized();

        TaskWorkflowEvent event = service.recordPlan(goal, plan, review, 31L, 1, "root");
        TaskWorkflowEvent checkpointEvent = service.recordCheckpoint(checkpoint, 31L, "root");
        ArgumentCaptor<WorkflowEvent> workflowEventCaptor = ArgumentCaptor.forClass(WorkflowEvent.class);

        assertThat(event.getEventType()).isEqualTo(WorkflowEventType.COLLABORATION_PLAN_RECORDED);
        assertThat(event.getTopic()).isEqualTo("task-workflow-events");
        assertThat(event.getPayload()).contains("cp-task-88-v1").contains("STANDARD_COMPETITOR_ANALYSIS_V1");
        assertThat(event.getSourceUrls()).contains("https://www.notion.so");
        assertThat(checkpointEvent.getEventType()).isEqualTo(WorkflowEventType.COLLABORATION_CHECKPOINT_UPDATED);
        assertThat(checkpointEvent.getTopic()).isEqualTo("task-workflow-events");

        verify(workflowEventOutboxService, times(2)).stage(workflowEventCaptor.capture());
        assertThat(workflowEventCaptor.getAllValues())
                .extracting(WorkflowEvent::getEventType)
                .containsExactly(
                        WorkflowEventType.COLLABORATION_PLAN_RECORDED,
                        WorkflowEventType.COLLABORATION_CHECKPOINT_UPDATED
                );
        assertThat(workflowEventCaptor.getAllValues())
                .extracting(WorkflowEvent::getSourceUrls)
                .allSatisfy(sourceUrls -> assertThat(sourceUrls).contains("https://www.notion.so"));
    }
}
