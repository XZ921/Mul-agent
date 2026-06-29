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
     * 默认 3 表示在不打破同层顺序语义的前提下，优先把真实可并发的页面任务跑起来。
     */
    private int concurrency = 3;

    /**
     * 是否优先执行带有 Tavily 预取正文的任务包。
     * 开启后可先消费 TAVILY_PREFETCHED，减少慢网页包拖住整批结果。
     */
    private boolean prioritizePrefetchedPackages = true;

    /**
     * 是否在 CollectionExecutionReport 中写入耗时统计。
     */
    private boolean timingEnabled = true;
}
