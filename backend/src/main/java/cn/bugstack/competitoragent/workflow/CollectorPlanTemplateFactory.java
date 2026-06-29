package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.agent.collector.CollectorNodeConfig;
import cn.bugstack.competitoragent.config.CollectorProperties;
import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.search.SearchBrowserProperties;
import cn.bugstack.competitoragent.search.SearchExecutionPlan;
import cn.bugstack.competitoragent.search.SearchExecutionStep;
import cn.bugstack.competitoragent.search.SearchPolicyResolver;
import cn.bugstack.competitoragent.search.SearchProperties;
import cn.bugstack.competitoragent.search.SearchRuntimePolicy;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourcePlan;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Collector 计划模板工厂只负责“搜索与采集计划语义”的固化。
 * 一旦 preview/create 确认了 collector 节点配置，执行期就直接复用同一份模板，
 * 避免 fallback、补源步骤和运行时策略继续散落在 WorkflowFactory 里各自拼装。
 */
@Component
@RequiredArgsConstructor
public class CollectorPlanTemplateFactory {

    private final PromptTemplateService promptTemplateService;
    private final SearchBrowserProperties searchBrowserProperties;
    private final SearchProperties searchProperties;
    private final CollectorProperties collectorProperties;
    private final SearchPolicyResolver searchPolicyResolver;

    public CollectorNodeConfig createCollectorNodeConfig(String competitorName,
                                                         List<String> requestedScopes,
                                                         String schemaName,
                                                         SourcePlan sourcePlan) {
        String searchMode = resolveSearchMode();
        boolean browserEnabled = isBrowserSearchEnabledForMode(searchMode);
        int candidateCount = sourcePlan == null || sourcePlan.getCandidates() == null ? 0 : sourcePlan.getCandidates().size();
        List<String> searchQueries = buildDefaultSearchQueries(
                competitorName,
                sourcePlan == null ? null : sourcePlan.getSourceType(),
                sourcePlan == null ? List.of() : sourcePlan.getCandidates()
        );
        List<String> fallbackOrder = searchPolicyResolver.resolveFallbackOrder(searchMode, browserEnabled);
        int targetCount = searchPolicyResolver.resolveTargetCount(
                collectorProperties == null ? null : collectorProperties.getMaxPagesPerCompetitor(),
                sourcePlan == null ? List.of() : sourcePlan.getUrls(),
                candidateCount
        );
        int plannedUrlCount = sourcePlan == null || sourcePlan.getUrls() == null ? 0 : sourcePlan.getUrls().size();
        int minVerifiedCandidates = searchPolicyResolver.resolveMinVerifiedCandidates(null, plannedUrlCount, targetCount);
        SearchExecutionPlan executionPlan = buildDefaultSearchExecutionPlan(
                searchQueries,
                fallbackOrder,
                targetCount,
                minVerifiedCandidates
        );
        SearchRuntimePolicy runtimePolicy = buildDefaultSearchRuntimePolicy();
        long searchTimeoutMillis = searchPolicyResolver.resolveSearchTimeoutMillis(null, executionPlan);

        return CollectorNodeConfig.builder()
                .competitorName(competitorName)
                .competitorUrls(sourcePlan == null || sourcePlan.getUrls() == null ? List.of() : sourcePlan.getUrls())
                .sourceType(sourcePlan == null ? null : sourcePlan.getSourceType())
                .sourceFamilyKey(sourcePlan == null ? null : sourcePlan.getSourceFamilyKey())
                .sourceFamilyRole(sourcePlan == null ? null : sourcePlan.getSourceFamilyRole())
                .primaryTools(sourcePlan == null || sourcePlan.getPrimaryTools() == null ? List.of() : sourcePlan.getPrimaryTools())
                .auxiliaryTools(sourcePlan == null || sourcePlan.getAuxiliaryTools() == null ? List.of() : sourcePlan.getAuxiliaryTools())
                .queryTemplates(sourcePlan == null || sourcePlan.getQueryTemplates() == null ? List.of() : sourcePlan.getQueryTemplates())
                .sourceScope(requestedScopes)
                .schemaName(schemaName)
                .discoveryNotes(sourcePlan == null ? null : sourcePlan.getNotes())
                .sourceCandidates(sourcePlan == null || sourcePlan.getCandidates() == null ? List.of() : sourcePlan.getCandidates())
                .searchMode(searchMode)
                .searchQueries(searchQueries)
                .searchFallbackOrder(fallbackOrder)
                .verifyCandidates(Boolean.TRUE)
                .verifyResultPage(searchBrowserProperties.isVerifyResultPage())
                .minVerifiedCandidates(minVerifiedCandidates)
                .preferredDomains(buildPreferredDomains(sourcePlan == null ? List.of() : sourcePlan.getCandidates()))
                .blockedDomains(List.of())
                .browserSearchEnabled(browserEnabled)
                .maxSearchResults(targetCount)
                .searchTimeoutMillis(searchTimeoutMillis)
                .searchRuntimePolicy(runtimePolicy)
                .searchExecutionPlan(executionPlan)
                .build();
    }

