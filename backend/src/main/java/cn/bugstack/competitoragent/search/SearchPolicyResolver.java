package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.agent.collector.CollectorNodeConfig;
import cn.bugstack.competitoragent.collection.WebPageRenderHint;
import cn.bugstack.competitoragent.workflow.coverage.FieldEvidenceQuery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 搜索策略解析器。
 * <p>
 * 规划期、预览期与运行期都通过这里解释正式搜索与 source family 策略，
 * 避免 fallback 顺序、候选家族语义、render hint 和直达路径模板继续散落在多个类里。
 */
@Component
public class SearchPolicyResolver {

    private static final long DEFAULT_SEARCH_TIMEOUT_MILLIS = 15000L;
    private static final long MIN_TIMEOUT_MILLIS = 1000L;
    private static final long FIELD_QUERY_BASE_TIMEOUT_MILLIS = 12000L;
    private static final long FIELD_QUERY_PER_QUERY_TIMEOUT_MILLIS = 6000L;
    private static final int DEFAULT_FIELD_QUERY_EXECUTABLE_QUOTA = 3;

    /**
     * resolver 既会被 Spring 注入，也会在测试里直接 new。
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
     * 历史 HEURISTIC_ONLY 只作为兼容输入保留，正式执行统一收口为 HTTP 兜底阶段。
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
     * 这里同时处理：
     * 1. 把历史遗留的 HEURISTIC 阶段映射成正式 HTTP 兜底；
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
     * 最小验证数既要尊重节点显式配置，也不能超过当前计划目标数量。
     */
    public int resolveMinVerifiedCandidates(Integer configuredValue, int plannedUrlCount, int targetCount) {
        if (configuredValue != null && configuredValue > 0) {
            return Math.min(configuredValue, Math.max(1, targetCount));
        }
        return Math.min(2, Math.max(1, Math.min(plannedUrlCount, Math.max(1, targetCount))));
    }

    /**
     * 目标数量优先取显式上限，其次复用规划期 URL 数量，最后再回退到候选数量兜底。
     */
    public int resolveTargetCount(Integer configuredMaxSearchResults,
                                  List<String> plannedUrls,
                                  int candidateCount) {
        int plannedUrlCount = plannedUrls == null ? 0 : plannedUrls.size();
        if (plannedUrlCount > 0) {
            // competitorUrls 是用户显式输入的采集目标，maxSearchResults 只限制补源/搜索结果，不能裁掉显式 URL。
            return plannedUrlCount;
        }
        if (configuredMaxSearchResults != null && configuredMaxSearchResults > 0) {
            return Math.max(1, configuredMaxSearchResults);
        }
        return Math.max(1, candidateCount);
    }

    public int resolveBootstrapCandidateLimit(SearchRuntimePolicy policy, int targetCount) {
        if (policy != null && policy.getBootstrapCandidateLimit() != null && policy.getBootstrapCandidateLimit() > 0) {
            return policy.getBootstrapCandidateLimit();
        }
        return Math.max(6, Math.max(1, targetCount) * 2);
    }

    public int resolveSupplementCandidateLimit(SearchRuntimePolicy policy, int targetCount) {
        if (policy != null && policy.getSupplementCandidateLimit() != null && policy.getSupplementCandidateLimit() > 0) {
            return policy.getSupplementCandidateLimit();
        }
        return Math.max(6, Math.max(1, targetCount) * 2);
    }

    public int resolveMaxCandidatePoolSize(SearchRuntimePolicy policy, int targetCount) {
        if (policy != null && policy.getMaxCandidatePoolSize() != null && policy.getMaxCandidatePoolSize() > 0) {
            return policy.getMaxCandidatePoolSize();
        }
        return Math.max(10, Math.max(1, targetCount) * 3);
    }

    public int resolveMaxCandidatesPerDomain(SearchRuntimePolicy policy) {
        if (policy != null && policy.getMaxCandidatesPerDomain() != null && policy.getMaxCandidatesPerDomain() > 0) {
            return policy.getMaxCandidatesPerDomain();
        }
        return 2;
    }

