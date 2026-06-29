package cn.bugstack.competitoragent.search.tavily;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TavilySearchProfileResolverTest {

    @Test
    void shouldUseOfficialDocsAsAnchorAndKeepOpenWebUnrestricted() {
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

        assertThat(docsProfile.getQueryMode()).isEqualTo(TavilyQueryMode.OFFICIAL_DOCS);
        assertThat(docsProfile.getIncludeDomains()).containsExactly("open.douyin.com");
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
        TavilySearchProfile docsProfile = resolver.resolve("抖音", "DOCS", hints, List.of());

        assertThat(docsProfile.getIncludeDomains()).containsExactly("open.douyin.com");
    }
}
