package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.agent.collector.CollectorNodeConfig;
import cn.bugstack.competitoragent.config.CollectorProperties;
import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.search.BrowserSearchRuntimeResult;
import cn.bugstack.competitoragent.search.BrowserSearchRuntimeService;
import cn.bugstack.competitoragent.search.SearchPolicyResolver;
import cn.bugstack.competitoragent.search.SearchProviderRole;
import cn.bugstack.competitoragent.search.SearchRuntimePolicy;
import cn.bugstack.competitoragent.search.SearchSourceCatalogProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 规划期浏览器预览补源实现。
 * 复用运行期浏览器搜索能力，只解析结果页，不做正文抓取，保证任务启动前仍可预览候选来源。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BrowserPreviewSearchSourceProvider implements SearchSourceProvider {

    private static final List<String> DEFAULT_SCOPES = List.of("OFFICIAL", "DOCS", "PRICING", "NEWS", "REVIEW");

    private final SearchPolicyResolver searchPolicyResolver = new SearchPolicyResolver();
    private final BrowserSearchRuntimeService browserSearchRuntimeService;
    private final SearchProviderProperties properties;
    private final PromptTemplateService promptTemplateService;
    private final CollectorProperties collectorProperties;

    @Override
    public SearchSourceProviderDescriptor descriptor() {
        return SearchSourceProviderDescriptor.builder()
                .providerKey("browserpreview")
                .displayName("浏览器预览补源")
                .capabilities(List.of("BROWSER_PREVIEW", "WEB_SEARCH"))
                .defaultEnabled(true)
                .defaultFailOpen(true)
                .build();
    }

    @Override
    public boolean isAvailable() {
        return properties.isBrowserPreviewEnabled();
    }

    @Override
    public List<SourceCandidate> search(String competitorName, List<String> requestedScopes) {
        if (!isAvailable() || !StringUtils.hasText(competitorName)) {
            return List.of();
        }

        Set<String> scopes = new LinkedHashSet<>(requestedScopes == null || requestedScopes.isEmpty()
                ? DEFAULT_SCOPES
                : requestedScopes);
        List<SourceCandidate> previewCandidates = new ArrayList<>();
        for (String scope : scopes) {
            CollectorNodeConfig previewConfig = buildPreviewConfig(competitorName, scope);
            BrowserSearchRuntimeResult previewResult = browserSearchRuntimeService.search(previewConfig);
            if (previewResult.getCandidates().isEmpty()) {
                log.debug("browser preview search returned no candidates, competitor={}, scope={}, summary={}",
                        competitorName, scope, previewResult.getSummary());
                continue;
            }
            previewCandidates.addAll(normalizePreviewCandidates(previewResult.getCandidates()));
        }
        return previewCandidates;
    }

    private CollectorNodeConfig buildPreviewConfig(String competitorName, String scope) {
        String familyKey = searchPolicyResolver.resolveSourceFamilyKeyForSourceType(scope);
        SearchSourceCatalogProperties.SourceFamilyProperties family =
                searchPolicyResolver.resolveSourceFamilyForSourceType(scope);
        return CollectorNodeConfig.builder()
                .competitorName(competitorName)
                .sourceType(scope)
                .sourceFamilyKey(familyKey)
                .sourceFamilyRole(resolveFamilyRole(family))
                .primaryTools(family == null || family.getPrimaryTools() == null ? List.of() : family.getPrimaryTools())
                .auxiliaryTools(family == null || family.getAuxiliaryTools() == null ? List.of() : family.getAuxiliaryTools())
                .queryTemplates(family == null || family.getQueryTemplates() == null ? List.of() : family.getQueryTemplates())
                .searchMode("BROWSER_ONLY")
                // 规划期预览的职责就是复用浏览器搜索能力做候选预览，因此这里显式强制开启。
                .browserSearchEnabled(Boolean.TRUE)
                .searchFallbackOrder(searchPolicyResolver.resolveFallbackOrder("BROWSER_ONLY", true))
                .verifyResultPage(Boolean.FALSE)
                .searchQueries(buildQueries(competitorName, scope))
                .maxSearchResults(Math.max(1, properties.getResultsPerScope()))
                .searchRuntimePolicy(SearchRuntimePolicy.builder()
                        .verifyResultPage(Boolean.FALSE)
                        .maxRetries(1)
                        .minIntervalMillis(1000L)
                        .maxSearchesPerTask(2)
                        .pageTimeoutMillis(Math.max(1000, collectorProperties.getPageTimeoutSeconds() * 1000))
                        .maxOpenResultPages(1)
                        .userAgents(StringUtils.hasText(collectorProperties.getUserAgent())
                                ? List.of(collectorProperties.getUserAgent())
                                : List.of())
                        .recoveryHint("浏览器预览补源失败时可回退到 HTTP 或启发式候选。")
                        .build())
                .build();
    }

    /**
     * 第一阶段先沿用规则模板生成 query，保证预览补源稳定、可复现。
     */
    private List<String> buildQueries(String competitorName, String scope) {
        return promptTemplateService.buildSearchQueries(competitorName, scope, null);
    }

    private List<SourceCandidate> normalizePreviewCandidates(List<SourceCandidate> previewCandidates) {
        return previewCandidates.stream()
                .map(candidate -> candidate.toBuilder()
                        .discoveryMethod("BROWSER_PREVIEW")
                        .selectionStage("PLANNED")
                        .selectionReason("规划期通过浏览器预览补源生成候选来源")
                        .reason(buildPreviewReason(candidate.getReason()))
                        .sourceFamilyKey(searchPolicyResolver.resolveSourceFamilyKeyForSourceType(candidate.getSourceType()))
                        .sourceFamilyRole(resolveFamilyRole(searchPolicyResolver.resolveSourceFamilyForSourceType(candidate.getSourceType())))
                        .sourceUrls(resolveCandidateSourceUrls(candidate))
                        .build())
                .toList();
    }

    private String resolveFamilyRole(SearchSourceCatalogProperties.SourceFamilyProperties family) {
        if (family == null || !StringUtils.hasText(family.getRole())) {
            return SearchProviderRole.AUXILIARY_PUBLIC.name();
        }
        return family.getRole();
    }

    private List<String> resolveCandidateSourceUrls(SourceCandidate candidate) {
        if (candidate.getSourceUrls() != null && !candidate.getSourceUrls().isEmpty()) {
            return candidate.getSourceUrls();
        }
        if (StringUtils.hasText(candidate.getUrl())) {
            return List.of(candidate.getUrl());
        }
        return List.of();
    }

    private String buildPreviewReason(String originalReason) {
        if (!StringUtils.hasText(originalReason)) {
            return "浏览器预览补源命中候选入口";
        }
        return "浏览器预览补源：" + originalReason;
    }
}
