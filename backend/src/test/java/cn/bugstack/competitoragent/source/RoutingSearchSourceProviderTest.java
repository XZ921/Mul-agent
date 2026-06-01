package cn.bugstack.competitoragent.source;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoutingSearchSourceProviderTest {

    @Test
    void shouldAggregateCandidatesFromAllProvidersWhenBrowserPreviewEnabled() {
        SearchProviderProperties properties = new SearchProviderProperties();
        properties.setBrowserPreviewEnabled(true);

        SerpApiSearchSourceProvider serpApiSearchSourceProvider = mock(SerpApiSearchSourceProvider.class);
        BrowserPreviewSearchSourceProvider browserPreviewProvider = mock(BrowserPreviewSearchSourceProvider.class);
        HttpSearchSourceProvider httpSearchSourceProvider = mock(HttpSearchSourceProvider.class);
        SourceCandidateRanker sourceCandidateRanker = mock(SourceCandidateRanker.class);

        List<SourceCandidate> serpCandidates = List.of(SourceCandidate.builder().url("https://docs.example.com").build());
        List<SourceCandidate> previewCandidates = List.of(SourceCandidate.builder().url("https://preview.example.com").build());
        List<SourceCandidate> httpCandidates = List.of(SourceCandidate.builder().url("https://api.example.com/docs").build());
        List<SourceCandidate> ranked = List.of(
                serpCandidates.get(0),
                httpCandidates.get(0),
                previewCandidates.get(0)
        );
        when(serpApiSearchSourceProvider.search("Notion AI", List.of("DOCS"))).thenReturn(serpCandidates);
        when(browserPreviewProvider.search("Notion AI", List.of("DOCS"))).thenReturn(previewCandidates);
        when(httpSearchSourceProvider.search("Notion AI", List.of("DOCS"))).thenReturn(httpCandidates);
        when(sourceCandidateRanker.rankAndDeduplicate(anyList())).thenReturn(ranked);

        RoutingSearchSourceProvider provider = new RoutingSearchSourceProvider(
                properties,
                serpApiSearchSourceProvider,
                browserPreviewProvider,
                httpSearchSourceProvider,
                sourceCandidateRanker
        );

        assertEquals(ranked, provider.search("Notion AI", List.of("DOCS")));
        verify(serpApiSearchSourceProvider).search("Notion AI", List.of("DOCS"));
        verify(browserPreviewProvider).search("Notion AI", List.of("DOCS"));
        verify(httpSearchSourceProvider).search("Notion AI", List.of("DOCS"));
        verify(sourceCandidateRanker).rankAndDeduplicate(anyList());
    }

    @Test
    void shouldAggregateAvailableCandidatesWhenSomeProvidersReturnEmpty() {
        SearchProviderProperties properties = new SearchProviderProperties();
        properties.setBrowserPreviewEnabled(true);

        SerpApiSearchSourceProvider serpApiSearchSourceProvider = mock(SerpApiSearchSourceProvider.class);
        BrowserPreviewSearchSourceProvider browserPreviewProvider = mock(BrowserPreviewSearchSourceProvider.class);
        HttpSearchSourceProvider httpSearchSourceProvider = mock(HttpSearchSourceProvider.class);
        SourceCandidateRanker sourceCandidateRanker = mock(SourceCandidateRanker.class);

        List<SourceCandidate> httpCandidates = List.of(SourceCandidate.builder().url("https://api.example.com/docs").build());
        when(serpApiSearchSourceProvider.search("Notion AI", List.of("DOCS"))).thenReturn(List.of());
        when(browserPreviewProvider.search("Notion AI", List.of("DOCS"))).thenReturn(List.of());
        when(httpSearchSourceProvider.search("Notion AI", List.of("DOCS"))).thenReturn(httpCandidates);
        when(sourceCandidateRanker.rankAndDeduplicate(anyList())).thenReturn(httpCandidates);

        RoutingSearchSourceProvider provider = new RoutingSearchSourceProvider(
                properties,
                serpApiSearchSourceProvider,
                browserPreviewProvider,
                httpSearchSourceProvider,
                sourceCandidateRanker
        );

        assertEquals(httpCandidates, provider.search("Notion AI", List.of("DOCS")));
        verify(sourceCandidateRanker).rankAndDeduplicate(anyList());
    }

    @Test
    void shouldSkipBrowserPreviewWhenDisabled() {
        SearchProviderProperties properties = new SearchProviderProperties();
        properties.setBrowserPreviewEnabled(false);

        SerpApiSearchSourceProvider serpApiSearchSourceProvider = mock(SerpApiSearchSourceProvider.class);
        BrowserPreviewSearchSourceProvider browserPreviewProvider = mock(BrowserPreviewSearchSourceProvider.class);
        HttpSearchSourceProvider httpSearchSourceProvider = mock(HttpSearchSourceProvider.class);
        SourceCandidateRanker sourceCandidateRanker = mock(SourceCandidateRanker.class);
        List<SourceCandidate> serpCandidates = List.of(SourceCandidate.builder().url("https://www.example.com").build());
        List<SourceCandidate> httpCandidates = List.of(SourceCandidate.builder().url("https://www.example.com/pricing").build());
        List<SourceCandidate> ranked = List.of(serpCandidates.get(0), httpCandidates.get(0));
        when(serpApiSearchSourceProvider.search("Notion AI", List.of("OFFICIAL"))).thenReturn(serpCandidates);
        when(httpSearchSourceProvider.search("Notion AI", List.of("OFFICIAL"))).thenReturn(httpCandidates);
        when(sourceCandidateRanker.rankAndDeduplicate(anyList())).thenReturn(ranked);

        RoutingSearchSourceProvider provider = new RoutingSearchSourceProvider(
                properties,
                serpApiSearchSourceProvider,
                browserPreviewProvider,
                httpSearchSourceProvider,
                sourceCandidateRanker
        );

        assertEquals(ranked, provider.search("Notion AI", List.of("OFFICIAL")));
        verify(browserPreviewProvider, never()).search("Notion AI", List.of("OFFICIAL"));
        verify(sourceCandidateRanker).rankAndDeduplicate(anyList());
    }
}
