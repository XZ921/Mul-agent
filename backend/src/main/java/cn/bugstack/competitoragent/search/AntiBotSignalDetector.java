package cn.bugstack.competitoragent.search;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 多信号反爬检测器。
 * 这里严格按照“HTTP/URL/标题/正文/结构”多维组合来判断 blocked 或 suspected，
 * 避免继续使用“命中任意一个关键词就直接 blocked”的单点规则。
 */
@Component
public class AntiBotSignalDetector {

    private final SearchBrowserProperties properties;

    public AntiBotSignalDetector(SearchBrowserProperties properties) {
        this.properties = properties;
    }

    public AntiBotDetectionResult detect(BrowserSignalSnapshot snapshot) {
        return detect(snapshot, null);
    }

    /**
     * 运行时允许节点级策略覆盖全局默认阈值和信号列表，
     * 这样计划期写入的 searchRuntimePolicy 就能真正影响本次浏览器检测行为。
     */
    public AntiBotDetectionResult detect(BrowserSignalSnapshot snapshot, SearchRuntimePolicy runtimePolicy) {
        BrowserSignalSnapshot safeSnapshot = snapshot == null ? new BrowserSignalSnapshot() : snapshot;
        String normalizedUrl = normalize(safeSnapshot.getFinalUrl());
        String normalizedTitle = normalize(safeSnapshot.getPageTitle());
        String normalizedBody = normalize(safeSnapshot.getBodyText());

        List<String> matchedSignals = new ArrayList<>();
        List<String> matchedSelectors = new ArrayList<>();

        Integer httpStatus = safeSnapshot.getHttpStatus();
        if (httpStatus != null && (httpStatus == 403 || httpStatus == 429)) {
            matchedSignals.add("http_status:" + httpStatus);
            return buildResult(true, false, "HTTP_STATUS_BLOCKED", safeSnapshot, matchedSignals, matchedSelectors);
        }

        boolean urlRiskMatched = false;
        for (String keyword : resolveBlockedUrlKeywords(runtimePolicy)) {
            String normalizedKeyword = normalize(keyword);
            if (normalizedKeyword.isBlank()) {
                continue;
            }
            if (normalizedUrl.contains(normalizedKeyword)) {
                matchedSignals.add("url:" + normalizedKeyword);
                urlRiskMatched = true;
            }
        }

        boolean titleRiskMatched = false;
        boolean bodyRiskMatched = false;
        for (String signal : resolveBlockedSignals(runtimePolicy)) {
            String normalizedSignal = normalize(signal);
            if (normalizedSignal.isBlank()) {
                continue;
            }
            if (normalizedTitle.contains(normalizedSignal)) {
                matchedSignals.add("title:" + normalizedSignal);
                titleRiskMatched = true;
            }
            if (normalizedBody.contains(normalizedSignal)) {
                matchedSignals.add("body:" + normalizedSignal);
                bodyRiskMatched = true;
            }
        }

        int bodyLength = safeSnapshot.getBodyLength() > 0
                ? safeSnapshot.getBodyLength()
                : normalizedBody.trim().length();
        int primaryResultCount = Math.max(0, safeSnapshot.getPrimaryResultCount());
        boolean bodyTooShort = safeSnapshot.isBodyTooShort() || bodyLength < resolveShortBodyThreshold(runtimePolicy);
        boolean missingPrimaryResults = safeSnapshot.isMissingPrimaryResults()
                || primaryResultCount < resolveMinimumPrimaryResultCount(runtimePolicy);
        boolean suspectShortBody = bodyLength < resolveSuspectBlockedBodyThreshold(runtimePolicy);

        if (bodyTooShort) {
            matchedSelectors.add("body_too_short");
        }
        if (missingPrimaryResults) {
            matchedSelectors.add("missing_primary_results");
        }

        if (urlRiskMatched && titleRiskMatched) {
            return buildResult(true, false, "LOGIN_OR_CHALLENGE_REDIRECT", safeSnapshot, matchedSignals, matchedSelectors);
        }

        if ((bodyRiskMatched || titleRiskMatched)
                && (suspectShortBody || bodyTooShort || missingPrimaryResults)) {
            return buildResult(true, false, "TEXT_SIGNAL_SHORT_BODY_BLOCKED", safeSnapshot, matchedSignals, matchedSelectors);
        }

        if (bodyRiskMatched || titleRiskMatched || urlRiskMatched) {
            return buildResult(false, true, "TEXT_SIGNAL_ONLY", safeSnapshot, matchedSignals, matchedSelectors);
        }

        if (bodyTooShort) {
            return buildResult(false, true, "BODY_TOO_SHORT", safeSnapshot, matchedSignals, matchedSelectors);
        }

        if (missingPrimaryResults) {
            return buildResult(false, true, "MISSING_PRIMARY_RESULTS", safeSnapshot, matchedSignals, matchedSelectors);
        }

        return buildResult(false, false, "NONE", safeSnapshot, matchedSignals, matchedSelectors);
    }

    private AntiBotDetectionResult buildResult(boolean blocked,
                                               boolean suspected,
                                               String reasonCode,
                                               BrowserSignalSnapshot snapshot,
                                               List<String> matchedSignals,
                                               List<String> matchedSelectors) {
        return AntiBotDetectionResult.builder()
                .blocked(blocked)
                .suspected(suspected)
                .reasonCode(reasonCode)
                .httpStatus(snapshot == null ? null : snapshot.getHttpStatus())
                .finalUrl(snapshot == null ? null : snapshot.getFinalUrl())
                .pageTitle(snapshot == null ? null : snapshot.getPageTitle())
                .matchedSignals(matchedSignals)
                .matchedSelectors(matchedSelectors)
                .build();
    }

    private List<String> resolveBlockedSignals(SearchRuntimePolicy runtimePolicy) {
        if (runtimePolicy != null
                && runtimePolicy.getBlockedSignals() != null
                && !runtimePolicy.getBlockedSignals().isEmpty()) {
            return runtimePolicy.getBlockedSignals();
        }
        return properties.getBlockedSignals() == null ? List.of() : properties.getBlockedSignals();
    }

    private List<String> resolveBlockedUrlKeywords(SearchRuntimePolicy runtimePolicy) {
        if (runtimePolicy != null
                && runtimePolicy.getBlockedUrlKeywords() != null
                && !runtimePolicy.getBlockedUrlKeywords().isEmpty()) {
            return runtimePolicy.getBlockedUrlKeywords();
        }
        return properties.getBlockedUrlKeywords() == null ? List.of() : properties.getBlockedUrlKeywords();
    }

    private int resolveShortBodyThreshold(SearchRuntimePolicy runtimePolicy) {
        if (runtimePolicy != null && runtimePolicy.getShortBodyThreshold() != null) {
            return Math.max(1, runtimePolicy.getShortBodyThreshold());
        }
        return Math.max(1, properties.getShortBodyThreshold());
    }

    private int resolveMinimumPrimaryResultCount(SearchRuntimePolicy runtimePolicy) {
        if (runtimePolicy != null && runtimePolicy.getMinimumPrimaryResultCount() != null) {
            return Math.max(0, runtimePolicy.getMinimumPrimaryResultCount());
        }
        return Math.max(0, properties.getMinimumPrimaryResultCount());
    }

    private int resolveSuspectBlockedBodyThreshold(SearchRuntimePolicy runtimePolicy) {
        if (runtimePolicy != null && runtimePolicy.getSuspectBlockedBodyThreshold() != null) {
            return Math.max(1, runtimePolicy.getSuspectBlockedBodyThreshold());
        }
        return Math.max(1, properties.getSuspectBlockedBodyThreshold());
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
