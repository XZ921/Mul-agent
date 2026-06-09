package cn.bugstack.competitoragent.search;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * 千帆 AI Search 配置。
 */
@Data
@ConfigurationProperties(prefix = "qianfan-search")
public class QianfanSearchProperties {

    private String apiKey;
    private String endpoint = "https://qianfan.baidubce.com/v2/ai_search/web_search";
    private String defaultEngine = "baidu";

    /**
     * 解析千帆默认搜索引擎对应的稳定 key。
     * 这里复用 SearchEngineProperties 的别名归一化能力，避免 msedge / ddg 这类别名写法
     * 在审计和路由记录中落成不一致的引擎标识。
     */
    public String resolveDefaultEngineKey(SearchEngineProperties searchEngineProperties) {
        if (searchEngineProperties == null) {
            return StringUtils.hasText(defaultEngine) ? defaultEngine : "baidu";
        }
        return searchEngineProperties.resolveAvailableEngineKey(defaultEngine);
    }
}
