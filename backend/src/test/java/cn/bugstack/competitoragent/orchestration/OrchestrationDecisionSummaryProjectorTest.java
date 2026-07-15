package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.model.dto.OrchestrationDecisionSummary;
import cn.bugstack.competitoragent.model.entity.TaskWorkflowEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestrationDecisionSummaryProjectorTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void shouldProjectOriginMetadataPolicyContractAndSourceUrlsFromCurrentEventShape() {
        TaskWorkflowEvent event = TaskWorkflowEvent.builder()
                .taskId(50L)
                .nodeName("quality_check_final")
                .payload("""
                        {
                          "summary": "Orchestrator 已生成运行期编排决策",
                          "decision": {
                            "decisionId": "od-50-review",
                            "decisionOrigin": "RULE_FALLBACK",
                            "decisionMetadata": {
                              "modelName": "deepseek-chat",
                              "temperature": 0.0,
                              "promptHash": "sha256:prompt",
                              "llmResponseHash": "sha256:response",
                              "parseRetryCount": 1,
                              "fallbackUsed": true,
                              "fallbackReason": "LLM_TIMEOUT",
                              "shadowExecuted": false,
                              "shadowSkippedReason": "SHADOW_DISABLED"
                            },
                            "decisionType": "WAIT_FOR_HUMAN",
                            "actionType": "MANUAL_REVIEW",
                            "reason": "模型超时后规则转人工",
                            "sourceUrls": ["https://docs.example.com/a"]
                          },
                          "policyResult": {
                            "decisionOrigin": "RULE_FALLBACK",
                            "decisionContract": "LEGACY_RULE_SET"
                          }
                        }
                        """)
                .sourceUrls("[\"https://docs.example.com/a\",\"https://docs.example.com/b\"]")
                .build();

        OrchestrationDecisionSummary summary = OrchestrationDecisionSummaryProjector
                .fromWorkflowEvent(event, objectMapper)
                .orElseThrow();

        assertThat(summary.getDecisionOrigin()).isEqualTo("RULE_FALLBACK");
        assertThat(summary.getDecisionContract()).isEqualTo("LEGACY_RULE_SET");
        assertThat(summary.getModelName()).isEqualTo("deepseek-chat");
        assertThat(summary.getTemperature()).isZero();
        assertThat(summary.getPromptHash()).isEqualTo("sha256:prompt");
        assertThat(summary.getLlmResponseHash()).isEqualTo("sha256:response");
        assertThat(summary.getParseRetryCount()).isEqualTo(1);
        assertThat(summary.isFallbackUsed()).isTrue();
        assertThat(summary.getFallbackReason()).isEqualTo("LLM_TIMEOUT");
        assertThat(summary.getShadowExecuted()).isFalse();
        assertThat(summary.getShadowSkippedReason()).isEqualTo("SHADOW_DISABLED");
        assertThat(summary.getSourceUrls()).containsExactly(
                "https://docs.example.com/a",
                "https://docs.example.com/b");
    }

    @Test
    void shouldReadFlatPayloadAndLegacyInputRefsMetadata() {
        Map<String, Object> payload = Map.of(
                "decisionId", "od-legacy-flat",
                "decisionType", "NO_ACTION",
                "actionType", "NO_ACTION",
                "inputRefs", Map.of(
                        "decisionOrigin", "LLM_SHADOW",
                        "modelName", "shadow-model",
                        "shadowExecuted", false,
                        "shadowSkippedReason", "SHADOW_BUDGET_EXHAUSTED"),
                "sourceUrls", List.of("https://docs.example.com/flat"));

        OrchestrationDecisionSummary summary = OrchestrationDecisionSummaryProjector
                .fromEventPayload(payload, 51L, "analyze_competitors", List.of(), objectMapper)
                .orElseThrow();

        assertThat(summary.getDecisionOrigin()).isEqualTo("LLM_SHADOW");
        assertThat(summary.getDecisionContract()).isEqualTo("LLM_ACTION_MATRIX");
        assertThat(summary.getModelName()).isEqualTo("shadow-model");
        assertThat(summary.getShadowExecuted()).isFalse();
        assertThat(summary.getShadowSkippedReason()).isEqualTo("SHADOW_BUDGET_EXHAUSTED");
    }

    @Test
    void shouldDefaultHistoricalEventWithoutOriginToLegacyAdapter() {
        TaskWorkflowEvent historicalEvent = TaskWorkflowEvent.builder()
                .taskId(52L)
                .nodeName("quality_check_final")
                .payload("""
                        {
                          "decision": {
                            "decisionId": "od-historical",
                            "decisionType": "RERUN_NODE",
                            "actionType": "RERUN_NODE"
                          }
                        }
                        """)
                .sourceUrls("[]")
                .build();

        OrchestrationDecisionSummary summary = OrchestrationDecisionSummaryProjector
                .fromWorkflowEvent(historicalEvent, objectMapper)
                .orElseThrow();

        assertThat(summary.getDecisionOrigin()).isEqualTo("LEGACY_ADAPTER");
        assertThat(summary.getDecisionContract()).isEqualTo("LEGACY_RULE_SET");
        assertThat(summary.getPolicyAllowed()).isNull();
        assertThat(summary.getRuntimeStatus()).isNull();
        assertThat(summary.getMutationType()).isNull();
        assertThat(summary.getPolicyBlockedReasons()).isEmpty();
    }

    @Test
    void shouldDefaultInvalidOriginToLegacyAdapter() {
        TaskWorkflowEvent invalidOriginEvent = TaskWorkflowEvent.builder()
                .taskId(52L)
                .nodeName("quality_check_final")
                .payload("""
                        {
                          "decision": {
                            "decisionId": "od-historical",
                            "decisionOrigin": "NOT_A_REAL_ORIGIN",
                            "decisionType": "RERUN_NODE",
                            "actionType": "RERUN_NODE"
                          }
                        }
                        """)
                .sourceUrls("[]")
                .build();

        OrchestrationDecisionSummary summary = OrchestrationDecisionSummaryProjector
                .fromWorkflowEvent(invalidOriginEvent, objectMapper)
                .orElseThrow();

        assertThat(summary.getDecisionOrigin()).isEqualTo("LEGACY_ADAPTER");
        assertThat(summary.getDecisionContract()).isEqualTo("LEGACY_RULE_SET");
    }

    @Test
    void shouldIgnoreWorkflowPayloadWithoutDecisionMarker() {
        TaskWorkflowEvent ordinaryEvent = TaskWorkflowEvent.builder()
                .taskId(53L)
                .nodeName("collect_sources")
                .payload("{\"summary\":\"普通节点事件\",\"status\":\"SUCCESS\"}")
                .sourceUrls("[]")
                .build();

        assertThat(OrchestrationDecisionSummaryProjector.fromWorkflowEvent(ordinaryEvent, objectMapper))
                .isEmpty();
    }
}
