package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.agent.collector.CollectorNodeConfig;
import cn.bugstack.competitoragent.config.CollectorProperties;
import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.search.SearchBrowserProperties;
import cn.bugstack.competitoragent.search.SearchPolicyResolver;
import cn.bugstack.competitoragent.search.SearchProperties;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourcePlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CollectorPlanTemplateFactoryTest {

    @Test
    void shouldCarryOfficialDomainsIntoIncludeDomainsForCollectorConfig() {
        PromptTemplateService promptTemplateService = mock(PromptTemplateService.class);
        when(promptTemplateService.buildSearchQueries(any(), any(), any()))
                .thenReturn(List.of("Notion official docs"));
        when(promptTemplateService.buildThirdPartyFallbackQueries(any()))
                .thenReturn(List.of("Notion review documentation"));
        CollectorPlanTemplateFactory factory = new CollectorPlanTemplateFactory(
                promptTemplateService,
                new SearchBrowserProperties(),
                new SearchProperties(),
                new CollectorProperties(),
                new SearchPolicyResolver()
        );
        SourcePlan sourcePlan = SourcePlan.builder()
                .sourceType("DOCS")
                .urls(List.of("https://www.notion.so/docs"))
                .candidates(List.of(SourceCandidate.builder()
                        .url("https://www.notion.so/docs")
                        .domain("www.notion.so")
                        .sourceType("DOCS")
                        .build()))
                .build();

        CollectorNodeConfig config = factory.createCollectorNodeConfig(
                "Notion",
                List.of("产品文档"),
                "阶段1首报",
                sourcePlan
        );

        assertThat(config.getPreferredDomains()).containsExactly("www.notion.so");
        assertThat(config.getIncludeDomains()).containsExactly("www.notion.so");
    }
}
