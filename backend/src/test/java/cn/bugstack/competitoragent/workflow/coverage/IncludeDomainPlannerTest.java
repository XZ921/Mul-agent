package cn.bugstack.competitoragent.workflow.coverage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IncludeDomainPlannerTest {

    private final IncludeDomainPlanner planner = new IncludeDomainPlanner();

    @Test
    void shouldPlanOfficialDomainsForOfficialSourceType() {
        List<String> domains = planner.planIncludeDomains(
                "哔哩哔哩",
                List.of("bilibili.com"),
                List.of("OFFICIAL", "DOCS"),
                "OFFICIAL_POSITIONING");

        assertThat(domains).contains("bilibili.com");
        assertThat(domains).anySatisfy(domain -> assertThat(domain).contains("bilibili"));
    }

    @Test
    void shouldReturnEmptyForThirdPartySourceType() {
        List<String> domains = planner.planIncludeDomains(
                "哔哩哔哩",
                List.of("bilibili.com"),
                List.of("REVIEW", "NEWS"),
                "THIRD_PARTY_REVIEW");

        // 空列表表示不约束域名，允许全网第三方来源进入候选池。
        assertThat(domains).isEmpty();
    }

    @Test
    void shouldNotExcludeThirdPartyEvenForOfficialField() {
        List<String> domains = planner.planIncludeDomains(
                "哔哩哔哩",
                List.of("bilibili.com"),
                List.of("OFFICIAL"),
                "OFFICIAL_POSITIONING");

        assertThat(domains).contains("bilibili.com");
    }
}
