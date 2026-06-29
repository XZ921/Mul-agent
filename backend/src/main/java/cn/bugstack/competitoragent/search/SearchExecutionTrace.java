package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.search.tavily.TavilyFastLaneAudit;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 搜索执行轨迹。
 * <p>
 * 用强类型模型收口规划、动作、降级与恢复线索，替代零散 Map 字段。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SearchExecutionTrace {

    private String traceVersion;
    private String searchMode;
    private List<String> searchQueries;
    private List<String> fallbackOrder;
    private Integer plannedCandidateCount;
    private Integer attemptedCandidateCount;
    private Integer discardedCandidateCount;
    private Integer verifiedCandidateCount;
    private Integer supplementedCandidateCount;
    private Integer selectedCandidateCount;
    private Long candidateVerificationElapsedMillis;
    private Integer candidateVerificationConcurrency;
    private Integer candidateVerificationInputCount;
    private Integer candidateVerificationUniqueCount;
    private Integer candidateVerificationReusedPageCount;
    private Integer candidateVerificationDirectAttemptCount;
    private Integer candidateVerificationDirectUsableCount;
    private Integer candidateVerificationDirectShortcutCount;
    private List<String> selectedUrls;
    private String supplementMethod;
    private String browserSearchEngine;
    private String browserTraceId;
    private List<String> browserExecutedQueries;
    private String browserSearchSummary;
    private String browserFailureKind;
    private String browserRestartScope;
    private String browserFallbackAction;
    private List<String> browserMatchedSignals;
    private Boolean providerFallbackUsed;
    private Long searchTimeoutMillis;
    private Long searchElapsedMillis;
    private Boolean circuitBroken;
    private Boolean degraded;
    private String degradationReason;
    private SearchRuntimePolicy runtimePolicy;
    private String browserBlockedReason;
    private Integer browserBlockedCount;
    private String fallbackDecision;
    private String recoveryCheckpoint;
    private String recoveryAdvice;
    private Boolean publicEvidenceRecoveryTriggered;
    private List<String> publicEvidenceAttemptedUrls;
    private List<String> publicEvidenceAttemptedEvidencePaths;
    private String publicEvidenceRecoveryFieldName;
    private String publicEvidenceRecoveryEvidencePathKey;
    private List<String> publicEvidenceRecoveryQueryIntents;
    private Integer publicEvidenceRecoveryCandidateCount;
    private Integer publicEvidenceRecoveryVerifiedCount;
    private String publicEvidenceRecoveryStatus;
    /**
     * repair 生命周期的稳定审计投影。
     * 使用 Map 是为了让 replay/前端在不绑定内部值对象的情况下，也能识别统一状态词汇和 URL 列表。
     */
    private Map<String, Object> evidenceRepairPlan;
    /**
     * 把 Tavily 快速通道结果跟随搜索 trace 一起落盘，
     * 便于后续报告聚合和节点级别审计直接从 trace 读取。
     */
    private TavilyFastLaneAudit tavilyFastLaneAudit;
    private Boolean resumedFromCheckpoint;
    private String checkpointSource;
    private LocalDateTime generatedAt;
}
