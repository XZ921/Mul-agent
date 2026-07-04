package cn.bugstack.competitoragent.search.tavily;

import cn.bugstack.competitoragent.workflow.coverage.FieldEvidenceQuery;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TavilyFieldEvidenceProfileResolverTest {

    @Test
    void shouldUseBasicProfileWithoutRawForFieldEvidenceDiscovery() {
        TavilySearchProperties properties = new TavilySearchProperties();
        properties.setSearchDepth("advanced");
        properties.setIncludeRawContent(true);
        properties.setMaxResults(5);
        TavilySearchProfileResolver resolver = new TavilySearchProfileResolver(properties);

        TavilySearchProfile profile = resolver.resolveFieldEvidence(FieldEvidenceQuery.builder()
                .fieldName("summary")
                .evidencePathKey("OPEN_PLATFORM_PROFILE")
                .queryIntent("OFFICIAL_PROFILE")
                .sourceType("OFFICIAL")
                .query("douyin open platform official profile")
                .includeDomains(List.of("open.douyin.com"))
                .queryFingerprint("q-summary-official")
                .reason("字段发现阶段先做轻量候选发现")
                .build());

        assertThat(profile.getQueryMode()).isEqualTo(TavilyQueryMode.TRUSTED_WEB_EXPANSION);
        assertThat(profile.getSearchDepth()).isEqualTo("basic");
        assertThat(profile.isIncludeRawContent()).isFalse();
        assertThat(profile.getProfileStage()).isEqualTo("FIELD_EVIDENCE_DISCOVERY");
        assertThat(profile.getOfficialDomains()).containsExactly("open.douyin.com");
        assertThat(profile.getMaxResults()).isEqualTo(5);
    }

    @Test
    void shouldUseRawForWinnerFetchOnly() {
        TavilySearchProperties properties = new TavilySearchProperties();
        properties.setSearchDepth("advanced");
        properties.setIncludeRawContent(true);
        properties.setMaxResults(5);
        TavilySearchProfileResolver resolver = new TavilySearchProfileResolver(properties);

        TavilySearchProfile profile = resolver.resolveFieldEvidenceWinnerRawFetch(
                FieldEvidenceQuery.builder()
                        .fieldName("summary")
                        .evidencePathKey("OPEN_PLATFORM_PROFILE")
                        .queryIntent("OFFICIAL_PROFILE")
                        .sourceType("OFFICIAL")
                        .query("douyin open platform official profile")
                        .includeDomains(List.of("open.douyin.com"))
                        .queryFingerprint("q-summary-official")
                        .reason("候选胜出后回拉 raw")
                        .build(),
                "https://open.example.com/docs/profile"
        );

        assertThat(profile.getQueryMode()).isEqualTo(TavilyQueryMode.TRUSTED_WEB_EXPANSION);
        assertThat(profile.getQuery()).isEqualTo("site:open.example.com douyin open platform official profile");
        assertThat(profile.getIncludeDomains()).containsExactly("open.example.com");
        assertThat(profile.getOfficialDomains()).containsExactly("open.douyin.com");
        assertThat(profile.getSearchDepth()).isEqualTo("advanced");
        assertThat(profile.isIncludeRawContent()).isTrue();
        assertThat(profile.getMaxResults()).isEqualTo(1);
        assertThat(profile.getProfileStage()).isEqualTo("FIELD_EVIDENCE_WINNER_RAW_FETCH");
    }
}
