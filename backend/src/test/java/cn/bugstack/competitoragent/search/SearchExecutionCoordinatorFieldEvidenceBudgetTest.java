package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.agent.collector.CollectorNodeConfig;
import cn.bugstack.competitoragent.source.SearchRequestPhase;
import cn.bugstack.competitoragent.source.SearchSourceProvider;
import cn.bugstack.competitoragent.source.SearchSourceProviderDescriptor;
import cn.bugstack.competitoragent.source.SearchSourceRequest;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCandidateRanker;
import cn.bugstack.competitoragent.source.SourceCollectRequest;
import cn.bugstack.competitoragent.source.SourceCollector;
import cn.bugstack.competitoragent.search.tavily.FieldEvidenceQueryExecutionAudit;
import cn.bugstack.competitoragent.search.tavily.TavilyFastLaneAudit;
import cn.bugstack.competitoragent.workflow.coverage.DimensionEvidencePlan;
import cn.bugstack.competitoragent.workflow.coverage.FieldEvidenceCoverage;
import cn.bugstack.competitoragent.workflow.coverage.FieldEvidenceCoverageStatus;
import cn.bugstack.competitoragent.workflow.coverage.FieldEvidenceQuery;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchExecutionCoordinatorFieldEvidenceBudgetTest {

    @Test
    void shouldApplyFieldExecutionGateBeforeBuildingBudget() {
        RecordingBudgetAwareSearchSourceProvider provider = new RecordingBudgetAwareSearchSourceProvider();
        SearchExecutionCoordinator coordinator = newCoordinator(provider);

        SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
                .competitorName("哔哩哔哩")
                .sourceType("DOCS")
                .verifyCandidates(false)
                .searchMode("HTTP_ONLY")
                .searchFallbackOrder(List.of("HTTP"))
                .preferredSearchProvider("tavily")
                .browserSearchEnabled(false)
                .maxSearchResults(1)
                .minVerifiedCandidates(1)
                .searchTimeoutMillis(18_300L)
                .dimensionEvidencePlan(task84ScaledFieldPlan())
                .build());

        assertThat(provider.requests).hasSize(1);
        SearchSourceRequest request = provider.requests.get(0);
        assertThat(request.getRequestPhase()).isEqualTo(SearchRequestPhase.SUPPLEMENT);
        assertThat(request.getFieldEvidenceQueries()).hasSize(21);
        assertThat(request.getFieldEvidenceQueryPlannedCount()).isEqualTo(71);
        assertThat(request.getFieldEvidenceQueryExecutableCount()).isEqualTo(21);
        assertThat(request.getFieldEvidenceQuerySkippedCount()).isEqualTo(50);
        assertThat(result.getExecutionTrace().getFieldEvidenceQueryPlannedCount()).isEqualTo(71);
        assertThat(result.getExecutionTrace().getFieldEvidenceQueryExecutedCount()).isEqualTo(21);
        assertThat(result.getExecutionTrace().getFieldEvidenceQuerySkippedCount()).isEqualTo(50);
        assertThat(result.getExecutionTrace().getFieldEvidenceQuerySkipReasons())
                .containsEntry("SKIPPED_FIELD_SOURCE_QUOTA_EXHAUSTED", 50);
        assertThat(result.getExecutionTrace().getSearchTimeoutMillis()).isEqualTo(78_000L);
    }

    @Test
    void shouldNotCollapseHighPriorityFieldQueriesToOneWhenBaseBudgetIsShort() {
        RecordingBudgetAwareSearchSourceProvider provider = new RecordingBudgetAwareSearchSourceProvider();
        SearchExecutionCoordinator coordinator = newCoordinator(provider);

        coordinator.execute(CollectorNodeConfig.builder()
                .competitorName("鍝斿摡鍝斿摡")
                .sourceType("DOCS")
                .verifyCandidates(false)
                .searchMode("HTTP_ONLY")
                .searchFallbackOrder(List.of("HTTP"))
                .preferredSearchProvider("tavily")
                .browserSearchEnabled(false)
                .maxSearchResults(1)
                .minVerifiedCandidates(1)
                .searchTimeoutMillis(6_000L)
                .dimensionEvidencePlan(prioritizedFieldPlan())
                .build());

        assertThat(provider.requests).hasSize(1);
        assertThat(provider.requests.get(0).getFieldEvidenceQueries())
                .extracting(FieldEvidenceQuery::getQueryFingerprint)
                .containsExactly("q-priority-10", "q-priority-20", "q-priority-30");
        assertThat(provider.requests.get(0).getFieldEvidenceQuerySkippedCount()).isEqualTo(1);
    }

    @Test
    void shouldExposePlannedExecutedAndSkippedFieldEvidenceCountsInTraceAndSummary() {
        RecordingBudgetAwareSearchSourceProvider provider = new RecordingBudgetAwareSearchSourceProvider();
        SearchExecutionCoordinator coordinator = newCoordinator(provider);

        SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
                .competitorName("哔哩哔哩")
                .sourceType("DOCS")
                .verifyCandidates(false)
                .searchMode("HTTP_ONLY")
                .searchFallbackOrder(List.of("HTTP"))
                .preferredSearchProvider("tavily")
                .browserSearchEnabled(false)
                .maxSearchResults(1)
                .minVerifiedCandidates(1)
                .searchTimeoutMillis(18_300L)
                .dimensionEvidencePlan(prioritizedFieldPlan())
                .build());

        assertThat(result.getExecutionTrace().getFieldEvidenceQueryCount()).isEqualTo(4);
        assertThat(result.getExecutionTrace().getFieldEvidenceQueryPlannedCount()).isEqualTo(4);
        assertThat(result.getExecutionTrace().getFieldEvidenceQueryExecutedCount()).isEqualTo(3);
        assertThat(result.getExecutionTrace().getFieldEvidenceQuerySkippedCount()).isEqualTo(1);
        assertThat(result.getExecutionTrace().getFieldEvidenceQuerySkipReasons())
                .containsEntry("SKIPPED_FIELD_SOURCE_QUOTA_EXHAUSTED", 1);
        assertThat(result.getAuditSnapshot().getSummary().getFieldEvidenceQueryPlannedCount()).isEqualTo(4);
        assertThat(result.getAuditSnapshot().getSummary().getFieldEvidenceQueryExecutedCount()).isEqualTo(3);
        assertThat(result.getAuditSnapshot().getSummary().getFieldEvidenceQuerySkippedCount()).isEqualTo(1);
        assertThat(result.getAuditSnapshot().getSummary().getFieldEvidenceQuerySkipReasons())
                .containsEntry("SKIPPED_FIELD_SOURCE_QUOTA_EXHAUSTED", 1);
    }

    @Test
    void shouldExposeProviderFieldQueryAuditInTraceSummaryAndSupplementStepMessage() {
        RecordingBudgetAwareSearchSourceProvider provider = new RecordingBudgetAwareSearchSourceProvider();
        provider.emitProviderFieldQueryAudit = true;
        SearchExecutionCoordinator coordinator = newCoordinator(provider);

        SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
                .competitorName("哔哩哔哩")
                .sourceType("DOCS")
                .verifyCandidates(false)
                .searchMode("HTTP_ONLY")
                .searchFallbackOrder(List.of("HTTP"))
                .preferredSearchProvider("tavily")
                .browserSearchEnabled(false)
                .maxSearchResults(1)
                .minVerifiedCandidates(1)
                .searchTimeoutMillis(18_300L)
                .dimensionEvidencePlan(prioritizedFieldPlan())
                .build());

        assertThat(result.getExecutionTrace().getFieldEvidenceQueryPlannedCount()).isEqualTo(4);
        assertThat(result.getExecutionTrace().getFieldEvidenceQueryExecutedCount()).isEqualTo(2);
        assertThat(result.getExecutionTrace().getFieldEvidenceQuerySkippedCount()).isEqualTo(2);
        assertThat(result.getExecutionTrace().getFieldEvidenceQuerySkipReasons())
                .containsEntry("SKIPPED_FIELD_SOURCE_QUOTA_EXHAUSTED", 1)
                .containsEntry("SKIPPED_BUDGET_EXHAUSTED", 1);

        TavilyFastLaneAudit traceAudit = result.getExecutionTrace().getTavilyFastLaneAudit();
        assertThat(traceAudit).isNotNull();
        assertThat(traceAudit.getQueriesSent()).isEqualTo(2);
        assertThat(traceAudit.getFieldEvidenceQueryExecutions())
                .extracting("queryFingerprint", "status", "resultCount", "skipReason", "failureReason")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("q-priority-10", "SUCCESS", 1, null, null),
                        org.assertj.core.groups.Tuple.tuple("q-priority-20", "FAILED", 0, null, "HTTP 429"),
                        org.assertj.core.groups.Tuple.tuple("q-priority-30", "SKIPPED", 0, "SKIPPED_BUDGET_EXHAUSTED", null)
                );
        assertThat(result.getAuditSnapshot().getSummary().getTavilyFastLaneAudit().getFieldEvidenceQueryExecutions()).hasSize(3);
        assertThat(result.getAuditSnapshot().getSummary().getTavilyFastLaneAudit().getFieldEvidenceQueryExecutions())
                .hasSize(3);
        SearchExecutionStep supplementStep = result.getExecutionPlan().getSteps().stream()
                .filter(step -> "BROWSER_SUPPLEMENT_SEARCH".equals(step.getStepCode()))
                .findFirst()
                .orElseThrow();
        assertThat(supplementStep.getMessage())
                .contains("field query plan 4")
                .contains("executed 2")
                .contains("skipped 2")
                .contains("SKIPPED_FIELD_SOURCE_QUOTA_EXHAUSTED")
                .contains("SKIPPED_BUDGET_EXHAUSTED");
    }

    /**
     * 这里复用一个最小 coordinator 上下文，只保留“能进入 HTTP supplement”所需的依赖，
     * 这样失败测试只盯住 field query 预算分层，不会被浏览器或页面验证噪声干扰。
     */
    @Test
    void shouldUseBumpedSearchTimeoutWhenBuildingFieldEvidenceDeadline() {
        RecordingBudgetAwareSearchSourceProvider provider = new RecordingBudgetAwareSearchSourceProvider();
        SearchExecutionCoordinator coordinator = newCoordinator(provider);
        long startedAt = System.currentTimeMillis();

        SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
                .competitorName("哔哩哔哩")
                .sourceType("DOCS")
                .verifyCandidates(false)
                .searchMode("HTTP_ONLY")
                .searchFallbackOrder(List.of("HTTP"))
                .preferredSearchProvider("tavily")
                .browserSearchEnabled(false)
                .maxSearchResults(1)
                .minVerifiedCandidates(1)
                .searchTimeoutMillis(15_000L)
                .dimensionEvidencePlan(deadlineFieldPlan())
                .build());

        assertThat(provider.requests).hasSize(1);
        SearchSourceRequest request = provider.requests.get(0);
        assertThat(request.getFieldEvidenceQueries())
                .extracting(FieldEvidenceQuery::getQueryFingerprint)
                .containsExactly("q-deadline-1", "q-deadline-2");
        assertThat(request.getFieldEvidenceExecutionDeadlineEpochMillis()).isNotNull();
        assertThat(request.getFieldEvidenceExecutionDeadlineEpochMillis() - startedAt)
                .isGreaterThanOrEqualTo(20_000L);
        assertThat(result.getExecutionTrace().getSearchTimeoutMillis()).isGreaterThanOrEqualTo(21_000L);
    }

    private SearchExecutionCoordinator newCoordinator(RecordingBudgetAwareSearchSourceProvider provider) {
        BrowserSearchRuntimeService browserSearchRuntimeService = mock(BrowserSearchRuntimeService.class);
        when(browserSearchRuntimeService.search(any())).thenReturn(BrowserSearchRuntimeResult.builder()
                .candidates(List.of())
                .executedQueries(List.of())
                .summary("browser disabled")
                .fallbackSuggested(false)
                .build());
        return new SearchExecutionCoordinator(
                new CandidateVerifier(new SourceCollector() {
                    @Override
                    public CollectedPage collect(SourceCollectRequest request) {
                        return CollectedPage.builder()
                                .url(request == null ? null : request.getUrl())
                                .success(false)
                                .errorMessage("not needed in budget tests")
                                .build();
                    }

                    @Override
                    public List<CollectedPage> collectBatch(List<String> urls, String competitorName, String sourceType) {
                        return List.of();
                    }
                }),
                browserSearchRuntimeService,
                provider,
                new SourceCandidateRanker(),
                new CollectionTargetSelector(),
                new SearchPolicyResolver()
        );
    }

    /**
     * 这组 query 刻意做成“4 条计划 / 18.3s 预算只能容纳 3 条”的形状，
     * 用来锁定“quota 按放大前预算计算”和“优先级稳定排序后只透传 executable”两个根因约束。
     */
    private DimensionEvidencePlan prioritizedFieldPlan() {
        return DimensionEvidencePlan.builder()
                .competitorName("哔哩哔哩")
                .maxCollectionRounds(2)
                .fieldCoverages(List.of(FieldEvidenceCoverage.builder()
                        .fieldName("coreFeatures")
                        .status(FieldEvidenceCoverageStatus.NOT_STARTED)
                        .minimumAttemptedPaths(1)
                        .completedPaths(List.of())
                        .plannedQueries(List.of(
                                fieldQuery("q-priority-30", 30, "站内 SDK 文档"),
                                fieldQuery("q-priority-10", 10, "官方 API 文档"),
                                fieldQuery("q-priority-20", 20, "开放平台接入说明"),
                                fieldQuery("q-priority-40", 40, "补充 FAQ 文档")
                        ))
                        .build()))
                .build();
    }

    /**
     * 这个计划专门复刻 task84 的量级特征：
     * planned query 很多，但经过字段闸门后每个字段只能留下 3 条，总执行量应稳定收敛到 21。
     */
    private DimensionEvidencePlan task84ScaledFieldPlan() {
        List<FieldEvidenceCoverage> fieldCoverages = new ArrayList<>();
        for (int fieldIndex = 0; fieldIndex < 7; fieldIndex++) {
            String fieldName = "field-" + fieldIndex;
            List<FieldEvidenceQuery> plannedQueries = new ArrayList<>();
            plannedQueries.add(task84FieldQuery(fieldName, "OFFICIAL", 0, fieldName + "-official-1"));
            plannedQueries.add(task84FieldQuery(fieldName, "DOCS", 1, fieldName + "-docs-1"));
            plannedQueries.add(task84FieldQuery(fieldName, "PRICING", 2, fieldName + "-pricing-1"));
            plannedQueries.add(task84FieldQuery(fieldName, "TERMS", 3, fieldName + "-terms-1"));
            plannedQueries.add(task84FieldQuery(fieldName, "REVIEW", 4, fieldName + "-review-1"));
            plannedQueries.add(task84FieldQuery(fieldName, "NEWS", 5, fieldName + "-news-1"));
            plannedQueries.add(task84FieldQuery(fieldName, "OPEN_WEB", 6, fieldName + "-open-web-1"));
            plannedQueries.add(task84FieldQuery(fieldName, "DOCS", 7, fieldName + "-docs-2"));
            plannedQueries.add(task84FieldQuery(fieldName, "OFFICIAL", 8, fieldName + "-official-2"));
            plannedQueries.add(task84FieldQuery(fieldName, "TERMS", 9, fieldName + "-terms-2"));
            if (fieldIndex == 0) {
                plannedQueries.add(task84FieldQuery(fieldName, "DOCS", 10, fieldName + "-docs-3"));
            }
            fieldCoverages.add(FieldEvidenceCoverage.builder()
                    .fieldName(fieldName)
                    .status(FieldEvidenceCoverageStatus.NOT_STARTED)
                    .minimumAttemptedPaths(1)
                    .completedPaths(List.of())
                    .plannedQueries(plannedQueries)
                    .build());
        }
        return DimensionEvidencePlan.builder()
                .competitorName("哔哩哔哩")
                .maxCollectionRounds(2)
                .fieldCoverages(fieldCoverages)
                .build();
    }

    private FieldEvidenceQuery fieldQuery(String fingerprint, Integer priority, String suffix) {
        return FieldEvidenceQuery.builder()
                .fieldName("coreFeatures")
                .evidencePathKey("DOCS_API_GUIDE")
                .queryIntent("API_DOCS")
                .sourceType("DOCS")
                .query("哔哩哔哩 开放平台 " + suffix)
                .queryFingerprint(fingerprint)
                .priority(priority)
                .reason("预算红测-" + suffix)
                .build();
    }

    private FieldEvidenceQuery task84FieldQuery(String fieldName,
                                                String sourceType,
                                                Integer priority,
                                                String fingerprint) {
        return FieldEvidenceQuery.builder()
                .fieldName(fieldName)
                .evidencePathKey(fieldName.toUpperCase() + "_PATH")
                .queryIntent("FIELD_TEST")
                .sourceType(sourceType)
                .query("哔哩哔哩 " + fieldName + " " + sourceType + " 证据")
                .queryFingerprint(fingerprint)
                .priority(priority)
                .reason("task84 字段配额回归")
                .build();
    }

    private DimensionEvidencePlan deadlineFieldPlan() {
        return DimensionEvidencePlan.builder()
                .competitorName("哔哩哔哩")
                .maxCollectionRounds(2)
                .fieldCoverages(List.of(FieldEvidenceCoverage.builder()
                        .fieldName("coreFeatures")
                        .status(FieldEvidenceCoverageStatus.NOT_STARTED)
                        .minimumAttemptedPaths(1)
                        .completedPaths(List.of())
                        .plannedQueries(List.of(
                                fieldQuery("q-deadline-1", 10, "官方 API 文档"),
                                fieldQuery("q-deadline-2", 20, "第三方接入分析")
                        ))
                        .build()))
                .build();
    }

    private static final class RecordingBudgetAwareSearchSourceProvider implements SearchSourceProvider {

        private final List<SearchSourceRequest> requests = new ArrayList<>();
        private boolean emitProviderFieldQueryAudit = false;

        @Override
        public SearchSourceProviderDescriptor descriptor() {
            return SearchSourceProviderDescriptor.builder()
                    .providerKey("tavily")
                    .displayName("test-tavily")
                    .capabilities(List.of("WEB_SEARCH"))
                    .defaultEnabled(true)
                    .defaultFailOpen(true)
                    .build();
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public List<SourceCandidate> search(SearchSourceRequest request) {
            requests.add(request);
            if (request == null || request.getFieldEvidenceQueries() == null) {
                return List.of();
            }
            if (emitProviderFieldQueryAudit) {
                request.setTavilyFastLaneAudit(TavilyFastLaneAudit.builder()
                        .queryModes(List.of("FIELD_EVIDENCE"))
                        .queryOrigins(List.of("SUPPLEMENT"))
                        .queriesSent(2)
                        .totalResults(1)
                        .fastLaneUsableCount(1)
                        .fastLaneRejectedCount(3)
                        .rejectionReasons(java.util.Map.of(
                                "HTTP 429", 1,
                                "SKIPPED_BUDGET_EXHAUSTED", 2
                        ))
                        .fieldEvidenceQueryExecutions(List.of(
                                FieldEvidenceQueryExecutionAudit.builder()
                                        .queryFingerprint("q-priority-10")
                                        .fieldName("coreFeatures")
                                        .evidencePathKey("DOCS_API_GUIDE")
                                        .queryIntent("API_DOCS")
                                        .query("哔哩哔哩 开放平台 官方 API 文档")
                                        .status("SUCCESS")
                                        .elapsedMillis(120L)
                                        .resultCount(1)
                                        .tavilyRequestId("req-q10")
                                        .build(),
                                FieldEvidenceQueryExecutionAudit.builder()
                                        .queryFingerprint("q-priority-20")
                                        .fieldName("coreFeatures")
                                        .evidencePathKey("DOCS_API_GUIDE")
                                        .queryIntent("API_DOCS")
                                        .query("哔哩哔哩 开放平台 开放平台接入说明")
                                        .status("FAILED")
                                        .elapsedMillis(900L)
                                        .resultCount(0)
                                        .failureReason("HTTP 429")
                                        .build(),
                                FieldEvidenceQueryExecutionAudit.builder()
                                        .queryFingerprint("q-priority-30")
                                        .fieldName("coreFeatures")
                                        .evidencePathKey("DOCS_API_GUIDE")
                                        .queryIntent("API_DOCS")
                                        .query("哔哩哔哩 开放平台 站内 SDK 文档")
                                        .status("SKIPPED")
                                        .elapsedMillis(0L)
                                        .resultCount(0)
                                        .skipReason("SKIPPED_BUDGET_EXHAUSTED")
                                        .build()
                        ))
                        .tavilyRequestIds(List.of("req-q10"))
                        .build());
            }
            List<SourceCandidate> candidates = new ArrayList<>();
            for (FieldEvidenceQuery query : request.getFieldEvidenceQueries()) {
                if (emitProviderFieldQueryAudit && !"q-priority-10".equals(query.getQueryFingerprint())) {
                    continue;
                }
                candidates.add(SourceCandidate.builder()
                        .url("https://open.bilibili.com/doc/" + query.getQueryFingerprint())
                        .title(query.getQuery())
                        .sourceType("DOCS")
                        .providerKey("tavily")
                        .discoveryMethod("TAVILY_FIELD_EVIDENCE_QUERY")
                        .reason(query.getReason())
                        .sourceUrls(List.of("https://open.bilibili.com/doc/" + query.getQueryFingerprint()))
                        .fieldName(query.getFieldName())
                        .evidencePathKey(query.getEvidencePathKey())
                        .queryIntent(query.getQueryIntent())
                        .fieldEvidenceQueryFingerprint(query.getQueryFingerprint())
                        .tavilyQuery(query.getQuery())
                        .tavilyQueryMode("OFFICIAL_DOCS")
                        .fastLaneUsable(Boolean.TRUE)
                        .verified(Boolean.TRUE)
                        .pageType("OFFICIAL_DOC")
                        .qualityTier("STRONG")
                        .skipNetworkVerification(Boolean.TRUE)
                        .build());
            }
            return candidates;
        }

        @Override
        public List<SourceCandidate> search(String competitorName, List<String> requestedScopes) {
            return List.of();
        }
    }
}
