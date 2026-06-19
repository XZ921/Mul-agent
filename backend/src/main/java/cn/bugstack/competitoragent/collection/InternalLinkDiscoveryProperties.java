package cn.bugstack.competitoragent.collection;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 站内链接发现配置。
 * 这一层集中控制递归采集的深度与数量预算，避免内部发现能力接入后出现“默认无限下钻”的风险。
 */
@Data
@Component
@ConfigurationProperties(prefix = "collection.internal-link-discovery")
public class InternalLinkDiscoveryProperties {

    /**
     * 是否启用内部链接发现。
     */
    private boolean enabled = true;

    /**
     * 最大递归深度。
     * 入口页 depth=0，当 currentDepth 已达到该值时，不再继续发现子链接。
     */
    private int maxDepth = 2;

    /**
     * 每个入口页最多允许追加多少个内部发现链接。
     */
    private int maxLinksPerEntry = 10;

    /**
     * 每个页面节点最多允许向下发现多少个候选链接。
     */
    private int maxLinksPerNode = 30;
}
