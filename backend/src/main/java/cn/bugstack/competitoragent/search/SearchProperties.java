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
     * 搜索模式：
     * HEURISTIC_ONLY / HTTP_ONLY / BROWSER_ONLY / HYBRID
     */
    private String mode = "HYBRID";
}
