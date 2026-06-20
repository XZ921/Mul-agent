package cn.bugstack.competitoragent.integration;

import cn.bugstack.competitoragent.agent.collector.CollectorNodeConfig;
import cn.bugstack.competitoragent.collection.CollectionExecutionCoordinator;
import cn.bugstack.competitoragent.collection.CollectionExecutionReport;
import cn.bugstack.competitoragent.collection.CollectionExecutionResult;
import cn.bugstack.competitoragent.search.SearchCollectionTarget;
import cn.bugstack.competitoragent.search.SearchExecutionCoordinator;
import cn.bugstack.competitoragent.search.SearchExecutionResult;
import cn.bugstack.competitoragent.search.SearchExecutionTrace;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceDiscoveryService;
import cn.bugstack.competitoragent.source.SourcePlan;
import cn.bugstack.competitoragent.workflow.CollectorPlanTemplateFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B 站开放平台入口页真实采集冒烟。
 * <p>
 * 完整 name-only 冒烟会把所有选中目标和站内递归一起带入采集，排查成本较高；
 * 这个用例只保留搜索阶段选中的 open.bilibili.com 官方目标，并关闭站内递归，
 * 用来快速确认“千帆/SerpAPI 搜索可达之后，真实网页采集链路本身是否可用”。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "search.mode=HYBRID",
        "search.discovery.domain.llm-enabled=true",
        "playwright.startup-warmup-enabled=true",
        "playwright.health-check-warmup-enabled=true",
        "collection.internal-link-discovery.max-depth=0",
        "collection.internal-link-discovery.max-links-per-entry=0",
        "collection.internal-link-discovery.max-links-per-node=0",
        "collection.web-page.playwright-link-supplement-enabled=false",
        "logging.level.cn.bugstack.competitoragent=INFO"
})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_REAL_SMOKE", matches = "true")
class BilibiliOfficialDocsEntryCollectionRealSmokeTest {

    private static final String COMPETITOR_NAME = "哔哩哔哩";
    private static final String DOC_SCOPE = "文档";
    private static final String DOC_SOURCE_TYPE = "DOCS";
    private static final String OFFICIAL_DOMAIN = "open.bilibili.com";

    @Autowired
    private SourceDiscoveryService sourceDiscoveryService;

    @Autowired
    private CollectorPlanTemplateFactory collectorPlanTemplateFactory;

    @Autowired
    private SearchExecutionCoordinator searchExecutionCoordinator;

