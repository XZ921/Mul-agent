package cn.bugstack.competitoragent.workflow;

import cn.bugstack.competitoragent.task.definition.ExecutionPlanDefinition;
import cn.bugstack.competitoragent.workflow.coverage.CoverageBlockingLevel;
import cn.bugstack.competitoragent.workflow.coverage.CoverageContract;
import cn.bugstack.competitoragent.workflow.coverage.CoverageFieldContract;
import cn.bugstack.competitoragent.workflow.coverage.CoverageFieldStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowPlanCoverageContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldSerializeCoverageContractIntoWorkflowPlanSnapshot() throws Exception {
        CoverageContract contract = CoverageContract.builder()
                .taskMode("CAPABILITY_INTRO")
                .contractVersion("coverage-capability_intro-v1")
                .source("PLANNER")
                .fields(List.of(CoverageFieldContract.builder()
                        .field("pricing")
                        .status(CoverageFieldStatus.OUT_OF_SCOPE)
                        .blockingLevel(CoverageBlockingLevel.NONE)
                        .overrideReason("能力介绍任务不强检定价")
                        .build()))
                .build();

        ExecutionPlanDefinition definition = ExecutionPlanDefinition.builder()
                .contractType("COMPETITOR_ANALYSIS_EXECUTION_PLAN")
                .goal("test")
                .coverageContract(contract)
                .stages(List.of())
                .nodes(List.of())
                .sourceUrls(List.of())
                .build();

        WorkflowPlan workflowPlan = new WorkflowPlanAssembler().fromExecutionPlan(definition);
        String json = objectMapper.writeValueAsString(workflowPlan);
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.at("/coverageContract/taskMode").asText()).isEqualTo("CAPABILITY_INTRO");
        assertThat(node.at("/coverageContract/fields/0/field").asText()).isEqualTo("pricing");
    }
}
