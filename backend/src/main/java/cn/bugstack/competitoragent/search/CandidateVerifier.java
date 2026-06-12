package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCollector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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

    /**
     * 运行时必须显式注入 SourceCollector 与 SearchKeywordPolicy，
     * 否则 Spring 在某些集成测试装配路径下会退回到默认构造尝试并直接失败。
     */
    @Autowired
    public CandidateVerifier(SourceCollector sourceCollector, SearchKeywordPolicy searchKeywordPolicy) {
        this.sourceCollector = sourceCollector;
        this.searchKeywordPolicy = searchKeywordPolicy;
    }

    public CandidateVerifier(SourceCollector sourceCollector) {
        this(sourceCollector, new SearchKeywordPolicy());
    }

    public CandidateVerificationResult verify(String competitorName,
                                              String sourceType,
                                              List<SourceCandidate> candidates) {
        List<SourceCandidate> uniqueCandidates = deduplicateCandidates(candidates);
        if (uniqueCandidates.isEmpty()) {
            return CandidateVerificationResult.builder()
                    .updatedCandidates(List.of())
                    .attemptedTargets(List.of())
                    .verifiedTargets(List.of())
                    .build();
        }

        List<SourceCandidate> updatedCandidates = new ArrayList<>();
        List<SearchCollectionTarget> attemptedTargets = new ArrayList<>();
        List<SearchCollectionTarget> verifiedTargets = new ArrayList<>();

        for (SourceCandidate candidate : uniqueCandidates) {
            SourceCollector.CollectedPage page = collectWithRetry(candidate, competitorName, sourceType);
            List<String> matchedSignals = collectMatchedSignals(candidate, page, sourceType);
            boolean marketingPage = isMarketingLandingPage(page, sourceType);
            boolean verified = isVerified(page, matchedSignals, marketingPage);
            String verificationReason = buildVerificationReason(page, sourceType, matchedSignals, verified, marketingPage);

            SourceCandidate updatedCandidate = candidate.toBuilder()
                    .verified(verified)
                    .verificationReason(verificationReason)
                    .matchedSignals(matchedSignals)
                    .selectionStage(verified ? "VERIFIED" : "DISCARDED")
                    .selectionReason(verified ? "运行期验证通过，允许直接进入正式采集" : "运行期验证未通过，降级为候选兜底")
                    .build();
            SearchCollectionTarget target = SearchCollectionTarget.builder()
                    .candidate(updatedCandidate)
                    .collectedPage(page)
                    .build();

            updatedCandidates.add(updatedCandidate);
            attemptedTargets.add(target);
            if (verified) {
                verifiedTargets.add(target);
            }
        }

        return CandidateVerificationResult.builder()
                .updatedCandidates(updatedCandidates)
                .attemptedTargets(attemptedTargets)
                .verifiedTargets(verifiedTargets)
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
                               boolean marketingPage) {
        if (!isUsableCollectedPage(page)) {
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
                                           boolean marketingPage) {
        if (!isUsableCollectedPage(page)) {
            return page == null ? "采集器未返回页面结果" : safe(page.getErrorMessage(), "页面无可用正文");
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
        for (int attempt = 1; attempt <= MAX_COLLECT_ATTEMPTS; attempt++) {
            try {
                return sourceCollector.collect(candidate.getUrl(), competitorName, sourceType);
            } catch (RuntimeException ex) {
                lastError = ex;
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
}
