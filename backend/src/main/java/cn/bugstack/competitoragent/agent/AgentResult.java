package cn.bugstack.competitoragent.agent;

import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import lombok.Builder;
import lombok.Data;

/**
 * Agent 执行结果。
 * Phase 1 先冻结编排层真正依赖的最小结果合同，
 * 让执行器只消费标准化状态、输出和观测信息，而不反向耦合具体业务 Agent 内部对象。
 */
@Data
@Builder
public class AgentResult {

    /** 执行状态，决定 DAG 是否继续推进。 */
    private TaskNodeStatus status;

    /** 输出数据，通常为 JSON 字符串，供下游节点继续消费。 */
    private String outputData;

    /** 输出摘要，主要用于任务节点概览和日志展示。 */
    private String outputSummary;

    /** 执行耗时，单位毫秒。 */
    private long durationMs;

    /** 模型推理摘要，可用于后续可观测性或人工审查。 */
    private String reasoningSummary;

    /** Token 使用量，按 JSON 字符串记录。 */
    private String tokenUsage;

    /** 实际调用的模型名称。 */
    private String modelName;

    /** 实际使用的 Prompt 内容，便于追查生成链路。 */
    private String promptUsed;

    /** 失败时的错误信息。 */
    private String errorMessage;

    public static AgentResult success(String outputData, String outputSummary) {
        return AgentResult.builder()
                .status(TaskNodeStatus.SUCCESS)
                .outputData(outputData)
                .outputSummary(outputSummary)
                .build();
    }

    public static AgentResult success(String outputData, String outputSummary,
                                      long durationMs, String modelName, String tokenUsage) {
        return AgentResult.builder()
                .status(TaskNodeStatus.SUCCESS)
                .outputData(outputData)
                .outputSummary(outputSummary)
                .durationMs(durationMs)
                .modelName(modelName)
                .tokenUsage(tokenUsage)
                .build();
    }

    public static AgentResult failed(String errorMessage) {
        return AgentResult.builder()
                .status(TaskNodeStatus.FAILED)
                .errorMessage(errorMessage)
                .outputSummary("执行失败: " + errorMessage)
                .build();
    }
}
