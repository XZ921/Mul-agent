package cn.bugstack.competitoragent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 回放扩展接入点 DTO。
 * <p>
 * 这个对象只负责把“未来会接到统一回放里的能力边界”正式化，
 * 避免 Task 5.7 / Task 5.9 落地时再次改动主响应结构。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "任务回放扩展接入点")
public class ReplayIntegrationEntryPoint {

    @Schema(description = "接入点键", example = "CONVERSATION_ACTION_REPLAY")
    private String entryKey;

    @Schema(description = "接入点就绪状态", example = "RESERVED_FOR_TASK_5_9")
    private String readinessStatus;

    @Schema(description = "后续承接任务键", example = "Task 5.9")
    private String targetTaskKey;

    @Schema(description = "接入点说明")
    private String summary;

    @Schema(description = "接入点相关追溯来源")
    private List<String> sourceUrls;
}
