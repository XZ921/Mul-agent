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
    private Integer queriesSent;
    private Integer totalResults;
    private Integer fastLaneUsableCount;
    private Integer fastLaneRejectedCount;
    private Map<String, Integer> rejectionReasons;
    private Boolean bootstrapTriggered;
    private Boolean fallbackTriggered;
    private List<String> tavilyRequestIds;
    private Integer playwrightInvocationBaselineHint;

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
        LinkedHashSet<String> requestIds = new LinkedHashSet<>();
        LinkedHashMap<String, Integer> rejectionReasons = new LinkedHashMap<>();
        int queriesSent = 0;
        int totalResults = 0;
        int usableCount = 0;
        int rejectedCount = 0;
        int playwrightHint = 0;
        boolean bootstrapTriggered = false;
        boolean fallbackTriggered = false;
        boolean hasValue = false;

        for (TavilyFastLaneAudit audit : audits) {
            if (audit == null) {
                continue;
            }
            hasValue = true;
            appendDistinct(queryModes, audit.getQueryModes());
            appendDistinct(queryOrigins, audit.getQueryOrigins());
            appendDistinct(requestIds, audit.getTavilyRequestIds());
            mergeCounters(rejectionReasons, audit.getRejectionReasons());
            queriesSent += value(audit.getQueriesSent());
            totalResults += value(audit.getTotalResults());
            usableCount += value(audit.getFastLaneUsableCount());
            rejectedCount += value(audit.getFastLaneRejectedCount());
            playwrightHint += value(audit.getPlaywrightInvocationBaselineHint());
            bootstrapTriggered = bootstrapTriggered || Boolean.TRUE.equals(audit.getBootstrapTriggered());
            fallbackTriggered = fallbackTriggered || Boolean.TRUE.equals(audit.getFallbackTriggered());
        }

        if (!hasValue) {
            return null;
        }
        return TavilyFastLaneAudit.builder()
                .queryModes(new ArrayList<>(queryModes))
                .queryOrigins(new ArrayList<>(queryOrigins))
                .queriesSent(queriesSent)
                .totalResults(totalResults)
                .fastLaneUsableCount(usableCount)
                .fastLaneRejectedCount(rejectedCount)
                .rejectionReasons(rejectionReasons.isEmpty() ? Map.of() : rejectionReasons)
                .bootstrapTriggered(bootstrapTriggered)
                .fallbackTriggered(fallbackTriggered)
                .tavilyRequestIds(new ArrayList<>(requestIds))
                .playwrightInvocationBaselineHint(playwrightHint)
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

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }
}
