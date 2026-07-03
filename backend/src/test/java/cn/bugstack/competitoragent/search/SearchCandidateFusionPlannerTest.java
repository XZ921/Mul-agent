package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.agent.collector.CollectorNodeConfig;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCandidateRanker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchCandidateFusionPlannerTest {

    @Test
    void shouldBuildVerificationPlanFromFusedCandidatesAndKeepFastLaneOutOfBrowserVerification() {
        SearchCandidateFusionPlanner planner = new SearchCandidateFusionPlanner(
                new SearchPolicyResolver(),
                new SourceCandidateRanker()
        );
        CollectorNodeConfig config = CollectorNodeConfig.builder()
                .competitorName("抖音")
                .sourceType("OFFICIAL")
                .competitorUrls(List.of("https://open.douyin.com"))
                .maxSearchResults(1)
                .searchRuntimePolicy(SearchRuntimePolicy.builder()
                        .searchFirstEvidenceTargetFloor(3)
                        .searchFirstEvidenceTargetCeiling(3)
                        .preSelectionVerificationLimit(3)
                        .build())
                .build();

        SearchCandidateFusionDecision decision = planner.plan(
                config,
                List.of(
                        SourceCandidate.builder()
                                .url("https://open.douyin.com")
                                .title("抖音开放平台")
                                .providerKey("planned")
                                .discoveryMethod("DIRECT_LOCATOR")
                                .domain("open.douyin.com")
                                .totalScore(0.98)
                                .build(),
                        SourceCandidate.builder()
                                .url("https://developer.open-douyin.com/docs/resource/zh-CN/openapi/introduction")
                                .title("抖音开放平台介绍")
                                .providerKey("tavily")
                                .discoveryMethod("TAVILY_PHASE1_BOOTSTRAP")
                                .domain("developer.open-douyin.com")
                                .qualityTier("STRONG")
                                .fastLaneUsable(Boolean.TRUE)
                                .hasPrefetchedContent(Boolean.TRUE)
                                .skipNetworkVerification(Boolean.TRUE)
                                .prefetchedContentRef("prefetch-official")
                                .prefetchedRawContentLength(19_555)
                                .tavilyQueryMode("TRUSTED_WEB_EXPANSION")
                                .pageType("ARTICLE")
                                .totalScore(0.94)
                                .build(),
                        SourceCandidate.builder()
                                .url("https://www.woshipm.com/pd/6100000.html")
                                .title("抖音开放平台接入分析")
                                .providerKey("tavily")
                                .discoveryMethod("TAVILY_PHASE1_BOOTSTRAP")
                                .domain("www.woshipm.com")
                                .qualityTier("STRONG")
                                .fastLaneUsable(Boolean.TRUE)
                                .hasPrefetchedContent(Boolean.TRUE)
                                .skipNetworkVerification(Boolean.TRUE)
                                .prefetchedContentRef("prefetch-third-party")
                                .prefetchedRawContentLength(3_109)
                                .tavilyQueryMode("TRUSTED_WEB_EXPANSION")
                                .pageType("ARTICLE")
                                .totalScore(0.89)
                                .build(),
                        SourceCandidate.builder()
                                .url("https://open.douyin.com/solution")
                                .title("抖音开放平台解决方案")
                                .providerKey("planned")
                                .discoveryMethod("FAMILY_TEMPLATE")
                                .domain("open.douyin.com")
                                .totalScore(0.93)
                                .build()
                ),
                1,
                2
        );

        assertThat(decision.getEffectiveTargetCount()).isGreaterThanOrEqualTo(3);
        assertThat(decision.getFastLaneCandidates()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(decision.getVerificationCandidates())
                .noneMatch(candidate -> Boolean.TRUE.equals(candidate.getSkipNetworkVerification()));
        assertThat(decision.getPreselectedCandidates())
                .anyMatch(candidate -> "www.woshipm.com".equals(candidate.getDomain()));
    }
}
