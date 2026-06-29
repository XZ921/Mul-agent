package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.collection.WebPageRenderHint;
import cn.bugstack.competitoragent.source.DirectHtmlReaderClient;
import cn.bugstack.competitoragent.source.PageContentExtractionResult;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCollectRequest;
import cn.bugstack.competitoragent.source.SourceCollector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 候选来源验证器。
 * <p>
 * 运行期会打开候选页面并抽取正文，再结合关键词策略判断该来源是否真的适合进入正式采集。
 */
@Component
public class CandidateVerifier {

    private static final int MAX_COLLECT_ATTEMPTS = 3;

    private final SourceCollector sourceCollector;
    private final SearchKeywordPolicy searchKeywordPolicy;
    private final CandidateOwnershipPolicy candidateOwnershipPolicy;
    private final SearchBrowserProperties searchBrowserProperties;
    private final DirectHtmlReaderClient directHtmlReaderClient;

    /**
     * 运行时必须显式注入 SourceCollector 与 SearchKeywordPolicy，
     * 否则 Spring 在某些集成测试装配路径下会退回到默认构造尝试并直接失败。
     */
    @Autowired
    public CandidateVerifier(SourceCollector sourceCollector,
                             SearchKeywordPolicy searchKeywordPolicy,
                             CandidateOwnershipPolicy candidateOwnershipPolicy,
                             SearchBrowserProperties searchBrowserProperties,
                             ObjectProvider<DirectHtmlReaderClient> directHtmlReaderClientProvider) {
        this.sourceCollector = sourceCollector;
        this.searchKeywordPolicy = searchKeywordPolicy;
        this.candidateOwnershipPolicy = candidateOwnershipPolicy == null
                ? new CandidateOwnershipPolicy()
                : candidateOwnershipPolicy;
        this.searchBrowserProperties = searchBrowserProperties == null
                ? new SearchBrowserProperties()
                : searchBrowserProperties;
        this.directHtmlReaderClient = directHtmlReaderClientProvider == null
                ? null
                : directHtmlReaderClientProvider.getIfAvailable();
    }

    public CandidateVerifier(SourceCollector sourceCollector,
                             SearchKeywordPolicy searchKeywordPolicy,
                             CandidateOwnershipPolicy candidateOwnershipPolicy,
                             SearchBrowserProperties searchBrowserProperties) {
        this(sourceCollector, searchKeywordPolicy, candidateOwnershipPolicy, searchBrowserProperties, null);
    }

    public CandidateVerifier(SourceCollector sourceCollector,
                             SearchKeywordPolicy searchKeywordPolicy,
                             CandidateOwnershipPolicy candidateOwnershipPolicy) {
        this(sourceCollector, searchKeywordPolicy, candidateOwnershipPolicy, new SearchBrowserProperties(), null);
    }

    public CandidateVerifier(SourceCollector sourceCollector, SearchKeywordPolicy searchKeywordPolicy) {
        this(sourceCollector, searchKeywordPolicy, new CandidateOwnershipPolicy());
    }

    public CandidateVerifier(SourceCollector sourceCollector) {
        this(sourceCollector, new SearchKeywordPolicy(), new CandidateOwnershipPolicy());
    }

    public CandidateVerificationResult verify(String competitorName,
                                              String sourceType,
                                              List<SourceCandidate> candidates) {
        long startedAt = System.currentTimeMillis();
        List<SourceCandidate> uniqueCandidates = deduplicateCandidates(candidates);
        if (uniqueCandidates.isEmpty()) {
            return CandidateVerificationResult.builder()
                    .updatedCandidates(List.of())
                    .attemptedTargets(List.of())
                    .verifiedTargets(List.of())
                    .inputCandidateCount(candidates == null ? 0 : candidates.size())
                    .uniqueCandidateCount(0)
                    .attemptedCandidateCount(0)
                    .verifiedCandidateCount(0)
                    .reusedCollectedPageCount(0)
                    .directVerificationAttemptCount(0)
                    .directVerificationUsableCount(0)
                    .directVerificationShortcutCount(0)
                    .verificationConcurrency(1)
                    .verificationElapsedMillis(Math.max(0L, System.currentTimeMillis() - startedAt))
                    .build();
        }

        List<SourceCandidate> updatedCandidates = new ArrayList<>();
        List<SearchCollectionTarget> attemptedTargets = new ArrayList<>();
        List<SearchCollectionTarget> verifiedTargets = new ArrayList<>();
        DirectVerificationCounters directCounters = new DirectVerificationCounters();
        int concurrency = Math.max(1, searchBrowserProperties.getVerificationConcurrency());

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(concurrency, uniqueCandidates.size()));
        try {
            List<CompletableFuture<SearchCollectionTarget>> futures = uniqueCandidates.stream()
                    .map(candidate -> CompletableFuture.supplyAsync(
                            () -> verifyOneCandidate(candidate, competitorName, sourceType, directCounters),
                            executor
                    ))
                    .toList();

            for (CompletableFuture<SearchCollectionTarget> future : futures) {
                SearchCollectionTarget target = future.join();
                SourceCandidate updatedCandidate = target.getCandidate();
                updatedCandidates.add(updatedCandidate);
                attemptedTargets.add(target);
                if (updatedCandidate != null && Boolean.TRUE.equals(updatedCandidate.getVerified())) {
                    verifiedTargets.add(target);
                }
            }
        } finally {
            executor.shutdownNow();
        }

