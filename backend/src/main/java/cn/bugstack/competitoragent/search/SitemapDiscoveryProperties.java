package cn.bugstack.competitoragent.search;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Sitemap/robots 发现配置。
 * <p>
 * 这一层只负责控制低成本站点入口补全能力的预算，避免把 sitemap 抓取策略散落到业务代码里。
 */
@Data
@Component
@ConfigurationProperties(prefix = "search.discovery.sitemap")
public class SitemapDiscoveryProperties {

    /**
     * 是否启用 sitemap/robots 发现。
     */
    private boolean enabled = true;

    /**
     * 单次网络请求超时时间，单位毫秒。
     */
    private int timeoutMillis = 3000;

    /**
     * 每个根域最多递归解析多少个 sitemap 文档。
     */
    private int maxSitemapsPerDomain = 3;

    /**
     * 单个 sitemap 文档最多采纳多少条 URL。
     */
    private int maxUrlsPerSitemap = 80;

    /**
     * 网络请求最大重试次数。
     */
    private int maxRetries = 1;
}
