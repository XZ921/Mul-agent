package cn.bugstack.competitoragent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 任务计划预览中的节点视图。
 * 这里只保留计划语义字段，避免把运行态控制字段混入创建页预览。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "任务计划预览节点")
public class TaskPlanPreviewNodeResponse {

    @Schema(description = "节点名称")
    private String nodeName;

    @Schema(description = "节点展示名称")
    private String displayName;

    @Schema(description = "Agent 类型")
    private String agentType;

    @Schema(description = "阶段代码")
    private String stageCode;

    @Schema(description = "节点目标")
    private String goal;

    @Schema(description = "节点摘要")
    private String summary;

    @Schema(description = "节点配置摘要")
    private TaskNodeConfigSummary configSummaryData;

    @Schema(description = "依赖节点")
    private List<String> dependsOn;

    @Schema(description = "是否为必需节点")
    private boolean required;

    @Schema(description = "执行顺序")
    private int executionOrder;

    @Schema(description = "失败回退顺序")
    private List<String> fallbackOrder;

    @Schema(description = "节点来源")
    private List<String> sourceUrls;
}
