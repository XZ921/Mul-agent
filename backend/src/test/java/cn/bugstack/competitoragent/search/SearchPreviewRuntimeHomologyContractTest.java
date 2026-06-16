package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.SourcePlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchPreviewRuntimeHomologyContractTest {

    @Test
    void shouldCarrySourceFamilyContextFromPreviewPlanToRuntimeConfig() {
        SourcePlan plan = SourcePlan.builder()
                .sourceType("DOCS")
                .sourceFamilyKey("official")
                .sourceFamilyRole("PRIMARY_VERTICAL")
                .primaryTools(List.of("WEB_SCRAPER", "JINA_READER"))
                .auxiliaryTools(List.of("PUBLIC_SEARCH"))
                .queryTemplates(List.of("search-docs-primary"))
                .sourceUrls(List.of("https://docs.example.com/reference"))
                .build();

        assertThat(plan.getSourceFamilyKey()).isEqualTo("official");
        assertThat(plan.getSourceFamilyRole()).isEqualTo("PRIMARY_VERTICAL");
        assertThat(plan.getPrimaryTools()).contains("WEB_SCRAPER", "JINA_READER");
        assertThat(plan.getAuxiliaryTools()).contains("PUBLIC_SEARCH");
        assertThat(plan.getQueryTemplates()).containsExactly("search-docs-primary");
        assertThat(plan.getSourceUrls()).containsExactly("https://docs.example.com/reference");
    }

    @Test
    void shouldResolveSourceFamilyContextBySourceType() {
        SearchPolicyResolver resolver = new SearchPolicyResolver();

        assertThat(resolver.resolveSourceFamilyKeyForSourceType("DOCS")).isEqualTo("official");
        assertThat(resolver.resolveSourceFamilyKeyForSourceType("news")).isEqualTo("news");
        assertThat(resolver.resolveSourceFamilyKeyForSourceType("GITHUB")).isEqualTo("github");
        assertThat(resolver.resolveSourceFamilyForSourceType("DOCS").getPrimaryTools())
                .contains("WEB_SCRAPER", "JINA_READER");
        assertThat(resolver.resolveSourceFamilyForSourceType("NEWS").getQueryTemplates())
                .contains("search-news-primary");
    }
}
