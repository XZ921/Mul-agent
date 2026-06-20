package cn.bugstack.competitoragent.collection;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 网页采集路由配置。
 * 这里放的是 Direct/Jina/Playwright 之间的路由策略，不能放在 JinaReaderProperties 中，
 * 因为补链接策略同时适用于 DirectHtmlReader 与 JinaReader 的轻量正文结果。
 */
@Data
@ConfigurationProperties(prefix = "collection.web-page")
public class WebPageCollectionProperties {

    /**
     * 当轻量正文可用但入口页没有发现足够站内链接时，是否允许 Playwright 只补充链接。
     */
    private boolean playwrightLinkSupplementEnabled = true;

    /**
     * 允许 Playwright 补链接的最大采集深度。
     * 默认 0 表示只允许入口页补链接，递归详情页不因为链接少而升级 Playwright。
     */
    private int playwrightLinkSupplementMaxDepth = 0;

    /**
     * 轻量结果发现的站内链接数量低于该值时，入口页才触发 Playwright 补链接。
     */
    private int playwrightLinkSupplementMinLinks = 1;

    /**
     * 允许 Playwright 执行入口页补链接的来源类型白名单。
     * 默认只覆盖 DOCS，避免官网、新闻、评价页被过度渲染；如果真实冒烟证明 OFFICIAL 入口页缺链接，
     * 可以通过配置追加 OFFICIAL，而不需要改代码。
     */
    private List<String> playwrightLinkSupplementSourceTypes = List.of("DOCS");
}
