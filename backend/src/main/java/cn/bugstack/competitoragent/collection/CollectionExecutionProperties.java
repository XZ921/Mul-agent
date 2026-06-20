package cn.bugstack.competitoragent.collection;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Collection 执行效率配置。
 * 这些配置只控制执行方式和复用策略，不改变默认采集深度、页面数量和 sourceUrls 追溯语义。
 */
@Data
@Component
@ConfigurationProperties(prefix = "collection.execution")
public class CollectionExecutionProperties {

    /**
     * 是否复用搜索验证阶段已经抓到的页面快照。
     * 开启后可以避免同一 URL 在 search verification 与正式 collection 中重复 Playwright 渲染。
     */
    private boolean reusePrefetchedPage = true;

    /**
     * Collection 同一层级任务的最大并发数。
     * 默认 1 表示保持现有串行行为；调大后只并发同一 depth 的独立页面。
     */
    private int concurrency = 1;

    /**
     * 是否在 CollectionExecutionReport 中写入耗时统计。
     */
    private boolean timingEnabled = true;
}
