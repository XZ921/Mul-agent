package cn.bugstack.competitoragent.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务图规划快照。
 * <p>
 * 它描述某一版任务图的拓扑结构，
 * 与持久化层的 TaskPlan 一起构成“版本事实 + 图结构快照”的最小骨架。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowPlan {

    @Builder.Default
    private Long planVersionId = null;

    @Builder.Default
    private int planVersion = 1;

    @Builder.Default
    private Long parentPlanVersionId = null;

    @Builder.Default
    private String branchKey = "root";

    @Builder.Default
    private boolean dynamicPlan = false;

    @Builder.Default
    private List<WorkflowPlanNode> nodes = new ArrayList<>();

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
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
        @Builder.Default
        private String branchKey = "root";
        @Builder.Default
        private boolean dynamicNode = false;
        private String originNodeName;
    }
}