    @Autowired
    private CollectionExecutionCoordinator collectionExecutionCoordinator;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldCollectOneOfficialBilibiliDocsEntryAfterSearchSelection() throws Exception {
        // 先走真实规划与搜索，避免把固定 URL 冒烟误判为搜索链路可用。
        List<SourcePlan> sourcePlans = sourceDiscoveryService.discover(
                COMPETITOR_NAME,
                List.of(),
                List.of(DOC_SCOPE)
        );
        print("limitedCollectionSourcePlans", Map.of(
                "count", sourcePlans.size(),
                "plans", sourcePlans.stream().map(this::summarizePlan).toList()
        ));

        SourcePlan docsPlan = sourcePlans.stream()
                .filter(plan -> DOC_SOURCE_TYPE.equalsIgnoreCase(plan.getSourceType()))
                .findFirst()
                .orElseGet(() -> SourcePlan.builder()
                        .sourceType(DOC_SOURCE_TYPE)
                        .urls(List.of())
                        .candidates(List.of())
                        .build());

        CollectorNodeConfig collectorConfig = collectorPlanTemplateFactory.createCollectorNodeConfig(
                COMPETITOR_NAME,
                List.of(DOC_SCOPE),
                null,
                docsPlan
        );
        SearchExecutionResult searchResult = searchExecutionCoordinator.execute(collectorConfig);
        List<SearchCollectionTarget> selectedTargets = searchResult.getSelectedTargets() == null
                ? List.of()
                : searchResult.getSelectedTargets();
        print("limitedCollectionSearchResult", Map.of(
                "candidateCount", searchResult.getSourceCandidates() == null ? 0 : searchResult.getSourceCandidates().size(),
                "selectedTargetCount", selectedTargets.size(),
                "efficiencyStats", searchEfficiencyStats(searchResult),
                "reasoningSummary", searchResult.getReasoningSummary(),
                "selectedTargets", selectedTargets.stream()
                        .map(target -> summarizeCandidate(target == null ? null : target.getCandidate()))
                        .toList()
        ));

        SearchCollectionTarget officialDocsTarget = selectOfficialDocsTarget(selectedTargets);
        print("limitedCollectionSelectedTarget", summarizeCandidate(officialDocsTarget.getCandidate()));

        // 只采集一个官方入口页，且通过 Spring 属性关闭站内递归，避免真实冒烟被旁路站点或深层 sitemap 拖长。
        CollectionExecutionReport collectionReport = collectionExecutionCoordinator.execute(
                99002L,
                "collect_bilibili_official_docs_entry_real_smoke",
                null,
                COMPETITOR_NAME,
                List.of(officialDocsTarget)
        );
        List<CollectionExecutionResult> results = collectionReport == null || collectionReport.getResults() == null
                ? List.of()
                : collectionReport.getResults();
        print("limitedCollectionReport", Map.of(
                "status", collectionReport == null ? null : collectionReport.getStatus(),
                "sourceUrls", collectionReport == null ? List.of() : nullSafe(collectionReport.getSourceUrls()),
                "stats", collectionReport == null ? null : collectionReport.getStats(),
                "resultCount", results.size(),
                "results", results.stream().map(this::summarizeCollectionResult).toList()
        ));

        assertThat(selectedTargets)
                .as("name-only search should select candidates before limited collection")
                .isNotEmpty();
        assertThat(searchResult.getExecutionTrace() == null
                ? null
                : searchResult.getExecutionTrace().getCandidateVerificationElapsedMillis())
                .as("candidate verification timing should be present")
                .isNotNull();
        assertThat(searchResult.getExecutionTrace() == null
                ? 0
                : searchResult.getExecutionTrace().getCandidateVerificationConcurrency())
                .as("candidate verification concurrency should be recorded")
                .isGreaterThanOrEqualTo(1);
        assertThat(nullSafe(searchResult.getSourceCandidates()))
                .as("qianfan should contribute candidates before collection starts")
                .anySatisfy(candidate -> assertThat(candidate.getProviderKey()).isEqualToIgnoringCase("qianfan"));
        assertThat(results)
                .as("limited collection should only execute the selected official entry target")
                .hasSize(1);
        assertThat(results.get(0).isSuccess()).isTrue();
        assertThat(results.get(0).getContent()).isNotBlank();
        assertThat(results.get(0).getSourceUrls()).isNotEmpty();
        assertThat(results.get(0).getResourceLocator()).contains(OFFICIAL_DOMAIN);
        assertThat(collectionReport.getStats()).isNotNull();
    }

    private SearchCollectionTarget selectOfficialDocsTarget(List<SearchCollectionTarget> selectedTargets) {
        return nullSafe(selectedTargets).stream()
                .filter(target -> target != null && target.getCandidate() != null)
                .filter(target -> isOfficialBilibiliUrl(target.getCandidate().getUrl()))
                .min(Comparator.comparingInt(target -> docsTargetPriority(target.getCandidate())))
                .map(this::normalizeOfficialDocsTarget)
                .orElseThrow(() -> new AssertionError("search did not select an open.bilibili.com docs target"));
    }

    private SearchCollectionTarget normalizeOfficialDocsTarget(SearchCollectionTarget target) {
        SourceCandidate candidate = target.getCandidate();
        List<String> sourceUrls = candidate.getSourceUrls() == null || candidate.getSourceUrls().isEmpty()
                ? List.of(candidate.getUrl())
                : candidate.getSourceUrls();
        SourceCandidate normalizedCandidate = candidate.toBuilder()
                .sourceType(StringUtils.hasText(candidate.getSourceType()) ? candidate.getSourceType() : DOC_SOURCE_TYPE)
                .sourceFamilyKey(StringUtils.hasText(candidate.getSourceFamilyKey()) ? candidate.getSourceFamilyKey() : "official")
                .sourceUrls(sourceUrls)
                .build();
        return target.toBuilder()
                .candidate(normalizedCandidate)
                .build();
    }

    private boolean isOfficialBilibiliUrl(String url) {
        return StringUtils.hasText(url) && url.toLowerCase().contains(OFFICIAL_DOMAIN);
    }

