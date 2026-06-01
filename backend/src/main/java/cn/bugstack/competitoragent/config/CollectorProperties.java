package cn.bugstack.competitoragent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 采集阶段全局配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "collector")
public class CollectorProperties {

    /**
     * 每个竞品在搜索/采集阶段最多保留多少个正式目标页。
     */
    private int maxPagesPerCompetitor = 5;

    /**
     * 页面采集超时秒数。
     */
    private int pageTimeoutSeconds = 30;

    /**
     * 全局默认 User-Agent。
     */
    private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
}
