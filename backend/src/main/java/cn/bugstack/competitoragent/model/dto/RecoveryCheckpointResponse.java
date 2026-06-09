package cn.bugstack.competitoragent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 恢复点响应 DTO。
 * <p>
 * 该对象用于在回放 / 恢复平台中说明“可以从哪里恢复、对应哪个计划版本、为什么可恢复”。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "任务恢复点摘要")
public class RecoveryCheckpointResponse {

    @Schema(description = "恢复点主键", example = "1")
    private Long id;

    @Schema(description = "所属任务 ID", example = "1001")
    private Long taskId;

    @Schema(description = "关联计划版本 ID", example = "12")
    private Long planVersionId;

    @Schema(description = "关联计划版本号", example = "3")
    private Integer planVersion;

    @Schema(description = "恢复点唯一业务键", example = "task-1001-quality-check-1")
    private String checkpointKey;

    @Schema(description = "恢复点类型", example = "NODE_SUCCESS")
    private String checkpointType;

    @Schema(description = "关联节点名", example = "quality_check")
    private String nodeName;

    @Schema(description = "恢复点摘要")
    private String summary;

    @Schema(description = "恢复快照载荷")
    private String payloadSnapshot;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "证据 / 追溯来源地址")
    private List<String> sourceUrls;
}
