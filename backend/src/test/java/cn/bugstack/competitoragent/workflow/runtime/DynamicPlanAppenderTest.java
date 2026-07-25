package cn.bugstack.competitoragent.workflow.runtime;

import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.orchestration.DecisionPolicyResult;
import cn.bugstack.competitoragent.orchestration.DecisionPolicyService;
import cn.bugstack.competitoragent.orchestration.DecisionExecutorAdapter;
import cn.bugstack.competitoragent.orchestration.DynamicPlanMutation;
import cn.bugstack.competitoragent.orchestration.EvidenceState;
import cn.bugstack.competitoragent.orchestration.OrchestrationDecision;
import cn.bugstack.competitoragent.orchestration.OrchestrationDecisionActionMatrix;
import cn.bugstack.competitoragent.orchestration.OrchestrationDecisionOutcome;
import cn.bugstack.competitoragent.orchestration.OrchestrationDecisionOrigin;
import cn.bugstack.competitoragent.orchestration.OrchestrationRuntimeDecision;
import cn.bugstack.competitoragent.orchestration.OrchestrationRuntimeDecisionBatch;
import cn.bugstack.competitoragent.orchestration.OrchestrationRuntimeDecisionService;
import cn.bugstack.competitoragent.orchestration.OrchestrationRuntimeState;
import cn.bugstack.competitoragent.orchestration.OrchestrationShadowExecution;
import cn.bugstack.competitoragent.orchestration.OrchestratorDecisionMode;
import cn.bugstack.competitoragent.orchestration.OrchestrationTraceService;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import cn.bugstack.competitoragent.workflow.DynamicTaskGraphService;
import cn.bugstack.competitoragent.workflow.WorkflowPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamicPlanAppenderTest {

    private final AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
    private final TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
    private final DynamicTaskGraphService dynamicTaskGraphService = mock(DynamicTaskGraphService.class);
    private final TaskPlanRepository taskPlanRepository = mock(TaskPlanRepository.class);
    private final OrchestrationRuntimeDecisionService runtimeDecisionService =
            mock(OrchestrationRuntimeDecisionService.class);
    private final OrchestrationTraceService orchestrationTraceService = mock(OrchestrationTraceService.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private final DynamicPlanAppender appender = new DynamicPlanAppender(
            taskRepository,
            nodeRepository,
            dynamicTaskGraphService,
            taskPlanRepository,
            objectMapper,
            runtimeDecisionService,
            orchestrationTraceService
    );

    @Test
    void shouldSendInitialReviewThroughUnifiedRuntimeDecisionPipeline() throws Exception {
        TaskNode initialReview = TaskNode.builder()
                .taskId(60L)
                .nodeName("quality_check")
                .agentType(AgentType.REVIEWER)
                .status(TaskNodeStatus.SUCCESS)
                .planVersionId(20L)
                .branchKey("root")
                .outputData("""
                        {"reviewStage":"initial","passed":false,
                        "requiresHumanIntervention":false,
                        "diagnoses":[{"type":"missing_evidence","level":"MAJOR"}]}
                        """)
                .build();
        AnalysisTask task = AnalysisTask.builder().id(60L).status(AnalysisTaskStatus.RUNNING)
                .currentPlanVersionId(20L).currentPlanVersion(1).build();
        TaskPlan parentPlan = TaskPlan.builder().id(20L).taskId(60L).planVersion(1).branchKey("root")
                .active(true).planSnapshot(objectMapper.writeValueAsString(WorkflowPlan.builder()
                        .planVersionId(20L).planVersion(1).branchKey("root").nodes(List.of()).build())).build();
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-60-quality_check-review-cycle").taskId(60L)
                .triggerNodeName("quality_check").decisionOrigin(OrchestrationDecisionOrigin.RULE_ONLY)
                .decisionType("WAIT_FOR_HUMAN").actionType("MANUAL_REVIEW")
                .targetNode("quality_check").affectedScope("CURRENT_NODE_ONLY")
                .reason("初审缺少完整结构化诊断").requiresHumanIntervention(true)
                .sourceUrls(List.of()).evidenceState(EvidenceState.MISSING_SOURCE).build().normalized();
        DecisionPolicyResult policy = DecisionPolicyResult.builder().decisionId(decision.getDecisionId())
                .allowed(true).requiresConfirmation(true).normalizedAction("MANUAL_ONLY").sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE).build();
        DynamicPlanMutation mutation = new DecisionExecutorAdapter(objectMapper)
                .toMutation(decision, policy, 20L, 2);
        OrchestrationRuntimeDecision waiting = new OrchestrationRuntimeDecision(decision, policy, mutation,
                false, OrchestrationRuntimeDecision.CONFIRMATION_REQUIRED, List.of());
        when(taskRepository.findById(60L)).thenReturn(Optional.of(task));
        when(taskPlanRepository.findById(20L)).thenReturn(Optional.of(parentPlan));
        when(runtimeDecisionService.decide(any(), eq(AnalysisTaskStatus.RUNNING.name()),
                eq(TaskNodeStatus.SUCCESS.name())))
                .thenReturn(batch(List.of(waiting), List.of(waiting), 20L, 2));

        boolean appended = appender.maybeAppendDynamicPlan(
                60L,
                new ArrayList<>(List.of(initialReview)),
                new LinkedHashMap<>(Map.of(initialReview.getNodeName(), initialReview)),
                initialReview);

        assertThat(appended).isFalse();
        verify(runtimeDecisionService).decide(argThat(context -> "initial".equals(context.getReviewStage())),
                eq(AnalysisTaskStatus.RUNNING.name()), eq(TaskNodeStatus.SUCCESS.name()));
        verify(orchestrationTraceService).recordDecisionBatch(eq(60L), eq(initialReview), any());
        verify(dynamicTaskGraphService, never()).createDynamicPlan(
                any(), any(), any(DynamicPlanMutation.class), any());
    }

    @Test
    void shouldNotAppendNodesForInvalidLlmPairBlockedByPolicy() throws Exception {
        AnalysisTask task = AnalysisTask.builder()
                .id(51L)
                .status(AnalysisTaskStatus.STOPPED)
                .currentPlanVersionId(10L)
                .currentPlanVersion(1)
                .build();
        TaskNode completedNode = TaskNode.builder()
                .taskId(51L)
                .nodeName("quality_check_final")
                .agentType(AgentType.REVIEWER)
                .status(TaskNodeStatus.SUCCESS)
                .planVersionId(10L)
                .branchKey("root")
                .outputData("""
                        {
                          "reviewStage":"final",
                          "passed":false,
                          "requiresHumanIntervention":false,
                          "summary":"存在非法 LLM 动作组合"
                        }
                        """)
                .build();
        TaskPlan parentPlan = TaskPlan.builder()
                .id(10L)
                .taskId(51L)
                .planVersion(1)
                .branchKey("root")
                .active(true)
                .planSnapshot(objectMapper.writeValueAsString(WorkflowPlan.builder()
                        .planVersionId(10L)
                        .planVersion(1)
                        .branchKey("root")
                        .nodes(List.of())
                        .build()))
                .build();
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-invalid-llm-appender")
                .taskId(51L)
                .decisionOrigin(OrchestrationDecisionOrigin.LLM_PRIMARY)
                .triggerNodeName("quality_check_final")
                .decisionType("REWRITE_ONLY")
                .actionType("SUPPLEMENT_EVIDENCE")
                .targetNode("rewrite_report")
                .affectedScope("CURRENT_NODE_ONLY")
                .sourceUrls(List.of("https://example.com/evidence"))
                .evidenceState(EvidenceState.FULL_SOURCE)
                .build();
        when(taskRepository.findById(51L)).thenReturn(Optional.of(task));
        when(taskPlanRepository.findById(10L)).thenReturn(Optional.of(parentPlan));
        DecisionPolicyResult policy = new DecisionPolicyService(new OrchestrationDecisionActionMatrix())
                .evaluate(decision, cn.bugstack.competitoragent.orchestration.DecisionPolicyRuleSet.builder().build(),
                        0, AnalysisTaskStatus.STOPPED.name(), TaskNodeStatus.SUCCESS.name());
        DynamicPlanMutation mutation = new DecisionExecutorAdapter(objectMapper)
                .toMutation(decision, policy, 10L, 2);
        OrchestrationRuntimeDecision rejected = new OrchestrationRuntimeDecision(
                decision, policy, mutation, false,
                OrchestrationRuntimeDecision.POLICY_REJECTED, List.of());
        when(runtimeDecisionService.decide(any(), eq(AnalysisTaskStatus.STOPPED.name()),
                eq(TaskNodeStatus.SUCCESS.name())))
                .thenReturn(batch(List.of(rejected), List.of(rejected), 10L, 2));

        boolean appended = appender.maybeAppendDynamicPlan(
                51L,
                new ArrayList<>(List.of(completedNode)),
                new LinkedHashMap<>(Map.of(completedNode.getNodeName(), completedNode)),
                completedNode);

        assertThat(appended).isFalse();
        verify(orchestrationTraceService).recordDecisionBatch(
                eq(51L),
                eq(completedNode),
                argThat(recordedBatch -> recordedBatch.attempts().size() == 1
                        && "od-invalid-llm-appender".equals(
                        recordedBatch.attempts().get(0).decision().getDecisionId())
                        && !recordedBatch.attempts().get(0).policyResult().isAllowed()
                        && "NO_MUTATION".equals(
                        recordedBatch.attempts().get(0).mutation().getMutationType())));
        verify(orchestrationTraceService, never()).recordDecision(any(), any(), any(), any(), any());
        verify(dynamicTaskGraphService, never()).createDynamicPlan(
                any(), any(), any(DynamicPlanMutation.class), any());
        verify(nodeRepository, never()).saveAll(any());
    }

    @Test
    void shouldAppendDynamicPlanThroughOrchestratorDecisionPipeline() throws Exception {
        AnalysisTask task = AnalysisTask.builder()
                .id(50L)
                .status(AnalysisTaskStatus.STOPPED)
                .currentPlanVersionId(8L)
                .currentPlanVersion(1)
                .build();
        TaskNode completedNode = TaskNode.builder()
                .taskId(50L)
                .nodeName("quality_check_final")
                .displayName("质量终审")
                .agentType(AgentType.REVIEWER)
                .status(TaskNodeStatus.SUCCESS)
                .planVersionId(8L)
                .branchKey("root")
                .outputData("""
                        {
                          "reviewStage":"final",
                          "passed":false,
                          "requiresHumanIntervention":false,
                          "summary":"缺少官网定价证据",
                          "sourceUrls":["https://www.notion.so/pricing"],
                          "revisionDirectives":[
                            {
                              "category":"SEARCH_QUALITY",
                              "actionType":"SUPPLEMENT_EVIDENCE",
                              "summary":"补充官网定价证据",
                              "searchQueries":["Notion AI pricing official"],
                              "sourceUrls":["https://www.notion.so/pricing"]
                            }
                          ]
                        }
                        """)
                .build();
        TaskPlan parentPlan = TaskPlan.builder()
                .id(8L)
                .taskId(50L)
                .planVersion(1)
                .branchKey("root")
                .active(true)
                .planSnapshot(objectMapper.writeValueAsString(WorkflowPlan.builder()
                        .planVersionId(8L)
                        .planVersion(1)
                        .branchKey("root")
                        .nodes(List.of(WorkflowPlan.WorkflowPlanNode.builder()
                                .nodeName("quality_check_final")
                                .displayName("质量终审")
                                .agentType(AgentType.REVIEWER.name())
                                .dependsOn(List.of("rewrite_report"))
                                .executionOrder(6)
                                .branchKey("root")
                                .build()))
                        .build()))
                .build();
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-001")
                .taskId(50L)
                .triggerNodeName("quality_check_final")
                .decisionType("APPEND_DYNAMIC_BRANCH")
                .actionType("SUPPLEMENT_EVIDENCE")
                .targetNode("collect_sources")
                .affectedScope("CURRENT_SECTION_ONLY")
                .priority("HIGH")
                .confidence(0.92d)
                .decisionOrigin(OrchestrationDecisionOrigin.RULE_FALLBACK)
                .inputRefs(Map.of("qualityDiagnosisIds", List.of("qd-quality_check_final-1")))
                .suggestedQueries(List.of("Notion AI pricing official"))
                .sourceUrls(List.of("https://www.notion.so/pricing"))
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build()
                .normalized();
        DecisionPolicyResult policyResult = DecisionPolicyResult.builder()
                .decisionId("od-001")
                .allowed(true)
                .normalizedAction("CREATE_SUPPLEMENT_BRANCH")
                .riskLevel("HIGH")
                .sourceUrls(List.of("https://www.notion.so/pricing"))
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build();
        DynamicPlanMutation mutation = DynamicPlanMutation.builder()
                .mutationId("dpm-od-001")
                .decisionId("od-001")
                .mutationType("APPEND_NODES")
                .branchReason("ORCHESTRATOR_DECISION")
                .dynamicAction("CREATE_SUPPLEMENT_BRANCH")
                .expectedResumeNodeName("collect_revision_evidence_v2_1")
                .sourceUrls(List.of("https://www.notion.so/pricing"))
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build();
        TaskPlan derivedPlan = TaskPlan.builder()
                .id(9L)
                .taskId(50L)
                .planVersion(2)
                .parentPlanId(8L)
                .branchKey("root/review-2")
                .planSnapshot(objectMapper.writeValueAsString(WorkflowPlan.builder()
                        .planVersionId(9L)
                        .planVersion(2)
                        .branchKey("root/review-2")
                        .nodes(List.of(
                                WorkflowPlan.WorkflowPlanNode.builder()
                                        .nodeName("collect_revision_evidence_v2_1")
                                        .displayName("补充证据采集")
                                        .agentType(AgentType.COLLECTOR.name())
                                        .dependsOn(List.of("quality_check_final"))
                                        .executionOrder(7)
                                        .branchKey("root/review-2")
                                        .dynamicNode(true)
                                        .originNodeName("quality_check_final")
                                        .build(),
                                WorkflowPlan.WorkflowPlanNode.builder()
                                        .nodeName("rewrite_revision_patch_v2")
                                        .displayName("修订报告改写")
                                        .agentType(AgentType.WRITER.name())
                                        .dependsOn(List.of("collect_revision_evidence_v2_1"))
                                        .executionOrder(10)
                                        .branchKey("root/review-2")
                                        .dynamicNode(true)
                                        .originNodeName("quality_check_final")
                                        .build(),
                                WorkflowPlan.WorkflowPlanNode.builder()
                                        .nodeName("quality_check_revision_patch_v2")
                                        .displayName("修订终审复核")
                                        .agentType(AgentType.REVIEWER.name())
                                        .dependsOn(List.of("rewrite_revision_patch_v2"))
                                        .executionOrder(11)
                                        .branchKey("root/review-2")
                                        .dynamicNode(true)
                                        .originNodeName("quality_check_final")
                                        .build()))
                        .build()))
                .build();

        when(taskRepository.findById(50L)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(AnalysisTask.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(taskPlanRepository.findById(8L)).thenReturn(Optional.of(parentPlan));
        OrchestrationRuntimeDecision ready = new OrchestrationRuntimeDecision(
                decision, policyResult, mutation, true, OrchestrationRuntimeDecision.READY, List.of());
        OrchestrationDecision primaryDecision = decision.toBuilder()
                .decisionId("od-primary-not-final")
                .decisionOrigin(OrchestrationDecisionOrigin.LLM_PRIMARY)
                .build()
                .normalized();
        DecisionPolicyResult primaryPolicy = policyResult.toBuilder()
                .decisionId("od-primary-not-final")
                .decisionOrigin(OrchestrationDecisionOrigin.LLM_PRIMARY)
                .build()
                .normalized();
        DynamicPlanMutation primaryMutation = mutation.toBuilder()
                .mutationId("dpm-od-primary-not-final")
                .decisionId("od-primary-not-final")
                .build()
                .normalized();
        OrchestrationRuntimeDecision primaryAttempt = new OrchestrationRuntimeDecision(
                primaryDecision,
                primaryPolicy,
                primaryMutation,
                false,
                OrchestrationRuntimeDecision.READY,
                List.of());
        when(runtimeDecisionService.decide(any(), eq(AnalysisTaskStatus.STOPPED.name()),
                eq(TaskNodeStatus.SUCCESS.name())))
                .thenReturn(batch(List.of(primaryAttempt, ready), List.of(ready), 8L, 2));
        when(dynamicTaskGraphService.createDynamicPlan(eq(parentPlan), eq(completedNode),
                any(DynamicPlanMutation.class), any()))
                .thenReturn(derivedPlan);
        when(nodeRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        List<TaskNode> nodes = new ArrayList<>(List.of(completedNode));
        Map<String, TaskNode> nodeMap = new LinkedHashMap<>();
        nodeMap.put(completedNode.getNodeName(), completedNode);

        boolean appended = appender.maybeAppendDynamicPlan(50L, nodes, nodeMap, completedNode);

        assertThat(appended).isTrue();
        ArgumentCaptor<cn.bugstack.competitoragent.orchestration.OrchestrationContext> contextCaptor =
                ArgumentCaptor.forClass(cn.bugstack.competitoragent.orchestration.OrchestrationContext.class);
        verify(runtimeDecisionService).decide(
                contextCaptor.capture(),
                eq(AnalysisTaskStatus.STOPPED.name()),
                eq(TaskNodeStatus.SUCCESS.name()));
        assertThat(contextCaptor.getValue().getTaskStatus()).isEqualTo(AnalysisTaskStatus.STOPPED.name());
        assertThat(contextCaptor.getValue().getSourceUrls())
                .containsExactly("https://www.notion.so/pricing");
        verify(orchestrationTraceService).recordDecisionBatch(
                eq(50L),
                eq(completedNode),
                argThat(recordedBatch -> recordedBatch.attempts().stream()
                        .map(item -> item.decision().getDecisionId())
                        .toList()
                        .equals(List.of("od-primary-not-final", "od-001"))
                        && recordedBatch.finalDecisions().size() == 1
                        && "od-001".equals(recordedBatch.finalDecisions().get(0).decision().getDecisionId())));
        verify(orchestrationTraceService, never()).recordDecision(any(), any(), any(), any(), any());
        verify(dynamicTaskGraphService, never()).createDynamicPlan(
                eq(parentPlan),
                eq(completedNode),
                eq(primaryMutation),
                any());
        verify(orchestrationTraceService).recordCheckpoint(
                eq(50L),
                eq(completedNode),
                eq(derivedPlan),
                argThat(recordedDecision -> "od-001".equals(recordedDecision.getDecisionId())),
                argThat(recordedMutation -> "dpm-od-001".equals(recordedMutation.getMutationId())));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TaskNode>> savedNodesCaptor = ArgumentCaptor.forClass(List.class);
        verify(nodeRepository).saveAll(savedNodesCaptor.capture());
        assertThat(savedNodesCaptor.getValue())
                .extracting(TaskNode::getNodeName)
                .contains("collect_revision_evidence_v2_1", "rewrite_revision_patch_v2", "quality_check_revision_patch_v2");
        assertThat(task.getCurrentPlanVersionId()).isEqualTo(9L);
        assertThat(task.getCurrentPlanVersion()).isEqualTo(2);
        assertThat(nodeMap).containsKeys("collect_revision_evidence_v2_1", "rewrite_revision_patch_v2", "quality_check_revision_patch_v2");
    }

    @Test
    void shouldMarkReviewerWaitingWhenFinalMutationRequiresConfirmation() throws Exception {
        AnalysisTask task = AnalysisTask.builder()
                .id(52L)
                .status(AnalysisTaskStatus.RUNNING)
                .currentPlanVersionId(12L)
                .currentPlanVersion(1)
                .build();
        TaskNode completedNode = TaskNode.builder()
                .taskId(52L)
                .nodeName("quality_check_final")
                .agentType(AgentType.REVIEWER)
                .status(TaskNodeStatus.SUCCESS)
                .planVersionId(12L)
                .branchKey("root")
                .outputData("""
                        {"reviewStage":"final","passed":false,
                        "requiresHumanIntervention":false,"summary":"需要确认补图"}
                        """)
                .build();
        TaskPlan parentPlan = TaskPlan.builder()
                .id(12L)
                .taskId(52L)
                .planVersion(1)
                .branchKey("root")
                .active(true)
                .planSnapshot(objectMapper.writeValueAsString(WorkflowPlan.builder()
                        .planVersionId(12L)
                        .planVersion(1)
                        .branchKey("root")
                        .nodes(List.of())
                        .build()))
                .build();
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-confirm-appender")
                .taskId(52L)
                .triggerNodeName("quality_check_final")
                .decisionType("APPEND_DYNAMIC_BRANCH")
                .actionType("SUPPLEMENT_EVIDENCE")
                .decisionOrigin(OrchestrationDecisionOrigin.RULE_ONLY)
                .reason("策略要求人工确认")
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .sourceUrls(List.of())
                .build();
        DecisionPolicyResult policy = DecisionPolicyResult.builder()
                .decisionId("od-confirm-appender")
                .decisionOrigin(OrchestrationDecisionOrigin.RULE_ONLY)
                .allowed(true)
                .requiresConfirmation(true)
                .normalizedAction("CREATE_SUPPLEMENT_BRANCH")
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build();
        DynamicPlanMutation mutation = DynamicPlanMutation.builder()
                .mutationId("dpm-od-confirm-appender")
                .decisionId("od-confirm-appender")
                .mutationType("MARK_WAITING_INTERVENTION")
                .branchReason("POLICY_CONFIRMATION_REQUIRED")
                .dynamicAction("MANUAL_ONLY")
                .runtimeCommand("AWAIT_CONFIRMATION")
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build();
        OrchestrationRuntimeDecision confirmation = new OrchestrationRuntimeDecision(
                decision,
                policy,
                mutation,
                false,
                OrchestrationRuntimeDecision.CONFIRMATION_REQUIRED,
                List.of());
        when(taskRepository.findById(52L)).thenReturn(Optional.of(task));
        when(taskPlanRepository.findById(12L)).thenReturn(Optional.of(parentPlan));
        when(runtimeDecisionService.decide(any(), eq(AnalysisTaskStatus.RUNNING.name()),
                eq(TaskNodeStatus.SUCCESS.name())))
                .thenReturn(batch(List.of(confirmation), List.of(confirmation), 12L, 2));
        when(nodeRepository.save(any(TaskNode.class))).thenAnswer(invocation -> invocation.getArgument(0));

        boolean appended = appender.maybeAppendDynamicPlan(
                52L,
                new ArrayList<>(List.of(completedNode)),
                new LinkedHashMap<>(Map.of(completedNode.getNodeName(), completedNode)),
                completedNode);

        assertThat(appended).isFalse();
        assertThat(completedNode.getStatus()).isEqualTo(TaskNodeStatus.WAITING_INTERVENTION);
        assertThat(completedNode.getInterventionReason()).isEqualTo("策略要求人工确认");
        verify(nodeRepository).save(completedNode);
        verify(dynamicTaskGraphService, never()).createDynamicPlan(
                any(), any(), any(DynamicPlanMutation.class), any());
        verify(orchestrationTraceService, never()).recordCheckpoint(any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRouteBothHumanInterventionQuadrantsThroughRuntime(boolean passed) throws Exception {
        long taskId = passed ? 54L : 53L;
        long planId = passed ? 14L : 13L;
        AnalysisTask task = AnalysisTask.builder()
                .id(taskId)
                .status(AnalysisTaskStatus.RUNNING)
                .currentPlanVersionId(planId)
                .currentPlanVersion(1)
                .build();
        TaskNode completedNode = TaskNode.builder()
                .taskId(taskId)
                .nodeName("quality_check_final")
                .agentType(AgentType.REVIEWER)
                .status(TaskNodeStatus.SUCCESS)
                .planVersionId(planId)
                .branchKey("root")
                .outputData("""
                        {"reviewStage":"final","passed":%s,
                        "requiresHumanIntervention":true,"summary":"终审要求人工介入"}
                        """.formatted(passed))
                .build();
        TaskPlan parentPlan = TaskPlan.builder()
                .id(planId)
                .taskId(taskId)
                .planVersion(1)
                .branchKey("root")
                .active(true)
                .planSnapshot(objectMapper.writeValueAsString(WorkflowPlan.builder()
                        .planVersionId(planId)
                        .planVersion(1)
                        .branchKey("root")
                        .nodes(List.of())
                        .build()))
                .build();
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-human-" + taskId)
                .taskId(taskId)
                .triggerNodeName("quality_check_final")
                .decisionOrigin(OrchestrationDecisionOrigin.RULE_ONLY)
                .decisionType("WAIT_FOR_HUMAN")
                .actionType("MANUAL_REVIEW")
                .reason("终审要求人工介入")
                .requiresHumanIntervention(true)
                .requiresConfirmation(true)
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build();
        DecisionPolicyResult policy = DecisionPolicyResult.builder()
                .decisionId(decision.getDecisionId())
                .decisionOrigin(OrchestrationDecisionOrigin.RULE_ONLY)
                .allowed(true)
                .requiresConfirmation(true)
                .normalizedAction("WAIT_FOR_HUMAN")
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build();
        DynamicPlanMutation mutation = DynamicPlanMutation.builder()
                .mutationId("dpm-" + decision.getDecisionId())
                .decisionId(decision.getDecisionId())
                .mutationType("MARK_WAITING_INTERVENTION")
                .branchReason("MANUAL_REVIEW_REQUIRED")
                .dynamicAction("MANUAL_ONLY")
                .runtimeCommand("AWAIT_HUMAN")
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build();
        OrchestrationRuntimeDecision waiting = new OrchestrationRuntimeDecision(
                decision,
                policy,
                mutation,
                false,
                OrchestrationRuntimeDecision.CONFIRMATION_REQUIRED,
                List.of());
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskPlanRepository.findById(planId)).thenReturn(Optional.of(parentPlan));
        when(runtimeDecisionService.decide(any(), eq(AnalysisTaskStatus.RUNNING.name()),
                eq(TaskNodeStatus.SUCCESS.name())))
                .thenReturn(batch(List.of(waiting), List.of(waiting), planId, 2));
        when(nodeRepository.save(any(TaskNode.class))).thenAnswer(invocation -> invocation.getArgument(0));

        boolean appended = appender.maybeAppendDynamicPlan(
                taskId,
                new ArrayList<>(List.of(completedNode)),
                new LinkedHashMap<>(Map.of(completedNode.getNodeName(), completedNode)),
                completedNode);

        assertThat(appended).isFalse();
        assertThat(completedNode.getStatus()).isEqualTo(TaskNodeStatus.WAITING_INTERVENTION);
        assertThat(completedNode.getInterventionReason()).isEqualTo("终审要求人工介入");
        verify(runtimeDecisionService).decide(any(), eq(AnalysisTaskStatus.RUNNING.name()),
                eq(TaskNodeStatus.SUCCESS.name()));
        verify(orchestrationTraceService).recordDecisionBatch(eq(taskId), eq(completedNode), any());
        verify(nodeRepository).save(completedNode);
        verify(dynamicTaskGraphService, never()).createDynamicPlan(
                any(), any(), any(DynamicPlanMutation.class), any());
    }

    @Test
    void shouldAuditPassedReviewAsNoMutationThroughUnifiedPipeline() throws Exception {
        TaskNode completedNode = TaskNode.builder()
                .taskId(55L)
                .nodeName("quality_check_final")
                .agentType(AgentType.REVIEWER)
                .status(TaskNodeStatus.SUCCESS)
                .planVersionId(15L)
                .outputData("""
                        {"reviewStage":"final","passed":true,
                        "requiresHumanIntervention":false,"summary":"终审通过",
                        "sourceUrls":["https://example.com/review"]}
                        """)
                .build();
        AnalysisTask task = AnalysisTask.builder().id(55L).status(AnalysisTaskStatus.RUNNING)
                .currentPlanVersionId(15L).currentPlanVersion(1).build();
        TaskPlan parentPlan = TaskPlan.builder().id(15L).taskId(55L).planVersion(1).branchKey("root")
                .active(true).planSnapshot(objectMapper.writeValueAsString(WorkflowPlan.builder()
                        .planVersionId(15L).planVersion(1).branchKey("root").nodes(List.of()).build())).build();
        OrchestrationDecision decision = OrchestrationDecision.builder().decisionId("od-55-pass")
                .taskId(55L).triggerNodeName("quality_check_final").decisionOrigin(OrchestrationDecisionOrigin.RULE_ONLY)
                .decisionType("NO_ACTION").actionType("NO_ACTION").targetNode("quality_check_final")
                .affectedScope("CURRENT_NODE_ONLY").sourceUrls(List.of("https://example.com/review"))
                .evidenceState(EvidenceState.FULL_SOURCE).build().normalized();
        DecisionPolicyResult policy = DecisionPolicyResult.builder().decisionId(decision.getDecisionId())
                .allowed(true).normalizedAction("NO_ACTION").sourceUrls(decision.getSourceUrls())
                .evidenceState(EvidenceState.FULL_SOURCE).build();
        DynamicPlanMutation mutation = new DecisionExecutorAdapter(objectMapper).toMutation(decision, policy, 15L, 2);
        OrchestrationRuntimeDecision noMutation = new OrchestrationRuntimeDecision(decision, policy, mutation,
                false, OrchestrationRuntimeDecision.NO_MUTATION, List.of());
        when(taskRepository.findById(55L)).thenReturn(Optional.of(task));
        when(taskPlanRepository.findById(15L)).thenReturn(Optional.of(parentPlan));
        when(runtimeDecisionService.decide(any(), eq(AnalysisTaskStatus.RUNNING.name()),
                eq(TaskNodeStatus.SUCCESS.name())))
                .thenReturn(batch(List.of(noMutation), List.of(noMutation), 15L, 2));

        boolean appended = appender.maybeAppendDynamicPlan(
                55L,
                new ArrayList<>(List.of(completedNode)),
                new LinkedHashMap<>(Map.of(completedNode.getNodeName(), completedNode)),
                completedNode);

        assertThat(appended).isFalse();
        assertThat(completedNode.getStatus()).isEqualTo(TaskNodeStatus.SUCCESS);
        verify(runtimeDecisionService).decide(any(), eq(AnalysisTaskStatus.RUNNING.name()),
                eq(TaskNodeStatus.SUCCESS.name()));
        verify(orchestrationTraceService).recordDecisionBatch(eq(55L), eq(completedNode), any());
        verify(nodeRepository, never()).save(any());
    }

    @Test
    void shouldNotTreatTargetCoverageGateAsReviewerDecisionCycle() throws Exception {
        TaskNode coverageGate = TaskNode.builder()
                .taskId(56L)
                .nodeName("target_coverage_gate_v2")
                .agentType(AgentType.REVIEWER)
                .status(TaskNodeStatus.SUCCESS)
                .planVersionId(16L)
                .branchKey("root/review-2")
                .outputData("""
                        {
                          "targetCoverageGate": "PASSED",
                          "targetCoverageDelta": 1,
                          "closedGapKeys": ["notion|pricing|official_pricing"],
                          "sourceUrls": ["https://www.notion.so/pricing"]
                        }
                        """)
                .build();
        AnalysisTask task = AnalysisTask.builder().id(56L).status(AnalysisTaskStatus.RUNNING)
                .currentPlanVersionId(16L).currentPlanVersion(2).build();
        TaskPlan parentPlan = TaskPlan.builder().id(16L).taskId(56L).planVersion(2).branchKey("root/review-2")
                .active(true).planSnapshot(objectMapper.writeValueAsString(WorkflowPlan.builder()
                        .planVersionId(16L).planVersion(2).branchKey("root/review-2").nodes(List.of()).build())).build();
        OrchestrationRuntimeDecisionBatch emptyBatch = batch(List.of(), List.of(), 16L, 3);
        when(taskRepository.findById(56L)).thenReturn(Optional.of(task));
        when(taskPlanRepository.findById(16L)).thenReturn(Optional.of(parentPlan));
        when(runtimeDecisionService.decide(any(), eq(AnalysisTaskStatus.RUNNING.name()),
                eq(TaskNodeStatus.SUCCESS.name()))).thenReturn(emptyBatch);

        boolean appended = appender.maybeAppendDynamicPlan(
                56L,
                new ArrayList<>(List.of(coverageGate)),
                new LinkedHashMap<>(Map.of(coverageGate.getNodeName(), coverageGate)),
                coverageGate);

        assertThat(appended).isFalse();
        verify(runtimeDecisionService, never()).decide(any(), any(), any());
    }

    private OrchestrationRuntimeDecisionBatch batch(List<OrchestrationRuntimeDecision> attempts,
                                                    List<OrchestrationRuntimeDecision> finalDecisions,
                                                    Long planVersionId,
                                                    int nextPlanVersion) {
        List<OrchestrationDecision> decisions = attempts.stream()
                .map(OrchestrationRuntimeDecision::decision)
                .filter(decision -> decision.getDecisionOrigin() != OrchestrationDecisionOrigin.RULE_FALLBACK)
                .toList();
        OrchestrationDecisionOutcome outcome = new OrchestrationDecisionOutcome(
                OrchestratorDecisionMode.LLM_PRIMARY,
                decisions,
                List.of(),
                OrchestrationShadowExecution.notRequested(List.of()),
                null,
                List.of());
        OrchestrationRuntimeState state = new OrchestrationRuntimeState(
                0,
                Map.of(),
                planVersionId,
                nextPlanVersion,
                OrchestrationRuntimeState.CheckpointStateStatus.ABSENT,
                List.of());
        return new OrchestrationRuntimeDecisionBatch(
                outcome, state, attempts, finalDecisions, !attempts.equals(finalDecisions), List.of());
    }
}