    /**
     * search-first 场景下，正式采集目标数不能再直接等于用户显式 URL 数量。
     * 这里显式拆开“用户给了几个锚点”和“系统最终要保留几条正式证据”两个概念。
     */
    public int resolveEffectiveTargetCountForSearchFirst(CollectorNodeConfig config,
                                                         int baseTargetCount,
                                                         int fusedCandidateCount) {
        if (!shouldApplySearchFirstExpansionPolicy(config)) {
            return Math.max(0, baseTargetCount);
        }
        if (fusedCandidateCount <= 0) {
            return 0;
        }
        SearchRuntimePolicy runtimePolicy = config.getSearchRuntimePolicy();
        int floor = runtimePolicy != null
                && runtimePolicy.getSearchFirstEvidenceTargetFloor() != null
                && runtimePolicy.getSearchFirstEvidenceTargetFloor() > 0
                ? runtimePolicy.getSearchFirstEvidenceTargetFloor()
                : 3;
        int ceiling = runtimePolicy != null
                && runtimePolicy.getSearchFirstEvidenceTargetCeiling() != null
                && runtimePolicy.getSearchFirstEvidenceTargetCeiling() > 0
                ? runtimePolicy.getSearchFirstEvidenceTargetCeiling()
                : 5;
        ceiling = Math.max(floor, ceiling);
        int candidateBound = Math.max(baseTargetCount, Math.min(fusedCandidateCount, ceiling));
        int resolved = Math.max(baseTargetCount, Math.min(ceiling, Math.max(floor, candidateBound)));
        return Math.min(fusedCandidateCount, resolved);
    }

    /**
     * 预选验证预算只控制“还需要网页验证的候选数”，
     * 已通过 fast lane gate 的正文候选不会再占用这份预算。
     */
    public int resolvePreSelectionVerificationLimit(CollectorNodeConfig config,
                                                    int effectiveTargetCount) {
        SearchRuntimePolicy runtimePolicy = config == null ? null : config.getSearchRuntimePolicy();
        if (runtimePolicy != null
                && runtimePolicy.getPreSelectionVerificationLimit() != null
                && runtimePolicy.getPreSelectionVerificationLimit() > 0) {
            return runtimePolicy.getPreSelectionVerificationLimit();
        }
        if (shouldApplySearchFirstExpansionPolicy(config)) {
            return Math.max(effectiveTargetCount, 3);
        }
        return Math.max(1, effectiveTargetCount);
    }

    /**
     * search-first 的扩目标数与预验证预算只应该作用在“仅提供 competitorUrls、由系统展开 seed”的新主路径。
     * 如果节点已经显式给出 sourceCandidates，说明它走的是旧的规划/回放语义，此时不能再把 target floor
     * 和强制搜索优先策略叠加上去，否则会把本来只需要一个正式目标的显式链路意外放大。
     */
    public boolean shouldApplySearchFirstExpansionPolicy(CollectorNodeConfig config) {
        return config != null
                && isSearchFirstSourceFamilyForSourceType(config.getSourceType())
                && config.getCompetitorUrls() != null
                && !config.getCompetitorUrls().isEmpty()
                && (config.getSourceCandidates() == null || config.getSourceCandidates().isEmpty());
    }

    /**
     * 搜索超时优先使用节点显式配置；未配置时按执行计划预计时长折算搜索预算。
     */
    public long resolveSearchTimeoutMillis(Long configuredValue, SearchExecutionPlan executionPlan) {
        if (configuredValue != null && configuredValue >= 0) {
            return configuredValue;
        }
        long expectedNodeDuration = executionPlan == null || executionPlan.getSteps() == null
                ? 0L
                : executionPlan.getSteps().stream().mapToLong(SearchExecutionStep::getExpectedDurationMs).sum();
        if (expectedNodeDuration <= 0L) {
            return DEFAULT_SEARCH_TIMEOUT_MILLIS;
        }
        return Math.max(MIN_TIMEOUT_MILLIS, Math.round(expectedNodeDuration * 0.6D));
    }

    /**
     * 字段证据 query 是补源阶段的主力取证手段，若节点显式超时预算过短，
     * 会在进入 HTTP stage 之前就被 Playwright 壳页或前置验证耗光。
     * 这里为 pending field query 场景提供一个最小预算兜底，避免 query 根本发不出去。
     */
    public long ensureMinimumTimeoutForFieldEvidenceQueries(long baseTimeoutMillis,
                                                            List<FieldEvidenceQuery> fieldEvidenceQueries) {
        if (fieldEvidenceQueries == null || fieldEvidenceQueries.isEmpty()) {
            return baseTimeoutMillis;
        }
        long minimumTimeoutMillis = FIELD_QUERY_BASE_TIMEOUT_MILLIS
                + (long) fieldEvidenceQueries.size() * FIELD_QUERY_PER_QUERY_TIMEOUT_MILLIS;
        return Math.max(baseTimeoutMillis, minimumTimeoutMillis);
    }

