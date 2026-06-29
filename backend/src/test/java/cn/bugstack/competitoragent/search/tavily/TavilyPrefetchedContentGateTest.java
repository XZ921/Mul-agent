package cn.bugstack.competitoragent.search.tavily;

import cn.bugstack.competitoragent.source.SourceCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TavilyPrefetchedContentGateTest {

    private final TavilySearchProperties properties = properties();
    private final TavilyPrefetchedContentGate gate = new TavilyPrefetchedContentGate(properties, new TavilyPageTypeClassifier());

    @Test
    void shouldMarkOfficialDocumentAsStrongFastLaneWhenRawContentIsEnough() {
        SourceCandidate candidate = baseCandidate(
                "https://open.douyin.com/docs/api",
                "DOCS",
                "OFFICIAL_DOCS",
                0.72D);
        TavilyPrefetchedContent content = TavilyPrefetchedContent.builder()
                .url(candidate.getUrl())
                .title("开放平台 API 文档")
                .content("接口说明")
                .rawContent(repeat('官', 557))
                .cleanedContent(repeat('官', 557))
                .sourceUrls(List.of(candidate.getUrl()))
                .build();

        SourceCandidate gated = gate.apply(candidate, content, Set.of("open.douyin.com"));

        assertThat(gated.getPageType()).isEqualTo("OFFICIAL_DOC");
        assertThat(gated.getQualityTier()).isEqualTo("STRONG");
        assertThat(gated.getFastLaneUsable()).isTrue();
        assertThat(gated.getSkipNetworkVerification()).isFalse();
        assertThat(gated.getContentCompleteness()).isEqualTo("FULL_ENOUGH");
        assertThat(gated.getFastLaneRejectReason()).isNull();
    }

    @Test
    void shouldKeepPdfUsableButMarkCompletenessPartial() {
        SourceCandidate candidate = baseCandidate(
                "https://pdf.dfcfw.com/pdf/H3_AP202404191630123.pdf",
                "RESEARCH",
                "OPEN_WEB",
                0.63D);
        TavilyPrefetchedContent content = TavilyPrefetchedContent.builder()
                .url(candidate.getUrl())
                .title("东方财富证券研究报告.pdf")
                .content("研究报告摘要")
                .rawContent(repeat('研', 3200))
                .cleanedContent(repeat('研', 3200))
                .sourceUrls(List.of(candidate.getUrl()))
                .build();

        SourceCandidate gated = gate.apply(candidate, content, Set.of());

        assertThat(gated.getPageType()).isEqualTo("PDF");
        assertThat(gated.getQualityTier()).isIn("MEDIUM", "STRONG");
        assertThat(gated.getFastLaneUsable()).isTrue();
        assertThat(gated.getSkipNetworkVerification()).isTrue();
        assertThat(gated.getContentCompleteness()).isEqualTo("PARTIAL");
        assertThat(gated.getFastLaneRejectReason()).isNull();
    }

    @Test
    void shouldRejectSearchPageWithoutRawContent() {
        SourceCandidate candidate = baseCandidate(
                "https://www.douyin.com/search/%E6%8A%96%E9%9F%B3",
                "NEWS",
                "OPEN_WEB",
                0.91D);
        TavilyPrefetchedContent content = TavilyPrefetchedContent.builder()
                .url(candidate.getUrl())
                .title("抖音搜索")
                .content("只有一句摘要")
                .rawContent("")
                .cleanedContent("")
                .sourceUrls(List.of(candidate.getUrl()))
                .build();

        SourceCandidate gated = gate.apply(candidate, content, Set.of());

        assertThat(gated.getPageType()).isEqualTo("SEARCH_PAGE");
        assertThat(gated.getQualityTier()).isEqualTo("REJECT");
        assertThat(gated.getFastLaneUsable()).isFalse();
        assertThat(gated.getSkipNetworkVerification()).isFalse();
        assertThat(gated.getContentCompleteness()).isEqualTo("THIN");
        assertThat(gated.getFastLaneRejectReason()).isEqualTo("SEARCH_PAGE");
    }

    @Test
    void shouldMarkThinCourseOutlineAsWeakInsteadOfFastLane() {
        SourceCandidate candidate = baseCandidate(
                "https://www.example.com/course/recommendation-outline",
                "REVIEW",
                "OPEN_WEB",
                0.56D);
        TavilyPrefetchedContent content = TavilyPrefetchedContent.builder()
                .url(candidate.getUrl())
                .title("推荐算法课程大纲")
                .content("目录摘要")
                .rawContent(repeat('课', 900))
                .cleanedContent(repeat('课', 900))
                .sourceUrls(List.of(candidate.getUrl()))
                .build();

        SourceCandidate gated = gate.apply(candidate, content, Set.of());

        assertThat(gated.getPageType()).isEqualTo("ARTICLE");
        assertThat(gated.getQualityTier()).isEqualTo("WEAK");
        assertThat(gated.getFastLaneUsable()).isFalse();
        assertThat(gated.getSkipNetworkVerification()).isFalse();
        assertThat(gated.getContentCompleteness()).isEqualTo("THIN");
        assertThat(gated.getFastLaneRejectReason()).isEqualTo("WEAK_CONTENT");
    }

    private SourceCandidate baseCandidate(String url, String sourceType, String queryMode, double tavilyScore) {
        return SourceCandidate.builder()
                .url(url)
                .title("候选来源")
                .sourceType(sourceType)
                .providerKey("tavily")
                .sourceUrls(List.of(url))
                .hasPrefetchedContent(true)
                .prefetchedContentRef("tavily:req-1:1")
                .prefetchedRawContentLength(0)
                .tavilyScore(tavilyScore)
                .tavilyQuery("测试查询")
                .tavilyQueryMode(queryMode)
                .build();
    }

    private TavilySearchProperties properties() {
        TavilySearchProperties properties = new TavilySearchProperties();
        properties.setEnabled(true);
        properties.setApiKey("test-key");
        properties.setEndpoint("https://api.tavily.com/search");
        properties.setMinRawContentChars(500);
        properties.setMinTavilyScore(0.45D);
        return properties;
    }

    private String repeat(char ch, int count) {
        return String.valueOf(ch).repeat(Math.max(0, count));
    }
}
