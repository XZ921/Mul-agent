package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.orchestration.DynamicPlanMutation;
import cn.bugstack.competitoragent.orchestration.EvidenceState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompensationGraphAssemblerTest {

    private final CompensationGraphAssembler assembler =
            new CompensationGraphAssembler(new ObjectMapper());

    @Test
    void shouldAssembleSupplementMutationIntoCollectorExtractAnalyzeRewriteReviewChain() {
        TaskPlan parentPlan = TaskPlan.builder()
                .id(8L)
                .taskId(50L)
                .planVersion(1)
                .branchKey("root")
                .build();
        TaskNode triggerNode = TaskNode.builder()
                .taskId(50L)
                .nodeName("quality_check_final")
                .agentType(AgentType.REVIEWER)
                .planVersionId(8L)
                .branchKey("root")
                .build();
        DynamicPlanMutation mutation = DynamicPlanMutation.builder()
                .mutationId("dpm-001")
                .decisionId("od-001")
                .mutationType("APPEND_NODES")
                .dynamicAction("CREATE_SUPPLEMENT_BRANCH")
                .nodeTemplates(List.of(WorkflowPlan.WorkflowPlanNode.builder()
                        .nodeName("collect_revision_evidence_v2_1")
                        .displayName("补充证据采集")
                        .agentType(AgentType.COLLECTOR.name())
                        .nodeConfig("{\"decisionId\":\"od-001\"}")
                        .build()))
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build();

        List<WorkflowPlan.WorkflowPlanNode> nodes = assembler.assembleDynamicNodes(
                parentPlan, triggerNode, mutation, 10, "root/review-2");

        assertThat(nodes).extracting(WorkflowPlan.WorkflowPlanNode::getNodeName)
                .containsExactly(
                        "collect_revision_evidence_v2_1",
                        "extract_revision_patch_v2",
                        "analyze_revision_patch_v2",
                        "rewrite_revision_patch_v2",
                        "quality_check_revision_patch_v2");
    }

    @Test
    void shouldAssembleRewriteMutationIntoRewriteAndReviewOnly() {
        TaskPlan parentPlan = TaskPlan.builder()
                .id(8L)
                .taskId(50L)
                .planVersion(1)
                .branchKey("root")
                .build();
        TaskNode triggerNode = TaskNode.builder()
                .taskId(50L)
                .nodeName("quality_check_final")
                .agentType(AgentType.REVIEWER)
                .planVersionId(8L)
                .branchKey("root")
                .build();
        DynamicPlanMutation mutation = DynamicPlanMutation.builder()
                .mutationId("dpm-002")
                .decisionId("od-002")
                .mutationType("APPEND_NODES")
                .dynamicAction("CREATE_REWRITE_BRANCH")
                .evidenceState(EvidenceState.FULL_SOURCE)
                .build();

        List<WorkflowPlan.WorkflowPlanNode> nodes = assembler.assembleDynamicNodes(
                parentPlan, triggerNode, mutation, 10, "root/review-2");

        assertThat(nodes).extracting(WorkflowPlan.WorkflowPlanNode::getNodeName)
                .containsExactly("rewrite_revision_patch_v2", "quality_check_revision_patch_v2");
    }

    @Test
    void shouldReturnEmptyWhenMutationIsNotAppendNodes() {
        List<WorkflowPlan.WorkflowPlanNode> nodes = assembler.assembleDynamicNodes(
                TaskPlan.builder().planVersion(1).build(),
                TaskNode.builder().nodeName("quality_check_final").build(),
                DynamicPlanMutation.builder()
                        .mutationType("NO_MUTATION")
                        .dynamicAction("MANUAL_ONLY")
                        .build(),
                10,
                "root/review-2");

        assertThat(nodes).isEmpty();
    }
}
