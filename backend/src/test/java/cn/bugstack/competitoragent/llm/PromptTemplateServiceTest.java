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

        List<String> queries = service.buildSearchQueries("Notion AI", "DOCS", "docs.notion.so");

        assertTrue(queries.contains("Notion AI documentation"));
        assertTrue(queries.contains("Notion AI help center"));
        assertTrue(queries.contains("site:docs.notion.so Notion AI"));
    }

    @Test
    void shouldKeepPromptTemplateRenderingAvailable() {
        PromptTemplateService service = new PromptTemplateService(new ObjectMapper());
        service.init();

        String content = service.render("search-official", java.util.Map.of("competitorName", "Notion AI"));

        assertEquals("Notion AI official website", content);
    }

    @Test
    void shouldIncludeZhihuQueryForReviewScope() {
        PromptTemplateService service = new PromptTemplateService(new ObjectMapper());
        service.init();

        List<String> queries = service.buildSearchQueries("Notion AI", "REVIEW", null);

        assertTrue(queries.contains("site:zhihu.com Notion AI 评测 对比"));
    }

    @Test
    void shouldKeepZhihuFallbackTemplateAvailableWithoutYamlOverride() {
        PromptTemplateService service = new PromptTemplateService(new ObjectMapper());

        String query = service.buildSearchQuery(
                "search-review-zhihu",
                java.util.Map.of("competitorName", "Notion AI", "domainHint", "")
        );

        assertEquals("site:zhihu.com Notion AI 评测 对比", query);
    }
}
