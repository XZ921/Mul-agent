package cn.bugstack.competitoragent.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import cn.bugstack.competitoragent.search.SearchAuditSnapshot;
import cn.bugstack.competitoragent.search.SearchExecutionTrace;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 搜索审计轻量摘要。
 * <p>
 * 该对象面向报告、洞察和恢复入口，只保留计数、结论和 sourceUrls，
 * 避免下游再次绑定完整候选池、执行计划和页面正文。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SearchAuditSummary {

    private Integer candidateCount;
    private Integer selectedCount;
    private Integer discardedCount;
    private Integer attemptedCount;
    private Boolean degraded;
    private String degradationReason;
    private String fallbackDecision;
    private String recoveryCheckpoint;
    private List<String> sourceUrls;

    /**
     * 从正式审计快照中提取轻量摘要。
     * 这里显式兜底空列表和空计数，保证下游 DTO 不再因为 null 判空而重新回读大对象。
     */
    public static SearchAuditSummary from(SearchAuditSnapshot snapshot) {
        if (snapshot == null) {
            return SearchAuditSummary.builder()
                    .candidateCount(0)
                    .selectedCount(0)
                    .discardedCount(0)
                    .attemptedCount(0)
                    .sourceUrls(List.of())
                    .build();
        }
        SearchExecutionTrace trace = snapshot.getExecutionTrace();
        return SearchAuditSummary.builder()
                .candidateCount(size(snapshot.getSourceCandidates()))
                .selectedCount(size(snapshot.getSelectedTargets()))
                .discardedCount(size(snapshot.getDiscardedCandidates()))
                .attemptedCount(size(snapshot.getAttemptedTargets()))
                .degraded(trace == null ? null : trace.getDegraded())
                .degradationReason(trace == null ? null : trace.getDegradationReason())
                .fallbackDecision(trace == null ? null : trace.getFallbackDecision())
                .recoveryCheckpoint(trace == null ? null : trace.getRecoveryCheckpoint())
                .sourceUrls(snapshot.getSourceUrls() == null ? List.of() : snapshot.getSourceUrls())
                .build();
    }

    private static int size(List<?> values) {
        return values == null ? 0 : values.size();
    }
}
