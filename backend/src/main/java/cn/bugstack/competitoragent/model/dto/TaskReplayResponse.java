package cn.bugstack.competitoragent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 任务回放平台正式响应 DTO。
 * <p>
 * Task 5.6.a 先只定义稳定承载对象，不在这里提前耦合具体投影算法；
 * 这样 5.6.b 可以直接围绕该 DTO 继续补齐查询与控制接口。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "任务回放正式响应")
public class TaskReplayResponse {

    @Schema(description = "任务 ID", example = "1001")
    private Long taskId;

    @Schema(description = "当前激活计划版本 ID", example = "12")
    private Long currentPlanVersionId;

    @Schema(description = "回放时间线")
    private List<ReplayTimelineEvent> timeline;

    @Schema(description = "节点摘要列表")
    private List<ReplayNodeSummary> nodeSummaries;

    @Schema(description = "任务级恢复建议")
    private TaskRecoveryAdvice recoveryAdvice;

    @Schema(description = "恢复点列表")
    private List<RecoveryCheckpointResponse> recoveryCheckpoints;

    @Schema(description = "计划版本摘要列表")
    private List<ReplayPlanVersionSummary> planVersions;

    @Schema(description = "涓哄璇濆姩浣滃洖鏀句笌姝ｅ紡瀵煎嚭棰勭暀鐨勭ǔ瀹氭帴鍏ョ偣")
    private List<ReplayIntegrationEntryPoint> integrationEntryPoints;

    @Schema(description = "回放整体证据 / 追溯来源地址")
    private List<String> sourceUrls;
}
