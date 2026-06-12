package cn.bugstack.competitoragent.search;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 搜索策略解析器。
 * <p>
 * 规划期、预览期与运行期都通过这里推导正式搜索策略，
 * 避免 fallback 顺序、目标数量、超时预算和搜索引擎解析继续散落在多处。
 */
@Component
public class SearchPolicyResolver {

    /**
     * resolver 既会被 Spring 注入，也会在测试里被直接 new。
     * 因此这里保留一份默认 catalog，保证没有容器参与时仍然能解释首轮家族语义。
     */
    private final SearchSourceCatalogProperties defaultSourceCatalog = new SearchSourceCatalogProperties();
    private SearchProperties searchProperties;

    @Autowired(required = false)
    public void setSearchProperties(SearchProperties searchProperties) {
        this.searchProperties = searchProperties;
    }

    /**
     * 根据搜索模式与浏览器能力推导正式 fallback 顺序。
     * 旧的 HEURISTIC_ONLY 只作为兼容输入保留，正式执行语义统一收口到 HTTP 兜底阶段。
     */
    public List<String> resolveFallbackOrder(String searchMode, boolean browserSearchEnabled) {
        String normalizedMode = searchMode == null ? "HYBRID" : searchMode.trim().toUpperCase(Locale.ROOT);
        List<String> baseOrder = switch (normalizedMode) {
            case "BROWSER_ONLY" -> List.of("PLANNED", "BROWSER");
            case "HTTP_ONLY" -> List.of("PLANNED", "HTTP");
            case "HEURISTIC_ONLY" -> List.of("PLANNED", "HTTP");
            default -> List.of("PLANNED", "BROWSER", "HTTP");
        };
        LinkedHashSet<String> resolvedOrder = new LinkedHashSet<>(baseOrder);
        if (!browserSearchEnabled) {
            resolvedOrder.remove("BROWSER");
        }
        return new ArrayList<>(resolvedOrder);
    }

    /**
     * 统一清洗显式配置的 fallback 顺序。
     * <p>
     * 这里会处理三件事：
     * 1. 把历史遗留的 HEURISTIC 阶段映射到正式 HTTP 兜底；
     * 2. 过滤掉当前 searchMode / browser 能力下不合法的阶段；
     * 3. 保证 PLANNED 始终作为正式链路的起点被保留。
     */
    public List<String> resolveFallbackOrder(String searchMode,
                                             boolean browserSearchEnabled,
                                             List<String> configuredOrder) {
        List<String> formalOrder = resolveFallbackOrder(searchMode, browserSearchEnabled);
        if (configuredOrder == null || configuredOrder.isEmpty()) {
            return formalOrder;
        }

        LinkedHashSet<String> resolvedOrder = new LinkedHashSet<>();
        if (formalOrder.contains("PLANNED")) {
            resolvedOrder.add("PLANNED");
        }
        for (String configuredStage : configuredOrder) {
            String normalizedStage = normalizeFallbackStage(configuredStage);
            if (formalOrder.contains(normalizedStage)) {
                resolvedOrder.add(normalizedStage);
            }
        }
        if (resolvedOrder.isEmpty()) {
            return formalOrder;
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
     * 目标数量优先取显式上限，其次复用规划期 URL 数量，最后再回退到候选数兜底。
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
     * 搜索超时优先使用节点显式配置；未配置时按执行计划预计时长折算出搜索预算。
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

    /**
     * 首轮只把现有 provider 正式归类为公网辅助能力，
     * 避免把 qianfan / serpapi / browser / http 误写成业务家族本身。
     */
    public SearchProviderRole resolveProviderRole(String providerKey) {
        if (!StringUtils.hasText(providerKey)) {
            return SearchProviderRole.AUXILIARY_PUBLIC;
        }
        return SearchProviderRole.AUXILIARY_PUBLIC;
    }

    /**
     * “采什么”由 Source Family Catalog 统一解释。
     * 若家族角色缺失或非法，则安全回退为辅助公网角色，避免解析异常打断主链路。
     */
    public SearchProviderRole resolveSourceFamilyRole(String familyKey) {
        SearchSourceCatalogProperties.SourceFamilyProperties family = resolveSourceCatalog().resolveFamily(familyKey);
        if (family == null || !StringUtils.hasText(family.getRole())) {
            return SearchProviderRole.AUXILIARY_PUBLIC;
        }
        try {
            return SearchProviderRole.valueOf(family.getRole().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return SearchProviderRole.AUXILIARY_PUBLIC;
        }
    }

    private String normalizeFallbackStage(String stage) {
        if (stage == null) {
            return "";
        }
        String normalized = stage.trim().toUpperCase(Locale.ROOT);
        if ("HEURISTIC".equals(normalized)) {
            return "HTTP";
        }
        return normalized;
    }

    private SearchSourceCatalogProperties resolveSourceCatalog() {
        if (searchProperties != null && searchProperties.getSourceCatalog() != null) {
            return searchProperties.getSourceCatalog();
        }
        return defaultSourceCatalog;
    }
}
