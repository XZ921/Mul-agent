package cn.bugstack.competitoragent.search;

/**
 * 浏览器故障恢复决策。
 * 这里不直接暴露“字符串包含什么”，而是把调用方真正关心的恢复动作收口成统一语义。
 */
public record BrowserFailureDecision(
        BrowserFailureKind kind,
        boolean closePageOnly,
        boolean closeContextOnly,
        boolean restartSharedBrowser,
        boolean recreateRuntime,
        boolean allowSingleRetry,
        boolean fallbackToHttp,
        boolean markBlocked
) {
}
