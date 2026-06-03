package cn.bugstack.competitoragent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptTemplateServiceTest {

    @Test
    void shouldLoadSearchQueriesFromExternalYamlTemplateFile() {
        PromptTemplateService service = new PromptTemplateService(new ObjectMapper());
        service.init();

        List<String> queries = service.buildSearchQueries("哔哩哔哩", "DOCS", "www.bilibili.com");

        assertTrue(queries.contains("哔哩哔哩 文档 API 开发指南"));
        assertTrue(queries.contains("哔哩哔哩 文档"));
        assertTrue(queries.contains("site:www.bilibili.com 哔哩哔哩 文档"));
    }

    @Test
    void shouldKeepPromptTemplateRenderingAvailable() {
        PromptTemplateService service = new PromptTemplateService(new ObjectMapper());
        service.init();

        String content = service.render("search-official", java.util.Map.of("competitorName", "哔哩哔哩"));

        assertEquals("哔哩哔哩 官方网站", content);
    }

    @Test
    void shouldIncludeZhihuQueryForReviewScope() {
        PromptTemplateService service = new PromptTemplateService(new ObjectMapper());
        service.init();

        List<String> queries = service.buildSearchQueries("哔哩哔哩", "REVIEW", null);

        assertTrue(queries.contains("哔哩哔哩 评测 评价 对比"));
        assertTrue(queries.contains("哔哩哔哩 怎么样 好不好用"));
    }

    @Test
    void shouldKeepZhihuFallbackTemplateAvailableWithoutYamlOverride() {
        PromptTemplateService service = new PromptTemplateService(new ObjectMapper());

        String query = service.buildSearchQuery(
                "search-review-zhihu",
                java.util.Map.of("competitorName", "哔哩哔哩", "domainHint", "")
        );

        assertEquals("site:zhihu.com 哔哩哔哩 评测 对比", query);
    }

    @Test
    void shouldKeepEnglishQueriesAsSupplementForPureEnglishCompetitor() {
        PromptTemplateService service = new PromptTemplateService(new ObjectMapper());
        service.init();

        List<String> queries = service.buildSearchQueries("Notion AI", "DOCS", "docs.notion.so");

        assertTrue(queries.contains("Notion AI documentation api reference"));
        assertTrue(queries.contains("site:docs.notion.so Notion AI"));
    }
}
