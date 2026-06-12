package cn.bugstack.competitoragent.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchSourceCatalogPropertiesTest {

    @Test
    void shouldExposeFirstIterationSourceFamiliesWithToolsAndUpdatePolicies() {
        SearchSourceCatalogProperties properties = new SearchSourceCatalogProperties();

        assertThat(properties.getFamilies()).containsKeys("official", "news", "github");
        assertThat(properties.getFamilies().get("official").getRole()).isEqualTo("PRIMARY_VERTICAL");
        assertThat(properties.getFamilies().get("official").getPrimaryTools())
                .contains("WEB_SCRAPER", "JINA_READER");
        assertThat(properties.getFamilies().get("official").getAuxiliaryTools())
                .contains("PUBLIC_SEARCH");
        assertThat(properties.getFamilies().get("news").getUpdatePolicy().getMode())
                .isEqualTo("REALTIME_RSS_AND_SCHEDULED_SWEEP");
        assertThat(properties.getFamilies().get("github").getPrimaryTools())
                .contains("GITHUB_API");
    }
}
