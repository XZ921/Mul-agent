package cn.bugstack.competitoragent.search;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 搜索阶段全局配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "search")
public class SearchProperties {

    /**
     * 搜索模式。
     * 兼容 HEURISTIC_ONLY / HTTP_ONLY / BROWSER_ONLY / HYBRID 旧输入，
     * 但正式 fallback 语义统一由 SearchPolicyResolver 收口。
     */
    private String mode = "HYBRID";

    /**
     * 数据源家族目录。
     * 把官网、新闻、GitHub 等业务来源显式挂到搜索配置下，
     * 避免后续继续把 provider / engine 当成业务数据源本身。
     */
    private SearchSourceCatalogProperties sourceCatalog = new SearchSourceCatalogProperties();
}
