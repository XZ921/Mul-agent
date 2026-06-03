package cn.bugstack.competitoragent.search;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 运行期浏览器搜索配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "search.browser")
public class SearchBrowserProperties {

    private boolean enabled = true;
    private String engine = "baidu";
    private List<String> fallbackEngines = List.of("bing");
    private boolean verifyResultPage = true;
    private int maxResultsPerQuery = 5;
    private int maxOpenResultPages = 3;
    private int resultPageTimeoutMillis = 8000;
    private int maxContentLengthPerPage = 500;
    private int maxRetries = 2;
    private long minIntervalMillis = 3000L;
    private int maxSearchesPerTask = 10;
    private int pageTimeoutMillis = 15000;
    private boolean continueOnBrowserUnavailable = true;
    private boolean continueOnSearchTimeout = true;
    private boolean continueOnPageCollectFailure = true;
    private boolean recoverPartialContentOnTimeout = true;
    private List<String> userAgents = List.of(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_4) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:124.0) Gecko/20100101 Firefox/124.0",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14.4; rv:124.0) Gecko/20100101 Firefox/124.0"
    );
    private List<String> blockedSignals = List.of(
            "captcha",
            "unusual traffic",
            "verify you are human",
            "access denied",
            "robot check"
    );
}
