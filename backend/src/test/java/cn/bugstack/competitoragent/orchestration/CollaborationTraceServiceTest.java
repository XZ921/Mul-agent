package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.model.entity.TaskWorkflowEvent;
import cn.bugstack.competitoragent.repository.TaskWorkflowEventRepository;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CollaborationTraceServiceTest {

    private final TaskWorkflowEventRepository repository = mock(TaskWorkflowEventRepository.class);
    private final CollaborationTraceService service = new CollaborationTraceService(repository, new ObjectMapper());

    @Test
    void shouldRecordPlanAndCheckpointEventsWithSourceUrls() {
        when(repository.save(any(TaskWorkflowEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
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

        TaskWorkflowEvent event = service.recordPlan(goal, plan, review, 31L, 1, "root");

        assertThat(event.getEventType()).isEqualTo(WorkflowEventType.COLLABORATION_PLAN_RECORDED);
        assertThat(event.getPayload()).contains("cp-task-88-v1").contains("STANDARD_COMPETITOR_ANALYSIS_V1");
        assertThat(event.getSourceUrls()).contains("https://www.notion.so");
    }
}
