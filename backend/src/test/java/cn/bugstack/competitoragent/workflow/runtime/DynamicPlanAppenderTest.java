package cn.bugstack.competitoragent.workflow.runtime;

import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.orchestration.DecisionExecutorAdapter;
import cn.bugstack.competitoragent.orchestration.DecisionPolicyResult;
import cn.bugstack.competitoragent.orchestration.DecisionPolicyService;
import cn.bugstack.competitoragent.orchestration.DynamicPlanMutation;
import cn.bugstack.competitoragent.orchestration.EvidenceState;
import cn.bugstack.competitoragent.orchestration.OrchestrationDecision;
import cn.bugstack.competitoragent.orchestration.OrchestrationDecisionService;
import cn.bugstack.competitoragent.orchestration.OrchestrationTraceService;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import cn.bugstack.competitoragent.workflow.DynamicTaskGraphService;
import cn.bugstack.competitoragent.workflow.WorkflowPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamicPlanAppenderTest {

    private final AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
    private final TaskNodeRepository nodeRepository = mock(TaskNodeRepository.class);
    private final DynamicTaskGraphService dynamicTaskGraphService = mock(DynamicTaskGraphService.class);
    private final TaskPlanRepository taskPlanRepository = mock(TaskPlanRepository.class);
    private final OrchestrationDecisionService orchestrationDecisionService = mock(OrchestrationDecisionService.class);
    private final DecisionPolicyService decisionPolicyService = mock(DecisionPolicyService.class);
    private final DecisionExecutorAdapter decisionExecutorAdapter = mock(DecisionExecutorAdapter.class);
    private final OrchestrationTraceService orchestrationTraceService = mock(OrchestrationTraceService.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private final DynamicPlanAppender appender = new DynamicPlanAppender(
            taskRepository,
            nodeRepository,
            dynamicTaskGraphService,
            taskPlanRepository,
            objectMapper,
            orchestrationDecisionService,
            decisionPolicyService,
            decisionExecutorAdapter,
            orchestrationTraceService
    );

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
        when(orchestrationDecisionService.decide(any())).thenReturn(List.of(decision));
        when(decisionPolicyService.evaluate(eq(decision), any(), eq(0), eq(AnalysisTaskStatus.STOPPED.name()), eq(TaskNodeStatus.SUCCESS.name())))
                .thenReturn(policyResult);
        when(decisionExecutorAdapter.toMutation(decision, policyResult, 8L, 2)).thenReturn(mutation);
        when(dynamicTaskGraphService.createDynamicPlan(eq(parentPlan), eq(completedNode), eq(mutation), any()))
                .thenReturn(derivedPlan);
        when(nodeRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        List<TaskNode> nodes = new ArrayList<>(List.of(completedNode));
        Map<String, TaskNode> nodeMap = new LinkedHashMap<>();
        nodeMap.put(completedNode.getNodeName(), completedNode);

        boolean appended = appender.maybeAppendDynamicPlan(50L, nodes, nodeMap, completedNode);

        assertThat(appended).isTrue();
        verify(orchestrationDecisionService).decide(any());
        verify(decisionPolicyService).evaluate(eq(decision), any(), eq(0), eq(AnalysisTaskStatus.STOPPED.name()), eq(TaskNodeStatus.SUCCESS.name()));
        verify(decisionExecutorAdapter).toMutation(decision, policyResult, 8L, 2);
        verify(orchestrationTraceService).recordDecision(50L, completedNode, decision, policyResult, mutation);
        verify(orchestrationTraceService).recordCheckpoint(
                eq(50L),
                eq(completedNode),
                eq(derivedPlan),
                eq(decision),
                eq(mutation),
                any());

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
}
