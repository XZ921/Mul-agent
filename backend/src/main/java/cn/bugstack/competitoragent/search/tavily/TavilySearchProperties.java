package cn.bugstack.competitoragent.search.tavily;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * Tavily Search API 配置。
 * 这里先把 Tavily 作为一条独立的搜索能力收口为最小配置对象，
 * 方便后续 provider、readiness guard、security guard 和重试策略复用同一份配置语义。
 */
@Data
@ConfigurationProperties(prefix = "tavily-search")
public class TavilySearchProperties {

    /**
     * 是否允许启用 Tavily 能力。
     * 默认关闭，避免在未完成灰度与验收前影响现有搜索链路。
     */
    private boolean enabled = false;

    /**
     * Tavily Search API 入口地址。
     */
    private String endpoint = "https://api.tavily.com/search";

    /**
     * Tavily API Key。
     */
    private String apiKey;

    /**
     * 搜索深度，MVP 默认走 advanced 以便尽量拿到更完整的候选与正文。
     */
    private String searchDepth = "advanced";

    /**
     * 是否请求 Tavily 返回 raw_content。
     * Fast Lane 的价值就在于搜索阶段即拿到正文，因此这里默认开启。
     */
    private boolean includeRawContent = true;

    /**
     * 单次查询最多返回多少条结果。
     */
    private int maxResults = 5;

    /**
     * HTTP 调用超时时间，单位秒。
     */
    private int timeoutSeconds = 45;

    /**
     * Tavily 调用最大重试次数。
     */
    private int maxRetries = 2;

    /**
     * 进入 Fast Lane 质量门禁前要求的最小 raw_content 长度。
     */
    private int minRawContentChars = 500;

    /**
     * Tavily 原始得分下限。
     */
    private double minTavilyScore = 0.45D;

    /**
     * 判断 Tavily 是否具备最小配置条件。
     * 这里显式要求 endpoint 和 apiKey 同时存在，避免只开开关却没有真正可调用的配置。
     */
    public boolean isConfigured() {
        return StringUtils.hasText(endpoint) && StringUtils.hasText(apiKey);
    }

    /**
     * 判断 Tavily 是否真正 ready。
     * enabled 表示“允许启用”，configured 表示“配置完整”，只有两者都满足才可对外宣称可用。
     */
    public boolean isReady() {
        return enabled && isConfigured();
    }

    /**
     * 统一生成 readiness 失败原因，便于启动守卫与运行时诊断输出一致的说明。
     */
    public String resolveReadinessFailureMessage() {
        if (!enabled) {
            return "tavily disabled";
        }
        if (!StringUtils.hasText(endpoint)) {
            return "tavily endpoint missing";
        }
        if (!StringUtils.hasText(apiKey)) {
            return "tavily api key missing";
        }
        return null;
    }
}
