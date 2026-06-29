package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.agent.collector.CollectorNodeConfig;
import cn.bugstack.competitoragent.collection.CollectionExecutionResult;
import cn.bugstack.competitoragent.collection.CollectionTaskPackage;
import cn.bugstack.competitoragent.collection.CollectionTaskPackageBuilder;
import cn.bugstack.competitoragent.collection.TavilyPrefetchedExecutor;
import cn.bugstack.competitoragent.source.SearchRequestPhase;
import cn.bugstack.competitoragent.search.tavily.TavilyPrefetchedContentRegistry;
import cn.bugstack.competitoragent.search.tavily.TavilySearchProfile;
import cn.bugstack.competitoragent.search.tavily.TavilySearchProfileResolver;
import cn.bugstack.competitoragent.search.tavily.TavilySearchProperties;
import cn.bugstack.competitoragent.source.SearchSourceRequest;
import cn.bugstack.competitoragent.source.SearchSourceProvider;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceCandidateRanker;
import cn.bugstack.competitoragent.source.SourceCollector;
import cn.bugstack.competitoragent.source.TavilyFastLaneProvider;
import cn.bugstack.competitoragent.source.TavilySearchClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SearchAndCollectionGoldenMasterTest {

    private final SourceCollector sourceCollector = mock(SourceCollector.class);
    private final CandidateVerifier candidateVerifier = new CandidateVerifier(sourceCollector);

    @Test
    void shouldRejectOfficialMarketingPageEvenWhenDomainAuthorityIsHigh() {
        when(sourceCollector.collect("https://www.aliyun.com/product/ecs", "aliyun", "OFFICIAL"))
                .thenReturn(SourceCollector.CollectedPage.builder()
                        .url("https://www.aliyun.com/product/ecs")
                        .title("Aliyun ECS Limited-Time Offer")
                        .content("Buy now discount limited time offer for new customers")
                        .snippet("discount offer")
                        .competitorName("aliyun")
                        .sourceType("OFFICIAL")
                        .success(true)
                        .build());

        CandidateVerificationResult result = candidateVerifier.verify(
                "aliyun",
                "OFFICIAL",
                List.of(SourceCandidate.builder()
                        .url("https://www.aliyun.com/product/ecs")
                        .title("Aliyun ECS")
                        .sourceType("OFFICIAL")
                        .domain("www.aliyun.com")
                        .relevanceScore(0.95)
                        .freshnessScore(0.80)
                        .qualityScore(0.99)
                        .build())
        );

        assertEquals(0, result.getVerifiedTargets().size());
        assertEquals("DISCARDED", result.getUpdatedCandidates().get(0).getSelectionStage());
        assertFalse(Boolean.TRUE.equals(result.getUpdatedCandidates().get(0).getVerified()));
    }

    @Test
    void shouldAcceptHighValuePricingDocument() {
        when(sourceCollector.collect("https://cloud.tencent.com/document/product/1234/5678", "tencent-cloud", "PRICING"))
                .thenReturn(SourceCollector.CollectedPage.builder()
                        .url("https://cloud.tencent.com/document/product/1234/5678")
                        .title("Cloud Database Pricing")
                        .content("This document explains billing mode, package differences, pricing, and charging rules.")
                        .snippet("Billing mode and pricing explanation")
                        .competitorName("tencent-cloud")
                        .sourceType("PRICING")
                        .success(true)
                        .build());

        CandidateVerificationResult result = candidateVerifier.verify(
                "tencent-cloud",
                "PRICING",
                List.of(SourceCandidate.builder()
                        .url("https://cloud.tencent.com/document/product/1234/5678")
                        .title("Cloud Database Pricing")
                        .sourceType("PRICING")
                        .domain("cloud.tencent.com")
                        .build())
        );

        assertEquals(1, result.getVerifiedTargets().size());
        assertTrue(Boolean.TRUE.equals(result.getUpdatedCandidates().get(0).getVerified()));
        assertFalse(result.getUpdatedCandidates().get(0).getMatchedSignals().isEmpty());
    }

    @Test
    void shouldAcceptDocsSignals() {
        when(sourceCollector.collect("https://cloud.tencent.com/document/product/1000/2000", "tencent-cloud", "DOCS"))
                .thenReturn(SourceCollector.CollectedPage.builder()
                        .url("https://cloud.tencent.com/document/product/1000/2000")
                        .title("Object Storage Development Guide")
                        .content("This page provides a development guide, API reference, and integration explanation.")
                        .snippet("Development guide and API reference")
                        .competitorName("tencent-cloud")
                        .sourceType("DOCS")
                        .success(true)
                        .build());

        CandidateVerificationResult result = candidateVerifier.verify(
                "tencent-cloud",
                "DOCS",
                List.of(SourceCandidate.builder()
                        .url("https://cloud.tencent.com/document/product/1000/2000")
                        .title("Object Storage Development Guide")
                        .sourceType("DOCS")
                        .domain("cloud.tencent.com")
                        .build())
        );

        assertEquals(1, result.getVerifiedTargets().size());
    }

    @Test
    void shouldCarryStrongTavilyDocFromSearchToCollectionWithoutExtraFetch() {
        TavilySearchProperties properties = tavilyProperties();
        TavilyPrefetchedContentRegistry registry = new TavilyPrefetchedContentRegistry();
        FixtureTavilySearchClient client = new FixtureTavilySearchClient();
        client.responses = List.of(TavilySearchClient.TavilySearchResponse.builder()
                .query("douyin open platform api docs")
                .requestId("req-golden-docs-1")
                .results(List.of(TavilySearchClient.TavilySearchResult.builder()
                        .title("Platform Introduction - Douyin Open Platform API")
                        .url("https://open.douyin.com/platform/resource/docs/accession-guide/platform-introduction")
                        .content("Official platform introduction for developers.")
                        .rawContent(
                                "Official platform introduction for developers, permissions, onboarding, compliance, support channels, API access, capability scope, callback behavior, and evidence traceability. "
                                        + "Official platform introduction for developers, permissions, onboarding, compliance, support channels, API access, capability scope, callback behavior, and evidence traceability. "
                                        + "Official platform introduction for developers, permissions, onboarding, compliance, support channels, API access, capability scope, callback behavior, and evidence traceability. "
                                        + "Official platform introduction for developers, permissions, onboarding, compliance, support channels, API access, capability scope, callback behavior, and evidence traceability. "
                                        + "Official platform introduction for developers, permissions, onboarding, compliance, support channels, API access, capability scope, callback behavior, and evidence traceability. "
                                        + "Official platform introduction for developers, permissions, onboarding, compliance, support channels, API access, capability scope, callback behavior, and evidence traceability. "
                                        + "Official platform introduction for developers, permissions, onboarding, compliance, support channels, API access, capability scope, callback behavior, and evidence traceability. "
                                        + "Official platform introduction for developers, permissions, onboarding, compliance, support channels, API access, capability scope, callback behavior, and evidence traceability. "
                                        + "Official platform introduction for developers, permissions, onboarding, compliance, support channels, API access, capability scope, callback behavior, and evidence traceability. "
                                        + "Official platform introduction for developers, permissions, onboarding, compliance, support channels, API access, capability scope, callback behavior, and evidence traceability. "
                                        + "Official platform introduction for developers, permissions, onboarding, compliance, support channels, API access, capability scope, callback behavior, and evidence traceability. "
                                        + "Official platform introduction for developers, permissions, onboarding, compliance, support channels, API access, capability scope, callback behavior, and evidence traceability. "
                                        + "Official platform introduction for developers, permissions, onboarding, compliance, support channels, API access, capability scope, callback behavior, and evidence traceability. "
                                        + "Official platform introduction for developers, permissions, onboarding, compliance, support channels, API access, capability scope, callback behavior, and evidence traceability."
                        )
                        .score(0.78D)
                        .build()))
                .build());

        TavilyFastLaneProvider provider = new TavilyFastLaneProvider(
                properties,
                client,
                new TavilySearchProfileResolver(properties),
                registry,
                new ObjectMapper()
        );

        List<SourceCandidate> candidates = provider.search(SearchSourceRequest.builder()
                .competitorName("douyin")
                .requestedScopes(List.of("DOCS"))
                .searchQueries(List.of("douyin open platform api docs"))
                .preferredDomains(List.of("open.douyin.com"))
                .includeDomains(List.of("open.douyin.com"))
                .preferredProviderKey("tavily")
                .build());

        CandidateVerificationResult verification = candidateVerifier.verify("douyin", "DOCS", candidates);
        CollectionTaskPackage taskPackage = new CollectionTaskPackageBuilder().build(
                1L,
                "collect_sources",
                1L,
                "douyin",
                verification.getUpdatedCandidates().get(0),
                0
        );
        CollectionExecutionResult collectionResult = new TavilyPrefetchedExecutor(registry).execute(taskPackage);

        assertEquals(1, verification.getVerifiedTargets().size());
        assertTrue(Boolean.TRUE.equals(verification.getUpdatedCandidates().get(0).getVerified()));
        assertEquals("TAVILY_FAST_LANE_GATE_VERIFIED",
                verification.getUpdatedCandidates().get(0).getVerificationReason());
        assertTrue(collectionResult.isSuccess());
        assertTrue(collectionResult.getSourceUrls()
                .contains("https://open.douyin.com/platform/resource/docs/accession-guide/platform-introduction"));
        assertTrue(collectionResult.getContent().contains("Official platform introduction"));
        verifyNoInteractions(sourceCollector);
    }

    @Test
    void shouldUseBootstrapCandidatesBeforeSupplementWhenWeakPlannedDocsEntryIsProvided() {
        SearchSourceProvider provider = mock(SearchSourceProvider.class);
        when(provider.search(argThat(request ->
                request != null
                        && request.getRequestPhase() == SearchRequestPhase.BOOTSTRAP
                        && "tavily".equalsIgnoreCase(request.getPreferredProviderKey()))))
                .thenReturn(List.of(SourceCandidate.builder()
                        .url("https://open.douyin.com/platform/resource/docs/accession-guide/platform-introduction")
                        .title("平台简介")
                        .sourceType("DOCS")
                        .providerKey("tavily")
                        .discoveryMethod("TAVILY_PHASE1_BOOTSTRAP")
                        .sourceUrls(List.of("https://open.douyin.com/", "https://open.douyin.com/platform/resource/docs/accession-guide/platform-introduction"))
                        .relevanceScore(0.95D)
                        .freshnessScore(0.72D)
                        .qualityScore(0.92D)
                        .build()));

        SearchExecutionCoordinator coordinator = new SearchExecutionCoordinator(
                mock(CandidateVerifier.class),
                mock(BrowserSearchRuntimeService.class),
                provider,
                new SourceCandidateRanker(),
                new CollectionTargetSelector(),
                new SearchPolicyResolver()
        );

        SearchExecutionResult result = coordinator.execute(CollectorNodeConfig.builder()
                .competitorName("抖音")
                .sourceType("DOCS")
                .preferredDomains(List.of("open.douyin.com"))
                .includeDomains(List.of("open.douyin.com"))
                .sourceCandidates(List.of(SourceCandidate.builder()
                        .url("https://open.douyin.com/")
                        .title("抖音开放平台")
                        .sourceType("DOCS")
                        .relevanceScore(0.90D)
                        .freshnessScore(0.60D)
                        .qualityScore(0.88D)
                        .build()))
                .verifyCandidates(Boolean.FALSE)
                .maxSearchResults(2)
                .build());

        assertTrue(result.getAuditSnapshot().getTavilyFastLaneAudit().getQueryOrigins().contains("BOOTSTRAP"));
        verify(provider, never()).search(argThat(request ->
                request != null && request.getRequestPhase() == SearchRequestPhase.SUPPLEMENT));
    }

    private TavilySearchProperties tavilyProperties() {
        TavilySearchProperties properties = new TavilySearchProperties();
        properties.setEnabled(true);
        properties.setApiKey("tavily-test-key");
        properties.setEndpoint("https://api.tavily.com/search");
        properties.setSearchDepth("advanced");
        properties.setIncludeRawContent(true);
        properties.setMaxResults(5);
        properties.setTimeoutSeconds(10);
        properties.setMaxRetries(1);
        properties.setMinRawContentChars(500);
        properties.setMinTavilyScore(0.45D);
        return properties;
    }

    private static final class FixtureTavilySearchClient extends TavilySearchClient {

        private List<TavilySearchResponse> responses = List.of();
        private final List<TavilySearchProfile> executedProfiles = new ArrayList<>();
        private int index = 0;

        private FixtureTavilySearchClient() {
            super(new TavilySearchProperties(), new ObjectMapper(), null);
        }

        @Override
        public TavilySearchResponse search(TavilySearchProfile profile) {
            executedProfiles.add(profile);
            if (index >= responses.size()) {
                return TavilySearchResponse.builder()
                        .query(profile == null ? null : profile.getQuery())
                        .results(List.of())
                        .failureReason("fixture response exhausted")
                        .build();
            }
            return responses.get(index++);
        }
    }
}
