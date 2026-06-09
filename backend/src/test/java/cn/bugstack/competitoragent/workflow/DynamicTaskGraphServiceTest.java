package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.workflow.contract.RevisionDirective;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamicTaskGraphServiceTest {

    @Test
    void shouldCreateDynamicBackflowPlanForEvidenceGapDirective() throws Exception {
        cn.bugstack.competitoragent.repository.TaskPlanRepository repository =
                mock(cn.bugstack.competitoragent.repository.TaskPlanRepository.class);
        when(repository.findFirstByTaskIdAndActiveTrueOrderByPlanVersionDesc(21L))
                .thenReturn(Optional.of(TaskPlan.builder().id(8L).taskId(21L).planVersion(1).branchKey("root").active(true).planSnapshot("{}").build()));
        when(repository.save(any(TaskPlan.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DynamicTaskGraphService service = new DynamicTaskGraphService(
                repository,
                new TaskPlanVersioner(new ObjectMapper()),
                new CompensationGraphAssembler(new ObjectMapper()));

        TaskPlan parentPlan = TaskPlan.builder()
                .id(8L)
                .taskId(21L)
                .planVersion(1)
                .branchKey("root")
                .planType("INITIAL")
                .active(true)
                .planSnapshot("{}")
                .build();
        TaskNode triggerNode = TaskNode.builder()
                .taskId(21L)
                .nodeName("quality_check")
                .displayName("质量初审")
                .agentType(AgentType.REVIEWER)
                .planVersionId(8L)
                .branchKey("root")
                .build();
        WorkflowPlan basePlan = WorkflowPlan.builder()
                .planVersionId(8L)
                .planVersion(1)
                .branchKey("root")
                .nodes(List.of(WorkflowPlan.WorkflowPlanNode.builder()
                        .nodeName("quality_check")
                        .displayName("质量初审")
                        .agentType(AgentType.REVIEWER.name())
                        .dependsOn(List.of("write_report"))
                        .executionOrder(4)
                        .branchKey("root")
                        .build()))
                .build();

        RevisionDirective directive = RevisionDirective.builder()
                .category("EVIDENCE_GAP")
                .actionType("SUPPLEMENT_EVIDENCE")
                .summary("补充关键章节证据")
                .searchQueries(List.of("Notion AI pricing official"))
                .sourceUrls(List.of("https://www.notion.so/pricing"))
                .build();

        TaskPlan derivedPlan = service.createDynamicPlan(parentPlan, triggerNode, List.of(directive), basePlan);

        assertThat(derivedPlan.getPlanVersion()).isEqualTo(2);
        assertThat(derivedPlan.getParentPlanId()).isEqualTo(8L);
        assertThat(derivedPlan.getBranchKey()).isEqualTo("root/review-2");
        assertThat(derivedPlan.getPlanSnapshot()).contains("collect_revision_evidence_v2_1");
        assertThat(derivedPlan.getPlanSnapshot()).contains("quality_check_revision_patch_v2");
        WorkflowPlan snapshot = new ObjectMapper().readValue(derivedPlan.getPlanSnapshot(), WorkflowPlan.class);
        assertThat(snapshot.getBranchKey()).isEqualTo("root/review-2");
        assertThat(snapshot.getNodes())
                .anySatisfy(node -> {
                    assertThat(node.getNodeName()).isEqualTo("collect_revision_evidence_v2_1");
                    assertThat(node.getBranchKey()).isEqualTo("root/review-2");
                });

        ArgumentCaptor<TaskPlan> planCaptor = ArgumentCaptor.forClass(TaskPlan.class);
        verify(repository, times(2)).save(planCaptor.capture());
        assertThat(planCaptor.getAllValues()).hasSize(2);
        assertThat(planCaptor.getAllValues().get(0).isActive()).isFalse();
        assertThat(planCaptor.getAllValues().get(1).getBranchKey()).isEqualTo("root/review-2");
    }

    @Test
    void shouldLimitAffectedNodesToCurrentBranchAndDescendants() {
        DynamicTaskGraphService service = new DynamicTaskGraphService(
                mock(cn.bugstack.competitoragent.repository.TaskPlanRepository.class),
                new TaskPlanVersioner(new ObjectMapper()),
                new CompensationGraphAssembler(new ObjectMapper()));

        TaskNode rootCollect = node("collect_sources_web", "[]", "root", 1L, 0);
        TaskNode rootExtract = node("extract_schema", "[\"collect_sources_web\"]", "root", 1L, 1);
        TaskNode branchRewrite = node("rewrite_revision_patch_v2", "[\"extract_schema\"]", "root/review-2", 2L, 2);
        TaskNode branchReview = node("quality_check_revision_patch_v2", "[\"rewrite_revision_patch_v2\"]", "root/review-2", 2L, 3);
        TaskNode siblingRewrite = node("rewrite_revision_patch_v3", "[\"extract_schema\"]", "root/review-3", 3L, 4);

        List<TaskNode> affectedNodes = service.calculateAffectedNodes(
                List.of(rootCollect, rootExtract, branchRewrite, branchReview, siblingRewrite),
                branchRewrite);

        assertThat(affectedNodes).extracting(TaskNode::getNodeName)
                .containsExactly("rewrite_revision_patch_v2", "quality_check_revision_patch_v2");
    }

    @Test
    void shouldCreateRewriteOnlyDynamicBranchForExpressionDirective() throws Exception {
        cn.bugstack.competitoragent.repository.TaskPlanRepository repository =
                mock(cn.bugstack.competitoragent.repository.TaskPlanRepository.class);
        when(repository.findFirstByTaskIdAndActiveTrueOrderByPlanVersionDesc(21L))
                .thenReturn(Optional.of(TaskPlan.builder().id(8L).taskId(21L).planVersion(1).branchKey("root").active(true).planSnapshot("{}").build()));
        when(repository.save(any(TaskPlan.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DynamicTaskGraphService service = new DynamicTaskGraphService(
                repository,
                new TaskPlanVersioner(new ObjectMapper()),
                new CompensationGraphAssembler(new ObjectMapper()));

        TaskPlan parentPlan = TaskPlan.builder()
                .id(8L)
                .taskId(21L)
                .planVersion(1)
                .branchKey("root")
                .planType("INITIAL")
                .active(true)
                .planSnapshot("{}")
                .build();
        TaskNode triggerNode = TaskNode.builder()
                .taskId(21L)
                .nodeName("quality_check_final")
                .displayName("质量终审")
                .agentType(AgentType.REVIEWER)
                .planVersionId(8L)
                .branchKey("root")
                .build();
        WorkflowPlan basePlan = WorkflowPlan.builder()
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
                .build();

        RevisionDirective directive = RevisionDirective.builder()
                .category("EXPRESSION_ISSUE")
                .targetSection("结论")
                .summary("收紧绝对化表述")
                .build();

        TaskPlan derivedPlan = service.createDynamicPlan(parentPlan, triggerNode, List.of(directive), basePlan);

        WorkflowPlan snapshot = new ObjectMapper().readValue(derivedPlan.getPlanSnapshot(), WorkflowPlan.class);
        assertThat(snapshot.getNodes())
                .extracting(WorkflowPlan.WorkflowPlanNode::getNodeName)
                .contains("rewrite_revision_patch_v2", "quality_check_revision_patch_v2")
                .doesNotContain("collect_revision_evidence_v2_1");
    }

    private TaskNode node(String nodeName, String dependsOn, String branchKey, Long planVersionId, int order) {
        return TaskNode.builder()
                .taskId(1L)
                .nodeName(nodeName)
                .displayName(nodeName)
                .agentType(AgentType.WRITER)
                .dependsOn(dependsOn)
                .branchKey(branchKey)
                .planVersionId(planVersionId)
                .executionOrder(order)
                .build();
    }
}
