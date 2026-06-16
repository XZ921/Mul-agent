package cn.bugstack.competitoragent.search;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 搜索运行期策略。
 * 统一承接重试、限流、反爬与恢复相关配置，便于规划期写入、运行期执行、审计时展示。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SearchRuntimePolicy {

    private Boolean verifyResultPage;
    private Integer maxRetries;
    private Long minIntervalMillis;
    private Integer maxSearchesPerTask;
    private Integer pageTimeoutMillis;
    private Integer maxOpenResultPages;
    private Integer resultPageTimeoutMillis;
    private Integer maxContentLengthPerPage;
    private Boolean stealthEnabled;
    private String locale;
    private String timezoneId;
    private Integer viewportWidth;
    private Integer viewportHeight;
    private Integer shortBodyThreshold;
    private Integer minimumPrimaryResultCount;
    private Integer suspectBlockedBodyThreshold;
    private List<String> userAgents;
    private List<String> blockedSignals;
    private List<String> blockedUrlKeywords;
    private Boolean continueOnBrowserUnavailable;
    private Boolean continueOnSearchTimeout;
    private Boolean continueOnPageCollectFailure;
    private Boolean recoverPartialContentOnTimeout;
    private String recoveryHint;
}
