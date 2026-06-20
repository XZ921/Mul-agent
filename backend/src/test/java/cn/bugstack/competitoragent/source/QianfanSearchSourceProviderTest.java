package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.search.QianfanSearchProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QianfanSearchSourceProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldBuildOfficialBaiduSearchRequestBody() throws Exception {
        QianfanSearchSourceProvider provider = new QianfanSearchSourceProvider(
                qianfanSearchProperties("https://qianfan.baidubce.com/v2/ai_search/web_search"),
                searchProviderProperties(),
                promptTemplateService(),
                objectMapper
        );
        Method buildBodyMethod = QianfanSearchSourceProvider.class.getDeclaredMethod("buildRequestBody", String.class);
        buildBodyMethod.setAccessible(true);

        String body = (String) buildBodyMethod.invoke(provider, "哔哩哔哩 文档 API");
        JsonNode root = objectMapper.readTree(body);

        assertEquals("哔哩哔哩 文档 API", root.path("messages").get(0).path("content").asText());
        assertEquals("user", root.path("messages").get(0).path("role").asText());
        assertEquals("baidu_search_v2", root.path("search_source").asText());
        assertEquals("web", root.path("resource_type_filter").get(0).path("type").asText());
        assertEquals(3, root.path("resource_type_filter").get(0).path("top_k").asInt());
    }

    @Test
    void shouldNormalizeBearerAuthorizationHeader() throws Exception {
        QianfanSearchSourceProvider provider = new QianfanSearchSourceProvider(
                qianfanSearchProperties("https://qianfan.baidubce.com/v2/ai_search/web_search"),
                searchProviderProperties(),
                promptTemplateService(),
                objectMapper
        );
        Method authorizationMethod = QianfanSearchSourceProvider.class.getDeclaredMethod("resolveAuthorizationHeader");
        authorizationMethod.setAccessible(true);

        assertEquals("Bearer bce-v3/test-key", authorizationMethod.invoke(provider));

        QianfanSearchProperties properties = qianfanSearchProperties("https://qianfan.baidubce.com/v2/ai_search/web_search");
        properties.setApiKey("Bearer bce-v3/test-key");
        QianfanSearchSourceProvider providerWithBearer = new QianfanSearchSourceProvider(
                properties,
                searchProviderProperties(),
                promptTemplateService(),
                objectMapper
        );
        assertEquals("Bearer bce-v3/test-key", authorizationMethod.invoke(providerWithBearer));
    }

    @Test
    void shouldParseOfficialQianfanBaiduSearchResults() throws Exception {
        QianfanSearchSourceProvider provider = new QianfanSearchSourceProvider(
                qianfanSearchProperties("https://qianfan.baidubce.com/v2/ai_search/web_search"),
                searchProviderProperties(),
                promptTemplateService(),
                objectMapper
        );
        Method parseMethod = QianfanSearchSourceProvider.class.getDeclaredMethod(
                "parseCandidates",
                String.class,
                String.class,
                String.class,
                String.class
        );
        parseMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<SourceCandidate> candidates = (List<SourceCandidate>) parseMethod.invoke(
                provider,
                """
                {
                  "search_results": [
                    {
                      "title": "哔哩哔哩开放平台文档",
                      "url": "https://openhome.bilibili.com/doc",
                      "summary": "哔哩哔哩开放平台提供账号授权、内容管理、数据服务等开发文档。",
                      "publish_time": "2026-06-15"
                    }
                  ]
                }
                """,
                "哔哩哔哩",
                "DOCS",
                "哔哩哔哩 文档 API"
        );

        assertEquals(1, candidates.size());
        SourceCandidate candidate = candidates.get(0);
        assertEquals("QIANFAN_SEARCH", candidate.getDiscoveryMethod());
        assertEquals("https://openhome.bilibili.com/doc", candidate.getUrl());
        assertEquals("openhome.bilibili.com", candidate.getDomain());
        assertEquals("2026-06-15", candidate.getPublishedAt());
        assertTrue(candidate.getReason().contains("开放平台提供账号授权"));
    }

    @Test
    void shouldParseQianfanReferencesResultsReturnedByLiveApi() throws Exception {
        QianfanSearchSourceProvider provider = new QianfanSearchSourceProvider(
                qianfanSearchProperties("https://qianfan.baidubce.com/v2/ai_search/web_search"),
                searchProviderProperties(),
                promptTemplateService(),
                objectMapper
        );
        Method parseMethod = QianfanSearchSourceProvider.class.getDeclaredMethod(
                "parseCandidates",
                String.class,
                String.class,
                String.class,
                String.class
        );
        parseMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<SourceCandidate> candidates = (List<SourceCandidate>) parseMethod.invoke(
                provider,
                """
                {
                  "request_id": "194b76aa-67db-4b91-a8dd-e3bb3b533e54",
                  "references": [
                    {
                      "id": 1,
                      "url": "https://openhome.bilibili.com/",
                      "title": "开放平台",
                      "date": "2026-06-07 20:08:40",
                      "content": "立即加入 技术文档 开放平台关于春节时间安排通知 产品服务 业务开放"
                    }
                  ]
                }
                """,
                "哔哩哔哩",
                "DOCS",
                "哔哩哔哩 开放平台 文档 API"
        );

        assertEquals(1, candidates.size());
        SourceCandidate candidate = candidates.get(0);
        assertEquals("https://openhome.bilibili.com/", candidate.getUrl());
        assertEquals("2026-06-07 20:08:40", candidate.getPublishedAt());
        assertTrue(candidate.getReason().contains("技术文档"));
    }

    @Test
    void shouldParseQianfanSearchResults() throws Exception {
        QianfanSearchSourceProvider provider = new QianfanSearchSourceProvider(
                qianfanSearchProperties("https://qianfan.baidubce.com/v2/ai_search/web_search"),
                searchProviderProperties(),
                promptTemplateService(),
                objectMapper
        );
        Method parseMethod = QianfanSearchSourceProvider.class.getDeclaredMethod(
                "parseCandidates",
                String.class,
                String.class,
                String.class,
                String.class
        );
        parseMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<SourceCandidate> candidates = (List<SourceCandidate>) parseMethod.invoke(
                provider,
                """
                {
                  "data": {
                    "results": [
                      {
                        "title": "哔哩哔哩开发文档",
                        "url": "https://www.bilibili.com/read/cv-doc",
                        "content": "这里是哔哩哔哩开放平台和开发文档介绍",
                        "publish_time": "2026-06-01"
                      }
                    ]
                  }
                }
                """,
                "哔哩哔哩",
                "DOCS",
                "哔哩哔哩 文档 API 开发指南"
        );

        assertEquals(1, candidates.size());
        SourceCandidate candidate = candidates.get(0);
        assertEquals("QIANFAN_SEARCH", candidate.getDiscoveryMethod());
        assertEquals("baidu", candidate.getSearchEngine());
        assertEquals("https://www.bilibili.com/read/cv-doc", candidate.getUrl());
        assertEquals("www.bilibili.com", candidate.getDomain());
        assertEquals("2026-06-01", candidate.getPublishedAt());
        assertTrue(candidate.getReason().contains("千帆搜索命中文档入口"));
    }

    @Test
    void shouldReturnEmptyWhenApiKeyMissing() {
        QianfanSearchProperties properties = qianfanSearchProperties("https://qianfan.baidubce.com/v2/ai_search/web_search");
        properties.setApiKey("");

        QianfanSearchSourceProvider provider = new QianfanSearchSourceProvider(
                properties,
                searchProviderProperties(),
                promptTemplateService(),
                objectMapper
        );

        assertTrue(provider.search("哔哩哔哩", List.of("DOCS")).isEmpty());
    }

    @Test
    void shouldExposeStableDescriptorMetadata() {
        QianfanSearchSourceProvider provider = new QianfanSearchSourceProvider(
                qianfanSearchProperties("https://qianfan.baidubce.com/v2/ai_search/web_search"),
                searchProviderProperties(),
                promptTemplateService(),
                objectMapper
        );

        SearchSourceProviderDescriptor descriptor = provider.descriptor();

        assertEquals("qianfan", descriptor.getProviderKey());
        assertEquals("千帆搜索", descriptor.getDisplayName());
        assertTrue(descriptor.getCapabilities().contains("CHINESE_RESULTS"));
        assertTrue(descriptor.isEnabled(new SearchProviderProperties()));
        assertTrue(descriptor.isFailOpen(new SearchProviderProperties()));
    }

    private PromptTemplateService promptTemplateService() {
        PromptTemplateService service = new PromptTemplateService(new ObjectMapper());
        service.init();
        return service;
    }

    private SearchProviderProperties searchProviderProperties() {
        SearchProviderProperties properties = new SearchProviderProperties();
        properties.setResultsPerScope(3);
        properties.setMaxRetries(0);
        return properties;
    }

    private QianfanSearchProperties qianfanSearchProperties(String endpoint) {
        QianfanSearchProperties properties = new QianfanSearchProperties();
        properties.setApiKey("bce-v3/test-key");
        properties.setDefaultEngine("baidu");
        properties.setEndpoint(endpoint);
        return properties;
    }
}
