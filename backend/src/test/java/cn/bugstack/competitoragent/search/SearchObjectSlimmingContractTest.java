package cn.bugstack.competitoragent.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchObjectSlimmingContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void shouldBuildSmallSharedProjectionWithoutLargeCollectorPayload() throws Exception {
        String rawOutput = """
                {
                  "sourceUrls": ["https://docs.example.com/reference"],
                  "issueFlags": ["SEARCH_AUDIT_READY"],
                  "results": [
                    {
                      "url": "https://docs.example.com/reference",
                      "title": "Reference",
                      "content": "large-body-large-body-large-body"
                    }
                  ],
                  "selectedTargets": [
                    {
                      "url": "https://docs.example.com/reference",
                      "collectedPage": {
                        "content": "large-body-large-body-large-body"
                      }
                    }
                  ],
                  "searchExecutionTrace": {
                    "fallbackDecision": "PRIMARY_THEN_AUXILIARY",
                    "degradationReason": "AUXILIARY_NOT_USED"
                  },
                  "searchAudit": {
                    "sourceUrls": ["https://docs.example.com/reference"]
                  }
                }
                """;

        assertThat(SearchSharedProjection.supportsCollectorOutput(objectMapper, rawOutput)).isTrue();

        SearchSharedProjection projection = SearchSharedProjection.fromCollectorOutput(objectMapper, rawOutput);
        String serialized = objectMapper.writeValueAsString(projection);

        assertThat(projection.getSourceUrls()).containsExactly("https://docs.example.com/reference");
        assertThat(projection.getSelectedUrls()).containsExactly("https://docs.example.com/reference");
        assertThat(serialized).doesNotContain("large-body");
        assertThat(serialized.length()).isLessThan(600);
    }

    @Test
    void shouldNotTreatExtractorOutputAsCollectorProjection() {
        String extractorLikeOutput = """
                {
                  "contractVersion": "1.0",
                  "sourceUrls": ["https://docs.example.com/pricing"],
                  "issueFlags": ["TRACEABLE"],
                  "drafts": [
                    {
                      "competitorName": "Acme",
                      "summary": "workspace pricing"
                    }
                  ],
                  "downstreamEvidenceViews": [
                    {
                      "evidenceId": "E001",
                      "title": "Pricing Docs"
                    }
                  ]
                }
                """;

        assertThat(SearchSharedProjection.supportsCollectorOutput(objectMapper, extractorLikeOutput)).isFalse();
    }

    @Test
    void shouldPreserveSelectedTargetSearchFirstAuditFieldsInSharedProjection() {
        String rawOutput = """
                {
                  "sourceUrls": ["https://developer.open-douyin.com/docs/resource/zh-CN/mini-app/develop/server/open-capacity"],
                  "selectedTargets": [
                    {
                      "url": "https://developer.open-douyin.com/docs/resource/zh-CN/mini-app/develop/server/open-capacity",
                      "title": "开放能力文档",
                      "discoveryMethod": "TAVILY_PHASE1_BOOTSTRAP",
                      "tavilyQueryMode": "TRUSTED_WEB_EXPANSION",
                      "qualityTier": "STRONG",
                      "fastLaneUsable": true,
                      "prefetchedRawContentLength": 19555,
                      "skipNetworkVerification": true
                    }
                  ]
                }
                """;

        SearchSharedProjection projection = SearchSharedProjection.fromCollectorOutput(objectMapper, rawOutput);

        assertThat(projection.getSelectedTargets()).singleElement().satisfies(target -> {
            assertThat(target.getDiscoveryMethod()).isEqualTo("TAVILY_PHASE1_BOOTSTRAP");
            assertThat(target.getTavilyQueryMode()).isEqualTo("TRUSTED_WEB_EXPANSION");
            assertThat(target.getQualityTier()).isEqualTo("STRONG");
            assertThat(target.getFastLaneUsable()).isTrue();
            assertThat(target.getPrefetchedRawContentLength()).isEqualTo(19555);
            assertThat(target.getSkipNetworkVerification()).isTrue();
        });
    }
}
