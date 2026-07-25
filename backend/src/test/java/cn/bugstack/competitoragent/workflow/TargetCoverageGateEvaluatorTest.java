package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TargetCoverageGateEvaluatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final TargetCoverageGateEvaluator evaluator = new TargetCoverageGateEvaluator(objectMapper);

    @Test
    void shouldPassWhenExtractorClosesConfiguredGapKey() throws Exception {
        String gateConfig = objectMapper.writeValueAsString(Map.of(
                "decisionId", "od-001",
                "gapKey", "notion|pricing|official_pricing",
                "competitor", "Notion",
                "targetField", "pricing",
                "requiredSourceType", "OFFICIAL_PRICING"
        ));
        String extractorOutput = objectMapper.writeValueAsString(Map.of(
                "closedGapKeys", List.of("notion|pricing|official_pricing"),
                "sourceUrls", List.of("https://www.notion.so/pricing")
        ));

        AgentResult result = evaluator.evaluate(gateConfig, List.of(extractorOutput));

        assertThat(result.getStatus()).isEqualTo(TaskNodeStatus.SUCCESS);
        assertThat(result.getOutputData()).contains("\"targetCoverageGate\":\"PASSED\"");
        assertThat(result.getOutputData()).contains("\"targetCoverageDelta\":1");
        assertThat(result.getOutputData()).contains("https://www.notion.so/pricing");
    }

    @Test
    void shouldStopWhenExtractorOnlyAddsUnrelatedEvidence() throws Exception {
        String gateConfig = objectMapper.writeValueAsString(Map.of(
                "decisionId", "od-002",
                "gapKey", "notion|pricing|official_pricing",
                "competitor", "Notion",
                "targetField", "pricing",
                "requiredSourceType", "OFFICIAL_PRICING"
        ));
        String extractorOutput = objectMapper.writeValueAsString(Map.of(
                "closedGapKeys", List.of("notion|core_features|official_docs"),
                "sourceUrls", List.of("https://www.notion.so/help")
        ));

        AgentResult result = evaluator.evaluate(gateConfig, List.of(extractorOutput));

        assertThat(result.getStatus()).isEqualTo(TaskNodeStatus.WAITING_INTERVENTION);
        assertThat(result.getOutputData()).contains("\"targetCoverageGate\":\"FAILED\"");
        assertThat(result.getOutputData()).contains("\"targetCoverageDelta\":0");
        assertThat(result.getErrorMessage()).contains("targetCoverageDelta=0");
    }
}
