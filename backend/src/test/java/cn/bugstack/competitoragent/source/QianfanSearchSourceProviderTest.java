package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.search.QianfanSearchProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QianfanSearchSourceProviderTest {

    @Test
    void shouldParseQianfanSearchResults() throws Exception {
        QianfanSearchSourceProvider provider = new QianfanSearchSourceProvider(
                qianfanSearchProperties("https://qianfan.baidubce.com/v2/ai_search/web_search"),
                searchProviderProperties(),
                promptTemplateService(),
                new ObjectMapper()
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
                new ObjectMapper()
        );

        assertTrue(provider.search("哔哩哔哩", List.of("DOCS")).isEmpty());
    }

    @Test
    void shouldExposeStableDescriptorMetadata() {
        QianfanSearchSourceProvider provider = new QianfanSearchSourceProvider(
                qianfanSearchProperties("https://qianfan.baidubce.com/v2/ai_search/web_search"),
                searchProviderProperties(),
                promptTemplateService(),
                new ObjectMapper()
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
