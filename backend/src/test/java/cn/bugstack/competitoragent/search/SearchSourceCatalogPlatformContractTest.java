package cn.bugstack.competitoragent.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchSourceCatalogPlatformContractTest {

    @Test
    void shouldKeepBusinessSourceFamilyFieldsInCatalogInsteadOfProviderRouting() {
        SearchSourceCatalogProperties catalog = new SearchSourceCatalogProperties();

        SearchSourceCatalogProperties.SourceFamilyProperties official = catalog.resolveFamily("official");
        SearchSourceCatalogProperties.SourceFamilyProperties github = catalog.resolveFamily("github");

        assertThat(official.getSourceTypes()).contains("OFFICIAL", "PRICING", "DOCS");
        assertThat(official.getContentScopes()).contains("PRODUCT_PAGE", "PRICING", "DOCUMENTATION");
        assertThat(official.getDirectPathTemplates())
                .containsExactly("/", "/pricing", "/docs", "/documentation", "/help");
        assertThat(official.getPrimaryTools()).contains("WEB_SCRAPER", "JINA_READER");
        assertThat(official.getAuxiliaryTools()).contains("PUBLIC_SEARCH");
        assertThat(github.getDirectPathTemplates()).isEmpty();
        assertThat(github.getPrimaryTools()).contains("GITHUB_API");
        assertThat(github.getQueryTemplates()).contains("search-github-repository", "search-github-release");
    }

    @Test
    void shouldResolveToolBindingsWithoutRequiringRealProviderImplementation() {
        SearchSourceCatalogProperties catalog = new SearchSourceCatalogProperties();
        SearchSourceCatalogProperties.SourceFamilyProperties family = new SearchSourceCatalogProperties.SourceFamilyProperties();
        family.setRole(SearchProviderRole.PRIMARY_VERTICAL.name());
        family.setPrimaryTools(java.util.List.of("GITHUB_API"));
        family.setAuxiliaryTools(java.util.List.of("PUBLIC_SEARCH"));
        family.setToolProviderKeys(java.util.Map.of("GITHUB_API", "github"));
        catalog.getFamilies().put("github", family);

        assertThat(catalog.resolveFamily("github").resolveProviderKeys(SearchProviderRole.PRIMARY_VERTICAL))
                .containsExactly("github");
        assertThat(catalog.resolveFamily("github").resolveProviderKeys(SearchProviderRole.AUXILIARY_PUBLIC))
                .isEmpty();
    }
}
