package cn.bugstack.competitoragent.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务计划快照。
 * 这一层既保存 DAG 拓扑，也增量承载“阶段、目标、回退顺序”等正式计划语义，
 * 让 preview、create、rerun、resume 都能围绕同一份计划真相工作。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowPlan {

    @Builder.Default
    private String contractType = null;

    @Builder.Default
    private String goal = null;

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
    private List<WorkflowPlanStage> stages = new ArrayList<>();

    @Builder.Default
    private List<WorkflowPlanNode> nodes = new ArrayList<>();

    /**
     * 正式阶段合同采用“合同类型 + 非空阶段列表”的显式判断，
     * 避免历史快照在未补齐新字段时被误判为完整的阶段语义快照。
     */
    public boolean hasFormalStageContract() {
        return StringUtils.hasText(contractType) && stages != null && !stages.isEmpty();
    }

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WorkflowPlanStage {
        private String stageCode;
        private String title;
        private String summary;
        private String detail;

        @Builder.Default
        private List<String> sourceUrls = new ArrayList<>();
    }

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
        private String stageCode;
        private String goal;
        private String summary;

        @Builder.Default
        private List<String> fallbackOrder = new ArrayList<>();

        @Builder.Default
        private List<String> sourceUrls = new ArrayList<>();

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
