package cn.bugstack.competitoragent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestrationDecisionAuditPayloadSizeTest {

    @Test
    void shouldKeepMaximumCardinalityAuditPayloadBelowTextSafetyThreshold() throws Exception {
        OrchestrationRuntimeDecisionBatch batch =
                OrchestrationDecisionAuditTestFixtures.maximumCardinalityBatch();
        OrchestrationDecisionAuditAssembler assembler = new OrchestrationDecisionAuditAssembler();
        OrchestrationRuntimeDecisionTrace representative = assembler
                .selectRepresentativeAttempt(batch)
                .orElseThrow();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", "Orchestrator 已记录完整运行期决策周期");
        payload.put("traceSchemaVersion", "ORCHESTRATION_TRACE_V2");
        payload.put("decision", representative.decision());
        payload.put("policyResult", representative.policyResult());
        payload.put("mutation", representative.mutationSummary());
        payload.put("runtimeStatus", representative.runtimeStatus());
        payload.put("fallbackAttempt", representative.fallbackAttempt());
        payload.put("audit", assembler.assemble(batch));
        payload.put("sourceUrls", batch.sourceUrls());

        byte[] serialized = new ObjectMapper().findAndRegisterModules().writeValueAsBytes(payload);
        String json = new String(serialized, StandardCharsets.UTF_8);

        assertThat(serialized.length).isLessThan(60 * 1024);
        assertThat(json)
                .doesNotContain("nodeTemplates")
                .doesNotContain("runtimeCommand")
                .doesNotContain("DO_NOT_PERSIST")
                .doesNotContain("rawPrompt")
                .doesNotContain("rawResponse")
                .doesNotContain("exceptionMessage")
                .doesNotContain("apiKey");
    }
}
