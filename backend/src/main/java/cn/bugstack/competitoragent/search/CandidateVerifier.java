package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCollector;
import lombok.RequiredArgsConstructor;
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
 * 当前阶段直接复用 SourceCollector 打开页面并抽取正文，用结果页内容判断该来源是否匹配当前采集目标。
 */
@Component
@RequiredArgsConstructor
public class CandidateVerifier {

    private final SourceCollector sourceCollector;

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
            SourceCollector.CollectedPage page = sourceCollector.collect(
                    candidate.getUrl(),
                    competitorName,
                    sourceType
            );
            List<String> matchedSignals = collectMatchedSignals(candidate, page, sourceType);
            boolean verified = isVerified(page, sourceType, matchedSignals);
            String verificationReason = buildVerificationReason(page, sourceType, matchedSignals, verified);

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
     * 第一版先使用 URL、标题和正文关键词联合判断，保证规则透明且便于回归测试。
     */
    private List<String> collectMatchedSignals(SourceCandidate candidate,
                                               SourceCollector.CollectedPage page,
                                               String sourceType) {
        Set<String> signals = new LinkedHashSet<>();
        String combined = (safe(candidate.getUrl()) + "\n" + safe(page == null ? null : page.getTitle())
                + "\n" + safe(page == null ? null : page.getContent())).toLowerCase(Locale.ROOT);

        for (String keyword : expectedKeywords(sourceType)) {
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

    private boolean isVerified(SourceCollector.CollectedPage page,
                               String sourceType,
                               List<String> matchedSignals) {
        if (!isUsableCollectedPage(page)) {
            return false;
        }
        if ("OFFICIAL".equalsIgnoreCase(sourceType)) {
            return true;
        }
        return matchedSignals.stream().anyMatch(signal -> !signal.startsWith("domain:"));
    }

    private String buildVerificationReason(SourceCollector.CollectedPage page,
                                           String sourceType,
                                           List<String> matchedSignals,
                                           boolean verified) {
        if (!isUsableCollectedPage(page)) {
            return page == null ? "采集器未返回页面结果" : safe(page.getErrorMessage(), "页面无可用正文");
        }
        if (verified) {
            return "命中 " + sourceType + " 目标信号：" + String.join(", ", matchedSignals);
        }
        return "页面已打开，但未命中 " + sourceType + " 所需特征";
    }

    private List<String> expectedKeywords(String sourceType) {
        return switch (sourceType == null ? "" : sourceType.toUpperCase(Locale.ROOT)) {
            case "DOCS" -> List.of("docs", "documentation", "help", "guide", "api", "reference");
            case "PRICING" -> List.of("pricing", "plan", "plans", "billing", "subscription", "enterprise");
            case "NEWS" -> List.of("blog", "news", "changelog", "update", "release", "announcement");
            case "REVIEW" -> List.of("review", "reviews", "rating", "customer", "compare", "g2", "capterra");
            default -> List.of("official", "product", "platform", "homepage");
        };
    }

    private boolean isUsableCollectedPage(SourceCollector.CollectedPage page) {
        if (page == null || !page.isSuccess()) {
            return false;
        }
        boolean hasContent = StringUtils.hasText(page.getContent());
        boolean hasSnippet = StringUtils.hasText(page.getSnippet());
        return hasContent || hasSnippet;
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
