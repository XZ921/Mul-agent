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
    void shouldSkipBootstrapForDeepExactOfficialDocPage() {
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

        assertThat(decision.isShouldExecute()).isFalse();
    }
}
