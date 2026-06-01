package cn.bugstack.competitoragent.search;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SerpAPI 配置。
 */
@Data
@ConfigurationProperties(prefix = "serpapi")
public class SerpApiProperties {

    private String apiKey;
    private String endpoint = "https://serpapi.com/search";
    private String defaultEngine = "google";
}
