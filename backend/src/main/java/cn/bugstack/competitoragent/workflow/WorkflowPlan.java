package cn.bugstack.competitoragent.workflow;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Dynamic workflow definition for a competitor analysis task.
 */
@Data
@Builder
public class WorkflowPlan {

    @Builder.Default
    private List<WorkflowPlanNode> nodes = new ArrayList<>();

    @Data
    @Builder
    public static class WorkflowPlanNode {
        private String nodeName;
        private String displayName;
        private String agentType;
        private List<String> dependsOn;
        private boolean required;
        private int executionOrder;
        private String nodeConfig;
        private String notes;
        @Builder.Default
        private boolean allowFailedDependency = false;
        @Builder.Default
        private boolean retryable = true;
        @Builder.Default
        private int maxRetries = 3;
    }
}
