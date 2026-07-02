package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.workflow.coverage.FieldEvidenceQuery;
import cn.bugstack.competitoragent.search.tavily.TavilyFastLaneAudit;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索补源上下文请求对象。
 * 这里统一承接运行期补源所需的竞品、scope、查询词、域名偏好、候选种子与字段级查询计划，
 * 让新 provider 可以消费完整上下文，同时保持旧 provider 仍可通过兼容入口继续工作。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SearchSourceRequest {

    private String competitorName;

    @Builder.Default
    private List<String> requestedScopes = new ArrayList<>();

    @Builder.Default
    private List<String> searchQueries = new ArrayList<>();

    @Builder.Default
    private List<String> preferredDomains = new ArrayList<>();

    @Builder.Default
    private List<String> includeDomains = new ArrayList<>();

    @Builder.Default
    private List<String> blockedDomains = new ArrayList<>();

    @Builder.Default
    private List<SourceCandidate> seedCandidates = new ArrayList<>();

    /**
     * 当前补源请求聚焦的字段名。
     * 这个轻量字段供 repair/public recovery 兼容旧链路使用，字段级多 query 则优先看 fieldEvidenceQueries。
     */
    private String fieldName;
    private String evidencePathKey;
    private String queryIntent;

    @Builder.Default
    private List<FieldEvidenceQuery> fieldEvidenceQueries = new ArrayList<>();

    /**
     * 字段 query 的完整计划量与执行量由 coordinator 在进入 supplement 前统一计算，
     * provider 只消费可执行子集，但仍需要拿到这份轻量预算快照用于审计回传和可观测输出。
     */
    private Integer fieldEvidenceQueryPlannedCount;
    private Integer fieldEvidenceQueryExecutableCount;
    private Integer fieldEvidenceQuerySkippedCount;
    private Long fieldEvidenceExecutionDeadlineEpochMillis;
    private TavilyFastLaneAudit tavilyFastLaneAudit;

    private String preferredProviderKey;
    private String preferredQueryMode;
    /**
     * requestPhase 用于让 provider 明确知道当前请求来自 Phase 1 bootstrap
     * 还是运行期 supplement，从而打出不同的发现语义和审计标签。
     */
    private SearchRequestPhase requestPhase;
}
