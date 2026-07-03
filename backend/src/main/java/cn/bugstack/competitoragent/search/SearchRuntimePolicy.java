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
    /**
     * Phase 1 bootstrap 候选预算上限。
     * 用于避免 Tavily 在弱入口场景下一次性把候选池膨胀到不可控规模。
     */
    private Integer bootstrapCandidateLimit;
    /**
     * supplement 候选预算上限。
     * 与 bootstrap 分开配置，便于后续按阶段观察补源收益与噪音成本。
     */
    private Integer supplementCandidateLimit;
    /**
     * 单节点总候选池上限。
     * 所有 planned/bootstrap/supplement 候选在进入 select 前都应收敛到这个预算内。
     */
    private Integer maxCandidatePoolSize;
    /**
     * 单域名候选上限。
     * 通过 per-domain 裁剪做“软平衡”，避免单个域名把整个候选池挤满。
     */
    private Integer maxCandidatesPerDomain;
    /**
     * search-first 家族的证据目标下限。
     * 这个字段不是 maxSearchResults 的替代品，而是用于避免“用户只给 1 个官网 URL，
     * 最终证据也只能选 1 条”的结构性塌缩。
     */
    private Integer searchFirstEvidenceTargetFloor;
    /**
     * search-first 家族的证据目标上限。
     * 防止 Tavily 主搜索一次返回过多高质量页面时，把正式采集目标膨胀到不可控规模。
     */
    private Integer searchFirstEvidenceTargetCeiling;
    /**
     * 候选融合后允许进入浏览器验证的候选数量。
     * fast lane 可用正文不占用这个预算。
     */
    private Integer preSelectionVerificationLimit;
    private Double competitorCoverageSoftGapRatio;
    private List<String> userAgents;
    private List<String> blockedSignals;
    private List<String> blockedUrlKeywords;
    private Boolean continueOnBrowserUnavailable;
    private Boolean continueOnSearchTimeout;
    private Boolean continueOnPageCollectFailure;
    private Boolean recoverPartialContentOnTimeout;
    private String recoveryHint;
}
