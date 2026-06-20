package cn.bugstack.competitoragent.source;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Direct HTML 轻量采集配置。
 * 这一链路直接访问目标页面并用 Jsoup 提取正文，目标是绕过 r.jina.ai 转发链路，
 * 优先覆盖静态页面和服务端渲染页面；如果页面是 SPA 空壳，则明确返回不可用，让外层继续走 Jina 或 Playwright。
 */
@Data
@ConfigurationProperties(prefix = "collection.direct-html-reader")
public class DirectHtmlReaderProperties {

    /**
     * 是否启用 Direct HTML 直连采集。
     * 默认开启，因为它比 JinaReader 少一层外部代理，失败时也不会阻断后续轻量/渲染兜底。
     */
    private boolean enabled = true;

    /**
     * 单次直连目标站点的超时时间。
     * 该值必须短于 Playwright 超时，避免轻量链路拖慢整个采集阶段。
     */
    private int timeoutSeconds = 8;

    /**
     * Direct HTML 直连失败后的最大重试次数。
     * 总尝试次数为 maxRetries + 1。
     */
    private int maxRetries = 1;

    /**
     * 正文最小可用长度。
     * 低于该阈值时视为正文过薄，继续交给 JinaReader 或 Playwright 兜底。
     */
    private int minimumContentLength = 160;

    /**
     * SPA 空壳判断中的可读中文保护阈值。
     * 有些国内文档页 DOM 看起来像 SPA，但服务端 HTML 已经包含一段可读中文；
     * 当正文中的中文字符数达到该值时，不按 SPA 空壳失败处理，避免误伤半静态页面。
     */
    private int readableChineseGuardChars = 80;

    /**
     * 最多从页面中保留多少个链接到正文尾部。
     * InternalLinkDiscoveryService 会消费这些 Markdown 链接继续做站内递归发现。
     */
    private int maxExtractedLinks = 80;

    /**
     * Direct HTTP 请求使用的 User-Agent。
     * 使用普通桌面浏览器 UA，降低被简单反爬规则拦截的概率。
     */
    private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
}
