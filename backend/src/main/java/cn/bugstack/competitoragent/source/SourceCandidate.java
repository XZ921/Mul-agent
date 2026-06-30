package cn.bugstack.competitoragent.source;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单条候选来源。
 * 统一承接规划期补源、运行期验证、增补搜索和最终选源所需的元数据。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SourceCandidate {

    private String url;
    private String title;
    private String sourceType;
    private String discoveryMethod;
    private String reason;
    private String domain;
    private String publishedAt;
    private String sourceFamilyKey;
    private String sourceFamilyRole;
    private String providerKey;
    private String providerRole;
    private List<String> sourceUrls;
    private double relevanceScore;
    private double freshnessScore;
    private double qualityScore;
    private double totalScore;
    private SourceTrustTier trustTier;
    private String trustTierLabel;
    private List<String> qualitySignals;
    private List<String> rankingReasons;
    private String rankingSummary;

    /**
     * 字段级证据查询元数据。
     * 这些字段用于解释候选来源来自哪个 field-first query，保证后续覆盖闭环与审计可回溯。
     */
    private String fieldName;
    private String evidencePathKey;
    private String queryIntent;
    private String fieldEvidenceQueryFingerprint;
    private String fieldEvidenceQueryReason;

    // 以下字段为后续浏览器搜索与运行期筛选预留。
    private String searchQuery;
    private String searchEngine;
    private Integer resultRank;
    private String browserTraceId;
    /**
     * Tavily Fast Lane 只在候选里保留轻量引用与质量元数据，
     * 不在主对象中携带完整 raw_content，避免搜索链路对象膨胀。
     */
    private Boolean hasPrefetchedContent;
    private String prefetchedContentRef;
    private Integer prefetchedRawContentLength;
    private Double tavilyScore;
    private String tavilyRequestId;
    private String tavilyQuery;
    private String tavilyQueryMode;
    private String pageType;
    private String qualityTier;
    private Boolean fastLaneUsable;
    private String fastLaneRejectReason;
    private String contentCompleteness;
    private Boolean skipNetworkVerification;
    private Boolean verified;
    private String verificationReason;
    private List<String> matchedSignals;
    private String selectionStage;
    private String selectionReason;
    private String selectionSummary;
}