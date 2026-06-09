package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.Locale;

/**
 * 节点重试决策。
 * <p>
 * 统一收口以下问题：
 * 1. 失败属于哪一类；
 * 2. 当前是否还能自动重试；
 * 3. 应该进入 WAITING_RETRY、WAITING_INTERVENTION 还是 FAILED；
 * 4. 是否需要进入 DLQ 留痕。
 */
@Data
@Builder
@Schema(description = "节点重试决策")
public class NodeRetryDecision {

    private NodeFailureCategory failureCategory;
    private TaskNodeStatus nextStatus;
    private int nextRetryCount;
    private boolean retryPlanned;
    private boolean requiresManualIntervention;
    private boolean shouldEnterDlq;
    private boolean terminalFailure;
    private String userReadableSummary;

    /**
     * 为了让调用方直接以语义化方法读取“是否进入 DLQ”，
     * 这里显式补充访问器，避免受 Lombok 对布尔字段命名规则影响。
     */
    public boolean shouldEnterDlq() {
        return shouldEnterDlq;
    }

    /**
     * 统一暴露“是否需要人工介入”的语义化判断，
     * 避免上层服务直接依赖字段命名细节。
     */
    public boolean requiresManualIntervention() {
        return requiresManualIntervention;
    }

    public static NodeRetryDecision evaluate(TaskNode node, String errorMessage) {
        NodeFailureCategory category = classify(errorMessage);
        int currentRetryCount = node == null ? 0 : Math.max(0, node.getRetryCount());
        int maxRetries = node == null ? 0 : Math.max(0, node.getMaxRetries());
        boolean retryable = node != null && node.isRetryable() && category.isRetryable();
        boolean retryBudgetAvailable = currentRetryCount < maxRetries;

        if (retryable && retryBudgetAvailable) {
            int nextRetryCount = currentRetryCount + 1;
            return NodeRetryDecision.builder()
                    .failureCategory(category)
                    .nextStatus(TaskNodeStatus.WAITING_RETRY)
                    .nextRetryCount(nextRetryCount)
                    .retryPlanned(true)
                    .requiresManualIntervention(false)
                    .shouldEnterDlq(false)
                    .terminalFailure(false)
                    .userReadableSummary("节点执行失败，系统将按策略重试")
                    .build();
        }

        if (category.isCompensatable()) {
            return NodeRetryDecision.builder()
                    .failureCategory(category)
                    .nextStatus(TaskNodeStatus.WAITING_INTERVENTION)
                    .nextRetryCount(currentRetryCount)
                    .retryPlanned(false)
                    .requiresManualIntervention(true)
                    .shouldEnterDlq(true)
                    .terminalFailure(false)
                    .userReadableSummary("节点需要补偿或人工确认后才能继续")
                    .build();
        }

        if (category.isRequiresManualIntervention()) {
            return NodeRetryDecision.builder()
                    .failureCategory(category)
                    .nextStatus(TaskNodeStatus.WAITING_INTERVENTION)
                    .nextRetryCount(currentRetryCount)
                    .retryPlanned(false)
                    .requiresManualIntervention(true)
                    .shouldEnterDlq(true)
                    .terminalFailure(false)
                    .userReadableSummary("节点等待人工处理，不会继续自动推进")
                    .build();
        }

        boolean exhaustedRetryableFailure = retryable && !retryBudgetAvailable;
        if (exhaustedRetryableFailure) {
            return NodeRetryDecision.builder()
                    .failureCategory(category)
                    .nextStatus(TaskNodeStatus.WAITING_INTERVENTION)
                    .nextRetryCount(currentRetryCount)
                    .retryPlanned(false)
                    .requiresManualIntervention(true)
                    .shouldEnterDlq(true)
                    .terminalFailure(false)
                    .userReadableSummary("自动重试次数已耗尽，等待人工决定是否继续")
                    .build();
        }

        return NodeRetryDecision.builder()
                .failureCategory(category)
                .nextStatus(TaskNodeStatus.FAILED)
                .nextRetryCount(currentRetryCount)
                .retryPlanned(false)
                .requiresManualIntervention(false)
                .shouldEnterDlq(false)
                .terminalFailure(true)
                .userReadableSummary("节点失败且不满足自动重试条件")
                .build();
    }

    private static NodeFailureCategory classify(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return NodeFailureCategory.UNKNOWN;
        }
        String normalized = errorMessage.toLowerCase(Locale.ROOT);
        if (containsAny(normalized,
                "permission denied",
                "forbidden",
                "unauthorized",
                "manual",
                "requires human",
                "审批",
                "人工")) {
            return NodeFailureCategory.MANUAL_INTERVENTION_REQUIRED;
        }
        if (containsAny(normalized,
                "compensate",
                "compensation",
                "rollback",
                "补偿",
                "回滚")) {
            return NodeFailureCategory.COMPENSATABLE;
        }
        if (containsAny(normalized,
                "timeout",
                "timed out",
                "rate limit",
                "too many requests",
                "connection reset",
                "temporarily unavailable",
                "network",
                "5xx",
                "429")) {
            return NodeFailureCategory.TRANSIENT_INFRASTRUCTURE;
        }
        if (containsAny(normalized,
                "invalid",
                "malformed",
                "missing",
                "not found",
                "404",
                "illegal",
                "unsupported",
                "schema")) {
            return NodeFailureCategory.PERMANENT_BUSINESS;
        }
        return NodeFailureCategory.UNKNOWN;
    }

    private static boolean containsAny(String normalized, String... candidates) {
        if (normalized == null || candidates == null) {
            return false;
        }
        for (String candidate : candidates) {
            if (candidate != null && normalized.contains(candidate.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
