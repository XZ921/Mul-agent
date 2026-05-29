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
                .nodes(List.of(
                        WorkflowPlan.WorkflowPlanNode.builder()
                                .nodeName("collect")
                                .displayName("Collect")
                                .agentType("COLLECTOR")
                                .dependsOn(List.of())
                                .required(true)
                                .executionOrder(0)
                                .build(),
                        WorkflowPlan.WorkflowPlanNode.builder()
                                .nodeName("extract")
                                .displayName("Extract")
                                .agentType("EXTRACTOR")
                                .dependsOn(List.of("collect"))
                                .required(true)
                                .executionOrder(1)
                                .build()
                ))
                .build();

        assertDoesNotThrow(() -> validator.validate(plan));
    }

    @Test
    void shouldRejectMissingDependency() {
        WorkflowPlan plan = WorkflowPlan.builder()
                .nodes(List.of(
                        WorkflowPlan.WorkflowPlanNode.builder()
                                .nodeName("extract")
                                .displayName("Extract")
                                .agentType("EXTRACTOR")
                                .dependsOn(List.of("collect"))
                                .required(true)
                                .executionOrder(1)
                                .build()
                ))
                .build();

        assertThrows(BusinessException.class, () -> validator.validate(plan));
    }

    @Test
    void shouldRejectCyclicDependency() {
        WorkflowPlan plan = WorkflowPlan.builder()
                .nodes(List.of(
                        WorkflowPlan.WorkflowPlanNode.builder()
                                .nodeName("collect")
                                .displayName("Collect")
                                .agentType("COLLECTOR")
                                .dependsOn(List.of("extract"))
                                .required(true)
                                .executionOrder(0)
                                .build(),
                        WorkflowPlan.WorkflowPlanNode.builder()
                                .nodeName("extract")
                                .displayName("Extract")
                                .agentType("EXTRACTOR")
                                .dependsOn(List.of("collect"))
                                .required(true)
                                .executionOrder(1)
                                .build()
                ))
                .build();

        assertThrows(BusinessException.class, () -> validator.validate(plan));
    }
}
