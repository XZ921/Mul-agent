package cn.bugstack.competitoragent.task;

import cn.bugstack.competitoragent.search.SearchSharedNodeOutputProjector;
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
