package cn.bugstack.competitoragent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CollaborationContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldNormalizeCollaborationGoalWithMissingSourceState() throws Exception {
        CollaborationGoal goal = CollaborationGoal.builder()
                .goalId("cg-001")
                .taskId(50L)
                .subject("企业级 RAG 知识库竞品分析")
                .competitors(List.of("Notion AI", "Glean"))
                .analysisDimensions(List.of("pricing", "security", "integration"))
                .deliverableType("COMPETITOR_REPORT")
                .depth("standard")
                .budget(Map.of("maxSearchQueries", 20, "maxModelCalls", 12, "maxAutoDecisions", 5))
                .constraints(Map.of("requireSourceUrls", true, "allowDynamicBranch", true))
                .sourceUrls(List.of())
                .build()
                .normalized();

        assertThat(goal.getDepth()).isEqualTo("STANDARD");
        assertThat(goal.getEvidenceState()).isEqualTo(EvidenceState.MISSING_SOURCE);
        assertThat(goal.getSourceUrls()).isEmpty();
        assertThat(objectMapper.writeValueAsString(goal))
                .contains("sourceUrls")
                .contains("evidenceState")
                .contains("analysisDimensions");
    }

    @Test
    void shouldCarryPlanReviewAndCheckpointAsSeparateAuditObjects() {
        CollaborationPlan plan = CollaborationPlan.builder()
                .planId("cp-001")
                .goalId("cg-001")
                .taskId(50L)
                .planningMode("orchestrator_first")
                .agentRoleAssignments(List.of(
                        AgentRoleAssignment.builder()
                                .roleId("role-collector-01")
                                .agentType("collector")
                                .mission("采集竞品官网、文档和定价页证据")
                                .expectedOutputs(List.of("EvidenceFragment", "CollectionAudit"))
                                .dependsOn(List.of())
                                .qualityGate("sourceUrls must not be empty")
                                .build(),
                        AgentRoleAssignment.builder()
                                .roleId("role-extractor-01")
                                .agentType("extractor")
                                .mission("抽取结构化字段并输出 evidenceCoverage")
                                .expectedOutputs(List.of("ExtractResult", "AgentSuggestion"))
                                .dependsOn(List.of("role-collector-01"))
                                .qualityGate("evidenceCoverage must cover requested dimensions")
                                .build()))
                .checkpoints(List.of("after_extract_schema", "quality_check_final"))
                .sourceUrls(List.of("https://www.notion.so"))
                .build()
                .normalized();

        InitialPlanReview review = InitialPlanReview.builder()
                .reviewId("ipr-001")
                .planId("cp-001")
                .allowed(true)
                .mappedWorkflowTemplate("STANDARD_COMPETITOR_ANALYSIS_V1")
                .policyRuleRefs(List.of("agentRoleCoverage", "dagTemplateCompatibility"))
                .sourceUrls(plan.getSourceUrls())
                .build()
                .normalized();

        CollaborationCheckpoint checkpoint = CollaborationCheckpoint.builder()
                .checkpointId("cc-001")
                .taskId(50L)
                .goalId("cg-001")
                .planId("cp-001")
                .lastReviewId("ipr-001")
                .phase("plan_approved")
                .mappedWorkflowPlanId(27L)
                .pendingActions(List.of())
                .resumeReason("协作计划已通过初始校验，等待 WorkflowPlan 执行。")
                .sourceUrls(plan.getSourceUrls())
                .build()
                .normalized();

        assertThat(plan.getPlanningMode()).isEqualTo("ORCHESTRATOR_FIRST");
        assertThat(plan.getAgentRoleAssignments()).extracting(AgentRoleAssignment::getAgentType)
                .contains("COLLECTOR", "EXTRACTOR");
        assertThat(review.isAllowed()).isTrue();
        assertThat(review.getEvidenceState()).isEqualTo(EvidenceState.FULL_SOURCE);
        assertThat(checkpoint.getPhase()).isEqualTo("PLAN_APPROVED");
        assertThat(checkpoint.getEvidenceState()).isEqualTo(EvidenceState.FULL_SOURCE);
    }

    @Test
    void shouldNormalizeExtractorSuggestionWithoutGrantingExecutionPower() {
        AgentSuggestion suggestion = AgentSuggestion.builder()
                .suggestionId("as-001")
                .taskId(50L)
                .producerNodeName("extract_schema")
                .producerAgentType("extractor")
                .suggestionType("evidence_gap")
                .targetSection("pricing")
                .summary("pricing 字段缺少可验证来源。")
                .severity("high")
                .confidence(1.5d)
                .sourceUrls(List.of())
                .suggestedQueries(List.of("Notion AI pricing official"))
                .suggestedTargetNode("collect_sources")
                .build()
                .normalized();

        assertThat(suggestion.getProducerAgentType()).isEqualTo("EXTRACTOR");
        assertThat(suggestion.getSuggestionType()).isEqualTo("EVIDENCE_GAP");
        assertThat(suggestion.getSeverity()).isEqualTo("HIGH");
        assertThat(suggestion.getConfidence()).isEqualTo(1.0d);
        assertThat(suggestion.getEvidenceState()).isEqualTo(EvidenceState.MISSING_SOURCE);
        assertThat(suggestion.getSuggestedTargetNode()).isEqualTo("collect_sources");
    }
}
