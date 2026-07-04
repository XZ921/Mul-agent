package cn.bugstack.competitoragent.search.tavily;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Tavily Fast Lane 审计摘要。
 * <p>
 * 该对象只保留查询模式、结果数量、拒绝原因和 requestId 等轻量元数据，
 * 明确禁止把 raw content 之类的大正文放进审计链路，避免 replay / report 对象膨胀。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TavilyFastLaneAudit {

    private List<String> queryModes;
    private List<String> queryOrigins;
    private List<String> requestedScopes;
    private List<String> effectiveScopes;
    private Integer queriesSent;
    private Integer totalResults;
    private Integer fastLaneUsableCount;
    private Integer fastLaneRejectedCount;
    private Map<String, Integer> rejectionReasons;
    private Boolean bootstrapTriggered;
    private Boolean fallbackTriggered;
    private List<String> tavilyRequestIds;
    private Integer playwrightInvocationBaselineHint;
    private Integer winnerRawFetchCount;
    private List<FieldEvidenceQueryExecutionAudit> fieldEvidenceQueryExecutions;
    private Map<String, Integer> fieldDistribution;
    private Map<String, Integer> sourceTypeDistribution;
    private Map<String, List<String>> scopeExpansionSources;

    /**
     * 把多个 collector 节点上的 Tavily 审计聚合成一个统一摘要，
     * 供报告页、交付视图和节点概览直接消费。
     */
    public static TavilyFastLaneAudit merge(List<TavilyFastLaneAudit> audits) {
        if (audits == null || audits.isEmpty()) {
            return null;
        }
        LinkedHashSet<String> queryModes = new LinkedHashSet<>();
        LinkedHashSet<String> queryOrigins = new LinkedHashSet<>();
        LinkedHashSet<String> requestedScopes = new LinkedHashSet<>();
        LinkedHashSet<String> effectiveScopes = new LinkedHashSet<>();
        LinkedHashSet<String> requestIds = new LinkedHashSet<>();
        LinkedHashMap<String, Integer> rejectionReasons = new LinkedHashMap<>();
        LinkedHashMap<String, LinkedHashSet<String>> scopeExpansionSources = new LinkedHashMap<>();
        List<FieldEvidenceQueryExecutionAudit> fieldEvidenceQueryExecutions = new ArrayList<>();
        int queriesSent = 0;
        int totalResults = 0;
        int usableCount = 0;
        int rejectedCount = 0;
        int playwrightHint = 0;
        int winnerRawFetchCount = 0;
        boolean bootstrapTriggered = false;
        boolean fallbackTriggered = false;
        boolean hasValue = false;
        LinkedHashMap<String, Integer> fieldDistribution = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> sourceTypeDistribution = new LinkedHashMap<>();

        for (TavilyFastLaneAudit audit : audits) {
            if (audit == null) {
                continue;
            }
            hasValue = true;
            appendDistinct(queryModes, audit.getQueryModes());
            appendDistinct(queryOrigins, audit.getQueryOrigins());
            appendDistinct(requestedScopes, audit.getRequestedScopes());
            appendDistinct(effectiveScopes, audit.getEffectiveScopes());
            appendDistinct(requestIds, audit.getTavilyRequestIds());
            if (audit.getFieldEvidenceQueryExecutions() != null) {
                fieldEvidenceQueryExecutions.addAll(audit.getFieldEvidenceQueryExecutions());
            }
            mergeCounters(rejectionReasons, audit.getRejectionReasons());
            mergeDistinctStringLists(scopeExpansionSources, audit.getScopeExpansionSources());
            queriesSent += value(audit.getQueriesSent());
            totalResults += value(audit.getTotalResults());
            usableCount += value(audit.getFastLaneUsableCount());
            rejectedCount += value(audit.getFastLaneRejectedCount());
            playwrightHint += value(audit.getPlaywrightInvocationBaselineHint());
            winnerRawFetchCount += value(audit.getWinnerRawFetchCount());
            bootstrapTriggered = bootstrapTriggered || Boolean.TRUE.equals(audit.getBootstrapTriggered());
            fallbackTriggered = fallbackTriggered || Boolean.TRUE.equals(audit.getFallbackTriggered());
            mergeCounters(fieldDistribution, audit.getFieldDistribution());
            mergeCounters(sourceTypeDistribution, audit.getSourceTypeDistribution());
        }

        if (!hasValue) {
            return null;
        }
        return TavilyFastLaneAudit.builder()
                .queryModes(new ArrayList<>(queryModes))
                .queryOrigins(new ArrayList<>(queryOrigins))
                .requestedScopes(new ArrayList<>(requestedScopes))
                .effectiveScopes(new ArrayList<>(effectiveScopes))
                .queriesSent(queriesSent)
                .totalResults(totalResults)
                .fastLaneUsableCount(usableCount)
                .fastLaneRejectedCount(rejectedCount)
                .rejectionReasons(rejectionReasons.isEmpty() ? Map.of() : rejectionReasons)
                .bootstrapTriggered(bootstrapTriggered)
                .fallbackTriggered(fallbackTriggered)
                .tavilyRequestIds(new ArrayList<>(requestIds))
                .playwrightInvocationBaselineHint(playwrightHint)
                .winnerRawFetchCount(winnerRawFetchCount)
                .fieldEvidenceQueryExecutions(fieldEvidenceQueryExecutions.isEmpty() ? List.of() : fieldEvidenceQueryExecutions)
                .fieldDistribution(fieldDistribution.isEmpty() ? Map.of() : fieldDistribution)
                .sourceTypeDistribution(sourceTypeDistribution.isEmpty() ? Map.of() : sourceTypeDistribution)
                .scopeExpansionSources(copyDistinctStringLists(scopeExpansionSources))
                .build();
    }

    private static void appendDistinct(LinkedHashSet<String> values, List<String> additions) {
        if (values == null || additions == null) {
            return;
        }
        for (String addition : additions) {
            if (StringUtils.hasText(addition)) {
                values.add(addition.trim());
            }
        }
    }

    private static void mergeCounters(Map<String, Integer> target, Map<String, Integer> additions) {
        if (target == null || additions == null) {
            return;
        }
        for (Map.Entry<String, Integer> entry : additions.entrySet()) {
            if (!StringUtils.hasText(entry.getKey())) {
                continue;
            }
            target.merge(entry.getKey().trim(), value(entry.getValue()), Integer::sum);
        }
    }

    private static void mergeDistinctStringLists(Map<String, LinkedHashSet<String>> target,
                                                 Map<String, List<String>> additions) {
        if (target == null || additions == null) {
            return;
        }
        for (Map.Entry<String, List<String>> entry : additions.entrySet()) {
            if (!StringUtils.hasText(entry.getKey())) {
                continue;
            }
            LinkedHashSet<String> values = target.computeIfAbsent(entry.getKey().trim(), ignored -> new LinkedHashSet<>());
            if (entry.getValue() == null) {
                continue;
            }
            for (String value : entry.getValue()) {
                if (StringUtils.hasText(value)) {
                    values.add(value.trim());
                }
            }
        }
    }

    private static Map<String, List<String>> copyDistinctStringLists(Map<String, LinkedHashSet<String>> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, List<String>> copied = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashSet<String>> entry : values.entrySet()) {
            if (!StringUtils.hasText(entry.getKey()) || entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            copied.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return copied.isEmpty() ? Map.of() : copied;
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }
}
