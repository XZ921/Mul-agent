package cn.bugstack.competitoragent.search;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

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
    private Boolean resumedFromCheckpoint;
    private String checkpointSource;
    private LocalDateTime generatedAt;
}
