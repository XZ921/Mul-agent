package cn.bugstack.competitoragent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务回放时间线事件 DTO。
 * <p>
 * 该对象专门承载“用户可理解的回放时间线项”，
 * 先明确计划版本、节点、摘要与 sourceUrls 等正式字段，
 * 后续 5.6.b 再由统一投影服务负责填充。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "任务回放时间线事件")
public class ReplayTimelineEvent {

    @Schema(description = "回放事件唯一标识", example = "evt-1001")
    private String eventId;

    @Schema(description = "所属任务 ID", example = "1001")
    private Long taskId;

    @Schema(description = "关联计划版本 ID", example = "12")
    private Long planVersionId;

    @Schema(description = "关联计划版本号", example = "3")
    private Integer planVersion;

    @Schema(description = "分支标识", example = "root/review-3")
    private String branchKey;

    @Schema(description = "关联节点名称", example = "quality_check")
    private String nodeName;

    @Schema(description = "回放事件类型", example = "NODE_COMPLETED")
    private String eventType;

    @Schema(description = "用户可读摘要")
    private String summary;

    @Schema(description = "发生时间")
    private LocalDateTime occurredAt;

    @Schema(description = "证据 / 追溯来源地址")
    private List<String> sourceUrls;
}
