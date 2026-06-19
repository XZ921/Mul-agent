package cn.bugstack.competitoragent.integration;

import cn.bugstack.competitoragent.agent.collector.CollectorNodeConfig;
import cn.bugstack.competitoragent.collection.CollectionExecutionCoordinator;
import cn.bugstack.competitoragent.collection.CollectionExecutionReport;
import cn.bugstack.competitoragent.collection.CollectionExecutionResult;
import cn.bugstack.competitoragent.search.SearchCollectionTarget;
import cn.bugstack.competitoragent.search.SearchExecutionCoordinator;
import cn.bugstack.competitoragent.search.SearchExecutionResult;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 哔哩哔哩真实外网冒烟测试。
 * <p>
 * 该用例默认不进入常规测试套件，只在 RUN_REAL_SMOKE=true 时执行，用来验证“只给竞品名称”
 * 是否能通过规划期 LLM 域名发现或运行期浏览器搜索定位到官方文档，并继续采集入口页与内部子页正文。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "search.mode=HYBRID",
        "search.discovery.domain.llm-enabled=true",
        "playwright.startup-warmup-enabled=true",
        "playwright.health-check-warmup-enabled=true",
        "logging.level.cn.bugstack.competitoragent=INFO"
})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_REAL_SMOKE", matches = "true")
class BilibiliNameOnlyRealSmokeTest {

    private static final String COMPETITOR_NAME = "哔哩哔哩";
    private static final String DOC_SCOPE = "文档";
    private static final String DOC_SOURCE_TYPE = "DOCS";

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
    void shouldLocateAndCollectBilibiliOfficialDocsWhenOnlyNameIsProvided() throws Exception {
        // 只给名称，不给 URL，先验证规划期是否能产出官方文档候选。
        List<SourcePlan> sourcePlans = sourceDiscoveryService.discover(
                COMPETITOR_NAME,
                List.of(),
                List.of(DOC_SCOPE)
        );
        print("sourcePlans", Map.of(
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
        print("collectorConfig", Map.of(
                "searchMode", collectorConfig.getSearchMode(),
                "browserSearchEnabled", collectorConfig.getBrowserSearchEnabled(),
                "verifyResultPage", collectorConfig.getVerifyResultPage(),
                "competitorUrls", nullSafe(collectorConfig.getCompetitorUrls()),
                "sourceCandidateCount", collectorConfig.getSourceCandidates() == null ? 0 : collectorConfig.getSourceCandidates().size(),
                "searchQueries", nullSafe(collectorConfig.getSearchQueries()),
                "fallbackOrder", nullSafe(collectorConfig.getSearchFallbackOrder())
        ));

        // 运行期搜索会验证候选，并在不足时通过浏览器/HTTP fallback 补源。
        SearchExecutionResult searchResult = searchExecutionCoordinator.execute(collectorConfig);
        List<SearchCollectionTarget> selectedTargets = searchResult.getSelectedTargets() == null
                ? List.of()
                : searchResult.getSelectedTargets();
        print("searchResult", Map.of(
                "candidateCount", searchResult.getSourceCandidates() == null ? 0 : searchResult.getSourceCandidates().size(),
                "selectedTargetCount", selectedTargets.size(),
                "reasoningSummary", searchResult.getReasoningSummary(),
                "trace", searchResult.getExecutionTrace(),
                "candidates", nullSafe(searchResult.getSourceCandidates()).stream().map(this::summarizeCandidate).toList(),
                "selectedTargets", selectedTargets.stream()
                        .map(target -> summarizeCandidate(target == null ? null : target.getCandidate()))
                        .toList()
        ));

        // 正式采集阶段会优先轻量正文读取，不可用时升级到 Playwright，并消费内部链接发现结果继续采子页。
        CollectionExecutionReport collectionReport = collectionExecutionCoordinator.execute(
                99001L,
                "collect_bilibili_docs_real_smoke",
                null,
                COMPETITOR_NAME,
                selectedTargets
        );
        List<CollectionExecutionResult> results = collectionReport == null || collectionReport.getResults() == null
                ? List.of()
                : collectionReport.getResults();
        print("collectionReport", Map.of(
                "status", collectionReport == null ? null : collectionReport.getStatus(),
                "sourceUrls", collectionReport == null ? List.of() : nullSafe(collectionReport.getSourceUrls()),
                "resultCount", results.size(),
                "results", results.stream().map(this::summarizeCollectionResult).toList()
        ));

        assertThat(selectedTargets)
                .as("name-only search should select at least one Bilibili docs target")
                .isNotEmpty();
        assertThat(results)
                .as("collection should produce at least one successful page")
                .anySatisfy(result -> {
                    assertThat(result.isSuccess()).isTrue();
                    assertThat(result.getContent()).isNotBlank();
                });
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
        summary.put("discoveredCandidates", nullSafe(result.getDiscoveredCandidates()).stream()
                .map(this::summarizeCandidate)
                .toList());
        return summary;
    }

    private void print(String label, Object value) throws Exception {
        System.out.println("BILIBILI_REAL_SMOKE " + label + "=" + objectMapper.writeValueAsString(value));
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
