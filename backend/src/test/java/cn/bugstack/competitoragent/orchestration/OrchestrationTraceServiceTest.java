package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.model.entity.TaskWorkflowEvent;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.repository.TaskWorkflowEventRepository;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventPublisher;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrchestrationTraceServiceTest {

    @Test
    void shouldRecordDecisionAndCheckpointWithIncrementalDecisionCount() {
        WorkflowEventPublisher publisher = mock(WorkflowEventPublisher.class);
        TaskWorkflowEventRepository repository = mock(TaskWorkflowEventRepository.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        OrchestrationTraceService service = new OrchestrationTraceService(publisher, repository, objectMapper);
        TaskNode triggerNode = TaskNode.builder()
                .taskId(50L)
                .nodeName("quality_check_final")
                .agentType(AgentType.REVIEWER)
                .planVersionId(8L)
                .branchKey("root")
                .build();
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-001")
                .taskId(50L)
                .triggerNodeName("quality_check_final")
                .decisionType("APPEND_DYNAMIC_BRANCH")
                .actionType("SUPPLEMENT_EVIDENCE")
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build()
                .normalized();
        DecisionPolicyResult policyResult = DecisionPolicyResult.builder()
                .decisionId("od-001")
                .allowed(true)
                .normalizedAction("CREATE_SUPPLEMENT_BRANCH")
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build();
        DynamicPlanMutation mutation = DynamicPlanMutation.builder()
                .mutationId("dpm-001")
                .decisionId("od-001")
                .mutationType("APPEND_NODES")
                .expectedResumeNodeName("collect_revision_evidence_v2_1")
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build();
        TaskWorkflowEvent previousCheckpointEvent = TaskWorkflowEvent.builder()
                .taskId(50L)
                .branchKey("root/review-2")
                .eventType(WorkflowEventType.ORCHESTRATION_CHECKPOINT_UPDATED)
                .payload("{\"checkpoint\":{\"decisionCount\":1}}")
                .sourceUrls("[]")
                .build();
        when(repository.findFirstByTaskIdAndEventTypeOrderByCreatedAtDesc(
                50L,
                WorkflowEventType.ORCHESTRATION_CHECKPOINT_UPDATED))
                .thenReturn(Optional.of(previousCheckpointEvent));

        service.recordDecision(50L, triggerNode, decision, policyResult, mutation);
        service.recordCheckpoint(
                50L,
                triggerNode,
                TaskPlan.builder().id(9L).planVersion(2).branchKey("root/review-2").build(),
                decision,
                mutation,
                DecisionPolicyRuleSet.builder().maxAutoDecisions(2).build());

        ArgumentCaptor<Map<String, Object>> decisionPayloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(publisher).publishOrchestrationEvent(
                eq(50L),
                eq("quality_check_final"),
                eq(8L),
                eq("root"),
                eq(WorkflowEventType.ORCHESTRATION_DECISION_RECORDED),
                decisionPayloadCaptor.capture(),
                eq(List.of()));
        assertThat(decisionPayloadCaptor.getValue())
                .containsEntry("summary", "Orchestrator 已生成运行期编排决策")
                .containsEntry("decision", decision)
                .containsEntry("policyResult", policyResult)
                .containsEntry("mutation", mutation);

        ArgumentCaptor<Map<String, Object>> checkpointPayloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(publisher).publishOrchestrationEvent(
                eq(50L),
                eq("quality_check_final"),
                eq(9L),
                eq("root/review-2"),
                eq(WorkflowEventType.ORCHESTRATION_CHECKPOINT_UPDATED),
                checkpointPayloadCaptor.capture(),
                eq(List.of()));
        OrchestratorCheckpoint checkpoint = (OrchestratorCheckpoint) checkpointPayloadCaptor.getValue().get("checkpoint");
        assertThat(checkpoint.getDecisionCount()).isEqualTo(2);
        assertThat(checkpoint.getMaxAutoDecisions()).isEqualTo(2);
        assertThat(checkpoint.getPendingActions()).containsExactly("WAITING_FOR_SUPPLEMENT_RESULT");
        assertThat(checkpoint.getResumeAfterNodeName()).isEqualTo("collect_revision_evidence_v2_1");
    }
}
