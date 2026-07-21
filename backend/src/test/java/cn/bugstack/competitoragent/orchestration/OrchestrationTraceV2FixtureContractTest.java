package cn.bugstack.competitoragent.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestrationTraceV2FixtureContractTest {

    private static final String FIXTURE_RESOURCE =
            "orchestration/orchestration-trace-v2-fixtures.json";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void shouldFreezeFallbackAndShadowSkippedV2PayloadShapes() throws Exception {
        JsonNode suite = loadFixture();

        assertThat(suite.path("schemaVersion").asText()).isEqualTo("ORCHESTRATION_TRACE_V2");
        assertThat(suite.path("cases")).hasSize(2);
        assertThat(suite.path("cases").get(0).path("caseId").asText())
                .isEqualTo("llm-policy-rejected-rule-fallback");
        assertThat(suite.path("cases").get(1).path("caseId").asText())
                .isEqualTo("shadow-budget-skipped-without-decision");

        JsonNode fallbackPayload = suite.path("cases").get(0).path("payload");
        assertThat(fallbackPayload.path("traceSchemaVersion").asText())
                .isEqualTo("ORCHESTRATION_TRACE_V2");
        assertThat(fallbackPayload.path("audit").path("attempts")).hasSize(2);
        assertThat(fallbackPayload.path("decision").path("decisionMetadata")
                .path("aiAuditTraceId").asText()).isEqualTo("orch-fixture-trace");
        assertThat(fallbackPayload.path("audit").path("finalDecisionIds"))
                .extracting(JsonNode::asText)
                .containsExactly("od-801-rule-fallback");
        assertThat(fallbackPayload.path("audit").path("llmFailure").path("attempts"))
                .hasSize(2);
        assertThat(fallbackPayload.path("sourceUrls"))
                .extracting(JsonNode::asText)
                .containsExactly(
                        "https://docs.example.com/review-gap",
                        "https://docs.example.com/checkpoint")
                .doesNotContain("https://untrusted.example.net/outside");

        JsonNode shadowPayload = suite.path("cases").get(1).path("payload");
        assertThat(shadowPayload.path("decision").isNull()).isTrue();
        assertThat(shadowPayload.path("audit").path("shadowExecution").path("requested").asBoolean())
                .isTrue();
        assertThat(shadowPayload.path("audit").path("shadowExecution").path("executed").asBoolean())
                .isFalse();
        assertThat(shadowPayload.path("audit").path("shadowExecution").path("skippedReason").asText())
                .isEqualTo("SHADOW_BUDGET_EXHAUSTED");
        assertThat(shadowPayload.path("sourceUrls"))
                .extracting(JsonNode::asText)
                .containsExactly("https://docs.example.com/shadow-context");
    }

    @Test
    void shouldNotPersistForbiddenRuntimeOrRawModelFieldsInCanonicalFixture() throws Exception {
        String fixtureJson = loadFixture().toString();

        assertThat(fixtureJson)
                .doesNotContain("nodeTemplates")
                .doesNotContain("runtimeCommand")
                .doesNotContain("rawPrompt")
                .doesNotContain("rawResponse")
                .doesNotContain("exceptionMessage")
                .doesNotContain("apiKey");
    }

    private JsonNode loadFixture() throws Exception {
        try (InputStream inputStream = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream(FIXTURE_RESOURCE)) {
            assertThat(inputStream).as(FIXTURE_RESOURCE).isNotNull();
            return objectMapper.readTree(inputStream);
        }
    }
}
