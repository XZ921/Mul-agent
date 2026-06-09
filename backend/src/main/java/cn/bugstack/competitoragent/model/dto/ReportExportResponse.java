package cn.bugstack.competitoragent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 正式导出记录响应 DTO。
 * <p>
 * 先向前端暴露“当前有哪些正式导出记录、版本号是多少、来源在哪里”，
 * 让后续交付中心可以在不依赖原始日志的情况下消费稳定结构。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "正式导出记录响应")
public class ReportExportResponse {

    @Schema(description = "导出记录 ID", example = "1")
    private Long id;

    @Schema(description = "所属任务 ID", example = "42")
    private Long taskId;

    @Schema(description = "正式导出版本号", example = "3")
    private Integer exportVersion;

    @Schema(description = "导出格式", example = "MARKDOWN")
    private String exportFormat;

    @Schema(description = "导出状态", example = "REGISTERED")
    private String exportStatus;

    @Schema(description = "导出摘要")
    private String exportSummary;

    @Schema(description = "可回溯来源链接")
    private List<String> sourceUrls;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
