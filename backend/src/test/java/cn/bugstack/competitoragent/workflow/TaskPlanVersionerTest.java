package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.model.entity.TaskPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskPlanVersionerTest {

    private final TaskPlanVersioner versioner = new TaskPlanVersioner(new com.fasterxml.jackson.databind.ObjectMapper());

    @Test
    void shouldCreateInitialPlanVersionSnapshot() {
        WorkflowPlan workflowPlan = WorkflowPlan.builder()
                .planVersion(1)
                .branchKey("root")
                .nodes(List.of(WorkflowPlan.WorkflowPlanNode.builder()
                        .nodeName("collect")
                        .displayName("Collect")
                        .agentType("COLLECTOR")
                        .dependsOn(List.of())
                        .executionOrder(0)
                        .branchKey("root")
                        .build()))
                .build();

        TaskPlan initialPlan = versioner.createInitialPlan(12L, workflowPlan);

        assertThat(initialPlan.getTaskId()).isEqualTo(12L);
        assertThat(initialPlan.getPlanVersion()).isEqualTo(1);
        assertThat(initialPlan.getParentPlanId()).isNull();
        assertThat(initialPlan.getBranchKey()).isEqualTo("root");
        assertThat(initialPlan.getPlanType()).isEqualTo("INITIAL");
        assertThat(initialPlan.getPlanSnapshot()).contains("\"nodeName\":\"collect\"");
    }

    @Test
    void shouldDeriveNextPlanVersionFromParentPlan() {
        TaskPlan parent = TaskPlan.builder()
                .id(3L)
                .taskId(18L)
                .planVersion(2)
                .branchKey("root")
                .planType("INITIAL")
                .planSnapshot("{\"nodes\":[]}")
                .build();
        WorkflowPlan workflowPlan = WorkflowPlan.builder()
                .planVersion(3)
                .branchKey("root/review-3")
                .dynamicPlan(true)
                .nodes(List.of())
                .build();

        TaskPlan derivedPlan = versioner.createDerivedPlan(
                parent,
                workflowPlan,
                "quality_check",
                "DYNAMIC_BACKFLOW",
                "review-3");

        assertThat(derivedPlan.getTaskId()).isEqualTo(18L);
        assertThat(derivedPlan.getPlanVersion()).isEqualTo(3);
        assertThat(derivedPlan.getParentPlanId()).isEqualTo(3L);
        assertThat(derivedPlan.getTriggerNodeName()).isEqualTo("quality_check");
        assertThat(derivedPlan.getPlanType()).isEqualTo("DYNAMIC_BACKFLOW");
        assertThat(derivedPlan.getBranchKey()).isEqualTo("root/review-3");
    }
}
