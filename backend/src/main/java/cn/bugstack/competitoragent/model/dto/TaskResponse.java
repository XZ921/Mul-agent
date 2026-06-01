package cn.bugstack.competitoragent.model.dto;

import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 任务列表 / 详情响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "任务信息")
public class TaskResponse {

    @Schema(description = "任务 ID", example = "1")
    private Long id;

    @Schema(description = "分析主题", example = "AI 知识库产品竞品分析")
    private String taskName;

    @Schema(description = "本方产品", example = "企业级 RAG 知识库平台")
    private String subjectProduct;

    @Schema(description = "竞品名称列表", example = "[\"Notion AI\",\"Glean\"]")
    private String competitorNames;

    @Schema(description = "竞品 URL 列表")
    private String competitorUrls;

    @Schema(description = "分析维度")
    private String analysisDimensions;

    @Schema(description = "信息源范围")
    private String sourceScope;

    @Schema(description = "任务状态", example = "RUNNING")
    private AnalysisTaskStatus status;

    @Schema(description = "失败原因")
    private String errorMessage;

    @Schema(description = "节点总数", example = "6")
    private int totalNodes;

    @Schema(description = "已完成节点数", example = "3")
    private int completedNodes;

    @Schema(description = "任务创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "最近更新时间")
    private LocalDateTime updatedAt;

    @Schema(description = "任务完成时间")
    private LocalDateTime completedAt;

    @Schema(description = "是否允许直接执行任务")
    private Boolean canExecute;

    @Schema(description = "是否允许基于检查点恢复任务")
    private Boolean canResume;

    @Schema(description = "是否允许整任务重置后重试")
    private Boolean canRetry;

    @Schema(description = "是否允许停止任务")
    private Boolean canStop;

    @Schema(description = "是否允许查看报告")
    private Boolean canViewReport;

    @Schema(description = "人工干预规则摘要")
    private String interventionSummary;
}
