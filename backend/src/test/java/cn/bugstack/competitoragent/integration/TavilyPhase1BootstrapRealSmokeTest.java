package cn.bugstack.competitoragent.integration;

import cn.bugstack.competitoragent.agent.collector.CollectorNodeConfig;
import cn.bugstack.competitoragent.search.SearchExecutionCoordinator;
import cn.bugstack.competitoragent.search.SearchExecutionResult;
import cn.bugstack.competitoragent.search.SearchExecutionStep;
import cn.bugstack.competitoragent.source.SourceCandidate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tavily Phase 1 bootstrap 真实外网冒烟。
 * 这个用例只在显式打开 RUN_REAL_SMOKE 时执行，用来确认 Tavily 在 Phase 1
 * 而不是 supplement 阶段命中抖音开放网与 B 站官方文档样本。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "search.mode=HYBRID",
        "logging.level.cn.bugstack.competitoragent=INFO"
})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_REAL_SMOKE", matches = "true")
class TavilyPhase1BootstrapRealSmokeTest {

    @Autowired
    private SearchExecutionCoordinator searchExecutionCoordinator;

    @Test
    void shouldBootstrapDouyinOpenWebBeforeSupplement() {
        SearchExecutionResult result = executeSearch(
                "抖音",
                "DOCS",
                List.of("https://open.douyin.com/"),
                List.of("open.douyin.com"),
                List.of("抖音 开放平台 API 官方文档")
        );

        assertBootstrapEvidence(result);
    }

    @Test
    void shouldBootstrapBilibiliDocsBeforeSupplement() {
        SearchExecutionResult result = executeSearch(
                "哔哩哔哩",
                "DOCS",
                List.of("https://open.bilibili.com/"),
                List.of("open.bilibili.com"),
                List.of("哔哩哔哩 开放平台 官方文档")
        );

        assertBootstrapEvidence(result);
    }

    /**
     * 真实 smoke 的输入只保留 bootstrap 所需的最小上下文：
     * 一个弱入口 URL、一组官方域名和一个面向官方文档的 query。
     * 这样更容易判断 Tavily 命中到底发生在 Phase 1 还是 supplement。
     */
    private SearchExecutionResult executeSearch(String competitorName,
                                                String sourceType,
                                                List<String> weakEntryUrls,
                                                List<String> officialDomains,
                                                List<String> searchQueries) {
        return searchExecutionCoordinator.execute(CollectorNodeConfig.builder()
                .competitorName(competitorName)
                .sourceType(sourceType)
                .competitorUrls(weakEntryUrls)
                .sourceCandidates(weakEntryUrls.stream()
                        .map(url -> SourceCandidate.builder()
                                .url(url)
                                .title(competitorName)
                                .sourceType(sourceType)
                                .build())
                        .toList())
                .preferredDomains(officialDomains)
                .includeDomains(officialDomains)
                .searchQueries(searchQueries)
                .preferredSearchProvider("tavily")
                .tavilyQueryMode("OFFICIAL_DOCS")
                .verifyCandidates(Boolean.TRUE)
                .browserSearchEnabled(Boolean.TRUE)
                .maxSearchResults(2)
                .minVerifiedCandidates(1)
                .build());
    }

    /**
     * 真实 smoke 不能再把最终 selectionStage=BOOTSTRAPPED 当成唯一证据。
     * bootstrap 候选如果继续通过 VERIFY / SELECT，阶段名可能升级为 VERIFIED / SELECTED。
     * 因此这里改为同时校验候选来源、审计 queryOrigins 和执行计划 step 状态，
     * 直接证明 Tavily 命中发生在 Phase 1 bootstrap，且没有退化到 supplement。
     */
    private void assertBootstrapEvidence(SearchExecutionResult result) {
        assertThat(result.getSourceCandidates())
                .anySatisfy(candidate -> {
                    assertThat(candidate.getProviderKey()).isEqualToIgnoringCase("tavily");
                    assertThat(candidate.getDiscoveryMethod()).isEqualTo("TAVILY_PHASE1_BOOTSTRAP");
                });
        assertThat(result.getAuditSnapshot()).isNotNull();
        assertThat(result.getAuditSnapshot().getTavilyFastLaneAudit()).isNotNull();
        assertThat(result.getAuditSnapshot().getTavilyFastLaneAudit().getQueryOrigins())
                .contains("BOOTSTRAP")
                .doesNotContain("SUPPLEMENT");
        assertThat(result.getAuditSnapshot().getTavilyFastLaneAudit().getBootstrapTriggered()).isTrue();
        assertThat(resolveStepStatus(result, "TAVILY_BOOTSTRAP_ENRICH")).isEqualTo(SearchExecutionStep.StepStatus.SUCCESS);
        assertThat(resolveStepStatus(result, "BROWSER_SUPPLEMENT_SEARCH")).isEqualTo(SearchExecutionStep.StepStatus.SKIPPED);
    }

    private SearchExecutionStep.StepStatus resolveStepStatus(SearchExecutionResult result, String stepCode) {
        return result.getExecutionPlan().getSteps().stream()
                .filter(step -> stepCode.equals(step.getStepCode()))
                .findFirst()
                .map(SearchExecutionStep::getStatus)
                .orElseThrow();
    }
}
