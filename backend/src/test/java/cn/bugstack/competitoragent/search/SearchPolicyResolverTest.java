package cn.bugstack.competitoragent.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchPolicyResolverTest {

    private final SearchPolicyResolver resolver = new SearchPolicyResolver();

    @Test
    void shouldDropBrowserFallbackWhenBrowserSearchDisabled() {
        assertEquals(
                List.of("PLANNED", "HTTP"),
                resolver.resolveFallbackOrder("HYBRID", false)
        );
    }

    @Test
    void shouldCapTargetAndMinVerifiedCountByPlannedUrls() {
        int targetCount = resolver.resolveTargetCount(5, List.of("https://a.com", "https://b.com"), 4);

        assertEquals(2, targetCount);
        assertEquals(2, resolver.resolveMinVerifiedCandidates(null, 2, targetCount));
        assertEquals(1, resolver.resolveMinVerifiedCandidates(3, 2, 1));
    }

    @Test
    void shouldDeriveTimeoutFromExecutionPlanWhenConfigMissing() {
        SearchExecutionPlan executionPlan = SearchExecutionPlan.builder()
                .steps(List.of(
                        SearchExecutionStep.builder().expectedDurationMs(500L).build(),
                        SearchExecutionStep.builder().expectedDurationMs(4500L).build()
                ))
                .build();

        assertEquals(3000L, resolver.resolveSearchTimeoutMillis(null, executionPlan));
        assertEquals(12000L, resolver.resolveSearchTimeoutMillis(12000L, executionPlan));
    }

    @Test
    void shouldKeepPrimaryVerticalAndAuxiliaryPublicRolesDistinct() {
        SearchPolicyResolver resolver = new SearchPolicyResolver();
        resolver.setSearchProperties(new SearchProperties());

        assertEquals(SearchProviderRole.AUXILIARY_PUBLIC, resolver.resolveProviderRole("qianfan"));
        assertEquals(SearchProviderRole.AUXILIARY_PUBLIC, resolver.resolveProviderRole("serpapi"));
        assertEquals(SearchProviderRole.AUXILIARY_PUBLIC, resolver.resolveProviderRole("browser"));
        assertEquals(SearchProviderRole.AUXILIARY_PUBLIC, resolver.resolveProviderRole("http"));
        assertEquals(SearchProviderRole.PRIMARY_VERTICAL, resolver.resolveSourceFamilyRole("official"));
        assertEquals(SearchProviderRole.PRIMARY_VERTICAL, resolver.resolveSourceFamilyRole("news"));
        assertEquals(SearchProviderRole.PRIMARY_VERTICAL, resolver.resolveSourceFamilyRole("github"));
    }
}
