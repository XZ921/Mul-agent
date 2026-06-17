package cn.bugstack.competitoragent.collection;

import cn.bugstack.competitoragent.search.SearchPolicyResolver;
import cn.bugstack.competitoragent.source.SourceCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第四轮 Task 1 的最小采集任务包红灯测试。
 * 这里先锁死“候选元数据 -> 采集任务包”的最小映射契约，
 * 避免后续接入 GitHub API 采集时继续依赖 URL -> HTML 的旧路径。
 */
class CollectionTaskPackageBuilderTest {

    @Test
    void shouldBuildGithubTaskPackageFromCandidateMetadata() {
        SourceCandidate candidate = SourceCandidate.builder()
                .url("https://github.com/acme/rocket")
                .sourceType("GITHUB")
                .providerKey("github")
                .sourceFamilyKey("github")
                .sourceUrls(List.of("https://github.com/acme/rocket"))
                .build();

        CollectionTaskPackageBuilder builder = new CollectionTaskPackageBuilder();
        CollectionTaskPackage taskPackage = builder.build(
                41L,
                "collect_sources_01_01",
                9L,
                "Acme AI",
                candidate,
                1
        );

        assertThat(taskPackage.getPrimaryTool()).isEqualTo("GITHUB_API");
        assertThat(taskPackage.getResourceLocator()).isEqualTo("github://repo/acme/rocket");
        assertThat(taskPackage.getSourceUrls()).containsExactly("https://github.com/acme/rocket");
    }

    @Test
    void shouldBuildLightweightWebTaskPackageForDocsFamily() {
        SourceCandidate candidate = SourceCandidate.builder()
                .url("https://docs.example.com/api/reference")
                .sourceType("DOCS")
                .sourceFamilyKey("official")
                .providerKey("serpapi")
                .sourceUrls(List.of("https://docs.example.com/api/reference"))
                .build();

        CollectionTaskPackageBuilder builder = new CollectionTaskPackageBuilder(null);
        CollectionTaskPackage taskPackage = builder.build(
                41L,
                "collect_sources_docs",
                9L,
                "Acme AI",
                candidate,
                1
        );

        assertThat(taskPackage.getPrimaryTool()).isEqualTo("JINA_READER");
        assertThat(taskPackage.getRenderHint()).isEqualTo(WebPageRenderHint.LIGHTWEIGHT);
        assertThat(taskPackage.getExpectedBlockTypes())
                .contains("DOCUMENTATION_OUTLINE", "JSON_LD_METADATA");
    }

    @Test
    void shouldBuildRssTaskPackageForExplicitFeedUrl() {
        CollectionTaskPackageBuilder builder = new CollectionTaskPackageBuilder(new SearchPolicyResolver());
        SourceCandidate candidate = SourceCandidate.builder()
                .url("https://blog.example.com/feed.xml")
                .title("Acme feed")
                .sourceType("NEWS")
                .sourceFamilyKey("news")
                .providerKey("http")
                .sourceUrls(List.of("https://blog.example.com/feed.xml"))
                .build();

        CollectionTaskPackage taskPackage = builder.build(41L, "collect_sources_news", 9L, "Acme AI", candidate, 1);

        assertThat(taskPackage.getPrimaryTool()).isEqualTo("RSS");
        assertThat(taskPackage.getResourceLocator()).startsWith("rss://feed/");
    }

    @Test
    void shouldKeepNewsArticleUrlOnWebPathEvenWhenSourceTypeIsNews() {
        CollectionTaskPackageBuilder builder = new CollectionTaskPackageBuilder(new SearchPolicyResolver());
        SourceCandidate candidate = SourceCandidate.builder()
                .url("https://news.example.com/releases/acme-launches-agent")
                .title("Acme launches agent")
                .sourceType("NEWS")
                .sourceFamilyKey("news")
                .providerKey("serpapi")
                .sourceUrls(List.of("https://news.example.com/releases/acme-launches-agent"))
                .build();

        CollectionTaskPackage taskPackage = builder.build(41L, "collect_sources_news", 9L, "Acme AI", candidate, 2);

        assertThat(taskPackage.getPrimaryTool()).isEqualTo("JINA_READER");
        assertThat(taskPackage.getResourceLocator()).isEqualTo("https://news.example.com/releases/acme-launches-agent");
    }

    @Test
    void shouldNotTreatFeedbackOrSitemapXmlAsFeed() {
        CollectionTaskPackageBuilder builder = new CollectionTaskPackageBuilder(new SearchPolicyResolver());

        CollectionTaskPackage feedbackPackage = builder.build(41L, "collect_sources_news", 9L, "Acme AI",
                SourceCandidate.builder()
                        .url("https://example.com/blog/feedback/2026/agent-launch")
                        .sourceType("NEWS")
                        .sourceFamilyKey("news")
                        .sourceUrls(List.of("https://example.com/blog/feedback/2026/agent-launch"))
                        .build(),
                3);

        CollectionTaskPackage sitemapPackage = builder.build(41L, "collect_sources_news", 9L, "Acme AI",
                SourceCandidate.builder()
                        .url("https://example.com/sitemap.xml")
                        .sourceType("NEWS")
                        .sourceFamilyKey("news")
                        .sourceUrls(List.of("https://example.com/sitemap.xml"))
                        .build(),
                4);

        assertThat(feedbackPackage.getPrimaryTool()).isEqualTo("JINA_READER");
        assertThat(sitemapPackage.getPrimaryTool()).isEqualTo("JINA_READER");
    }
}
