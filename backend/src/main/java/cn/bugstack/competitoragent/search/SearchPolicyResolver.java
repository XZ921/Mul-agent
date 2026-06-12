package cn.bugstack.competitoragent.search;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 搜索策略解析器。
 * <p>
 * 规划期与运行期都通过这里推导默认搜索策略，避免 fallback 顺序、
 * 目标数量、最小验证数和超时预算继续散落在多个协作者的私有分支里。
 */
@Component
public class SearchPolicyResolver {

    /**
     * 根据搜索模式与浏览器能力推导正式 fallback 顺序。
     * 如果浏览器搜索被关闭，会显式移除 BROWSER，避免运行期还保留无效阶段。
     */
    public List<String> resolveFallbackOrder(String searchMode, boolean browserSearchEnabled) {
        String normalizedMode = searchMode == null ? "HYBRID" : searchMode.trim().toUpperCase(Locale.ROOT);
        List<String> baseOrder = switch (normalizedMode) {
            case "BROWSER_ONLY" -> List.of("PLANNED", "BROWSER");
            case "HTTP_ONLY" -> List.of("PLANNED", "HTTP");
            case "HEURISTIC_ONLY" -> List.of("PLANNED", "HEURISTIC");
            default -> List.of("PLANNED", "BROWSER", "HEURISTIC", "HTTP");
        };
        LinkedHashSet<String> resolvedOrder = new LinkedHashSet<>(baseOrder);
        if (!browserSearchEnabled) {
            resolvedOrder.remove("BROWSER");
        }
        return new ArrayList<>(resolvedOrder);
    }

    /**
     * 最小验证数既要尊重节点显式配置，也不能超过当前计划要选出的目标数。
     */
    public int resolveMinVerifiedCandidates(Integer configuredValue, int plannedUrlCount, int targetCount) {
        if (configuredValue != null && configuredValue > 0) {
            return Math.min(configuredValue, Math.max(1, targetCount));
        }
        return Math.min(2, Math.max(1, Math.min(plannedUrlCount, Math.max(1, targetCount))));
    }

    /**
     * 目标数量优先取显式上限，其次复用规划期 URL 数量，最后再退回候选数兜底。
     */
    public int resolveTargetCount(Integer configuredMaxSearchResults,
                                  List<String> plannedUrls,
                                  int candidateCount) {
        int plannedUrlCount = plannedUrls == null ? 0 : plannedUrls.size();
        if (configuredMaxSearchResults != null && configuredMaxSearchResults > 0) {
            if (plannedUrlCount > 0) {
                return Math.min(configuredMaxSearchResults, plannedUrlCount);
            }
            return Math.max(1, configuredMaxSearchResults);
        }
        if (plannedUrlCount > 0) {
            return plannedUrlCount;
        }
        return Math.max(1, candidateCount);
    }

    /**
     * 搜索超时优先使用节点显式配置；未配置时按执行计划预估时长折算出搜索预算。
     */
    public long resolveSearchTimeoutMillis(Long configuredValue, SearchExecutionPlan executionPlan) {
        if (configuredValue != null && configuredValue >= 0) {
            return configuredValue;
        }
        long expectedNodeDuration = executionPlan == null || executionPlan.getSteps() == null
                ? 0L
                : executionPlan.getSteps().stream().mapToLong(SearchExecutionStep::getExpectedDurationMs).sum();
        if (expectedNodeDuration <= 0L) {
            return 15000L;
        }
        return Math.max(1000L, Math.round(expectedNodeDuration * 0.6D));
    }

    /**
     * 搜索引擎解析也统一走这里，避免调用方自己拼默认值或绕过可用引擎校验。
     */
    public String resolveSearchEngineKey(String requestedEngineKey, SearchEngineProperties searchEngineProperties) {
        if (searchEngineProperties == null) {
            return "duckduckgo";
        }
        String normalized = requestedEngineKey == null
                ? "duckduckgo"
                : searchEngineProperties.normalizeEngineKey(requestedEngineKey);
        return searchEngineProperties.resolveAvailableEngineKey(normalized);
    }
}
