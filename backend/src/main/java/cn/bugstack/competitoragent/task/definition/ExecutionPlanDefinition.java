package cn.bugstack.competitoragent.task.definition;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * 执行计划定义是“任务如何运行”的正式业务合同。
 * Collector、Extractor、Writer、Reviewer 的节点只是它的技术投影，计划预览和运行时都必须沿用这份对象，而不是各自再拼装一遍。
 */
@Value
@Builder(toBuilder = true)
public class ExecutionPlanDefinition {
    String contractType;
    String goal;
    Integer competitorCount;
    Integer collectorCount;
    Integer pipelineCount;
    List<StageDefinition> stages;
    List<NodeDefinition> nodes;
    List<String> sourceUrls;

    @Value
    @Builder
    public static class StageDefinition {
        String stageCode;
        String title;
        String summary;
        String detail;
        List<String> sourceUrls;
    }

    @Value
    @Builder
    public static class NodeDefinition {
        String nodeName;
        String displayName;
        String agentType;
        String notes;
        String stageCode;
        String goal;
        String summary;
        List<String> dependsOn;
        boolean required;
        boolean allowFailedDependency;
        boolean retryable;
        int maxRetries;
        int executionOrder;
        String nodeConfig;
        List<String> fallbackOrder;
        List<String> sourceUrls;
    }
}
