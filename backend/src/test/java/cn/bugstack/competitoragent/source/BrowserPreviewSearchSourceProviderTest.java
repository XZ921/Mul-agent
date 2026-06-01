package cn.bugstack.competitoragent.source;

import cn.bugstack.competitoragent.agent.collector.CollectorNodeConfig;
import cn.bugstack.competitoragent.config.CollectorProperties;
import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.search.BrowserSearchRuntimeResult;
import cn.bugstack.competitoragent.search.BrowserSearchRuntimeService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BrowserPreviewSearchSourceProviderTest {

    private final BrowserSearchRuntimeService browserSearchRuntimeService = mock(BrowserSearchRuntimeService.class);
    private final PromptTemplateService promptTemplateService = new PromptTemplateService(new com.fasterxml.jackson.databind.ObjectMapper());

    @Test
    void shouldConvertRuntimeBrowserSearchResultsToPlannedPreviewCandidates() {
        SearchProviderProperties properties = new SearchProviderProperties();
        properties.setBrowserPreviewEnabled(true);
        properties.setResultsPerScope(3);
        CollectorProperties collectorProperties = new CollectorProperties();
        BrowserPreviewSearchSourceProvider provider =
                new BrowserPreviewSearchSourceProvider(
                        browserSearchRuntimeService,
                        properties,
                        promptTemplateService,
                        collectorProperties
                );

        when(browserSearchRuntimeService.search(any())).thenReturn(BrowserSearchRuntimeResult.builder()
                .candidates(List.of(SourceCandidate.builder()
                        .url("https://docs.notion.so/reference")
                        .title("Notion Docs")
                        .sourceType("DOCS")
                        .discoveryMethod("BROWSER")
                        .reason("浏览器搜索命中文档相关入口")
                        .domain("docs.notion.so")
                        .searchQuery("Notion AI documentation")
                        .searchEngine("bing")
                        .resultRank(1)
                        .relevanceScore(0.95)
                        .freshnessScore(0.60)
                        .qualityScore(0.92)
                        .selectionStage("BROWSER")
                        .selectionReason("运行期通过浏览器搜索结果页增补")
                        .build()))
                .executedQueries(List.of("Notion AI documentation"))
                .searchEngine("bing")
                .summary("preview search returned one candidate")
                .fallbackSuggested(false)
                .build());

        List<SourceCandidate> candidates = provider.search("Notion AI", List.of("DOCS"));

        assertEquals(1, candidates.size());
        SourceCandidate candidate = candidates.get(0);
        assertEquals("BROWSER_PREVIEW", candidate.getDiscoveryMethod());
        assertEquals("PLANNED", candidate.getSelectionStage());
        assertEquals("规划期通过浏览器预览补源生成候选来源", candidate.getSelectionReason());
        assertTrue(candidate.getReason().contains("浏览器预览补源"));

        ArgumentCaptor<CollectorNodeConfig> configCaptor = ArgumentCaptor.forClass(CollectorNodeConfig.class);
        verify(browserSearchRuntimeService).search(configCaptor.capture());
        CollectorNodeConfig previewConfig = configCaptor.getValue();
        assertEquals("Notion AI", previewConfig.getCompetitorName());
        assertEquals("DOCS", previewConfig.getSourceType());
        assertEquals("BROWSER_ONLY", previewConfig.getSearchMode());
        assertTrue(Boolean.TRUE.equals(previewConfig.getBrowserSearchEnabled()));
        assertTrue(Boolean.FALSE.equals(previewConfig.getVerifyResultPage()));
        assertEquals(3, previewConfig.getMaxSearchResults());
        assertEquals(1, previewConfig.getSearchRuntimePolicy().getMaxRetries());
        assertTrue(Boolean.FALSE.equals(previewConfig.getSearchRuntimePolicy().getVerifyResultPage()));
        assertEquals(30000, previewConfig.getSearchRuntimePolicy().getPageTimeoutMillis());
        assertEquals(collectorProperties.getUserAgent(), previewConfig.getSearchRuntimePolicy().getUserAgents().get(0));
    }

    @Test
    void shouldReturnEmptyWhenBrowserPreviewIsDisabled() {
        SearchProviderProperties properties = new SearchProviderProperties();
        properties.setBrowserPreviewEnabled(false);
        CollectorProperties collectorProperties = new CollectorProperties();
        BrowserPreviewSearchSourceProvider provider =
                new BrowserPreviewSearchSourceProvider(
                        browserSearchRuntimeService,
                        properties,
                        promptTemplateService,
                        collectorProperties
                );

        assertTrue(provider.search("Notion AI", List.of("DOCS")).isEmpty());
    }
}
