package cn.bugstack.competitoragent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 官网强依赖降级：OFFICIAL/DOCS 节点官网采集失败后回退第三方源的配置。
 * 用于让 Notion 这类强反爬官网在官网打不开时，仍能通过第三方转述产出证据。
 */
@Data
@Component
@ConfigurationProperties(prefix = "collector.thirdparty-fallback")
public class ThirdPartyFallbackProperties {

    /**
     * 是否启用第三方回退。关闭后行为与旧版一致，便于回归对照。
     */
    private boolean enabled = true;

    /**
     * 搜索选中 0 条时是否触发回退。
     */
    private boolean triggerOnZeroSelected = true;

    /**
     * 已采证据数低于该阈值时触发回退（含 HARD_DEADLINE 后证据不足场景）。
     */
    private int minEvidenceThreshold = 1;

    /**
     * 给第三方兜底预留的时间窗口。主搜索/主采集会提前收口，fallback 继续使用原始 hard deadline。
     */
    private long reserveMillis = 25_000L;

    /**
     * 启动第三方兜底前至少需要剩余的时间，避免只剩几秒时发起注定超时的外部搜索。
     */
    private long minStartMillis = 15_000L;

    /**
     * 允许触发回退的 sourceType。默认只覆盖官网/文档这类强官网依赖节点。
     */
    private List<String> sourceTypes = List.of("OFFICIAL", "DOCS");

    /**
     * 第三方回退候选统一标记的信任级别。
     */
    private String trustTier = "MEDIUM";
}
