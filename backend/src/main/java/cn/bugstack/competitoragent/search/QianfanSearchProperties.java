package cn.bugstack.competitoragent.search;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 千帆 AI Search 配置。
 */
@Data
@ConfigurationProperties(prefix = "qianfan-search")
public class QianfanSearchProperties {

    private String apiKey;
    private String endpoint = "https://qianfan.baidubce.com/v2/ai_search/web_search";
    private String defaultEngine = "baidu";
}