    /**
     * @deprecated 字段 query 配额已不再由 coordinator 做数量截断。
     * 实际执行截断完全交给 provider 的 per-query deadline 熔断负责，
     * 调用方应直接透传全量 planned queries，不再需要此方法。
     */
    @Deprecated
    public int resolveExecutableFieldEvidenceQueryQuota(long baseSearchTimeoutMillis) {
        return DEFAULT_FIELD_QUERY_EXECUTABLE_QUOTA;
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
     * 现有 provider 在首轮架构里先被正式归类为 public supplement 能力，
     * 避免把 qianfan / serpapi / browser / http 混写成业务家族本身。
     */
    public SearchProviderRole resolveProviderRole(String providerKey) {
        if (!StringUtils.hasText(providerKey)) {
            return SearchProviderRole.AUXILIARY_PUBLIC;
        }
        String normalized = providerKey.trim().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, SearchSourceCatalogProperties.SourceFamilyProperties> entry
                : resolveSourceCatalog().getFamilies().entrySet()) {
            SearchSourceCatalogProperties.SourceFamilyProperties family = entry.getValue();
            if (family == null) {
                continue;
            }
            if (family.resolveProviderKeys(SearchProviderRole.PRIMARY_VERTICAL).stream()
                    .anyMatch(bound -> normalized.equalsIgnoreCase(bound))) {
                return SearchProviderRole.PRIMARY_VERTICAL;
            }
        }
        return SearchProviderRole.AUXILIARY_PUBLIC;
    }

    /**
     * 按当前请求的 source family 判断 provider 角色。
     * 同一个 provider 可能在 official 家族是主取证入口，但在 GitHub/News 请求里只是公网补源，不能用全局角色误导路由短路。
     */
    public SearchProviderRole resolveProviderRoleForRequestedScopes(String providerKey, List<String> requestedScopes) {
        if (!StringUtils.hasText(providerKey) || requestedScopes == null || requestedScopes.isEmpty()) {
            return resolveProviderRole(providerKey);
        }
        String normalized = providerKey.trim().toLowerCase(Locale.ROOT);
        for (String requestedScope : requestedScopes) {
            SearchSourceCatalogProperties.SourceFamilyProperties family = resolveSourceFamilyForSourceType(requestedScope);
            if (family == null) {
                continue;
            }
            if (family.resolveProviderKeys(SearchProviderRole.PRIMARY_VERTICAL).stream()
                    .anyMatch(bound -> normalized.equalsIgnoreCase(bound))) {
                return SearchProviderRole.PRIMARY_VERTICAL;
            }
        }
        return SearchProviderRole.AUXILIARY_PUBLIC;
    }

    /**
     * 根据业务 sourceType 反查数据源家族 key。
     * preview、runtime、replay 都依赖这套解释，避免同一 sourceType 在不同阶段被打上不同家族语义。
     */
    public String resolveSourceFamilyKeyForSourceType(String sourceType) {
        if (!StringUtils.hasText(sourceType)) {
            return "official";
        }
        String normalizedSourceType = sourceType.trim().toUpperCase(Locale.ROOT);
        for (Map.Entry<String, SearchSourceCatalogProperties.SourceFamilyProperties> entry
                : resolveSourceCatalog().getFamilies().entrySet()) {
            SearchSourceCatalogProperties.SourceFamilyProperties family = entry.getValue();
            if (family != null && family.getSourceTypes() != null
                    && family.getSourceTypes().stream().anyMatch(type -> normalizedSourceType.equalsIgnoreCase(type))) {
                return entry.getKey();
            }
        }
        return "official";
    }

    /**
     * 根据业务 sourceType 反查完整家族配置。
     * 找不到显式配置时回退到 official，保证调用方仍能拿到稳定的工具与 query template 语义。
     */
    public SearchSourceCatalogProperties.SourceFamilyProperties resolveSourceFamilyForSourceType(String sourceType) {
        return resolveSourceCatalog().resolveFamily(resolveSourceFamilyKeyForSourceType(sourceType));
    }

    /**
     * 判断业务来源家族是否已经切换为“搜索优先”。
     * 搜索优先时，直连 discovery 只能作为 seed/ranking 信号，不能再短路公网搜索补源。
     */
    public boolean isSearchFirstSourceFamilyForSourceType(String sourceType) {
        SearchSourceCatalogProperties.SourceFamilyProperties family = resolveSourceFamilyForSourceType(sourceType);
        return family != null
                && family.getPrimaryTools() != null
                && family.getPrimaryTools().stream().anyMatch("PUBLIC_SEARCH"::equalsIgnoreCase);
    }

