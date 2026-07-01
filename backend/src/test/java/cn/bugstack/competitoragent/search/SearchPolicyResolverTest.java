package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.workflow.coverage.FieldEvidenceQuery;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

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
    void shouldRaiseTimeoutWhenFieldEvidenceQueriesNeedMinimumBudget() {
        long resolved = resolver.ensureMinimumTimeoutForFieldEvidenceQueries(
                18300L,
                List.of(
                        FieldEvidenceQuery.builder().query("q1").build(),
                        FieldEvidenceQuery.builder().query("q2").build()
                )
        );

        assertEquals(24000L, resolved);
        assertEquals(30000L, resolver.ensureMinimumTimeoutForFieldEvidenceQueries(
                30000L,
                List.of(FieldEvidenceQuery.builder().query("q1").build())
        ));
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

    @Test
    void shouldResolveWebRenderHintFromSourceFamilyCatalog() {
        SearchPolicyResolver resolver = new SearchPolicyResolver();
        resolver.setSearchProperties(new SearchProperties());

        assertEquals(
                cn.bugstack.competitoragent.collection.WebPageRenderHint.LIGHTWEIGHT,
                resolver.resolveWebRenderHint("official", "DOCS")
        );
        assertEquals(
                cn.bugstack.competitoragent.collection.WebPageRenderHint.FULL_RENDER,
                resolver.resolveWebRenderHint("github", "GITHUB")
        );
    }

    @Test
    void shouldResolveExpectedBlockTypesFromSourceFamilyCatalog() {
        SearchPolicyResolver resolver = new SearchPolicyResolver();
        resolver.setSearchProperties(new SearchProperties());

        assertIterableEquals(
                List.of("PRICING_BLOCK", "DOCUMENTATION_OUTLINE", "JSON_LD_METADATA"),
                resolver.resolveExpectedBlockTypes("official", "DOCS")
        );
        assertIterableEquals(
                List.of("RELEASE_NOTES", "JSON_LD_METADATA"),
                resolver.resolveExpectedBlockTypes("github", "GITHUB")
        );
    }

    @Test
    void shouldResolveDirectPathTemplatesFromSourceFamilyCatalog() {
        SearchPolicyResolver resolver = new SearchPolicyResolver();
        resolver.setSearchProperties(new SearchProperties());

        assertIterableEquals(
                List.of("/", "/pricing", "/docs", "/documentation", "/help"),
                resolver.resolveDirectPathTemplates("official")
        );
        assertIterableEquals(
                List.of(),
                resolver.resolveDirectPathTemplates("github")
        );
    }

    @Test
    void shouldRecognizeStableLocatorForOfficialAndGithubFamilies() {
        SearchPolicyResolver resolver = new SearchPolicyResolver();
        resolver.setSearchProperties(new SearchProperties());

        assertEquals(true, resolver.isStableLocatorForSourceFamily("official", "https://www.acme.ai"));
        assertEquals(true, resolver.isStableLocatorForSourceFamily("github", "https://github.com/acme/rocket"));
        assertEquals(false, resolver.isStableLocatorForSourceFamily("github", "https://www.acme.ai/docs"));
    }
}
