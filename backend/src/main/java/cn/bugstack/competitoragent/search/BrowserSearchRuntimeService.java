package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.agent.collector.CollectorNodeConfig;
import cn.bugstack.competitoragent.config.PlaywrightBrowserManager;
import cn.bugstack.competitoragent.security.UrlSecurityUtils;
import cn.bugstack.competitoragent.source.PageContentExtractionSupport;
import cn.bugstack.competitoragent.source.SourceCandidate;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;
import java.util.UUID;

/**
 * 浏览器搜索运行时服务。
 * 负责在运行期打开搜索结果页并提取候选来源，搜索不可用时返回明确的降级说明。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BrowserSearchRuntimeService {

    private static final List<String> GENERIC_RESULT_SELECTORS = List.of(
            "main article",
            "main section",
            "article",
            "a[href]"
    );

    private final PlaywrightBrowserManager browserManager;
    private final SearchBrowserProperties properties;
    private final SearchEngineProperties searchEngineProperties;
    private final ObjectMapper objectMapper;
    private final SearchRuntimeFallbackPolicy fallbackPolicy;

    public String getSearchEngineName() {
        return resolvePrimarySearchEngineKey();
    }

    public BrowserSearchRuntimeResult search(CollectorNodeConfig config) {
        if (!properties.isEnabled()) {
            return BrowserSearchRuntimeResult.builder()
                    .candidates(List.of())
                    .executedQueries(List.of())
                    .searchEngine(getSearchEngineName())
                    .summary("浏览器搜索已全局关闭(search.browser.enabled=false)，已回退到 HTTP/规划候选链路")
                    .fallbackSuggested(true)
                    .browserTraceId(null)
                    .build();
        }
        if (!Boolean.TRUE.equals(config.getBrowserSearchEnabled())) {
            return BrowserSearchRuntimeResult.builder()
                    .candidates(List.of())
                    .executedQueries(List.of())
                    .searchEngine(getSearchEngineName())
                    .summary("当前节点未启用浏览器补源，允许继续走回退补源链路")
                    .fallbackSuggested(true)
                    .browserTraceId(null)
                    .build();
        }

        Browser browser = browserManager.getBrowser();
        if (browser == null) {
            String summary = fallbackPolicy.buildSearchFallbackSummary("browser_unavailable",
                    "请检查 Playwright 浏览器依赖、系统内存或稍后重试");
            return BrowserSearchRuntimeResult.builder()
                    .candidates(List.of())
                    .executedQueries(List.of())
                    .searchEngine(getSearchEngineName())
                    .summary(summary)
                    .fallbackSuggested(fallbackPolicy.shouldContinueOnBrowserUnavailable(config.getSearchRuntimePolicy()))
                    .blockedReason("browser_unavailable")
                    .browserTraceId(null)
                    .build();
        }

        List<String> queries = resolveQueries(config);
        if (queries.isEmpty()) {
            return BrowserSearchRuntimeResult.builder()
                    .candidates(List.of())
                    .executedQueries(List.of())
                    .searchEngine(getSearchEngineName())
                    .summary("未生成可执行的浏览器搜索 query，允许继续走回退补源链路")
                    .fallbackSuggested(true)
                    .browserTraceId(null)
                    .build();
        }

        List<String> engineSequence = resolveSearchEngineSequence();
        if (engineSequence.isEmpty()) {
            return BrowserSearchRuntimeResult.builder()
                    .candidates(List.of())
                    .executedQueries(List.of())
                    .searchEngine(getSearchEngineName())
                    .summary("未配置可用的浏览器搜索引擎，已回退到 HTTP 补源链路")
                    .fallbackSuggested(true)
                    .browserTraceId(null)
                    .build();
        }

        String browserTraceId = UUID.randomUUID().toString().replace("-", "");
        List<SourceCandidate> candidates = new ArrayList<>();
        List<String> executedQueries = new ArrayList<>();
        AtomicInteger blockedCount = new AtomicInteger();
        AtomicInteger openedResultPages = new AtomicInteger();
        String blockedReason = null;
        for (int index = 0; index < queries.size(); index++) {
            long intervalMillis = resolveMinIntervalMillis(config);
            if (index > 0 && intervalMillis > 0) {
                sleepQuietly(intervalMillis);
            }
            String query = queries.get(index);
            executedQueries.add(query);
            SearchAttemptResult attemptResult = searchWithRetry(
                    config,
                    query,
                    browserTraceId,
                    openedResultPages,
                    engineSequence
            );
            candidates.addAll(attemptResult.candidates());
            if (attemptResult.blockedReason() != null) {
                blockedCount.incrementAndGet();
                blockedReason = attemptResult.blockedReason();
            }
            if (candidates.size() >= resolveMaxResults(config)) {
                break;
            }
        }

        List<SourceCandidate> filteredCandidates = limitAndFilterCandidates(candidates, config);
        String executedEngineSummary = summarizeEngines(filteredCandidates, engineSequence);
        String summary = filteredCandidates.isEmpty()
                ? buildEmptyResultSummary(blockedReason)
                : "浏览器搜索执行 " + executedQueries.size() + " 个 query，经由 " + executedEngineSummary + " 提取到 "
                + filteredCandidates.size() + " 条候选来源";
        return BrowserSearchRuntimeResult.builder()
                .candidates(filteredCandidates)
                .executedQueries(executedQueries)
                .searchEngine(resolveResultEngine(filteredCandidates))
                .summary(summary)
                .fallbackSuggested(filteredCandidates.isEmpty())
                .blockedReason(blockedReason)
                .blockedCount(blockedCount.get())
                .browserTraceId(browserTraceId)
                .build();
    }

    /**
     * 每个 query 独立重试，避免一次搜索页抖动直接打断整个补源阶段。
     */
    private SearchAttemptResult searchWithRetry(CollectorNodeConfig config,
                                                String query,
                                                String browserTraceId,
                                                AtomicInteger openedResultPages,
                                                List<String> engineSequence) {
        String blockedReason = null;
        for (String engineKey : engineSequence) {
            int attempts = Math.max(1, resolveMaxRetries(config) + 1);
            RuntimeException lastError = null;
            String failureCode = null;
            for (int attempt = 1; attempt <= attempts; attempt++) {
                try {
                    Browser runtimeBrowser = browserManager.getBrowser();
                    if (runtimeBrowser == null) {
                        return new SearchAttemptResult(List.of(), "browser_unavailable");
                    }
                    SearchAttemptResult result = searchOnce(
                            runtimeBrowser,
                            config,
                            query,
                            browserTraceId,
                            openedResultPages,
                            engineKey
                    );
                    if (result.blockedReason() != null) {
                        blockedReason = result.blockedReason();
                        break;
                    }
                    if (!result.candidates().isEmpty()) {
                        return result;
                    }
                    break;
                } catch (RuntimeException e) {
                    lastError = e;
                    failureCode = fallbackPolicy.classifyRuntimeFailure(e);
                    log.warn("browser runtime search failed, competitor={}, sourceType={}, query={}, engine={}, attempt={}/{}",
                            UrlSecurityUtils.maskForLog(config.getCompetitorName()),
                            config.getSourceType(),
                            UrlSecurityUtils.maskForLog(query),
                            engineKey,
                            attempt,
                        attempts);
                    browserManager.restartBrowser("browser runtime search failed: " + e.getMessage());
                }
            }
            if (lastError != null) {
                log.warn("browser runtime search exhausted retries, competitor={}, sourceType={}, query={}, engine={}, error={}",
                        UrlSecurityUtils.maskForLog(config.getCompetitorName()),
                        config.getSourceType(),
                        UrlSecurityUtils.maskForLog(query),
                        engineKey,
                        lastError.getMessage());
                if ("search_timeout".equals(failureCode)
                        && fallbackPolicy.shouldContinueOnSearchTimeout(config.getSearchRuntimePolicy())) {
                    return new SearchAttemptResult(List.of(), failureCode);
                }
                if ("browser_unavailable".equals(failureCode)
                        && fallbackPolicy.shouldContinueOnBrowserUnavailable(config.getSearchRuntimePolicy())) {
                    return new SearchAttemptResult(List.of(), failureCode);
                }
            }
        }
        return new SearchAttemptResult(List.of(), blockedReason);
    }

    private SearchAttemptResult searchOnce(Browser browser,
                                           CollectorNodeConfig config,
                                           String query,
                                           String browserTraceId,
                                           AtomicInteger openedResultPages,
                                           String engineKey) {
        BrowserContext browserContext = null;
        Page page = null;
        try {
            Browser.NewContextOptions contextOptions = new Browser.NewContextOptions();
            String userAgent = resolveUserAgent(config, query);
            if (StringUtils.hasText(userAgent)) {
                contextOptions.setUserAgent(userAgent);
            }
            browserContext = browser.newContext(contextOptions);
            page = browserContext.newPage();
            page.setDefaultTimeout(resolvePageTimeoutMillis(config));
            page.navigate(buildSearchUrl(engineKey, query),
                    new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            try {
                page.waitForLoadState(LoadState.LOAD,
                        new Page.WaitForLoadStateOptions().setTimeout((double) resolvePageTimeoutMillis(config)));
            } catch (Exception ignored) {
                log.debug("browser search page did not reach LOAD quickly, continue parsing DOM");
            }

            String blockedReason = detectBlockedReason(page, config);
            if (blockedReason != null) {
                return new SearchAttemptResult(List.of(), blockedReason);
            }

            List<Map<String, Object>> extractedRows = extractRows(page, engineKey);
            List<SourceCandidate> candidates = buildCandidatesFromRows(
                    config,
                    query,
                    browserTraceId,
                    extractedRows,
                    engineKey
            );
            candidates = enrichTopResultPages(browserContext, candidates, config, openedResultPages);
            return new SearchAttemptResult(candidates, null);
        } catch (Exception e) {
            throw new IllegalStateException("browser search failed: " + e.getMessage(), e);
        } finally {
            closePageQuietly(page, "browser search page");
            closeContextQuietly(browserContext, "browser search context");
        }
    }

    /**
     * 先把搜索结果页解析成候选来源，再由后续步骤按需打开少量结果页做轻量预览。
     */
    private List<SourceCandidate> buildCandidatesFromRows(CollectorNodeConfig config,
                                                          String query,
                                                          String browserTraceId,
                                                          List<Map<String, Object>> extractedRows,
                                                          String engineKey) {
        List<SourceCandidate> candidates = new ArrayList<>();
        for (Map<String, Object> row : extractedRows) {
            if (candidates.size() >= resolveMaxResults(config)) {
                break;
            }
            String url = normalizeSearchResultUrl(text(row.get("url")));
            if (!StringUtils.hasText(url)) {
                continue;
            }
            if (!UrlSecurityUtils.isHttpUrl(url)) {
                continue;
            }
            String domain = extractDomain(url);
            if (isSearchEngineDomain(domain)) {
                continue;
            }
            if (isBlockedDomain(domain, config.getBlockedDomains())) {
                continue;
            }
            Integer resultRank = parseInteger(row.get("resultRank"));
            candidates.add(SourceCandidate.builder()
                    .url(url)
                    .title(defaultText(text(row.get("title")), config.getCompetitorName() + " 搜索结果"))
                    .sourceType(defaultText(config.getSourceType(), "OFFICIAL"))
                    .discoveryMethod("BROWSER")
                    .reason(buildReason(config.getSourceType(), text(row.get("snippet"))))
                    .domain(domain)
                    .relevanceScore(inferRelevance(resultRank, domain, config.getPreferredDomains()))
                    .freshnessScore(inferFreshness(config.getSourceType()))
                    .qualityScore(inferQuality(domain, config.getPreferredDomains()))
                    .searchQuery(query)
                    .searchEngine(searchEngineProperties.normalizeEngineKey(engineKey))
                    .resultRank(resultRank)
                    .browserTraceId(browserTraceId)
                    .selectionStage("BROWSER")
                    .selectionReason("运行期通过浏览器搜索结果页增补")
                    .build());
        }
        return candidates;
    }

    /**
     * 企业级链路里 maxOpenResultPages 不能只是摆设。
     * 这里会对前 N 条候选打开真实结果页做轻量预览，补齐最终标题、落地页 URL 和域名。
     */
    private List<SourceCandidate> enrichTopResultPages(BrowserContext browserContext,
                                                       List<SourceCandidate> candidates,
                                                       CollectorNodeConfig config,
                                                       AtomicInteger openedResultPages) {
        if (browserContext == null || candidates.isEmpty()) {
            return candidates;
        }
        int maxOpenResultPages = resolveMaxOpenResultPages(config);
        if (maxOpenResultPages <= 0) {
            return candidates;
        }

        List<SourceCandidate> enriched = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            SourceCandidate candidate = candidates.get(index);
            if (openedResultPages.get() >= maxOpenResultPages) {
                enriched.add(candidate);
                continue;
            }
            enriched.add(previewCandidatePage(browserContext, candidate, config, openedResultPages));
        }
        return enriched;
    }

    int resolvePreviewBudget(CollectorNodeConfig config) {
        return resolveMaxOpenResultPages(config);
    }

    private SourceCandidate previewCandidatePage(BrowserContext browserContext,
                                                 SourceCandidate candidate,
                                                 CollectorNodeConfig config,
                                                 AtomicInteger openedResultPages) {
        if (candidate == null || !StringUtils.hasText(candidate.getUrl())) {
            return candidate;
        }
        if (!UrlSecurityUtils.isHttpUrl(candidate.getUrl())) {
            return candidate.toBuilder()
                    .verified(Boolean.FALSE)
                    .verificationReason("候选 URL 协议不安全，仅允许 http/https")
                    .build();
        }
        Page previewPage = null;
        try {
            openedResultPages.incrementAndGet();
            previewPage = browserContext.newPage();
            previewPage.setDefaultTimeout(resolveResultPageTimeoutMillis(config));
            previewPage.navigate(candidate.getUrl(),
                    new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            String finalUrl = safe(previewPage.url());
            String finalTitle = safe(previewPage.title());
            String finalDomain = extractDomain(finalUrl);
            String previewContent = PageContentExtractionSupport.extractMainContent(previewPage);
            String previewSummary = PageContentExtractionSupport.truncateForSummary(
                    previewContent,
                    resolveMaxContentLengthPerPage(config)
            );
            return candidate.toBuilder()
                    .url(StringUtils.hasText(finalUrl) ? finalUrl : candidate.getUrl())
                    .title(StringUtils.hasText(finalTitle) ? finalTitle : candidate.getTitle())
                    .domain(StringUtils.hasText(finalDomain) ? finalDomain : candidate.getDomain())
                    .reason(mergeReasonWithPreview(candidate.getReason(), previewSummary))
                    .build();
        } catch (Exception e) {
            log.debug("preview browser result page failed, url={}, reason={}",
                    UrlSecurityUtils.maskForLog(candidate.getUrl()), e.getMessage());
            return candidate;
        } finally {
            closePageQuietly(previewPage, "browser preview page");
        }
    }

    /**
     * 结果页结构不稳定，所以按多个选择器依次回退提取。
     */
    private List<Map<String, Object>> extractRows(Page page, String engineKey) {
        SearchEngineProfile profile = resolveEngineProfile(engineKey);
        for (String selector : profile.resultSelectors()) {
            List<Map<String, Object>> rows = evaluateRows(page, selector, profile.snippetSelectors());
            if (!rows.isEmpty()) {
                return rows;
            }
        }
        for (String selector : GENERIC_RESULT_SELECTORS) {
            List<Map<String, Object>> rows = evaluateRows(page, selector, profile.snippetSelectors());
            if (!rows.isEmpty()) {
                return rows;
            }
        }
        return extractRowsFromAnchors(page);
    }

    private List<Map<String, Object>> evaluateRows(Page page, String selector, List<String> snippetSelectors) {
        Object raw = page.evalOnSelectorAll(selector, """
                (nodes, snippetSelectors) => nodes.map((node, index) => {
                  const container = node;
                  const isAnchor = container.matches && container.matches('a[href]');
                  const anchor = isAnchor
                    ? container
                    : (container.querySelector ? container.querySelector('a[href]') : null);
                  const titleNode = container.querySelector
                    ? (container.querySelector('h1, h2, h3') || anchor)
                    : anchor;
                  let snippet = '';
                  if (container.querySelector && Array.isArray(snippetSelectors)) {
                    for (const selector of snippetSelectors) {
                      const snippetNode = container.querySelector(selector);
                      const text = snippetNode && snippetNode.innerText ? snippetNode.innerText.trim() : '';
                      if (text) {
                        snippet = text;
                        break;
                      }
                    }
                  }
                  if (!snippet && container.innerText) {
                    const lines = container.innerText
                      .split('\\n')
                      .map(line => line.trim())
                      .filter(Boolean);
                    snippet = lines.find(line => line.length >= 32 && line !== (titleNode?.innerText || '').trim()) || '';
                  }
                  return {
                    title: titleNode ? (titleNode.innerText || '').trim() : '',
                    url: anchor ? anchor.href : '',
                    snippet,
                    resultRank: index + 1
                  };
                })
                """, snippetSelectors);
        List<Map<String, Object>> rows = objectMapper.convertValue(
                raw,
                new TypeReference<List<Map<String, Object>>>() {}
        );
        return rows == null ? List.of() : rows;
    }

    /**
     * 当搜索引擎页面结构变化时，最后回退到整页链接提取。
     * 这里不追求完美结构化，而是优先保证“至少拿到一批可点进去再读正文的候选链接”。
     */
    private List<Map<String, Object>> extractRowsFromAnchors(Page page) {
        Object raw = page.evaluate("""
                () => {
                  const anchors = Array.from(document.querySelectorAll('a[href]'));
                  const rows = [];
                  const seen = new Set();
                  for (const anchor of anchors) {
                    const href = anchor && anchor.href ? anchor.href.trim() : '';
                    const title = anchor && anchor.innerText ? anchor.innerText.trim() : '';
                    if (!href || !title || title.length < 4) continue;
                    const container = anchor.closest ? (anchor.closest('article, section, div, li') || anchor.parentElement || anchor) : anchor;
                    const snippet = container && container.innerText
                      ? container.innerText.split('\\n').map(line => line.trim()).filter(Boolean)
                          .find(line => line.length >= 24 && line !== title) || ''
                      : '';
                    const key = `${href}::${title}`;
                    if (seen.has(key)) continue;
                    seen.add(key);
                    rows.push({
                      title,
                      url: href,
                      snippet,
                      resultRank: rows.length + 1
                    });
                  }
                  return rows.slice(0, 20);
                }
                """);
        List<Map<String, Object>> rows = objectMapper.convertValue(
                raw,
                new TypeReference<List<Map<String, Object>>>() {}
        );
        return rows == null ? List.of() : rows;
    }

    private List<SourceCandidate> limitAndFilterCandidates(List<SourceCandidate> candidates,
                                                           CollectorNodeConfig config) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        int maxResults = resolveMaxResults(config);
        Set<String> seen = new LinkedHashSet<>();
        List<SourceCandidate> filtered = new ArrayList<>();
        for (SourceCandidate candidate : candidates) {
            if (candidate == null || !StringUtils.hasText(candidate.getUrl())) {
                continue;
            }
            if (!seen.add(candidate.getUrl())) {
                continue;
            }
            filtered.add(candidate);
            if (filtered.size() >= maxResults) {
                break;
            }
        }
        return filtered;
    }

    private List<String> resolveQueries(CollectorNodeConfig config) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        if (config.getSearchQueries() != null) {
            config.getSearchQueries().stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .forEach(queries::add);
        }
        if (queries.isEmpty() && StringUtils.hasText(config.getCompetitorName())) {
            queries.add(config.getCompetitorName() + " " + defaultText(config.getSourceType(), "official"));
        }
        return queries.stream()
                .limit(Math.max(1, resolveMaxSearchesPerTask(config)))
                .toList();
    }

    private int resolveMaxResults(CollectorNodeConfig config) {
        int nodeLimit = config.getMaxSearchResults() == null ? 0 : config.getMaxSearchResults();
        int browserLimit = Math.max(1, properties.getMaxResultsPerQuery());
        if (nodeLimit <= 0) {
            return browserLimit;
        }
        return Math.min(nodeLimit, browserLimit);
    }

    private String detectBlockedReason(Page page, CollectorNodeConfig config) {
        String title = safe(page.title()).toLowerCase(Locale.ROOT);
        String html = safe(page.content()).toLowerCase(Locale.ROOT);
        for (String signal : resolveBlockedSignals(config)) {
            String normalized = safe(signal).toLowerCase(Locale.ROOT);
            if (normalized.isBlank()) {
                continue;
            }
            if (title.contains(normalized) || html.contains(normalized)) {
                return normalized;
            }
        }
        return null;
    }

    String buildSearchUrl(String query) {
        return buildSearchUrl(resolvePrimarySearchEngineKey(), query);
    }

    String buildSearchUrl(String engineKey, String query) {
        SearchEngineProperties.EngineConfig engineConfig = searchEngineProperties.resolve(engineKey);
        if (engineConfig == null || !StringUtils.hasText(engineConfig.getBaseUrl())) {
            engineConfig = searchEngineProperties.resolve(resolvePrimarySearchEngineKey());
        }
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String separator = engineConfig.getBaseUrl().contains("?") ? "&" : "?";
        return engineConfig.getBaseUrl() + separator
                + defaultText(engineConfig.getQueryParam(), "q") + "=" + encoded;
    }

    private String resolveResultEngine(List<SourceCandidate> filteredCandidates) {
        if (filteredCandidates != null) {
            for (SourceCandidate candidate : filteredCandidates) {
                if (candidate != null && StringUtils.hasText(candidate.getSearchEngine())) {
                    return candidate.getSearchEngine();
                }
            }
        }
        return getSearchEngineName();
    }

    private String summarizeEngines(List<SourceCandidate> filteredCandidates, List<String> engineSequence) {
        LinkedHashSet<String> engines = new LinkedHashSet<>();
        if (filteredCandidates != null) {
            for (SourceCandidate candidate : filteredCandidates) {
                if (candidate != null && StringUtils.hasText(candidate.getSearchEngine())) {
                    engines.add(candidate.getSearchEngine());
                }
            }
        }
        if (engines.isEmpty() && engineSequence != null) {
            engines.addAll(engineSequence);
        }
        return String.join(" / ", engines);
    }

    private List<String> resolveSearchEngineSequence() {
        return searchEngineProperties.resolveEnabledEngineKeys(
                properties.getEngine(),
                properties.getFallbackEngines()
        );
    }

    private String resolvePrimarySearchEngineKey() {
        List<String> sequence = resolveSearchEngineSequence();
        if (!sequence.isEmpty()) {
            return sequence.get(0);
        }
        return "baidu";
    }

    private String buildReason(String sourceType, String snippet) {
        String label = switch (defaultText(sourceType, "OFFICIAL").toUpperCase(Locale.ROOT)) {
            case "DOCS" -> "浏览器搜索命中文档相关入口";
            case "PRICING" -> "浏览器搜索命中定价相关入口";
            case "NEWS" -> "浏览器搜索命中新闻/更新入口";
            case "REVIEW" -> "浏览器搜索命中测评相关入口";
            default -> "浏览器搜索命中官网或产品入口";
        };
        if (!StringUtils.hasText(snippet)) {
            return label;
        }
        return label + "：" + snippet.substring(0, Math.min(160, snippet.length()));
    }

    /**
     * 结果页预览读到正文后，把可读摘要并回 reason。
     * 这样候选来源不仅知道“搜到了什么链接”，也知道“页内到底讲了什么”。
     */
    private String mergeReasonWithPreview(String originalReason, String previewSummary) {
        if (!StringUtils.hasText(previewSummary)) {
            return originalReason;
        }
        String prefix = StringUtils.hasText(originalReason) ? originalReason : "浏览器搜索命中结果页";
        return prefix + "；正文摘要：" + previewSummary;
    }

    private double inferRelevance(Integer rank, String domain, List<String> preferredDomains) {
        int safeRank = rank == null || rank <= 0 ? 1 : rank;
        double base = Math.max(0.55, 0.96 - (safeRank - 1) * 0.06);
        if (isPreferredDomain(domain, preferredDomains)) {
            base = Math.min(0.99, base + 0.05);
        }
        return round(base);
    }

    private double inferFreshness(String sourceType) {
        return "NEWS".equalsIgnoreCase(sourceType) ? 0.78 : 0.58;
    }

    private double inferQuality(String domain, List<String> preferredDomains) {
        if (isPreferredDomain(domain, preferredDomains)) {
            return 0.94;
        }
        if (!StringUtils.hasText(domain)) {
            return 0.70;
        }
        String normalized = domain.toLowerCase(Locale.ROOT);
        if (normalized.contains("g2.com") || normalized.contains("capterra.com")) {
            return 0.88;
        }
        if (normalized.startsWith("docs.") || normalized.contains("help") || normalized.contains("support")) {
            return 0.90;
        }
        return 0.82;
    }

    private boolean isPreferredDomain(String domain, List<String> preferredDomains) {
        if (!StringUtils.hasText(domain) || preferredDomains == null || preferredDomains.isEmpty()) {
            return false;
        }
        String normalized = domain.toLowerCase(Locale.ROOT);
        return preferredDomains.stream()
                .filter(StringUtils::hasText)
                .map(item -> item.toLowerCase(Locale.ROOT))
                .anyMatch(item -> normalized.equals(item) || normalized.endsWith("." + item));
    }

    private boolean isBlockedDomain(String domain, List<String> blockedDomains) {
        if (!StringUtils.hasText(domain) || blockedDomains == null || blockedDomains.isEmpty()) {
            return false;
        }
        String normalized = domain.toLowerCase(Locale.ROOT);
        return blockedDomains.stream()
                .filter(StringUtils::hasText)
                .map(item -> item.toLowerCase(Locale.ROOT))
                .anyMatch(item -> normalized.equals(item) || normalized.endsWith("." + item));
    }

    private String extractDomain(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private Integer parseInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String buildEmptyResultSummary(String blockedReason) {
        if (!StringUtils.hasText(blockedReason)) {
            return "浏览器搜索未提取到有效候选，建议回退到 HTTP 补源";
        }
        if ("browser_unavailable".equalsIgnoreCase(blockedReason)
                || "search_timeout".equalsIgnoreCase(blockedReason)
                || "runtime_failure".equalsIgnoreCase(blockedReason)) {
            return fallbackPolicy.buildSearchFallbackSummary(blockedReason, null);
        }
        return "浏览器搜索疑似被阻断[" + blockedReason + "]，建议稍后重试或回退到 HTTP 补源";
    }

    /**
     * Playwright 的关闭动作本身也可能抛异常。
     * 这里统一吞掉关闭阶段异常，避免“结果已经拿到，却在 finally 再把整条链路打断”。
     */
    private void closePageQuietly(Page page, String scene) {
        if (page == null) {
            return;
        }
        try {
            page.close();
        } catch (Exception e) {
            log.debug("close playwright page failed, scene={}, error={}", scene, e.getMessage());
        }
    }

    private void closeContextQuietly(BrowserContext browserContext, String scene) {
        if (browserContext == null) {
            return;
        }
        try {
            browserContext.close();
        } catch (Exception e) {
            log.debug("close playwright browser context failed, scene={}, error={}", scene, e.getMessage());
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private double round(double value) {
        return Math.round(value * 1000D) / 1000D;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private int resolveMaxRetries(CollectorNodeConfig config) {
        if (config.getSearchRuntimePolicy() != null && config.getSearchRuntimePolicy().getMaxRetries() != null) {
            return Math.max(0, config.getSearchRuntimePolicy().getMaxRetries());
        }
        return Math.max(0, properties.getMaxRetries());
    }

    private long resolveMinIntervalMillis(CollectorNodeConfig config) {
        if (config.getSearchRuntimePolicy() != null && config.getSearchRuntimePolicy().getMinIntervalMillis() != null) {
            return Math.max(0L, config.getSearchRuntimePolicy().getMinIntervalMillis());
        }
        return Math.max(0L, properties.getMinIntervalMillis());
    }

    private int resolvePageTimeoutMillis(CollectorNodeConfig config) {
        if (config.getSearchRuntimePolicy() != null && config.getSearchRuntimePolicy().getPageTimeoutMillis() != null) {
            return Math.max(1000, config.getSearchRuntimePolicy().getPageTimeoutMillis());
        }
        return Math.max(1000, properties.getPageTimeoutMillis());
    }

    private int resolveResultPageTimeoutMillis(CollectorNodeConfig config) {
        if (config.getSearchRuntimePolicy() != null
                && config.getSearchRuntimePolicy().getResultPageTimeoutMillis() != null) {
            return Math.max(1000, config.getSearchRuntimePolicy().getResultPageTimeoutMillis());
        }
        return Math.max(1000, properties.getResultPageTimeoutMillis());
    }

    private int resolveMaxOpenResultPages(CollectorNodeConfig config) {
        if (config.getSearchRuntimePolicy() != null && config.getSearchRuntimePolicy().getMaxOpenResultPages() != null) {
            return Math.max(0, config.getSearchRuntimePolicy().getMaxOpenResultPages());
        }
        return Math.max(0, properties.getMaxOpenResultPages());
    }

    private int resolveMaxContentLengthPerPage(CollectorNodeConfig config) {
        if (config.getSearchRuntimePolicy() != null
                && config.getSearchRuntimePolicy().getMaxContentLengthPerPage() != null) {
            return Math.max(80, config.getSearchRuntimePolicy().getMaxContentLengthPerPage());
        }
        return Math.max(80, properties.getMaxContentLengthPerPage());
    }

    private String resolveUserAgent(CollectorNodeConfig config, String query) {
        List<String> userAgents = config.getSearchRuntimePolicy() != null
                && config.getSearchRuntimePolicy().getUserAgents() != null
                && !config.getSearchRuntimePolicy().getUserAgents().isEmpty()
                ? config.getSearchRuntimePolicy().getUserAgents()
                : properties.getUserAgents();
        if (userAgents == null || userAgents.isEmpty()) {
            return null;
        }
        int index = Math.abs((query == null ? 0 : query.hashCode())) % userAgents.size();
        return userAgents.get(index);
    }

    private List<String> resolveBlockedSignals() {
        return properties.getBlockedSignals() == null ? List.of() : properties.getBlockedSignals();
    }

    private List<String> resolveBlockedSignals(CollectorNodeConfig config) {
        if (config != null
                && config.getSearchRuntimePolicy() != null
                && config.getSearchRuntimePolicy().getBlockedSignals() != null
                && !config.getSearchRuntimePolicy().getBlockedSignals().isEmpty()) {
            return config.getSearchRuntimePolicy().getBlockedSignals();
        }
        return resolveBlockedSignals();
    }

    private int resolveMaxSearchesPerTask(CollectorNodeConfig config) {
        if (config.getSearchRuntimePolicy() != null && config.getSearchRuntimePolicy().getMaxSearchesPerTask() != null) {
            return Math.max(1, config.getSearchRuntimePolicy().getMaxSearchesPerTask());
        }
        return Math.max(1, properties.getMaxSearchesPerTask());
    }

    private SearchEngineProfile resolveEngineProfile(String engineKey) {
        return switch (searchEngineProperties.normalizeEngineKey(engineKey)) {
            case "google" -> new SearchEngineProfile(
                    "google",
                    List.of("#search .g", "#rso > div", "div#search div.g"),
                    List.of(".VwiC3b", ".yXK7lf", "span.aCOpRe", "div[data-sncf='1']", "p")
            );
            case "baidu" -> new SearchEngineProfile(
                    "baidu",
                    List.of(
                            "#content_left .result",
                            "#content_left .c-container",
                            "#content_left .result, #content_left .c-container, #content_left > div, #content_left > div[data-log], .result-op, .result-op.c-container"
                    ),
                    List.of(".c-abstract", ".content-right_8Zs40", ".c-span-last p", "p")
            );
            case "duckduckgo" -> new SearchEngineProfile(
                    "duckduckgo",
                    List.of("[data-testid='result']", "article[data-testid='result']", ".react-results--main article", ".results_links"),
                    List.of("[data-result='snippet']", ".result__snippet", "p")
            );
            default -> new SearchEngineProfile(
                    "bing",
                    List.of("#b_results .b_algo", "ol#b_results li.b_algo", "main .b_algo", "main li[data-bm]"),
                    List.of(".b_caption p", ".b_lineclamp2", ".b_snippet", "p")
            );
        };
    }

    private String normalizeSearchResultUrl(String rawUrl) {
        if (!StringUtils.hasText(rawUrl)) {
            return null;
        }
        try {
            URI uri = URI.create(rawUrl);
            String host = safe(uri.getHost()).toLowerCase(Locale.ROOT);
            if (host.contains("google.") && "/url".equalsIgnoreCase(uri.getPath())) {
                String target = queryParam(uri.getRawQuery(), "q");
                return StringUtils.hasText(target) ? target : rawUrl;
            }
            if (host.contains("duckduckgo.com")) {
                String target = queryParam(uri.getRawQuery(), "uddg");
                return StringUtils.hasText(target) ? target : rawUrl;
            }
            return rawUrl;
        } catch (Exception e) {
            return rawUrl;
        }
    }

    private String queryParam(String rawQuery, String name) {
        if (!StringUtils.hasText(rawQuery) || !StringUtils.hasText(name)) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && name.equals(parts[0])) {
                return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private boolean isSearchEngineDomain(String domain) {
        if (!StringUtils.hasText(domain)) {
            return false;
        }
        String normalized = domain.toLowerCase(Locale.ROOT);
        Set<String> hosts = new HashSet<>();
        for (Map.Entry<String, SearchEngineProperties.EngineConfig> entry : searchEngineProperties.entrySet()) {
            SearchEngineProperties.EngineConfig config = entry.getValue();
            if (config == null || !StringUtils.hasText(config.getHost())) {
                continue;
            }
            hosts.add(config.getHost().toLowerCase(Locale.ROOT));
        }
        return hosts.stream().anyMatch(host -> normalized.equals(host) || normalized.endsWith("." + host));
    }

    private record SearchAttemptResult(List<SourceCandidate> candidates, String blockedReason) {
    }

    private record SearchEngineProfile(String name, List<String> resultSelectors, List<String> snippetSelectors) {
    }
}
