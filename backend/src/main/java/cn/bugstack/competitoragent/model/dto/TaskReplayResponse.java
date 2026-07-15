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
 * 收口时间线、节点摘要、恢复建议和 Collector 现场回放视图。
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

    @Schema(description = "Collector 搜索现场回放")
    private List<SearchReplaySnapshotResponse> searchReplays;

    @Schema(description = "Collector 采集现场回放")
    private List<CollectionReplaySnapshotResponse> collectionReplays;

    @Schema(description = "回放集成入口")
    private List<ReplayIntegrationEntryPoint> integrationEntryPoints;

    @Schema(description = "最近一次协作决策摘要")
    private OrchestrationDecisionSummary latestOrchestrationDecision;

    /**
     * 最近一次编排周期审计按时间线事件顺序选择，即使 shadow-only 周期没有代表决策也必须保留。
     */
    @Schema(description = "最近一次完整协作决策周期审计")
    private OrchestrationDecisionAuditSummary latestOrchestrationDecisionAudit;

    @Schema(description = "回放整体证据 / 追溯来源地址")
    private List<String> sourceUrls;
}
