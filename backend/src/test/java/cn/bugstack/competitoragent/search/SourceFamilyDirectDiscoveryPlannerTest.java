package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.SourceCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SourceFamilyDirectDiscoveryPlannerTest {

    @Test
    void shouldBuildOfficialDirectCandidatesFromProvidedRootBeforePublicSearch() {
        SourceFamilyDirectDiscoveryPlanner planner = new SourceFamilyDirectDiscoveryPlanner(new SearchPolicyResolver());

        List<SourceCandidate> candidates = planner.buildInitialCandidates(
                "Acme AI",
                "DOCS",
                List.of("https://www.acme.ai")
        );

        assertThat(candidates).extracting(SourceCandidate::getUrl)
                .contains("https://www.acme.ai", "https://www.acme.ai/pricing", "https://www.acme.ai/docs");
        assertThat(candidates).allMatch(candidate ->
                "official".equals(candidate.getSourceFamilyKey())
                        && "PRIMARY_VERTICAL".equals(candidate.getSourceFamilyRole())
                        && List.of("DIRECT_LOCATOR", "FAMILY_TEMPLATE", "FAMILY_SUBDOMAIN_TEMPLATE")
                        .contains(candidate.getDiscoveryMethod()));
    }

    @Test
    void shouldExpandOfficialSubdomainTemplatesFromRootUrl() {
        SearchPolicyResolver resolver = new SearchPolicyResolver();
        SourceFamilyDirectDiscoveryPlanner planner = new SourceFamilyDirectDiscoveryPlanner(resolver);

        List<SourceCandidate> candidates = planner.buildInitialCandidates(
                "哔哩哔哩",
                "OFFICIAL",
                List.of("https://bilibili.com")
        );

        assertThat(candidates)
                .extracting(SourceCandidate::getUrl)
                .contains("https://open.bilibili.com", "https://docs.bilibili.com");
        assertThat(candidates)
                .filteredOn(candidate -> "https://open.bilibili.com".equals(candidate.getUrl()))
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.getDiscoveryMethod()).isEqualTo("FAMILY_SUBDOMAIN_TEMPLATE");
                    assertThat(candidate.getSourceUrls()).containsExactly("https://open.bilibili.com");
                });
    }

    @Test
    void shouldMarkOfficialTemplateCandidatesAsLowTrustFallbacks() {
        SourceFamilyDirectDiscoveryPlanner planner = new SourceFamilyDirectDiscoveryPlanner(new SearchPolicyResolver());

        List<SourceCandidate> candidates = planner.buildInitialCandidates(
                "Airtable",
                "DOCS",
                List.of("https://www.airtable.com")
        );

        assertThat(candidates)
                .filteredOn(candidate -> "https://www.airtable.com/docs".equals(candidate.getUrl()))
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.getDiscoveryMethod()).isEqualTo("FAMILY_TEMPLATE");
                    assertThat(candidate.getTemplateFallback()).isTrue();
                    assertThat(candidate.getFallbackReason()).isEqualTo("no_discovered_docs_candidate");
                    assertThat(candidate.getSourceUrls()).containsExactly("https://www.airtable.com/docs");
                });
    }

    @Test
    void shouldKeepOpenPlatformSubdomainInOfficialCandidatePool() {
        SourceFamilyDirectDiscoveryPlanner planner = new SourceFamilyDirectDiscoveryPlanner(new SearchPolicyResolver());

        List<SourceCandidate> candidates = planner.buildInitialCandidates(
                "哔哩哔哩",
                "OFFICIAL",
                List.of("https://bilibili.com")
        );

        assertThat(candidates)
                .filteredOn(candidate -> "https://open.bilibili.com".equals(candidate.getUrl()))
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.getSourceType()).isEqualTo("OFFICIAL");
                    assertThat(candidate.getDiscoveryMethod()).isEqualTo("FAMILY_SUBDOMAIN_TEMPLATE");
                });
    }

    @Test
    void shouldKeepOpenPlatformSubdomainAsDocsEntryForDocsCandidatePool() {
        SourceFamilyDirectDiscoveryPlanner planner = new SourceFamilyDirectDiscoveryPlanner(new SearchPolicyResolver());

        List<SourceCandidate> candidates = planner.buildInitialCandidates(
                "抖音",
                "DOCS",
                List.of("https://douyin.com")
        );

        assertThat(candidates)
                .filteredOn(candidate -> "https://open.douyin.com".equals(candidate.getUrl()))
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.getSourceType()).isEqualTo("DOCS");
                    assertThat(candidate.getQualitySignals()).contains("OPEN_PLATFORM_DOCS_ENTRY");
                });
    }

    @Test
    void shouldKeepProvidedOfficialPathAsDirectLocatorButExpandTemplatesFromRootOnly() {
        SourceFamilyDirectDiscoveryPlanner planner = new SourceFamilyDirectDiscoveryPlanner(new SearchPolicyResolver());

        List<SourceCandidate> candidates = planner.buildInitialCandidates(
                "Acme AI",
                "DOCS",
                List.of("https://www.acme.ai/products/ai-platform")
        );

        assertThat(candidates).extracting(SourceCandidate::getUrl)
                .contains("https://www.acme.ai/products/ai-platform")
                .contains("https://www.acme.ai/pricing", "https://www.acme.ai/docs");
        assertThat(candidates).extracting(SourceCandidate::getUrl)
                .doesNotContain("https://www.acme.ai/products/ai-platform/pricing")
                .doesNotContain("https://www.acme.ai/products/ai-platform/docs");
    }

    @Test
    void shouldBuildGithubDirectCandidateFromExplicitRepoUrlWithoutSearchProvider() {
        SourceFamilyDirectDiscoveryPlanner planner = new SourceFamilyDirectDiscoveryPlanner(new SearchPolicyResolver());

        List<SourceCandidate> candidates = planner.buildInitialCandidates(
                "Acme AI",
                "GITHUB",
                List.of("https://github.com/acme/rocket")
        );

        assertThat(candidates).singleElement().satisfies(candidate -> {
            assertThat(candidate.getUrl()).isEqualTo("https://github.com/acme/rocket");
            assertThat(candidate.getSourceFamilyKey()).isEqualTo("github");
            assertThat(candidate.getDiscoveryMethod()).isEqualTo("DIRECT_LOCATOR");
            assertThat(candidate.getSourceUrls()).containsExactly("https://github.com/acme/rocket");
        });
    }
}