    private SearchRuntimePolicy buildDefaultSearchRuntimePolicy() {
        List<String> defaultUserAgents = new ArrayList<>();
        if (collectorProperties != null && StringUtils.hasText(collectorProperties.getUserAgent())) {
            defaultUserAgents.add(collectorProperties.getUserAgent());
        }
        if (searchBrowserProperties.getUserAgents() != null) {
            for (String userAgent : searchBrowserProperties.getUserAgents()) {
                if (StringUtils.hasText(userAgent) && !defaultUserAgents.contains(userAgent)) {
                    defaultUserAgents.add(userAgent);
                }
            }
        }
        return SearchRuntimePolicy.builder()
                .verifyResultPage(searchBrowserProperties.isVerifyResultPage())
                .maxRetries(2)
                .minIntervalMillis(3000L)
                .maxSearchesPerTask(10)
                .pageTimeoutMillis(Math.max(1000, collectorProperties.getPageTimeoutSeconds() * 1000))
                .maxOpenResultPages(searchBrowserProperties.getMaxOpenResultPages())
                .resultPageTimeoutMillis(searchBrowserProperties.getResultPageTimeoutMillis())
                .maxContentLengthPerPage(searchBrowserProperties.getMaxContentLengthPerPage())
                .stealthEnabled(searchBrowserProperties.isStealthEnabled())
                .locale(searchBrowserProperties.getLocale())
                .timezoneId(searchBrowserProperties.getTimezoneId())
                .viewportWidth(searchBrowserProperties.getViewportWidth())
                .viewportHeight(searchBrowserProperties.getViewportHeight())
                .shortBodyThreshold(searchBrowserProperties.getShortBodyThreshold())
                .minimumPrimaryResultCount(searchBrowserProperties.getMinimumPrimaryResultCount())
                .suspectBlockedBodyThreshold(searchBrowserProperties.getSuspectBlockedBodyThreshold())
                .userAgents(defaultUserAgents)
                .blockedSignals(searchBrowserProperties.getBlockedSignals())
                .blockedUrlKeywords(searchBrowserProperties.getBlockedUrlKeywords())
                .recoveryHint("如搜索中断，优先从 VERIFY_TOP_CANDIDATES 或 BROWSER_SUPPLEMENT_SEARCH 继续排查（展示语义：运行期补源）。")
                .build();
    }

    /**
     * 规划期生成的执行计划会被 runtime、replay、恢复链路直接复用，
     * 所以这里要把查询词、fallback 顺序、目标数与最小验证数一次性固化进节点配置。
     */
    private SearchExecutionPlan buildDefaultSearchExecutionPlan(List<String> searchQueries,
                                                                List<String> fallbackOrder,
                                                                int targetCount,
                                                                int minVerifiedCandidates) {
        return SearchExecutionPlan.builder()
                .stage("COLLECTOR_SEARCH_AND_COLLECT")
                .searchQueries(searchQueries)
                .fallbackOrder(fallbackOrder)
                .targetCount(targetCount)
                .minVerifiedCount(minVerifiedCandidates)
                .steps(List.of(
                        step("LOAD_CANDIDATES", "读取规划期候选来源", 500, "nodeConfig"),
                        step("TAVILY_BOOTSTRAP_ENRICH", "对弱规划期候选执行 Tavily Phase 1 候选增强", 4000, "tavily"),
                        step("VERIFY_TOP_CANDIDATES", "验证高优先级候选来源是否可用", 5000, "browser"),
                        step("BROWSER_SUPPLEMENT_SEARCH", "候选不足时执行运行期补源", 8000, "searchEngine"),
                        step("SELECT_TARGETS", "合并候选并选出最终采集目标", 1000, "ranker"),
                        step("COLLECT_PAGES", "抓取页面正文并持久化证据", 12000, "collector")
                ))
                .build();
    }

    private SearchExecutionStep step(String stepCode, String goal, long expectedDurationMs, String dependency) {
        return SearchExecutionStep.builder()
                .stepCode(stepCode)
                .goal(goal)
                .expectedDurationMs(expectedDurationMs)
                .dependency(dependency)
                .status(SearchExecutionStep.StepStatus.PENDING)
                .build();
    }

    private String resolveSearchMode() {
        String configuredMode = searchProperties == null ? null : searchProperties.getMode();
        String normalizedMode = StringUtils.hasText(configuredMode)
                ? configuredMode.trim().toUpperCase(Locale.ROOT)
                : "HYBRID";
        if (!searchBrowserProperties.isEnabled()
                && ("HYBRID".equals(normalizedMode) || "BROWSER_ONLY".equals(normalizedMode))) {
            return "HTTP_ONLY";
        }
        return normalizedMode;
    }

    private boolean isBrowserSearchEnabledForMode(String searchMode) {
        return searchBrowserProperties.isEnabled()
                && ("HYBRID".equalsIgnoreCase(searchMode) || "BROWSER_ONLY".equalsIgnoreCase(searchMode));
    }

    private List<String> buildDefaultSearchQueries(String competitorName,
                                                   String sourceType,
                                                   List<SourceCandidate> candidates) {
        String domainHint = candidates == null ? null : candidates.stream()
                .map(SourceCandidate::getDomain)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
        return promptTemplateService.buildSearchQueries(competitorName, sourceType, domainHint);
    }

    private List<String> buildPreferredDomains(List<SourceCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> domains = new LinkedHashSet<>();
        for (SourceCandidate candidate : candidates) {
            if (candidate != null && StringUtils.hasText(candidate.getDomain())) {
                domains.add(candidate.getDomain());
            }
        }
        return new ArrayList<>(domains);
    }
}
