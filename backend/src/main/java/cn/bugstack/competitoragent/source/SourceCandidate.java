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
    private double relevanceScore;
    private double freshnessScore;
    private double qualityScore;
    private double totalScore;

    // 以下字段为后续浏览器搜索与运行期筛选预留。
    private String searchQuery;
    private String searchEngine;
    private Integer resultRank;
    private String browserTraceId;
    private Boolean verified;
    private String verificationReason;
    private List<String> matchedSignals;
    private String selectionStage;
    private String selectionReason;
}