    private int docsTargetPriority(SourceCandidate candidate) {
        String url = candidate == null || candidate.getUrl() == null ? "" : candidate.getUrl().toLowerCase();
        if (url.contains("/doc") || url.contains("/docs")) {
            return 0;
        }
        return 1;
    }

    private Map<String, Object> summarizePlan(SourcePlan plan) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sourceType", plan == null ? null : plan.getSourceType());
        summary.put("urls", plan == null ? List.of() : nullSafe(plan.getUrls()));
        summary.put("notes", plan == null ? null : plan.getNotes());
        summary.put("candidateCount", plan == null || plan.getCandidates() == null ? 0 : plan.getCandidates().size());
        summary.put("candidates", plan == null || plan.getCandidates() == null
                ? List.of()
                : plan.getCandidates().stream().map(this::summarizeCandidate).toList());
        return summary;
    }

    private Map<String, Object> searchEfficiencyStats(SearchExecutionResult searchResult) {
        SearchExecutionTrace trace = searchResult == null ? null : searchResult.getExecutionTrace();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("candidateVerificationElapsedMillis", trace == null ? null : trace.getCandidateVerificationElapsedMillis());
        stats.put("candidateVerificationConcurrency", trace == null ? null : trace.getCandidateVerificationConcurrency());
        stats.put("candidateVerificationInputCount", trace == null ? null : trace.getCandidateVerificationInputCount());
        stats.put("candidateVerificationUniqueCount", trace == null ? null : trace.getCandidateVerificationUniqueCount());
        stats.put("candidateVerificationReusedPageCount", trace == null ? null : trace.getCandidateVerificationReusedPageCount());
        stats.put("candidateVerificationDirectAttemptCount", trace == null ? null : trace.getCandidateVerificationDirectAttemptCount());
        stats.put("candidateVerificationDirectUsableCount", trace == null ? null : trace.getCandidateVerificationDirectUsableCount());
        stats.put("candidateVerificationDirectShortcutCount", trace == null ? null : trace.getCandidateVerificationDirectShortcutCount());
        return stats;
    }

    private Map<String, Object> summarizeCandidate(SourceCandidate candidate) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (candidate == null) {
            return summary;
        }
        summary.put("url", candidate.getUrl());
        summary.put("title", candidate.getTitle());
        summary.put("sourceType", candidate.getSourceType());
        summary.put("discoveryMethod", candidate.getDiscoveryMethod());
        summary.put("domain", candidate.getDomain());
        summary.put("providerKey", candidate.getProviderKey());
        summary.put("verified", candidate.getVerified());
        summary.put("selectionStage", candidate.getSelectionStage());
        summary.put("verificationReason", candidate.getVerificationReason());
        summary.put("qualitySignals", nullSafe(candidate.getQualitySignals()));
        summary.put("sourceUrls", nullSafe(candidate.getSourceUrls()));
        summary.put("totalScore", candidate.getTotalScore());
        return summary;
    }

    private Map<String, Object> summarizeCollectionResult(CollectionExecutionResult result) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (result == null) {
            return summary;
        }
        String content = result.getContent();
        summary.put("status", result.getStatus());
        summary.put("success", result.isSuccess());
        summary.put("executorType", result.getExecutorType());
        summary.put("resourceLocator", result.getResourceLocator());
        summary.put("title", result.getTitle());
        summary.put("contentLength", content == null ? 0 : content.length());
        summary.put("contentSample", sample(content, 240));
        summary.put("sourceUrls", nullSafe(result.getSourceUrls()));
        summary.put("discoveryDepth", result.getDiscoveryDepth());
        summary.put("qualitySignals", nullSafe(result.getQualitySignals()));
        summary.put("qualityScore", result.getQualityScore());
        summary.put("failureKind", result.getFailureKind());
        summary.put("errorMessage", result.getErrorMessage());
        summary.put("discoveredCandidateCount", result.getDiscoveredCandidates() == null ? 0 : result.getDiscoveredCandidates().size());
        return summary;
    }

    private void print(String label, Object value) throws Exception {
        System.out.println("BILIBILI_LIMITED_COLLECTION_REAL_SMOKE " + label + "=" + objectMapper.writeValueAsString(value));
    }

    private <T> List<T> nullSafe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private String sample(String content, int maxLength) {
        if (content == null) {
            return null;
        }
        String normalized = content.replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}
