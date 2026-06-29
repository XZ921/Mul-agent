package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.DirectHtmlReaderClient;
import cn.bugstack.competitoragent.source.PageContentExtractionResult;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCollectRequest;
import cn.bugstack.competitoragent.source.SourceCollector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CandidateVerifierTest {

    private final SourceCollector sourceCollector = mock(SourceCollector.class);
    private final CandidateVerifier candidateVerifier = new CandidateVerifier(sourceCollector);

    @Test
    void shouldVerifyDuplicateCandidateUrlOnlyOnce() {
        when(sourceCollector.collect("https://docs.example.com/guide", "Notion AI", "DOCS"))
                .thenReturn(SourceCollector.CollectedPage.builder()
                        .url("https://docs.example.com/guide")
                        .title("Guide")
                        .content("documentation api reference guide")
                        .snippet("documentation")
                        .competitorName("Notion AI")
                        .sourceType("DOCS")
                        .success(true)
                        .build());

        CandidateVerificationResult result = candidateVerifier.verify(
                "Notion AI",
                "DOCS",
                List.of(
                        SourceCandidate.builder()
                                .url("https://docs.example.com/guide")
                                .title("Guide")
                                .sourceType("DOCS")
                                .domain("docs.example.com")
                                .build(),
                        SourceCandidate.builder()
                                .url("https://docs.example.com/guide/")
                                .title("Guide Duplicate")
                                .sourceType("DOCS")
                                .domain("docs.example.com")
                                .build()
                )
        );

        assertEquals(1, result.getAttemptedTargets().size());
        assertEquals(1, result.getUpdatedCandidates().size());
        assertEquals(1, result.getVerifiedTargets().size());
        verify(sourceCollector, times(1)).collect("https://docs.example.com/guide", "Notion AI", "DOCS");
    }

    @Test
    void shouldFallbackToOriginalSourceUrlWhenCanonicalCandidateUrlHasNoUsablePage() {
        when(sourceCollector.collect("https://acme.ai/docs", "Acme AI", "DOCS"))
                .thenReturn(null);
        when(sourceCollector.collect("https://www.acme.ai/docs", "Acme AI", "DOCS"))
                .thenReturn(SourceCollector.CollectedPage.builder()
                        .url("https://www.acme.ai/docs")
                        .title("Acme Docs")
                        .content("documentation api reference guide")
                        .snippet("documentation")
                        .competitorName("Acme AI")
                        .sourceType("DOCS")
                        .success(true)
                        .build());

        CandidateVerificationResult result = candidateVerifier.verify(
                "Acme AI",
                "DOCS",
                List.of(SourceCandidate.builder()
                        .url("https://acme.ai/docs")
                        .title("Acme Docs")
                        .sourceType("DOCS")
                        .domain("acme.ai")
                        .sourceUrls(List.of("https://www.acme.ai/docs"))
                        .build())
        );

        assertEquals(1, result.getAttemptedTargets().size());
        assertEquals(1, result.getVerifiedTargets().size());
        assertTrue(Boolean.TRUE.equals(result.getUpdatedCandidates().get(0).getVerified()));
        verify(sourceCollector, times(3)).collect("https://acme.ai/docs", "Acme AI", "DOCS");
        verify(sourceCollector, times(1)).collect("https://www.acme.ai/docs", "Acme AI", "DOCS");
    }

    @Test
    void shouldRejectSearchEngineCertificationMediatorPageForOfficialCandidate() {
        when(sourceCollector.collect("https://aiqicha.baidu.com/feedback/official", "哔哩哔哩", "OFFICIAL"))
                .thenReturn(SourceCollector.CollectedPage.builder()
                        .url("https://aiqicha.baidu.com/feedback/official")
                        .title("哔哩哔哩 (゜-゜)つロ 干杯~-bilibili")
                        .content("官网认证是百度对网站展示官方标识的增值服务认证。产品介绍 官网认证可以帮助网民识别官方站点。")
                        .snippet("官网认证 产品介绍")
                        .competitorName("哔哩哔哩")
                        .sourceType("OFFICIAL")
                        .success(true)
                        .build());

        CandidateVerificationResult result = candidateVerifier.verify(
                "哔哩哔哩",
                "OFFICIAL",
                List.of(SourceCandidate.builder()
                        .url("https://aiqicha.baidu.com/feedback/official")
                        .title("哔哩哔哩 (゜-゜)つロ 干杯~-bilibili")
                        .sourceType("OFFICIAL")
                        .domain("aiqicha.baidu.com")
                        .build())
        );

        assertEquals(1, result.getAttemptedTargets().size());
        assertEquals(1, result.getUpdatedCandidates().size());
        assertEquals(0, result.getVerifiedTargets().size());
        assertTrue(Boolean.FALSE.equals(result.getUpdatedCandidates().get(0).getVerified()));
    }

    @Test
    void shouldUseDirectHtmlAsPositiveShortcutBeforeBrowserVerification() {
        SourceCollector sourceCollector = mock(SourceCollector.class);
        DirectHtmlReaderClient directHtmlReaderClient = mock(DirectHtmlReaderClient.class);
        SearchBrowserProperties properties = new SearchBrowserProperties();
        properties.setVerificationDirectFirstEnabled(true);
        properties.setVerificationDirectPositiveShortcutEnabled(true);
        when(directHtmlReaderClient.collect(any(SourceCollectRequest.class))).thenReturn(PageContentExtractionResult.builder()
                .success(true)
                .title("Open API Docs")
                .mainContent("Open API 文档 OAuth SDK guide reference")
                .qualitySignals(List.of("DIRECT_HTML_CONTENT_READY"))
                .qualityScore(0.86D)
                .build());

        CandidateVerifier verifier = new CandidateVerifier(
                sourceCollector,
                new SearchKeywordPolicy(),
                new CandidateOwnershipPolicy(),
                properties,
                directHtmlReaderProvider(directHtmlReaderClient)
        );

        CandidateVerificationResult result = verifier.verify("Acme", "DOCS", List.of(
                SourceCandidate.builder()
                        .url("https://docs.example.com/a")
                        .sourceType("DOCS")
                        .domain("docs.example.com")
                        .build()
        ));

        assertEquals(1, result.getVerifiedTargets().size());
        assertEquals(1, result.getDirectVerificationAttemptCount());
        assertEquals(1, result.getDirectVerificationUsableCount());
        assertEquals(1, result.getDirectVerificationShortcutCount());
        assertTrue(result.getVerifiedTargets().get(0).getCandidate().getQualitySignals()
                .contains("DIRECT_HTML_VERIFICATION_SHORTCUT"));
        verify(sourceCollector, never()).collect(anyString(), anyString(), anyString());
    }

    @Test
    void shouldFallThroughToCollectorWhenDirectHtmlDoesNotProveRelevance() {
        SourceCollector sourceCollector = mock(SourceCollector.class);
        DirectHtmlReaderClient directHtmlReaderClient = mock(DirectHtmlReaderClient.class);
        SearchBrowserProperties properties = new SearchBrowserProperties();
        properties.setVerificationDirectFirstEnabled(true);
        when(directHtmlReaderClient.collect(any(SourceCollectRequest.class))).thenReturn(PageContentExtractionResult.builder()
                .success(false)
                .failureKind("CONTENT_UNUSABLE")
                .errorMessage("direct html content too thin")
                .qualitySignals(List.of("DIRECT_HTML_CONTENT_TOO_THIN"))
                .build());
        when(sourceCollector.collect(anyString(), anyString(), anyString())).thenReturn(SourceCollector.CollectedPage.builder()
                .title("Open API Docs")
                .content("Open API 文档 OAuth SDK guide reference")
                .snippet("Open API 文档")
                .success(true)
                .build());

        CandidateVerifier verifier = new CandidateVerifier(
                sourceCollector,
                new SearchKeywordPolicy(),
                new CandidateOwnershipPolicy(),
                properties,
                directHtmlReaderProvider(directHtmlReaderClient)
        );

        CandidateVerificationResult result = verifier.verify("Acme", "DOCS", List.of(
                SourceCandidate.builder()
                        .url("https://docs.example.com/a")
                        .sourceType("DOCS")
                        .domain("docs.example.com")
                        .build()
        ));

        assertEquals(1, result.getVerifiedTargets().size());
        assertEquals(1, result.getDirectVerificationAttemptCount());
        assertEquals(0, result.getDirectVerificationShortcutCount());
        verify(sourceCollector, times(1)).collect(anyString(), anyString(), anyString());
    }

    @Test
    void shouldVerifyCandidatesConcurrentlyWithoutDroppingResults() {
        SourceCollector sourceCollector = mock(SourceCollector.class);
        SearchBrowserProperties properties = new SearchBrowserProperties();
        properties.setVerificationConcurrency(3);
        when(sourceCollector.collect(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            Thread.sleep(200);
            String url = invocation.getArgument(0);
            return SourceCollector.CollectedPage.builder()
                    .url(url)
                    .title("Open API Docs")
                    .content("Open API 文档 OAuth SDK guide reference")
                    .snippet("Open API 文档")
                    .success(true)
                    .build();
        });

        CandidateVerifier verifier = new CandidateVerifier(
                sourceCollector,
                new SearchKeywordPolicy(),
                new CandidateOwnershipPolicy(),
                properties
        );

        long startedAt = System.currentTimeMillis();
        CandidateVerificationResult result = verifier.verify("Acme", "DOCS", List.of(
                SourceCandidate.builder().url("https://docs.example.com/a").sourceType("DOCS").domain("docs.example.com").build(),
                SourceCandidate.builder().url("https://docs.example.com/b").sourceType("DOCS").domain("docs.example.com").build(),
                SourceCandidate.builder().url("https://docs.example.com/c").sourceType("DOCS").domain("docs.example.com").build()
        ));
        long elapsedMillis = System.currentTimeMillis() - startedAt;

        assertEquals(3, result.getAttemptedTargets().size());
        assertEquals(List.of(
                        "https://docs.example.com/a",
                        "https://docs.example.com/b",
                        "https://docs.example.com/c"
                ),
                result.getUpdatedCandidates().stream().map(SourceCandidate::getUrl).toList());
        assertEquals(3, result.getVerificationConcurrency());
        assertTrue(elapsedMillis < 550L, "并发验证耗时应低于串行三次 200ms 的总耗时");
    }

    @Test
    void shouldUseSerialVerificationWhenConcurrencyIsOne() {
        SourceCollector sourceCollector = mock(SourceCollector.class);
        SearchBrowserProperties properties = new SearchBrowserProperties();
        properties.setVerificationConcurrency(1);
        when(sourceCollector.collect(anyString(), anyString(), anyString())).thenReturn(SourceCollector.CollectedPage.builder()
                .title("Open API Docs")
                .content("Open API 文档 OAuth SDK guide reference")
                .snippet("Open API 文档")
                .success(true)
                .build());

        CandidateVerifier verifier = new CandidateVerifier(
                sourceCollector,
                new SearchKeywordPolicy(),
                new CandidateOwnershipPolicy(),
                properties
        );

        CandidateVerificationResult result = verifier.verify("Acme", "DOCS", List.of(
                SourceCandidate.builder()
                        .url("https://docs.example.com/a")
                        .sourceType("DOCS")
                        .domain("docs.example.com")
                        .build()
        ));

        assertEquals(1, result.getVerificationConcurrency());
        assertEquals(1, result.getAttemptedTargets().size());
    }

    @Test
    void shouldSkipNetworkVerificationForStrongTavilyFastLaneCandidate() {
        SourceCollector sourceCollector = mock(SourceCollector.class);
        CandidateVerifier verifier = new CandidateVerifier(sourceCollector);

        CandidateVerificationResult result = verifier.verify("抖音", "DOCS", List.of(
                SourceCandidate.builder()
                        .url("https://open.douyin.com/docs/api")
                        .sourceType("DOCS")
                        .providerKey("tavily")
                        .hasPrefetchedContent(true)
                        .fastLaneUsable(true)
                        .skipNetworkVerification(true)
                        .pageType("OFFICIAL_DOC")
                        .qualityTier("STRONG")
                        .sourceUrls(List.of("https://open.douyin.com/docs/api"))
                        .build()
        ));

        assertEquals(1, result.getVerifiedCandidateCount());
        assertTrue(Boolean.TRUE.equals(result.getUpdatedCandidates().get(0).getVerified()));
        assertTrue(result.getUpdatedCandidates().get(0).getQualitySignals().contains("TAVILY_VERIFICATION_SKIPPED"));
        assertEquals("TAVILY_FAST_LANE_GATE_VERIFIED", result.getUpdatedCandidates().get(0).getVerificationReason());
        verifyNoInteractions(sourceCollector);
    }

    @Test
    void shouldFallbackToCollectorForWeakTavilyFastLaneCandidate() {
        SourceCollector sourceCollector = mock(SourceCollector.class);
        when(sourceCollector.collect("https://open.douyin.com/search", "抖音", "DOCS"))
                .thenReturn(SourceCollector.CollectedPage.builder()
                        .url("https://open.douyin.com/search")
                        .title("Open Search")
                        .content("documentation api reference guide")
                        .snippet("documentation")
                        .competitorName("抖音")
                        .sourceType("DOCS")
                        .success(true)
                        .build());
        CandidateVerifier verifier = new CandidateVerifier(sourceCollector);

        CandidateVerificationResult result = verifier.verify("抖音", "DOCS", List.of(
                SourceCandidate.builder()
                        .url("https://open.douyin.com/search")
                        .sourceType("DOCS")
                        .providerKey("tavily")
                        .fastLaneUsable(false)
                        .skipNetworkVerification(true)
                        .pageType("SEARCH_PAGE")
                        .sourceUrls(List.of("https://open.douyin.com/search"))
                        .build()
        ));

        assertEquals(1, result.getVerifiedCandidateCount());
        verify(sourceCollector, times(1)).collect("https://open.douyin.com/search", "抖音", "DOCS");
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<DirectHtmlReaderClient> directHtmlReaderProvider(DirectHtmlReaderClient directHtmlReaderClient) {
        ObjectProvider<DirectHtmlReaderClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(directHtmlReaderClient);
        return provider;
    }
}
