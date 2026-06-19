package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.llm.LlmClient;
import cn.bugstack.competitoragent.llm.LlmException;
import cn.bugstack.competitoragent.source.SourceCandidate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompetitorDomainDiscoveryServiceTest {

    @Test
    void shouldBuildSourceCandidatesFromLlmJsonWhenUrlsAreVerified() {
        LlmClient llmClient = mock(LlmClient.class);
        DomainVerificationClient domainVerificationClient = mock(DomainVerificationClient.class);
        DomainDiscoveryProperties properties = enabledProperties();
        when(llmClient.chatForJson(any(), any(), any())).thenReturn("""
                {
                  "urls": [
                    {
                      "url": "https://www.bilibili.com",
                      "category": "official",
                      "confidence": 0.92,
                      "reason": "主站官网",
                      "sourceUrls": ["llm://domain-discovery/哔哩哔哩"]
                    },
                    {
                      "url": "https://open.bilibili.com/doc/",
                      "category": "open",
                      "confidence": 0.88,
                      "reason": "开放平台文档",
                      "sourceUrls": ["llm://domain-discovery/哔哩哔哩"]
                    }
                  ],
                  "sourceUrls": ["llm://domain-discovery/哔哩哔哩"]
                }
                """);
        when(domainVerificationClient.isReachable("https://www.bilibili.com")).thenReturn(true);
        when(domainVerificationClient.isReachable("https://open.bilibili.com/doc/")).thenReturn(true);

        CompetitorDomainDiscoveryService service = new CompetitorDomainDiscoveryService(
                properties,
                llmClient,
                domainVerificationClient,
                new ObjectMapper().findAndRegisterModules()
        );

        List<SourceCandidate> candidates = service.discover("哔哩哔哩");

        assertThat(candidates).hasSize(2);
        assertThat(candidates)
                .extracting(SourceCandidate::getSourceType)
                .containsExactly("OFFICIAL", "DOCS");
        assertThat(candidates)
                .extracting(SourceCandidate::getDiscoveryMethod)
                .containsOnly("DOMAIN_DISCOVERY_LLM");
        assertThat(candidates)
                .allSatisfy(candidate -> assertThat(candidate.getSourceUrls()).isNotEmpty());
    }

    @Test
    void shouldReturnEmptyListWhenLlmInvocationFails() {
        LlmClient llmClient = mock(LlmClient.class);
        DomainVerificationClient domainVerificationClient = mock(DomainVerificationClient.class);
        when(llmClient.chatForJson(any(), any(), any()))
                .thenThrow(new LlmException("llm unavailable"));

        CompetitorDomainDiscoveryService service = new CompetitorDomainDiscoveryService(
                enabledProperties(),
                llmClient,
                domainVerificationClient,
                new ObjectMapper().findAndRegisterModules()
        );

        assertThat(service.discover("哔哩哔哩")).isEmpty();
    }

    @Test
    void shouldSynthesizeSourceUrlsWhenLlmPayloadMissesThem() {
        LlmClient llmClient = mock(LlmClient.class);
        DomainVerificationClient domainVerificationClient = mock(DomainVerificationClient.class);
        DomainDiscoveryProperties properties = enabledProperties();
        when(llmClient.chatForJson(any(), any(), any())).thenReturn("""
                {
                  "urls": [
                    {
                      "url": "https://open.bilibili.com/doc/",
                      "category": "open",
                      "confidence": 0.88,
                      "reason": "开放平台文档"
                    }
                  ]
                }
                """);
        when(domainVerificationClient.isReachable("https://open.bilibili.com/doc/")).thenReturn(true);

        CompetitorDomainDiscoveryService service = new CompetitorDomainDiscoveryService(
                properties,
                llmClient,
                domainVerificationClient,
                new ObjectMapper().findAndRegisterModules()
        );

        List<SourceCandidate> candidates = service.discover("哔哩哔哩");

        assertThat(candidates).singleElement().satisfies(candidate -> {
            assertThat(candidate.getSourceUrls()).containsExactly("llm://domain-discovery/哔哩哔哩");
            assertThat(candidate.getQualitySignals()).contains("SOURCE_URLS_SYNTHESIZED");
        });
    }

    @Test
    void shouldSupplementStableDocPathWhenLlmOnlyReturnsOpenPlatformRootOrLegacyWiki() {
        LlmClient llmClient = mock(LlmClient.class);
        DomainVerificationClient domainVerificationClient = mock(DomainVerificationClient.class);
        DomainDiscoveryProperties properties = enabledProperties();
        when(llmClient.chatForJson(any(), any(), any())).thenReturn("""
                {
                  "urls": [
                    {
                      "url": "https://open.bilibili.com",
                      "category": "open",
                      "confidence": 0.92,
                      "reason": "开放平台"
                    },
                    {
                      "url": "https://open.bilibili.com/wiki/",
                      "category": "docs",
                      "confidence": 0.88,
                      "reason": "历史文档入口"
                    }
                  ],
                  "sourceUrls": ["llm://domain-discovery/哔哩哔哩"]
                }
                """);
        when(domainVerificationClient.isReachable("https://open.bilibili.com")).thenReturn(true);
        when(domainVerificationClient.isReachable("https://open.bilibili.com/wiki/")).thenReturn(true);
        when(domainVerificationClient.isReachable("https://open.bilibili.com/doc")).thenReturn(true);

        CompetitorDomainDiscoveryService service = new CompetitorDomainDiscoveryService(
                properties,
                llmClient,
                domainVerificationClient,
                new ObjectMapper().findAndRegisterModules()
        );

        List<SourceCandidate> candidates = service.discover("哔哩哔哩");

        assertThat(candidates)
                .extracting(SourceCandidate::getUrl)
                .contains("https://open.bilibili.com/doc");
        assertThat(candidates.stream()
                .filter(candidate -> "https://open.bilibili.com/doc".equals(candidate.getUrl()))
                .findFirst())
                .hasValueSatisfying(candidate -> {
                    assertThat(candidate.getSourceType()).isEqualTo("DOCS");
                    assertThat(candidate.getDiscoveryMethod()).isEqualTo("DOMAIN_DISCOVERY_LLM_SUPPLEMENT");
                    assertThat(candidate.getQualitySignals()).contains("DETERMINISTIC_DOC_PATH_SUPPLEMENT");
                    assertThat(candidate.getSourceUrls()).contains("llm://domain-discovery/哔哩哔哩");
                });
    }

    @Test
    void shouldSupplementSiblingOpenDocsWhenLlmOnlyReturnsOpenHomeDomain() {
        LlmClient llmClient = mock(LlmClient.class);
        DomainVerificationClient domainVerificationClient = mock(DomainVerificationClient.class);
        DomainDiscoveryProperties properties = enabledProperties();
        when(llmClient.chatForJson(any(), any(), any())).thenReturn("""
                {
                  "urls": [
                    {
                      "url": "https://openhome.acme.com",
                      "category": "open",
                      "confidence": 0.92,
                      "reason": "open platform home"
                    }
                  ],
                  "sourceUrls": ["llm://domain-discovery/Acme Video"]
                }
                """);
        when(domainVerificationClient.isReachable("https://openhome.acme.com")).thenReturn(true);
        when(domainVerificationClient.isReachable("https://open.acme.com/doc")).thenReturn(true);

        CompetitorDomainDiscoveryService service = new CompetitorDomainDiscoveryService(
                properties,
                llmClient,
                domainVerificationClient,
                new ObjectMapper().findAndRegisterModules()
        );

        List<SourceCandidate> candidates = service.discover("Acme Video");

        assertThat(candidates)
                .extracting(SourceCandidate::getUrl)
                .contains("https://open.acme.com/doc");
        assertThat(candidates.stream()
                .filter(candidate -> "https://open.acme.com/doc".equals(candidate.getUrl()))
                .findFirst())
                .hasValueSatisfying(candidate -> {
                    assertThat(candidate.getDiscoveryMethod()).isEqualTo("DOMAIN_DISCOVERY_LLM_SUPPLEMENT");
                    assertThat(candidate.getQualitySignals()).contains("DETERMINISTIC_DOC_PATH_SUPPLEMENT");
                    assertThat(candidate.getReason()).contains("sibling open platform");
                });
    }

    private DomainDiscoveryProperties enabledProperties() {
        DomainDiscoveryProperties properties = new DomainDiscoveryProperties();
        properties.setLlmEnabled(true);
        properties.setLlmTimeoutMillis(8000);
        properties.setMaxLlmCandidates(8);
        properties.setVerificationTimeoutMillis(3000);
        properties.setMaxRetries(1);
        return properties;
    }
}
