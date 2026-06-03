package cn.bugstack.competitoragent.search;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * 搜索运行时回退策略。
 * 统一收口浏览器不可用、搜索超时、单页采集失败等场景下的降级开关与诊断文案，
 * 避免各个运行时组件各自拼接错误信息，导致行为不一致、难以测试。
 */
@Component
public class SearchRuntimeFallbackPolicy {

    private final SearchBrowserProperties properties;

    public SearchRuntimeFallbackPolicy(SearchBrowserProperties properties) {
        this.properties = properties;
    }

    public boolean shouldContinueOnBrowserUnavailable(SearchRuntimePolicy runtimePolicy) {
        if (runtimePolicy != null && runtimePolicy.getContinueOnBrowserUnavailable() != null) {
            return Boolean.TRUE.equals(runtimePolicy.getContinueOnBrowserUnavailable());
        }
        return properties.isContinueOnBrowserUnavailable();
    }

    public boolean shouldContinueOnSearchTimeout(SearchRuntimePolicy runtimePolicy) {
        if (runtimePolicy != null && runtimePolicy.getContinueOnSearchTimeout() != null) {
            return Boolean.TRUE.equals(runtimePolicy.getContinueOnSearchTimeout());
        }
        return properties.isContinueOnSearchTimeout();
    }

    public boolean shouldContinueOnPageCollectFailure(SearchRuntimePolicy runtimePolicy) {
        if (runtimePolicy != null && runtimePolicy.getContinueOnPageCollectFailure() != null) {
            return Boolean.TRUE.equals(runtimePolicy.getContinueOnPageCollectFailure());
        }
        return properties.isContinueOnPageCollectFailure();
    }

    public boolean shouldRecoverPartialContentOnTimeout(SearchRuntimePolicy runtimePolicy) {
        if (runtimePolicy != null && runtimePolicy.getRecoverPartialContentOnTimeout() != null) {
            return Boolean.TRUE.equals(runtimePolicy.getRecoverPartialContentOnTimeout());
        }
        return properties.isRecoverPartialContentOnTimeout();
    }

    /**
     * 把底层异常统一规约成有限的诊断代码，便于测试断言和前端展示。
     */
    public String classifyRuntimeFailure(Throwable error) {
        String normalized = error == null || error.getMessage() == null
                ? ""
                : error.getMessage().toLowerCase(Locale.ROOT);
        if (normalized.contains("timeout")) {
            return "search_timeout";
        }
        if (normalized.contains("browser has been closed")
                || normalized.contains("target page, context or browser has been closed")
                || normalized.contains("playwright connection closed")
                || normalized.contains("connection closed")) {
            return "browser_unavailable";
        }
        return "runtime_failure";
    }

    public String buildSearchFallbackSummary(String failureCode, String detail) {
        String suffix = StringUtils.hasText(detail) ? "，原因：" + detail : "";
        return switch (normalizeCode(failureCode)) {
            case "browser_unavailable" -> "浏览器实例不可用，已回退到 HTTP/规划候选链路" + suffix;
            case "search_timeout" -> "浏览器搜索执行超时，已回退到 HTTP/规划候选链路" + suffix;
            case "blocked" -> "浏览器搜索疑似触发反爬或访问受限，已回退到 HTTP/规划候选链路" + suffix;
            default -> "浏览器搜索运行时失败，已回退到 HTTP/规划候选链路" + suffix;
        };
    }

    public String buildCollectionFailureMessage(String failureCode, String detail) {
        String suffix = StringUtils.hasText(detail) ? "：" + detail : "";
        return switch (normalizeCode(failureCode)) {
            case "browser_unavailable" -> "页面采集时浏览器不可用，已降级返回失败结果" + suffix;
            case "search_timeout" -> "页面采集超时，已降级返回失败结果" + suffix;
            default -> "页面采集失败，已降级返回失败结果" + suffix;
        };
    }

    private String normalizeCode(String failureCode) {
        return failureCode == null ? "" : failureCode.trim().toLowerCase(Locale.ROOT);
    }
}
