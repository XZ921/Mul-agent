package cn.bugstack.competitoragent.search.tavily;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Tavily 预取正文运行时对象。
 * 这里专门承接 Tavily 返回的正文与轻量质量信号，避免把大段 raw_content 直接挂在 SourceCandidate 上，
 * 从而保持搜索链路对象瘦身，并为后续 executor 按需消费留出清晰边界。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class TavilyPrefetchedContent {

    private String url;
    private String title;
    private String content;
    private String rawContent;
    private String cleanedContent;

    @Builder.Default
    private List<String> sourceUrls = new ArrayList<>();

    private String requestId;
    private String query;
    private String queryMode;
    private Integer resultRank;
    private Double tavilyScore;

    /**
     * 字段级 query 元数据。
     * registry 回放时依赖这些字段解释 raw_content 属于哪个字段路径，而不是只知道它来自某次 Tavily 请求。
     */
    private String fieldName;
    private String evidencePathKey;
    private String queryIntent;
    private String fieldEvidenceQueryFingerprint;
    private String fieldEvidenceQueryReason;
}