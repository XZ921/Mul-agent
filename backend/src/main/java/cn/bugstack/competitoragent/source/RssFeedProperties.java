package cn.bugstack.competitoragent.source;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RSS feed 采集配置。
 * 当前阶段先只承接显式 feed URL 的一次性读取，
 * 因此配置只收口启用开关、超时、重试和单 feed 最大条目数这些最小必需项。
 */
@Data
@ConfigurationProperties(prefix = "collection.rss-feed")
public class RssFeedProperties {

    /**
     * 是否启用 RSS 采集能力。
     */
    private boolean enabled = true;

    /**
     * 单次请求超时时间，单位秒。
     */
    private int timeoutSeconds = 15;

    /**
     * 网络请求最大重试次数。
     */
    private int maxRetries = 2;

    /**
     * 单个 feed 最多保留多少条 item。
     */
    private int maxItemsPerFeed = 5;

    /**
     * 当响应明显不是 feed 时是否快速失败。
     */
    private boolean failFastOnNonFeedContent = true;
}
