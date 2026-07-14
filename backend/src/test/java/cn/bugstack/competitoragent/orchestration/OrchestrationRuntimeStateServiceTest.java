package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.model.entity.TaskWorkflowEvent;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import cn.bugstack.competitoragent.repository.TaskWorkflowEventRepository;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrchestrationRuntimeStateServiceTest {

    private TaskWorkflowEventRepository eventRepository;
    private TaskPlanRepository planRepository;
    private OrchestrationRuntimeStateService service;

    @BeforeEach
    void setUp() {
        eventRepository = mock(TaskWorkflowEventRepository.class);
        planRepository = mock(TaskPlanRepository.class);
        service = new OrchestrationRuntimeStateService(
                eventRepository,
                planRepository,
                new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void shouldLoadAbsentCheckpointWithActivePlan() {
        when(eventRepository.findFirstByTaskIdAndEventTypeOrderByCreatedAtDesc(
                1L, WorkflowEventType.ORCHESTRATION_CHECKPOINT_UPDATED))
                .thenReturn(Optional.empty());
        when(planRepository.findFirstByTaskIdAndActiveTrueOrderByPlanVersionDesc(1L))
                .thenReturn(Optional.of(TaskPlan.builder().id(11L).planVersion(1).build()));

        OrchestrationRuntimeState state = service.load(1L);

        assertThat(state.checkpointStateStatus())
                .isEqualTo(OrchestrationRuntimeState.CheckpointStateStatus.ABSENT);
        assertThat(state.currentDecisionCount()).isZero();
        assertThat(state.dynamicBranchCountsBySection()).isEmpty();
        assertThat(state.currentPlanVersionId()).isEqualTo(11L);
        assertThat(state.nextPlanVersion()).isEqualTo(2);
    }

    @Test
    void shouldRestoreCurrentAndLegacyCheckpointPayloads() {
        when(planRepository.findFirstByTaskIdAndActiveTrueOrderByPlanVersionDesc(2L))
                .thenReturn(Optional.of(TaskPlan.builder().id(12L).planVersion(3).build()));
        when(eventRepository.findFirstByTaskIdAndEventTypeOrderByCreatedAtDesc(
                2L, WorkflowEventType.ORCHESTRATION_CHECKPOINT_UPDATED))
                .thenReturn(Optional.of(checkpointEvent("""
                        {"checkpoint":{"decisionCount":1,
                        "dynamicBranchCountsBySection":{" Pricing ":1},
                        "sourceUrls":["https://example.com/a","https://example.com/a"]}}
                        """)));

        OrchestrationRuntimeState current = service.load(2L);

        assertThat(current.checkpointStateStatus())
                .isEqualTo(OrchestrationRuntimeState.CheckpointStateStatus.RESTORED);
        assertThat(current.currentDecisionCount()).isEqualTo(1);
        assertThat(current.dynamicBranchCountsBySection()).containsEntry("pricing", 1);
        assertThat(current.sourceUrls()).containsExactly("https://example.com/a");
        assertThat(current.nextPlanVersion()).isEqualTo(4);

        when(eventRepository.findFirstByTaskIdAndEventTypeOrderByCreatedAtDesc(
                2L, WorkflowEventType.ORCHESTRATION_CHECKPOINT_UPDATED))
                .thenReturn(Optional.of(checkpointEvent("""
                        {"checkpoint":{"decisionCount":2,
                        "sourceUrls":["https://example.com/legacy"]}}
                        """)));

        OrchestrationRuntimeState legacy = service.load(2L);

        assertThat(legacy.checkpointStateStatus())
                .isEqualTo(OrchestrationRuntimeState.CheckpointStateStatus.RESTORED);
        assertThat(legacy.currentDecisionCount()).isEqualTo(2);
        assertThat(legacy.dynamicBranchCountsBySection()).isEmpty();
        assertThat(legacy.sourceUrls()).containsExactly("https://example.com/legacy");
    }

    @Test
    void shouldMarkNegativeMalformedAndRepositoryFailureUnreadable() {
        when(planRepository.findFirstByTaskIdAndActiveTrueOrderByPlanVersionDesc(3L))
                .thenReturn(Optional.empty());
        when(eventRepository.findFirstByTaskIdAndEventTypeOrderByCreatedAtDesc(
                3L, WorkflowEventType.ORCHESTRATION_CHECKPOINT_UPDATED))
                .thenReturn(Optional.of(checkpointEvent("{\"checkpoint\":{\"decisionCount\":-1}}")));

        OrchestrationRuntimeState negative = service.load(3L);

        assertThat(negative.checkpointStateStatus())
                .isEqualTo(OrchestrationRuntimeState.CheckpointStateStatus.UNREADABLE);
        assertThat(negative.currentDecisionCount()).isZero();
        assertThat(negative.currentPlanVersionId()).isNull();
        assertThat(negative.nextPlanVersion()).isEqualTo(1);

        when(eventRepository.findFirstByTaskIdAndEventTypeOrderByCreatedAtDesc(
                3L, WorkflowEventType.ORCHESTRATION_CHECKPOINT_UPDATED))
                .thenReturn(Optional.of(checkpointEvent("{malformed")));
        OrchestrationRuntimeState malformed = service.load(3L);
        assertThat(malformed.checkpointStateStatus())
                .isEqualTo(OrchestrationRuntimeState.CheckpointStateStatus.UNREADABLE);
        assertThat(malformed.dynamicBranchCountsBySection()).isEmpty();

        when(eventRepository.findFirstByTaskIdAndEventTypeOrderByCreatedAtDesc(
                3L, WorkflowEventType.ORCHESTRATION_CHECKPOINT_UPDATED))
                .thenThrow(new IllegalStateException("repository unavailable"));
        OrchestrationRuntimeState unavailable = service.load(3L);
        assertThat(unavailable.checkpointStateStatus())
                .isEqualTo(OrchestrationRuntimeState.CheckpointStateStatus.UNREADABLE);
    }

    @Test
    void shouldIncrementOnlySuccessfulBranchUsingStableSectionKey() {
        OrchestrationRuntimeState current = new OrchestrationRuntimeState(
                1,
                Map.of("pricing", 1),
                12L,
                4,
                OrchestrationRuntimeState.CheckpointStateStatus.RESTORED,
                List.of("https://example.com/checkpoint"));
        OrchestrationDecision scoped = OrchestrationDecision.builder()
                .decisionId("od-scoped")
                .targetSection(" Pricing ")
                .sourceUrls(List.of("https://example.com/decision"))
                .build();

        OrchestrationRuntimeState next = service.afterSuccessfulBranch(current, scoped);

        assertThat(next.currentDecisionCount()).isEqualTo(2);
        assertThat(next.dynamicBranchCountsBySection()).containsEntry("pricing", 2);
        assertThat(next.sourceUrls()).containsExactly(
                "https://example.com/checkpoint", "https://example.com/decision");

        OrchestrationDecision unscoped = OrchestrationDecision.builder()
                .decisionId("od-unscoped")
                .targetSection(" ")
                .build();
        OrchestrationRuntimeState unscopedNext = service.afterSuccessfulBranch(next, unscoped);
        assertThat(unscopedNext.dynamicBranchCountsBySection())
                .containsEntry(OrchestrationRuntimeState.UNSCOPED_SECTION, 1);
    }

    private TaskWorkflowEvent checkpointEvent(String payload) {
        return TaskWorkflowEvent.builder()
                .taskId(1L)
                .eventType(WorkflowEventType.ORCHESTRATION_CHECKPOINT_UPDATED)
                .payload(payload)
                .build();
    }
}
