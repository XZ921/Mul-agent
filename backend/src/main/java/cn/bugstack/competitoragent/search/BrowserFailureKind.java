package cn.bugstack.competitoragent.search;

/**
 * 浏览器链路故障分类枚举。
 * 这里把浏览器运行时、页面/上下文资源、超时以及反爬阻断拆成稳定的有限集合，
 * 供运行时搜索和页面采集统一消费，禁止继续散落字符串判断。
 */
public enum BrowserFailureKind {
    PAGE_TIMEOUT,
    SEARCH_TIMEOUT,
    PAGE_OR_CONTEXT_RESOURCE_FAILURE,
    BROWSER_INSTANCE_DEAD,
    RUNTIME_PIPE_BROKEN,
    ANTI_BOT_BLOCKED,
    CONTENT_UNUSABLE,
    RUNTIME_FAILURE
}