        return CandidateVerificationResult.builder()
                .updatedCandidates(updatedCandidates)
                .attemptedTargets(attemptedTargets)
                .verifiedTargets(verifiedTargets)
                .inputCandidateCount(candidates == null ? 0 : candidates.size())
                .uniqueCandidateCount(uniqueCandidates.size())
                .attemptedCandidateCount(attemptedTargets.size())
                .verifiedCandidateCount(verifiedTargets.size())
                .reusedCollectedPageCount(0)
                .directVerificationAttemptCount(directCounters.attemptCount.get())
                .directVerificationUsableCount(directCounters.usableCount.get())
                .directVerificationShortcutCount(directCounters.shortcutCount.get())
                .verificationConcurrency(concurrency)
                .verificationElapsedMillis(Math.max(0L, System.currentTimeMillis() - startedAt))
                .build();
    }

    private SearchCollectionTarget verifyOneCandidate(SourceCandidate candidate,
                                                      String competitorName,
                                                      String sourceType,
                                                      DirectVerificationCounters directCounters) {
        if (shouldSkipNetworkVerification(candidate)) {
            return buildTavilyFastLaneVerificationTarget(candidate);
        }

        SourceCollector.CollectedPage directPage = collectByDirectForPositiveShortcut(
                candidate,
                competitorName,
                sourceType,
                directCounters
        );
        if (directPage != null) {
            List<String> directMatchedSignals = collectMatchedSignals(candidate, directPage, sourceType);
            boolean directMarketingPage = isMarketingLandingPage(directPage, sourceType);
            boolean directRejectedMediator = candidateOwnershipPolicy.isRejectedMediator(candidate, directPage);
            boolean directOwnershipMatched = !candidateOwnershipPolicy.shouldRequireOwnershipValidation(candidate, sourceType)
                    || candidateOwnershipPolicy.hasCompetitorOwnershipSignal(competitorName, candidate, directPage);
            boolean directVerified = isVerified(
                    directPage,
                    directMatchedSignals,
                    directMarketingPage,
                    directRejectedMediator,
                    directOwnershipMatched
            );
            if (directVerified && searchBrowserProperties.isVerificationDirectPositiveShortcutEnabled()) {
                directCounters.shortcutCount.incrementAndGet();
                return buildVerificationTarget(
                        candidate,
                        directPage,
                        sourceType,
                        directMatchedSignals,
                        directMarketingPage,
                        directRejectedMediator,
                        directOwnershipMatched,
                        "DIRECT_HTML_VERIFICATION_SHORTCUT"
                );
            }
        }

        SourceCollector.CollectedPage page = collectWithRetry(candidate, competitorName, sourceType);
        List<String> matchedSignals = collectMatchedSignals(candidate, page, sourceType);
        boolean marketingPage = isMarketingLandingPage(page, sourceType);
        boolean rejectedMediator = candidateOwnershipPolicy.isRejectedMediator(candidate, page);
        boolean ownershipMatched = !candidateOwnershipPolicy.shouldRequireOwnershipValidation(candidate, sourceType)
                || candidateOwnershipPolicy.hasCompetitorOwnershipSignal(competitorName, candidate, page);
        return buildVerificationTarget(
                candidate,
                page,
                sourceType,
                matchedSignals,
                marketingPage,
                rejectedMediator,
                ownershipMatched,
                null
        );
    }

    private SearchCollectionTarget buildVerificationTarget(SourceCandidate candidate,
                                                           SourceCollector.CollectedPage page,
                                                           String sourceType,
                                                           List<String> matchedSignals,
                                                           boolean marketingPage,
                                                           boolean rejectedMediator,
                                                           boolean ownershipMatched,
                                                           String extraQualitySignal) {
        boolean verified = isVerified(page, matchedSignals, marketingPage, rejectedMediator, ownershipMatched);
        String verificationReason = buildVerificationReason(
                page,
                sourceType,
                matchedSignals,
                verified,
                marketingPage,
                rejectedMediator,
                ownershipMatched
        );
        List<String> qualitySignals = new ArrayList<>();
        if (candidate.getQualitySignals() != null) {
            qualitySignals.addAll(candidate.getQualitySignals());
        }
        if (StringUtils.hasText(extraQualitySignal)) {
            qualitySignals.add(extraQualitySignal);
        }
        SourceCandidate updatedCandidate = candidate.toBuilder()
                .verified(verified)
                .verificationReason(verificationReason)
                .matchedSignals(matchedSignals)
                .qualitySignals(qualitySignals)
                .selectionStage(verified ? "VERIFIED" : "DISCARDED")
                .selectionReason(verified ? "运行期验证通过，允许直接进入正式采集" : "运行期验证未通过，降级为候选兜底")
                .build();
        return SearchCollectionTarget.builder()
                .candidate(updatedCandidate)
                .collectedPage(page)
                .build();
    }

    /**
     * Tavily Fast Lane 的强候选在搜索阶段已经经过 pageType / qualityTier / completeness 闸门，
     * 这里不再重复发起 DirectHtml 或 Playwright 重验，避免把 Fast Lane 又退化回“搜索后仍然重新抓网页”的旧链路。
     */
    private boolean shouldSkipNetworkVerification(SourceCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        if (!"tavily".equalsIgnoreCase(candidate.getProviderKey())) {
            return false;
        }
        if (!Boolean.TRUE.equals(candidate.getFastLaneUsable())
                || !Boolean.TRUE.equals(candidate.getSkipNetworkVerification())) {
            return false;
        }
        if (candidate.getSourceUrls() == null || candidate.getSourceUrls().isEmpty()) {
            return false;
        }
        if (!StringUtils.hasText(candidate.getPageType())) {
            return false;
        }
        String pageType = candidate.getPageType().trim().toUpperCase(Locale.ROOT);
        return "ARTICLE".equals(pageType) || "OFFICIAL_DOC".equals(pageType) || "PDF".equals(pageType);
    }

    private SearchCollectionTarget buildTavilyFastLaneVerificationTarget(SourceCandidate candidate) {
        List<String> qualitySignals = new ArrayList<>();
        if (candidate.getQualitySignals() != null) {
            qualitySignals.addAll(candidate.getQualitySignals());
        }
        qualitySignals.add("TAVILY_VERIFICATION_SKIPPED");

        SourceCandidate updatedCandidate = candidate.toBuilder()
                .verified(true)
                .verificationReason("TAVILY_FAST_LANE_GATE_VERIFIED")
                .qualitySignals(qualitySignals)
                .selectionStage("VERIFIED")
                .selectionReason("通过 Tavily Prefetched Content Gate，跳过网络重验")
                .build();
        return SearchCollectionTarget.builder()
                .candidate(updatedCandidate)
                .collectedPage(null)
                .build();
    }

    private SourceCollector.CollectedPage collectByDirectForPositiveShortcut(SourceCandidate candidate,
                                                                             String competitorName,
                                                                             String sourceType,
                                                                             DirectVerificationCounters directCounters) {
        if (directHtmlReaderClient == null
                || !searchBrowserProperties.isVerificationDirectFirstEnabled()
                || candidate == null
                || !StringUtils.hasText(candidate.getUrl())) {
            return null;
        }
        try {
            directCounters.attemptCount.incrementAndGet();
            PageContentExtractionResult directResult = directHtmlReaderClient.collect(SourceCollectRequest.builder()
                    .url(candidate.getUrl())
                    .competitorName(competitorName)
                    .sourceType(sourceType)
                    .renderHint(WebPageRenderHint.LIGHTWEIGHT)
                    .sourceUrls(resolveAttemptUrls(candidate))
                    .build());
            SourceCollector.CollectedPage page = toCollectedPage(candidate, competitorName, sourceType, directResult);
            if (page != null) {
                directCounters.usableCount.incrementAndGet();
            }
            return page;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private SourceCollector.CollectedPage toCollectedPage(SourceCandidate candidate,
                                                          String competitorName,
                                                          String sourceType,
                                                          PageContentExtractionResult directResult) {
        if (candidate == null || directResult == null || !directResult.isUsable()) {
            return null;
        }
        String content = directResult.getMainContent();
        if (!StringUtils.hasText(content)) {
            return null;
        }
        return SourceCollector.CollectedPage.builder()
                .url(candidate.getUrl())
                .title(StringUtils.hasText(directResult.getTitle()) ? directResult.getTitle() : candidate.getTitle())
                .content(content)
                .snippet(content.length() > 500 ? content.substring(0, 500) : content)
                .competitorName(competitorName)
                .sourceType(sourceType)
                .success(true)
                .metadata("{\"collector\":\"direct-html-verification\",\"qualitySignals\":[\"DIRECT_HTML_VERIFICATION_READY\"]}")
                .build();
    }

    private List<SourceCandidate> deduplicateCandidates(List<SourceCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Set<String> seenUrls = new LinkedHashSet<>();
        List<SourceCandidate> uniqueCandidates = new ArrayList<>();
        for (SourceCandidate candidate : candidates) {
            if (candidate == null || !StringUtils.hasText(candidate.getUrl())) {
                continue;
            }
            String normalizedUrl = normalizeUrl(candidate.getUrl());
            if (!StringUtils.hasText(normalizedUrl) || !seenUrls.add(normalizedUrl)) {
                continue;
            }
            uniqueCandidates.add(candidate);
        }
        return uniqueCandidates;
    }

    /**
     * 不同来源类型有不同的命中信号。
     * 第一版先使用 URL、标题、摘要和正文关键词联合判断，保证规则透明且便于回归测试。
     */
    private List<String> collectMatchedSignals(SourceCandidate candidate,
                                               SourceCollector.CollectedPage page,
                                               String sourceType) {
        Set<String> signals = new LinkedHashSet<>();
        String combined = (safe(candidate.getUrl()) + "\n"
                + safe(page == null ? null : page.getTitle()) + "\n"
                + safe(page == null ? null : page.getSnippet()) + "\n"
                + safe(page == null ? null : page.getContent())).toLowerCase(Locale.ROOT);

        for (String keyword : searchKeywordPolicy.expectedKeywords(sourceType)) {
            if (combined.contains(keyword)) {
                signals.add(keyword);
            }
        }

        String domain = StringUtils.hasText(candidate.getDomain()) ? candidate.getDomain() : extractDomain(candidate.getUrl());
        if (StringUtils.hasText(domain)) {
            signals.add("domain:" + domain.toLowerCase(Locale.ROOT));
        }
        return new ArrayList<>(signals);
    }

    /**
     * 页面可用只是前提，真正通过还要满足：
     * 1. 命中与 sourceType 对应的有效信息词；
     * 2. 不能是明显的营销落地页。
     */
    private boolean isVerified(SourceCollector.CollectedPage page,
                               List<String> matchedSignals,
                               boolean marketingPage,
                               boolean rejectedMediator,
                               boolean ownershipMatched) {
        if (!isUsableCollectedPage(page)) {
            return false;
        }
        if (rejectedMediator || !ownershipMatched) {
            return false;
        }
        if (marketingPage) {
            return false;
        }
        return matchedSignals.stream().anyMatch(signal -> !signal.startsWith("domain:"));
    }

    private String buildVerificationReason(SourceCollector.CollectedPage page,
                                           String sourceType,
                                           List<String> matchedSignals,
                                           boolean verified,
                                           boolean marketingPage,
                                           boolean rejectedMediator,
                                           boolean ownershipMatched) {
        if (!isUsableCollectedPage(page)) {
            return page == null ? "采集器未返回页面结果" : safe(page.getErrorMessage(), "页面无可用正文");
        }
        if (rejectedMediator) {
            return "页面命中搜索引擎认证/企业信息中介特征，不能作为竞品正式采集入口";
        }
        if (!ownershipMatched) {
            return "页面缺少竞品归属信号，无法确认属于目标竞品";
        }
        if (marketingPage) {
            return "页面疑似营销落地页，缺少可用于分析的高价值信息，已按营销页丢弃";
        }
        if (verified) {
            return "命中 " + sourceType + " 目标信号: " + String.join(", ", matchedSignals);
        }
        return "页面已打开，但未命中 " + sourceType + " 所需特征";
    }

    /**
     * 只看标题/摘要/正文，不把 URL 里的 product 等路径词算作“高价值信息”，
     * 否则营销落地页会因为 URL 命中而被误判为有效页面。
     */
    private boolean isMarketingLandingPage(SourceCollector.CollectedPage page, String sourceType) {
        if (!isUsableCollectedPage(page)) {
            return false;
        }
        String textualContent = (safe(page.getTitle()) + "\n" + safe(page.getSnippet()) + "\n" + safe(page.getContent()))
                .toLowerCase(Locale.ROOT);
        boolean containsMarketingSignal = searchKeywordPolicy.marketingKeywords(sourceType).stream()
                .anyMatch(textualContent::contains);
        if (!containsMarketingSignal) {
            return false;
        }
        boolean containsHighValueInformation = searchKeywordPolicy.highValueInformationKeywords(sourceType).stream()
                .anyMatch(textualContent::contains);
        return !containsHighValueInformation;
    }

    private boolean isUsableCollectedPage(SourceCollector.CollectedPage page) {
        if (page == null || !page.isSuccess()) {
            return false;
        }
        boolean hasContent = StringUtils.hasText(page.getContent());
        boolean hasSnippet = StringUtils.hasText(page.getSnippet());
        return hasContent || hasSnippet;
    }

    /**
     * 结果页验证属于外部抓取行为，这里统一加上 try-catch 与有限重试，
     * 避免偶发抓取抖动把本可用候选过早判成失败。
     */
    private SourceCollector.CollectedPage collectWithRetry(SourceCandidate candidate,
                                                           String competitorName,
                                                           String sourceType) {
        RuntimeException lastError = null;
        for (String attemptUrl : resolveAttemptUrls(candidate)) {
            for (int attempt = 1; attempt <= MAX_COLLECT_ATTEMPTS; attempt++) {
                try {
                    SourceCollector.CollectedPage page = sourceCollector.collect(attemptUrl, competitorName, sourceType);
                    if (isUsableCollectedPage(page)) {
                        return page;
                    }
                } catch (RuntimeException ex) {
                    lastError = ex;
                }
            }
        }
        return SourceCollector.CollectedPage.builder()
                .url(candidate == null ? null : candidate.getUrl())
                .competitorName(competitorName)
                .sourceType(sourceType)
                .success(false)
                .errorMessage(lastError == null ? "结果页验证抓取失败" : lastError.getMessage())
                .build();
    }

    private String extractDomain(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * sourceUrls 保存了候选的原始发现入口。
     * 当 canonical URL 无法抓到可用页面时，继续按原始入口回退尝试，避免因为统一协议或去掉 www 丢失真实可访问地址。
     */
    private List<String> resolveAttemptUrls(SourceCandidate candidate) {
        if (candidate == null) {
            return List.of();
        }
        Set<String> attemptUrls = new LinkedHashSet<>();
        if (StringUtils.hasText(candidate.getUrl())) {
            attemptUrls.add(candidate.getUrl());
        }
        if (candidate.getSourceUrls() != null) {
            for (String sourceUrl : candidate.getSourceUrls()) {
                if (StringUtils.hasText(sourceUrl)) {
                    attemptUrls.add(sourceUrl);
                }
            }
        }
        return new ArrayList<>(attemptUrls);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String safe(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String normalizeUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = StringUtils.hasText(uri.getScheme()) ? uri.getScheme().toLowerCase(Locale.ROOT) : "https";
            String host = StringUtils.hasText(uri.getHost()) ? uri.getHost().toLowerCase(Locale.ROOT) : "";
            String path = uri.getPath() == null || uri.getPath().isBlank()
                    ? ""
                    : uri.getPath().replaceAll("/+$", "");
            return scheme + "://" + host + path;
        } catch (Exception e) {
            return url == null ? null : url.trim().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Direct 验证计数会在后续并发化中被多个候选任务共享，
     * 因此从一开始就使用 AtomicInteger，避免并发改造时重新定义统计语义。
     */
    private static class DirectVerificationCounters {

        private final AtomicInteger attemptCount = new AtomicInteger();
        private final AtomicInteger usableCount = new AtomicInteger();
        private final AtomicInteger shortcutCount = new AtomicInteger();
    }
}
