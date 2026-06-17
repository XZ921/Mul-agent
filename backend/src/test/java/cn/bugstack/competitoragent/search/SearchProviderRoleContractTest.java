package cn.bugstack.competitoragent.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第四轮 Task 1 的联合红灯契约。
 * 这里先锁死 GitHub provider 在 Source Family Catalog 绑定后的角色语义，
 * 避免后续 discovery owner / collection owner 继续被当成公网辅助 provider 处理。
 */
class SearchProviderRoleContractTest {

    @Test
    void shouldTreatGithubProviderAsPrimaryVerticalWhenBoundBySourceFamilyCatalog() {
        SearchPolicyResolver resolver = new SearchPolicyResolver();
        SearchSourceCatalogProperties catalog = new SearchSourceCatalogProperties();
        catalog.getFamilies().get("github").getToolProviderKeys().put("GITHUB_API", "github");

        SearchProperties properties = new SearchProperties();
        properties.setSourceCatalog(catalog);
        resolver.setSearchProperties(properties);

        assertThat(resolver.resolveProviderRole("github")).isEqualTo(SearchProviderRole.PRIMARY_VERTICAL);
        assertThat(resolver.resolveSourceFamilyRole("github")).isEqualTo(SearchProviderRole.PRIMARY_VERTICAL);
    }
}