    /**
     * “采什么”由 Source Family Catalog 统一解释。
     * 若家族角色缺失或非法，则安全回退为辅助公网页角色，避免解析异常打断主链路。
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

    /**
     * 统一供 Source Family Catalog 解析工具绑定到的 provider key。
     * 这样后续路由器不需要自己理解家族配置内部结构。
     */
    public List<String> resolveProviderKeysForSourceFamily(String familyKey, SearchProviderRole role) {
        SearchSourceCatalogProperties.SourceFamilyProperties family = resolveSourceCatalog().resolveFamily(familyKey);
        if (family == null) {
            return List.of();
        }
        return family.resolveProviderKeys(role);
    }

    /**
     * 统一把 source family 的网页采集偏好翻译成正式 render hint。
     * 找不到家族配置时回退到 LIGHTWEIGHT，保证网页路径有稳定默认语义。
     */
    public WebPageRenderHint resolveWebRenderHint(String sourceFamilyKey, String sourceType) {
        SearchSourceCatalogProperties.SourceFamilyProperties family = resolveSourceCatalog().resolveFamily(sourceFamilyKey);
        if (family == null) {
            return WebPageRenderHint.LIGHTWEIGHT;
        }
        return WebPageRenderHint.valueOf(family.resolvePreferredWebRenderHint());
    }

    /**
     * 统一解析网页结构块预期。
     * 调用方只消费标准化后的块类型列表，不需要自己理解 catalog 内部结构。
     */
    public List<String> resolveExpectedBlockTypes(String sourceFamilyKey, String sourceType) {
        SearchSourceCatalogProperties.SourceFamilyProperties family = resolveSourceCatalog().resolveFamily(sourceFamilyKey);
        return family == null || family.getExpectedBlockTypes() == null
                ? List.of()
                : family.getExpectedBlockTypes();
    }

    /**
     * 统一解析 source family 的直达路径模板。
     * planner、preview 与 runtime 都只消费这里的输出，避免 /pricing、/docs 推断规则散落多处。
     */
    public List<String> resolveDirectPathTemplates(String familyKey) {
        SearchSourceCatalogProperties.SourceFamilyProperties family = resolveSourceCatalog().resolveFamily(familyKey);
        return family == null || family.getDirectPathTemplates() == null
                ? List.of()
                : family.getDirectPathTemplates().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
    }

    /**
     * 统一解析 source family 的直达子域模板。
     * planner、preview 与 runtime 都只消费这里的输出，避免 docs/open/developer/help 的扩展逻辑散落多处。
     */
    public List<String> resolveDirectSubdomainTemplates(String familyKey) {
        SearchSourceCatalogProperties.SourceFamilyProperties family = resolveSourceCatalog().resolveFamily(familyKey);
        return family == null || family.getDirectSubdomainTemplates() == null
                ? List.of()
                : family.getDirectSubdomainTemplates().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
    }

    /**
     * 判断某个 URL 或 locator 是否已经足够稳定，可以直接进入该 source family 的正式 owner 路径。
     * 这一层只回答“它是不是稳定 locator”，不负责生成 direct candidate。
     */
    public boolean isStableLocatorForSourceFamily(String familyKey, String rawUrl) {
        if (!StringUtils.hasText(familyKey) || !StringUtils.hasText(rawUrl)) {
            return false;
        }
        SearchSourceCatalogProperties.SourceFamilyProperties family = resolveSourceCatalog().resolveFamily(familyKey);
        if (family == null) {
            return false;
        }
        try {
            URI uri = URI.create(rawUrl.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().trim().toLowerCase(Locale.ROOT);
            List<String> allowedSchemes = family.getStableLocatorSchemes() == null
                    ? List.of()
                    : family.getStableLocatorSchemes().stream()
                    .filter(StringUtils::hasText)
                    .map(value -> value.trim().toLowerCase(Locale.ROOT))
                    .toList();
            if (!allowedSchemes.isEmpty() && !allowedSchemes.contains(scheme)) {
                return false;
            }
            if ("github".equals(scheme)) {
                return StringUtils.hasText(uri.getHost())
                        && uri.getPath() != null
                        && uri.getPath().split("/").length >= 3;
            }
            if (!StringUtils.hasText(uri.getHost())) {
                return false;
            }
            List<String> allowedHosts = family.getStableLocatorHosts() == null
                    ? List.of()
                    : family.getStableLocatorHosts().stream()
                    .filter(StringUtils::hasText)
                    .map(value -> value.trim().toLowerCase(Locale.ROOT))
                    .toList();
            if (allowedHosts.isEmpty()) {
                return true;
            }
            return allowedHosts.contains(uri.getHost().trim().toLowerCase(Locale.ROOT));
        } catch (Exception exception) {
            return false;
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
