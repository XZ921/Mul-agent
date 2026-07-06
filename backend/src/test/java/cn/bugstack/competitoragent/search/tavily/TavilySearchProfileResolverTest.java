package cn.bugstack.competitoragent.search.tavily;

import cn.bugstack.competitoragent.workflow.coverage.FieldEvidenceQuery;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TavilySearchProfileResolverTest {

    @Test
    void shouldRouteOfficialDocsPrimaryThroughSearchFirstAndKeepOpenWebUnrestricted() {
        DomainHintSet hints = DomainHintSet.builder()
                .competitorName("抖音")
                .domains(List.of(DomainHint.builder()
                        .domain("open.douyin.com")
                        .sourceFamily("docs")
                        .confidence(0.88D)
                        .source("INFERRED")
                        .reason("开放平台文档域名")
                        .sourceUrls(List.of("https://open.douyin.com"))
                        .build()))
                .build();

        TavilySearchProfileResolver resolver = new TavilySearchProfileResolver(new TavilySearchProperties());

        TavilySearchProfile docsProfile = resolver.resolve("抖音", "DOCS", hints, List.of());
        TavilySearchProfile newsProfile = resolver.resolve("抖音", "NEWS", hints, List.of());

        assertThat(docsProfile.getQueryMode()).isEqualTo(TavilyQueryMode.TRUSTED_WEB_EXPANSION);
        assertThat(docsProfile.getIncludeDomains()).isEmpty();
        assertThat(docsProfile.getOfficialDomains()).containsExactly("open.douyin.com");
        assertThat(newsProfile.getQueryMode()).isEqualTo(TavilyQueryMode.OPEN_WEB);
        assertThat(newsProfile.getIncludeDomains()).isEmpty();
    }

    @Test
    void shouldAllowTrustedWebExpansionWhenOfficialAnchorIsInsufficient() {
        DomainHintSet hints = DomainHintSet.builder()
                .competitorName("抖音")
                .domains(List.of(DomainHint.builder()
                        .domain("open.douyin.com")
                        .sourceFamily("docs")
                        .confidence(0.88D)
                        .source("INFERRED")
                        .reason("开放平台文档域名")
                        .sourceUrls(List.of("https://open.douyin.com"))
                        .build()))
                .build();

        TavilySearchProfileResolver resolver = new TavilySearchProfileResolver(new TavilySearchProperties());
        TavilySearchProfile expansionProfile = resolver.resolveTrustedExpansion(
                "抖音",
                "DOCS",
                hints,
                "officialDocHitCount=0; usableContentRatio below threshold"
        );

        assertThat(expansionProfile.getQueryMode()).isEqualTo(TavilyQueryMode.TRUSTED_WEB_EXPANSION);
        assertThat(expansionProfile.getIncludeDomains()).isEmpty();
        assertThat(expansionProfile.getExpansionReason()).contains("officialDocHitCount=0");
        assertThat(expansionProfile.getQuery()).contains("抖音");
    }

    @Test
    void shouldRouteOfficialFieldEvidenceBackToOfficialDocsForNoiseReduction() {
        TavilySearchProfileResolver resolver = new TavilySearchProfileResolver(new TavilySearchProperties());

        for (String sourceType : List.of("OFFICIAL", "DOCS", "PRICING")) {
            FieldEvidenceQuery query = FieldEvidenceQuery.builder()
                    .fieldName("summary")
                    .evidencePathKey("OFFICIAL_PUBLIC_PROFILE")
                    .queryIntent("OFFICIAL_DOCS")
                    .sourceType(sourceType)
                    .query("douyin open platform official profile")
                    .includeDomains(List.of("open.douyin.com"))
                    .build();

            TavilySearchProfile profile = resolver.resolveFieldEvidence(query);

            assertThat(profile.getQueryMode())
                    .as("sourceType=%s", sourceType)
                    .isEqualTo(TavilyQueryMode.OFFICIAL_DOCS);
            assertThat(profile.getIncludeDomains())
                    .as("official docs mode must keep official include_domains for sourceType=%s", sourceType)
                    .containsExactly("open.douyin.com");
            assertThat(profile.getOfficialDomains())
                    .as("official docs mode must keep official domain hints for Gate sourceType=%s", sourceType)
                    .containsExactly("open.douyin.com");
        }
    }

    @Test
    void shouldRouteSearchFirstOfficialPrimarySearchToTrustedWebExpansion() {
        DomainHintSet hints = DomainHintSet.builder()
                .competitorName("Douyin")
                .domains(List.of(DomainHint.builder()
                        .domain("open.douyin.com")
                        .sourceFamily("docs")
                        .confidence(0.88D)
                        .source("INFERRED")
                        .reason("docs domain")
                        .sourceUrls(List.of("https://open.douyin.com"))
                        .build()))
                .build();

        TavilySearchProfileResolver resolver = new TavilySearchProfileResolver(new TavilySearchProperties());

        for (String family : List.of("OFFICIAL", "DOCS", "PRICING")) {
            TavilySearchProfile profile = resolver.resolve("Douyin", family, hints, List.of());

            assertThat(profile.getQueryMode())
                    .as("family=%s", family)
                    .isEqualTo(TavilyQueryMode.TRUSTED_WEB_EXPANSION);
            assertThat(profile.getIncludeDomains())
                    .as("primary search-first query must not be constrained by include_domains for family=%s", family)
                    .isEmpty();
            assertThat(profile.getOfficialDomains())
                    .as("primary search-first query must keep official domain hints for Gate family=%s", family)
                    .containsExactly("open.douyin.com");
        }
    }

    @Test
    void shouldUseSuggestedQueryForEvidenceRepairMode() {
        DomainHintSet hints = DomainHintSet.builder()
                .competitorName("抖音")
                .domains(List.of(DomainHint.builder()
                        .domain("open.douyin.com")
                        .sourceFamily("docs")
                        .confidence(0.88D)
                        .source("INFERRED")
                        .reason("开放平台文档域名")
                        .sourceUrls(List.of("https://open.douyin.com"))
                        .build()))
                .build();

        TavilySearchProfileResolver resolver = new TavilySearchProfileResolver(new TavilySearchProperties());
        TavilySearchProfile repairProfile = resolver.resolve(
                "抖音",
                "DOCS",
                hints,
                List.of("推荐算法缺少官方文档支撑")
        );

        assertThat(repairProfile.getQueryMode()).isEqualTo(TavilyQueryMode.EVIDENCE_REPAIR);
        assertThat(repairProfile.getQuery()).isEqualTo("推荐算法缺少官方文档支撑");
    }

    @Test
    void shouldIgnoreLowConfidenceOrBlankDomainHintsWhenBuildingOfficialAnchor() {
        DomainHintSet hints = DomainHintSet.builder()
                .competitorName("抖音")
                .domains(List.of(
                        DomainHint.builder()
                                .domain("open.douyin.com")
                                .sourceFamily("docs")
                                .confidence(0.88D)
                                .source("INFERRED")
                                .reason("开放平台文档域名")
                                .sourceUrls(List.of("https://open.douyin.com"))
                                .build(),
                        DomainHint.builder()
                                .domain("  ")
                                .sourceFamily("docs")
                                .confidence(0.99D)
                                .source("INFERRED")
                                .reason("空白域名无效")
                                .sourceUrls(List.of("https://example.com"))
                                .build(),
                        DomainHint.builder()
                                .domain("docs.douyin.example")
                                .sourceFamily("docs")
                                .confidence(0.59D)
                                .source("INFERRED")
                                .reason("置信度不足")
                                .sourceUrls(List.of("https://docs.douyin.example"))
                                .build()))
                .build();

        TavilySearchProfileResolver resolver = new TavilySearchProfileResolver(new TavilySearchProperties());
        TavilySearchProfile docsProfile = resolver.resolveOfficialDocsAnchor("抖音", "DOCS", hints);

        assertThat(docsProfile.getQueryMode()).isEqualTo(TavilyQueryMode.OFFICIAL_DOCS);
        assertThat(docsProfile.getIncludeDomains()).containsExactly("open.douyin.com");
        assertThat(docsProfile.getOfficialDomains()).containsExactly("open.douyin.com");
    }
}
