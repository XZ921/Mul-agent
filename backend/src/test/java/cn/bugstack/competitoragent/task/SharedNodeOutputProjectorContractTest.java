package cn.bugstack.competitoragent.task;

import cn.bugstack.competitoragent.search.SearchSharedNodeOutputProjector;
import cn.bugstack.competitoragent.extractor.ExtractSharedNodeOutputProjector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SharedNodeOutputProjectorContractTest {

    @Test
    void shouldProjectCollectorOutputToSharedEnvelope() throws Exception {
        SearchSharedNodeOutputProjector projector = new SearchSharedNodeOutputProjector(new ObjectMapper().findAndRegisterModules());
        String rawOutput = """
                {
                  "sourceUrls": ["https://docs.example.com/reference"],
                  "searchExecutionTrace": {
                    "recoveryCheckpoint": "SELECT_TARGETS",
                    "fallbackDecision": "USE_PLANNED_CANDIDATES"
                  },
                  "selectedTargets": [
                    {
                      "url": "https://docs.example.com/reference",
                      "title": "Reference",
                      "collectedPage": { "content": "very large body" }
                    }
                  ]
                }
                """;

        assertThat(projector.supports(rawOutput)).isTrue();

        SharedNodeOutputEnvelope envelope = projector.project(33L, "collect_sources_01_01", 7L, rawOutput);
        assertThat(envelope.getProjectionType()).isEqualTo("SEARCH_SHARED_PROJECTION_V1");
        assertThat(envelope.getSourceUrls()).containsExactly("https://docs.example.com/reference");
        assertThat(envelope.getPayloadJson()).doesNotContain("very large body");
    }

    @Test
    void shouldProjectExtractorOutputToLightweightSharedEnvelope() throws Exception {
        ExtractSharedNodeOutputProjector projector = new ExtractSharedNodeOutputProjector(new ObjectMapper().findAndRegisterModules());
        String rawOutput = """
                {
                  "contractVersion": "1.0",
                  "sourceUrls": ["https://docs.example.com/pricing"],
                  "issueFlags": ["TRACEABLE"],
                  "drafts": [
                    {
                      "competitorName": "Acme",
                      "summary": "workspace pricing",
                      "sourceUrls": ["https://docs.example.com/pricing"],
                      "downstreamEvidenceViews": [
                        {
                          "evidenceId": "E001",
                          "title": "Pricing Docs",
                          "sourceType": "DOCS",
                          "content": "very large body very large body very large body",
                          "sourceUrls": ["https://docs.example.com/pricing"],
                          "qualitySignals": ["STRUCTURED_BLOCK_HIT"],
                          "structuredBlocks": [
                            {
                              "blockType": "PRICING_BLOCK",
                              "summary": "Pro 199 / 月",
                              "content": "large structured block body"
                            }
                          ]
                        }
                      ]
                    }
                  ],
                  "downstreamEvidenceViews": [
                    {
                      "evidenceId": "E001",
                      "title": "Pricing Docs",
                      "sourceType": "DOCS",
                      "content": "very large body very large body very large body",
                      "sourceUrls": ["https://docs.example.com/pricing"],
                      "qualitySignals": ["STRUCTURED_BLOCK_HIT"],
                      "structuredBlocks": [
                        {
                          "blockType": "PRICING_BLOCK",
                          "summary": "Pro 199 / 月",
                          "content": "large structured block body"
                        }
                      ]
                    }
                  ],
                  "extractorInput": {
                    "inputSource": "REPOSITORY_BACKED_PORT",
                    "auditRefs": {
                      "searchAudit": {
                        "available": true
                      }
                    },
                    "competitors": [
                      {
                        "competitorName": "Acme",
                        "readableEvidence": [
                          {
                            "evidenceId": "E001",
                            "content": "very large body very large body very large body"
                          }
                        ]
                      }
                    ]
                  }
                }
                """;

        assertThat(projector.supports(rawOutput)).isTrue();

        SharedNodeOutputEnvelope envelope = projector.project(33L, "extract_schema", 7L, rawOutput);
        assertThat(envelope.getProjectionType()).isEqualTo("EXTRACT_SHARED_PROJECTION_V1");
        assertThat(envelope.getSourceUrls()).containsExactly("https://docs.example.com/pricing");
        assertThat(envelope.getPayloadJson()).contains("\"inputSource\":\"REPOSITORY_BACKED_PORT\"");
        assertThat(envelope.getPayloadJson()).contains("\"searchAudit\"");
        assertThat(envelope.getPayloadJson()).contains("PRICING_BLOCK");
        assertThat(envelope.getPayloadJson()).doesNotContain("very large body");
        assertThat(envelope.getPayloadJson()).doesNotContain("large structured block body");
    }

    @Test
    void shouldNotTreatCollectorOutputAsExtractorSharedProjection() {
        ExtractSharedNodeOutputProjector projector = new ExtractSharedNodeOutputProjector(new ObjectMapper().findAndRegisterModules());
        String rawOutput = """
                {
                  "sourceUrls": ["https://docs.example.com/reference"],
                  "searchExecutionTrace": {
                    "recoveryCheckpoint": "SELECT_TARGETS",
                    "fallbackDecision": "USE_PLANNED_CANDIDATES"
                  },
                  "selectedTargets": [
                    {
                      "url": "https://docs.example.com/reference",
                      "title": "Reference",
                      "collectedPage": { "content": "very large body" }
                    }
                  ],
                  "evidenceFragments": [
                    {
                      "fieldName": "pricing",
                      "sourceUrl": "https://docs.example.com/reference"
                    }
                  ],
                  "downstreamEvidenceViews": [
                    {
                      "evidenceId": "E001",
                      "title": "Reference",
                      "sourceType": "DOCS",
                      "content": "very large body"
                    }
                  ]
                }
                """;

        assertThat(projector.supports(rawOutput)).isFalse();
    }

    @Test
    void shouldKeepLegacyOutputsAsRawStringsWhenEnvelopeUnavailable() {
        SharedNodeOutputEnvelope envelope = SharedNodeOutputEnvelope.builder()
                .taskId(1L)
                .nodeName("write_report")
                .planVersionId(1L)
                .projectionType("LEGACY_STRING_OUTPUT")
                .payloadJson("{\"ok\":true}")
                .sourceUrls(java.util.List.of())
                .build();

        assertThat(envelope.getProjectionType()).isEqualTo("LEGACY_STRING_OUTPUT");
        assertThat(Map.of("write_report", envelope).get("write_report").getPayloadJson()).isEqualTo("{\"ok\":true}");
    }
}
