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
}
