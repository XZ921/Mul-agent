package cn.bugstack.competitoragent.model.dto;

import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Agent 执行日志响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Agent 执行日志")
public class AgentLogResponse {

    @Schema(description = "日志 ID", example = "100")
    private Long id;

    @Schema(description = "所属任务 ID", example = "1")
    private Long taskId;

    @Schema(description = "所属节点 ID", example = "10")
    private Long nodeId;

    @Schema(description = "Agent 类型", example = "COLLECTOR")
    private AgentType agentType;

    @Schema(description = "Agent 实例名称", example = "采集Agent-1")
    private String agentName;

    @Schema(description = "执行状态", example = "SUCCESS")
    private TaskNodeStatus status;

    @Schema(description = "LLM 模型", example = "claude-sonnet-4-6")
    private String modelName;

    @Schema(description = "执行耗时(毫秒)", example = "15200")
    private Long durationMs;

    @Schema(description = "推理摘要")
    private String reasoningSummary;

    /**
     * 完整的 prompt 内容，仅在详情查询时返回
     */
    @Schema(description = "使用的 Prompt 内容")
    private String promptUsed;

    /**
     * 输入数据，仅在详情查询时返回
     */
    @Schema(description = "Agent 输入数据")
    private String inputData;

    /**
     * 输出数据，仅在详情查询时返回
     */
    @Schema(description = "Agent 输出数据")
    private String outputData;

    @Schema(description = "Token 用量")
    private String tokenUsage;

    @Schema(description = "错误信息")
    private String errorMessage;

    @Schema(description = "追踪 ID")
    private String traceId;

    @Schema(description = "是否需要人工介入", example = "false")
    private boolean needsHumanIntervention;

    @Schema(description = "执行时间")
    private LocalDateTime createdAt;
}
