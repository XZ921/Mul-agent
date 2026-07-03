package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.agent.collector.CollectorNodeConfig;
import cn.bugstack.competitoragent.source.SourceCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TavilyBootstrapPlannerTest {

    @Test
    void shouldTreatRootAndOneLevelEntryPageAsWeakCandidate() {
        TavilyBootstrapPlanner planner = new TavilyBootstrapPlanner();
        CollectorNodeConfig config = CollectorNodeConfig.builder()
                .competitorName("抖音")
                .sourceType("DOCS")
                .preferredDomains(List.of("open.douyin.com"))
                .includeDomains(List.of("open.douyin.com"))
                .competitorUrls(List.of("https://open.douyin.com/"))
                .build();

        TavilyBootstrapDecision decision = planner.plan(config, List.of(SourceCandidate.builder()
                .url("https://open.douyin.com/docs")
                .title("抖音开放平台")
                .sourceType("DOCS")
                .build()));

        assertThat(decision.isShouldExecute()).isTrue();
    }

    @Test
    void shouldStillBootstrapForDeepExactOfficialDocPageWhenFamilyIsSearchFirst() {
        TavilyBootstrapPlanner planner = new TavilyBootstrapPlanner();
        CollectorNodeConfig config = CollectorNodeConfig.builder()
                .competitorName("抖音")
                .sourceType("DOCS")
                .preferredDomains(List.of("open.douyin.com"))
                .includeDomains(List.of("open.douyin.com"))
                .competitorUrls(List.of("https://open.douyin.com/"))
                .build();

        TavilyBootstrapDecision decision = planner.plan(config, List.of(SourceCandidate.builder()
                .url("https://open.douyin.com/platform/resource/docs/accession-guide/platform-introduction")
                .title("平台简介")
                .sourceType("DOCS")
                .build()));

        assertThat(decision.isShouldExecute()).isTrue();
    }

    @Test
    void shouldBuildTrustedWebExpansionBootstrapForSearchFirstOfficialFamily() {
        TavilyBootstrapPlanner planner = new TavilyBootstrapPlanner();
        CollectorNodeConfig config = CollectorNodeConfig.builder()
                .competitorName("抖音")
                .sourceType("OFFICIAL")
                .competitorUrls(List.of("https://open.douyin.com"))
                .preferredDomains(List.of("open.douyin.com"))
                .includeDomains(List.of("open.douyin.com"))
                .tavilyQueryMode("OFFICIAL_DOCS")
                .build();

        TavilyBootstrapDecision decision = planner.plan(config, List.of(SourceCandidate.builder()
                .url("https://open.douyin.com")
                .title("抖音开放平台")
                .sourceType("OFFICIAL")
                .build()));

        assertThat(decision.isShouldExecute()).isTrue();
        assertThat(decision.getRequest()).isNotNull();
        assertThat(decision.getRequest().getRequestPhase())
                .isEqualTo(cn.bugstack.competitoragent.source.SearchRequestPhase.BOOTSTRAP);
        assertThat(decision.getRequest().getPreferredQueryMode()).isEqualTo("TRUSTED_WEB_EXPANSION");
        assertThat(decision.getRequest().getIncludeDomains()).isEmpty();
        assertThat(decision.getRequest().getPreferredDomains()).contains("open.douyin.com");
    }
    @Test
    void shouldKeepLegacyBootstrapSemanticsWhenSearchFirstFamilyAlreadyHasExplicitSourceCandidates() {
        TavilyBootstrapPlanner planner = new TavilyBootstrapPlanner();
        SourceCandidate explicitDoc = SourceCandidate.builder()
                .url("https://open.douyin.com/platform/resource/docs/accession-guide/platform-introduction")
                .title("平台简介")
                .sourceType("DOCS")
                .discoveryMethod("DIRECT_LOCATOR")
                .build();
        CollectorNodeConfig config = CollectorNodeConfig.builder()
                .competitorName("鎶栭煶")
                .sourceType("DOCS")
                .competitorUrls(List.of("https://open.douyin.com/"))
                .sourceCandidates(List.of(explicitDoc))
                .preferredDomains(List.of("open.douyin.com"))
                .includeDomains(List.of("open.douyin.com"))
                .build();

        TavilyBootstrapDecision decision = planner.plan(config, List.of(explicitDoc));

        assertThat(decision.isShouldExecute()).isFalse();
        assertThat(decision.getRequest()).isNull();
    }
}
