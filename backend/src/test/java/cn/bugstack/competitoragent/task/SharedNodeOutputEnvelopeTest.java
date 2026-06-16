package cn.bugstack.competitoragent.task;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SharedNodeOutputEnvelopeTest {

    @Test
    void shouldRecordProjectionMetadataAndSourceUrls() {
        SharedNodeOutputEnvelope envelope = SharedNodeOutputEnvelope.builder()
                .taskId(33L)
                .nodeName("collect_sources_01_01")
                .planVersionId(7L)
                .projectionType("SEARCH_SHARED_PROJECTION_V1")
                .payloadJson("{\"sourceUrls\":[\"https://docs.example.com/reference\"]}")
                .sourceUrls(List.of("https://docs.example.com/reference"))
                .build();

        assertThat(envelope.getProjectionType()).isEqualTo("SEARCH_SHARED_PROJECTION_V1");
        assertThat(envelope.getSourceUrls()).containsExactly("https://docs.example.com/reference");
        assertThat(envelope.getPayloadJson()).doesNotContain("collectedPage");
    }
}
