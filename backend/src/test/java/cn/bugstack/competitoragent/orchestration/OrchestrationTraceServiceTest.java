package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventPublisher;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrchestrationTraceServiceTest {

    @Test
    void shouldRecordWholeRuntimeBatchAsSingleV2Event() {
        WorkflowEventPublisher publisher = mock(WorkflowEventPublisher.class);
        OrchestrationTraceService service = new OrchestrationTraceService(
                publisher,
                mock(OrchestrationRuntimeStateService.class),
                DecisionPolicyRuleSet.builder().build().normalized());
        TaskNode triggerNode = TaskNode.builder()
                .taskId(801L)
                .nodeName("quality_check_final")
                .agentType(AgentType.REVIEWER)
                .planVersionId(31L)
                .branchKey("root/review")
                .build();
        OrchestrationRuntimeDecisionBatch batch = OrchestrationDecisionAuditTestFixtures.fallbackBatch();

        service.recordDecisionBatch(801L, triggerNode, batch);

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(publisher).publishOrchestrationEvent(
                eq(801L),
                eq("quality_check_final"),
                eq(31L),
                eq("root/review"),
                eq(WorkflowEventType.ORCHESTRATION_DECISION_RECORDED),
                payloadCaptor.capture(),
                eq(batch.sourceUrls()));
        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload).containsEntry("traceSchemaVersion", "ORCHESTRATION_TRACE_V2");
        assertThat(payload.get("decision")).isInstanceOf(OrchestrationDecision.class);
        assertThat(((OrchestrationDecision) payload.get("decision")).getDecisionId())
                .isEqualTo("od-801-rule-fallback");
        assertThat(payload.get("runtimeStatus"))
                .isEqualTo(OrchestrationRuntimeDecision.CONFIRMATION_REQUIRED);
        assertThat(payload.get("fallbackAttempt")).isEqualTo(true);
        assertThat(payload.get("mutation")).isInstanceOf(OrchestrationMutationTrace.class);
        assertThat(payload.get("audit")).isInstanceOf(OrchestrationDecisionAuditTrace.class);
        assertThat(((OrchestrationDecisionAuditTrace) payload.get("audit")).attempts()).hasSize(2);
    }

    @Test
    void shouldRecordShadowSkippedBatchWithoutRepresentativeDecision() {
        WorkflowEventPublisher publisher = mock(WorkflowEventPublisher.class);
        OrchestrationTraceService service = new OrchestrationTraceService(
                publisher,
                mock(OrchestrationRuntimeStateService.class),
                DecisionPolicyRuleSet.builder().build().normalized());
        TaskNode triggerNode = TaskNode.builder()
                .taskId(801L)
                .nodeName("quality_check_final")
                .planVersionId(32L)
                .branchKey("root")
                .build();

        service.recordDecisionBatch(
                801L,
                triggerNode,
                OrchestrationDecisionAuditTestFixtures.shadowSkippedBatch());

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(publisher).publishOrchestrationEvent(
                eq(801L),
                eq("quality_check_final"),
                eq(32L),
                eq("root"),
                eq(WorkflowEventType.ORCHESTRATION_DECISION_RECORDED),
                payloadCaptor.capture(),
                eq(List.of(OrchestrationDecisionAuditTestFixtures.SHADOW_URL)));
        assertThat(payloadCaptor.getValue())
                .containsEntry("decision", null)
                .containsEntry("policyResult", null)
                .containsEntry("mutation", null)
                .containsEntry("runtimeStatus", null);
        OrchestrationDecisionAuditTrace audit =
                (OrchestrationDecisionAuditTrace) payloadCaptor.getValue().get("audit");
        assertThat(audit.shadowExecution().skippedReason())
                .isEqualTo("SHADOW_BUDGET_EXHAUSTED");
    }

    @Test
    void shouldRejectNullBatchWithoutPublishingEvent() {
        WorkflowEventPublisher publisher = mock(WorkflowEventPublisher.class);
        OrchestrationTraceService service = new OrchestrationTraceService(
                publisher,
                mock(OrchestrationRuntimeStateService.class),
                DecisionPolicyRuleSet.builder().build().normalized());

        assertThatThrownBy(() -> service.recordDecisionBatch(801L, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(publisher);
    }

    @Test
    void shouldTraceOriginalInvalidLlmPairAndStablePolicyReason() {
        WorkflowEventPublisher publisher = mock(WorkflowEventPublisher.class);
        OrchestrationRuntimeStateService runtimeStateService = mock(OrchestrationRuntimeStateService.class);
        OrchestrationTraceService service = new OrchestrationTraceService(
                publisher,
                runtimeStateService,
                DecisionPolicyRuleSet.builder().build().normalized());
        TaskNode triggerNode = TaskNode.builder()
                .taskId(60L)
                .nodeName("quality_check_final")
                .agentType(AgentType.REVIEWER)
                .planVersionId(10L)
                .branchKey("root")
                .build();
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-trace-invalid-llm")
                .taskId(60L)
                .decisionOrigin(OrchestrationDecisionOrigin.LLM_PRIMARY)
                .triggerNodeName("quality_check_final")
                .decisionType("REWRITE_ONLY")
                .actionType("SUPPLEMENT_EVIDENCE")
                .targetNode("rewrite_report")
                .affectedScope("CURRENT_NODE_ONLY")
                .sourceUrls(List.of("https://example.com/evidence"))
                .evidenceState(EvidenceState.FULL_SOURCE)
                .build();
        DecisionPolicyResult policyResult = new DecisionPolicyService(new OrchestrationDecisionActionMatrix())
                .evaluate(decision, DecisionPolicyRuleSet.builder().build(), 0, "RUNNING", "SUCCESS");
        DynamicPlanMutation mutation = new DecisionExecutorAdapter()
                .toMutation(decision, policyResult, 10L, 2);

        service.recordDecision(60L, triggerNode, decision, policyResult, mutation);

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(publisher).publishOrchestrationEvent(
                eq(60L),
                eq("quality_check_final"),
                eq(10L),
                eq("root"),
                eq(WorkflowEventType.ORCHESTRATION_DECISION_RECORDED),
                payloadCaptor.capture(),
                eq(List.of("https://example.com/evidence")));
        OrchestrationDecision recordedDecision =
                (OrchestrationDecision) payloadCaptor.getValue().get("decision");
        DecisionPolicyResult recordedPolicyResult =
                (DecisionPolicyResult) payloadCaptor.getValue().get("policyResult");
        assertThat(recordedDecision.getDecisionOrigin()).isEqualTo(OrchestrationDecisionOrigin.LLM_PRIMARY);
        assertThat(recordedDecision.getDecisionType()).isEqualTo("REWRITE_ONLY");
        assertThat(recordedDecision.getActionType()).isEqualTo("SUPPLEMENT_EVIDENCE");
        assertThat(recordedPolicyResult.getDecisionContract()).isEqualTo("LLM_ACTION_MATRIX");
        assertThat(recordedPolicyResult.isAllowed()).isFalse();
        assertThat(recordedPolicyResult.getBlockedReasons())
                .contains("INVALID_DECISION_ACTION_PAIR: REWRITE_ONLY 不允许搭配 SUPPLEMENT_EVIDENCE");
        assertThat(mutation.getMutationType()).isEqualTo("NO_MUTATION");
    }

    @Test
    void shouldRecordDecisionAndCheckpointWithIncrementalDecisionCount() {
        WorkflowEventPublisher publisher = mock(WorkflowEventPublisher.class);
        OrchestrationRuntimeStateService runtimeStateService = mock(OrchestrationRuntimeStateService.class);
        OrchestrationTraceService service = new OrchestrationTraceService(
                publisher,
                runtimeStateService,
                DecisionPolicyRuleSet.builder().maxAutoDecisions(2).build().normalized());
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
                .decisionOrigin(OrchestrationDecisionOrigin.RULE_FALLBACK)
                .decisionMetadata(OrchestratorDecisionMetadata.builder()
                        .modelName("deepseek-chat")
                        .fallbackReason("LLM_TIMEOUT")
                        .parseRetryCount(-1)
                        .build())
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build();
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
        OrchestrationRuntimeState currentState = new OrchestrationRuntimeState(
                1, Map.of(), 8L, 2,
                OrchestrationRuntimeState.CheckpointStateStatus.RESTORED, List.of());
        OrchestrationRuntimeState nextState = new OrchestrationRuntimeState(
                2, Map.of(OrchestrationRuntimeState.UNSCOPED_SECTION, 1), 8L, 2,
                OrchestrationRuntimeState.CheckpointStateStatus.RESTORED, List.of());
        when(runtimeStateService.load(50L)).thenReturn(currentState);
        when(runtimeStateService.afterSuccessfulBranch(currentState, decision)).thenReturn(nextState);

        service.recordDecision(50L, triggerNode, decision, policyResult, mutation);
        service.recordCheckpoint(
                50L,
                triggerNode,
                TaskPlan.builder().id(9L).planVersion(2).branchKey("root/review-2").build(),
                decision,
                mutation);

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
                .containsEntry("mutation", mutation);
        OrchestrationDecision recordedDecision = (OrchestrationDecision) decisionPayloadCaptor.getValue().get("decision");
        DecisionPolicyResult recordedPolicyResult =
                (DecisionPolicyResult) decisionPayloadCaptor.getValue().get("policyResult");
        assertThat(recordedDecision.getDecisionOrigin()).isEqualTo(OrchestrationDecisionOrigin.RULE_FALLBACK);
        assertThat(recordedDecision.getDecisionMetadata().isFallbackUsed()).isTrue();
        assertThat(recordedDecision.getDecisionMetadata().getFallbackReason()).isEqualTo("LLM_TIMEOUT");
        assertThat(recordedDecision.getDecisionMetadata().getParseRetryCount()).isZero();
        assertThat(recordedPolicyResult.getDecisionOrigin()).isEqualTo(OrchestrationDecisionOrigin.RULE_FALLBACK);
        assertThat(recordedPolicyResult.getDecisionContract()).isEqualTo("LEGACY_RULE_SET");

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
        assertThat(checkpoint.getDynamicBranchCountsBySection())
                .containsEntry(OrchestrationRuntimeState.UNSCOPED_SECTION, 1);
        assertThat(checkpoint.getPendingActions()).containsExactly("WAITING_FOR_SUPPLEMENT_RESULT");
        assertThat(checkpoint.getResumeAfterNodeName()).isEqualTo("collect_revision_evidence_v2_1");
    }
}
