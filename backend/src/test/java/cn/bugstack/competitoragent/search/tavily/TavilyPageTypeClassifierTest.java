package cn.bugstack.competitoragent.search.tavily;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TavilyPageTypeClassifierTest {

    private final TavilyPageTypeClassifier classifier = new TavilyPageTypeClassifier();

    @Test
    void shouldClassifyRepresentativePageTypesByPriority() {
        assertThat(classifier.classify(
                "https://www.douyin.com/search/%E6%8A%96%E9%9F%B3%E6%8E%A8%E8%8D%90%E7%AE%97%E6%B3%95",
                "抖音推荐算法机制",
                "",
                Set.of()))
                .isEqualTo("SEARCH_PAGE");

        assertThat(classifier.classify(
                "https://www.bilibili.com/v/popular/all",
                "热门视频",
                repeat('视', 120),
                Set.of()))
                .isEqualTo("VIDEO_LIST");

        assertThat(classifier.classify(
                "https://www.bilibili.com/video/BV1234567890",
                "推荐算法分析",
                repeat('文', 600),
                Set.of()))
                .isEqualTo("VIDEO_PAGE");

        assertThat(classifier.classify(
                "https://www.reddit.com/r/analytics/comments/123456/thread",
                "thread",
                repeat('帖', 800),
                Set.of()))
                .isEqualTo("FORUM_THREAD");

        assertThat(classifier.classify(
                "https://open.douyin.com/docs/api",
                "API 文档",
                repeat('接', 600),
                Set.of("open.douyin.com")))
                .isEqualTo("OFFICIAL_DOC");
    }

    @Test
    void shouldRecognizePdfArticleAndGenericPage() {
        assertThat(classifier.classify(
                "https://pdf.dfcfw.com/pdf/H3_AP202404191630123.pdf",
                "东方财富证券研究报告.pdf",
                repeat('研', 3200),
                Set.of()))
                .isEqualTo("PDF");

        assertThat(classifier.classify(
                "https://www.example.com/blog/agent-analysis",
                "平台算法分析",
                repeat('析', 2200),
                Set.of()))
                .isEqualTo("ARTICLE");

        assertThat(classifier.classify(
                "https://www.example.com/",
                "站点首页",
                "欢迎访问",
                Set.of()))
                .isEqualTo("GENERIC_PAGE");
    }

    private String repeat(char ch, int count) {
        return String.valueOf(ch).repeat(Math.max(0, count));
    }
}
