package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkflowPlanValidatorTest {

    private final WorkflowPlanValidator validator = new WorkflowPlanValidator();

    @Test
    void shouldAcceptValidWorkflowPlan() {
        WorkflowPlan plan = WorkflowPlan.builder()
                .contractType("TASK_PLAN_PREVIEW_V1")
                .stages(List.of(
                        WorkflowPlan.WorkflowPlanStage.builder()
                                .stageCode("SOURCE_STRATEGY")
                                .title("Source strategy")
                                .summary("summary")
                                .detail("detail")
                                .build(),
                        WorkflowPlan.WorkflowPlanStage.builder()
                                .stageCode("EXTRACT")
                                .title("Extract")
                                .summary("summary")
                                .detail("detail")
                                .build()
                ))
                .nodes(List.of(
                        WorkflowPlan.WorkflowPlanNode.builder()
                                .nodeName("collect")
                                .displayName("Collect")
                                .agentType("COLLECTOR")
                                .dependsOn(List.of())
                                .required(true)
                                .executionOrder(0)
                                .stageCode("SOURCE_STRATEGY")
                                .build(),
                        WorkflowPlan.WorkflowPlanNode.builder()
                                .nodeName("extract")
                                .displayName("Extract")
                                .agentType("EXTRACTOR")
                                .dependsOn(List.of("collect"))
                                .required(true)
                                .executionOrder(1)
                                .stageCode("EXTRACT")
                                .build()
                ))
                .build();

        assertDoesNotThrow(() -> validator.validateForCreation(plan));
    }

    @Test
    void shouldRejectMissingDependency() {
        WorkflowPlan plan = WorkflowPlan.builder()
                .contractType("TASK_PLAN_PREVIEW_V1")
                .stages(List.of(
                        WorkflowPlan.WorkflowPlanStage.builder()
                                .stageCode("EXTRACT")
                                .title("Extract")
                                .summary("summary")
                                .detail("detail")
                                .build()
                ))
                .nodes(List.of(
                        WorkflowPlan.WorkflowPlanNode.builder()
                                .nodeName("extract")
                                .displayName("Extract")
                                .agentType("EXTRACTOR")
                                .dependsOn(List.of("collect"))
                                .required(true)
                                .executionOrder(1)
                                .stageCode("EXTRACT")
                                .build()
                ))
                .build();

        assertThrows(BusinessException.class, () -> validator.validateForCreation(plan));
    }

    @Test
    void shouldRejectCyclicDependency() {
        WorkflowPlan plan = WorkflowPlan.builder()
                .contractType("TASK_PLAN_PREVIEW_V1")
                .stages(List.of(
                        WorkflowPlan.WorkflowPlanStage.builder()
                                .stageCode("SOURCE_STRATEGY")
                                .title("Source strategy")
                                .summary("summary")
                                .detail("detail")
                                .build(),
                        WorkflowPlan.WorkflowPlanStage.builder()
                                .stageCode("EXTRACT")
                                .title("Extract")
                                .summary("summary")
                                .detail("detail")
                                .build()
                ))
                .nodes(List.of(
                        WorkflowPlan.WorkflowPlanNode.builder()
                                .nodeName("collect")
                                .displayName("Collect")
                                .agentType("COLLECTOR")
                                .dependsOn(List.of("extract"))
                                .required(true)
                                .executionOrder(0)
                                .stageCode("SOURCE_STRATEGY")
                                .build(),
                        WorkflowPlan.WorkflowPlanNode.builder()
                                .nodeName("extract")
                                .displayName("Extract")
                                .agentType("EXTRACTOR")
                                .dependsOn(List.of("collect"))
                                .required(true)
                                .executionOrder(1)
                                .stageCode("EXTRACT")
                                .build()
                ))
                .build();

        assertThrows(BusinessException.class, () -> validator.validateForCreation(plan));
    }

    @Test
    void shouldRejectCreationPlanWhenFormalStagesAreMissing() {
        WorkflowPlan plan = WorkflowPlan.builder()
                .nodes(List.of(
                        WorkflowPlan.WorkflowPlanNode.builder()
                                .nodeName("collect")
                                .displayName("Collect")
                                .agentType("COLLECTOR")
                                .dependsOn(List.of())
                                .required(true)
                                .executionOrder(0)
                                .build()
                ))
                .build();

        assertThrows(BusinessException.class, () -> validator.validateForCreation(plan));
    }

    @Test
    void shouldAllowLegacySnapshotReuseWhenFormalStagesAreMissing() {
        WorkflowPlan plan = WorkflowPlan.builder()
                .nodes(List.of(
                        WorkflowPlan.WorkflowPlanNode.builder()
                                .nodeName("collect")
                                .displayName("Collect")
                                .agentType("COLLECTOR")
                                .dependsOn(List.of())
                                .required(true)
                                .executionOrder(0)
                                .build()
                ))
                .build();

        assertDoesNotThrow(() -> validator.validateForSnapshotReuse(plan));
    }
}
