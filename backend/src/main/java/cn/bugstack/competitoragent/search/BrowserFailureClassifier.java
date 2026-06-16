package cn.bugstack.competitoragent.search;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 浏览器故障统一分类器。
 * 这里负责把底层异常和外部检测结果映射成稳定的恢复决策，
 * 避免运行时搜索和页面采集继续各自手写 contains 判断，导致恢复行为分叉。
 */
@Component
public class BrowserFailureClassifier {

    public BrowserFailureDecision classify(Throwable error, AntiBotDetectionResult detection) {
        if (detection != null && detection.isBlocked()) {
            return new BrowserFailureDecision(
                    BrowserFailureKind.ANTI_BOT_BLOCKED,
                    true,
                    false,
                    false,
                    false,
                    false,
                    true,
                    true
            );
        }

        String normalized = normalize(error);
        if (normalized.contains("playwright connection closed")
                || normalized.contains("transport closed")
                || normalized.contains("pipe closed")) {
            return new BrowserFailureDecision(
                    BrowserFailureKind.RUNTIME_PIPE_BROKEN,
                    false,
                    false,
                    true,
                    true,
                    true,
                    true,
                    false
            );
        }
        if (normalized.contains("target page, context or browser has been closed")
                || normalized.contains("browser has been closed")) {
            return new BrowserFailureDecision(
                    BrowserFailureKind.BROWSER_INSTANCE_DEAD,
                    false,
                    false,
                    true,
                    false,
                    true,
                    true,
                    false
            );
        }
        if (normalized.contains("target.createtarget")) {
            return new BrowserFailureDecision(
                    BrowserFailureKind.PAGE_OR_CONTEXT_RESOURCE_FAILURE,
                    false,
                    true,
                    false,
                    false,
                    false,
                    true,
                    false
            );
        }
        if (normalized.contains("timeout") || normalized.contains("timed out")) {
            return new BrowserFailureDecision(
                    BrowserFailureKind.SEARCH_TIMEOUT,
                    true,
                    false,
                    false,
                    false,
                    false,
                    true,
                    false
            );
        }
        return new BrowserFailureDecision(
                BrowserFailureKind.RUNTIME_FAILURE,
                false,
                true,
                false,
                false,
                false,
                true,
                false
        );
    }

    /**
     * 为了兼容包装异常、根因异常和不同组件的 message 前缀，
     * 这里把整条 cause 链的消息和异常类型统一拍平成小写文本后再匹配。
     */
    private String normalize(Throwable error) {
        if (error == null) {
            return "";
        }
        Set<String> fragments = new LinkedHashSet<>();
        Throwable current = error;
        while (current != null) {
            fragments.add(current.getClass().getName());
            if (current.getMessage() != null) {
                fragments.add(current.getMessage());
            }
            current = current.getCause();
        }
        return String.join(" ", fragments).toLowerCase(Locale.ROOT);
    }
}
