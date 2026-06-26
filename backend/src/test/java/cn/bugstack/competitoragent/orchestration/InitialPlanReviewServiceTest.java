package cn.bugstack.competitoragent.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InitialPlanReviewServiceTest {

    private final InitialPlanReviewService service = new InitialPlanReviewService();

    @Test
    void shouldApproveStandardPlanThatCanMapToExistingDagTemplate() {
        InitialPlanReview review = service.review(standardPlan());

        assertThat(review.isAllowed()).isTrue();
        assertThat(review.getMappedWorkflowTemplate()).isEqualTo("STANDARD_COMPETITOR_ANALYSIS_V1");
        assertThat(review.getBlockedReasons()).isEmpty();
        assertThat(review.getPolicyRuleRefs())
                .contains("agentRoleCoverage", "dagTemplateCompatibility", "checkpointDensity", "sourceUrlsOrEvidenceStateRequired");
    }

    @Test
    void shouldBlockPlanWithoutCollectorRole() {
        CollaborationPlan plan = standardPlan().toBuilder()
                .agentRoleAssignments(standardPlan().getAgentRoleAssignments().stream()
                        .filter(role -> !"COLLECTOR".equals(role.getAgentType()))
                        .toList())
                .build();

        InitialPlanReview review = service.review(plan);

        assertThat(review.isAllowed()).isFalse();
        assertThat(review.getBlockedReasons()).contains("缺少必需角色 COLLECTOR");
        assertThat(review.getMappedWorkflowTemplate()).isEqualTo("UNMAPPED");
    }

    @Test
    void shouldBlockUnknownAgentTypeAndTooManyCheckpoints() {
        CollaborationPlan plan = standardPlan().toBuilder()
                .agentRoleAssignments(List.of(AgentRoleAssignment.builder()
                        .roleId("role-unknown")
                        .agentType("PLANNER")
                        .mission("未知角色")
                        .expectedOutputs(List.of("UnknownOutput"))
                        .qualityGate("unknown")
                        .build()
                        .normalized()))
                .checkpoints(List.of("a", "b", "c", "d"))
                .build();

        InitialPlanReview review = service.review(plan);

        assertThat(review.isAllowed()).isFalse();
        assertThat(review.getBlockedReasons()).contains("存在未登记 Agent 类型 PLANNER", "checkpoint 数量超过 P2 上限 2");
    }

    private CollaborationPlan standardPlan() {
        return CollaborationPlan.builder()
                .planId("cp-task-88-v1")
                .goalId("cg-task-88")
                .taskId(88L)
                .planningMode("ORCHESTRATOR_FIRST")
                .agentRoleAssignments(List.of(
                        role("role-collector-01", "COLLECTOR", List.of()),
                        role("role-extractor-01", "EXTRACTOR", List.of("role-collector-01")),
                        role("role-analyzer-01", "ANALYZER", List.of("role-extractor-01")),
                        role("role-writer-01", "WRITER", List.of("role-analyzer-01")),
                        role("role-citation-01", "CITATION", List.of("role-writer-01")),
                        role("role-reviewer-01", "REVIEWER", List.of("role-citation-01"))))
                .checkpoints(List.of("after_extract_schema", "quality_check_final"))
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build()
                .normalized();
    }

    private AgentRoleAssignment role(String roleId, String agentType, List<String> dependsOn) {
        return AgentRoleAssignment.builder()
                .roleId(roleId)
                .agentType(agentType)
                .mission(agentType + " mission")
                .expectedOutputs(List.of(agentType + "_OUTPUT"))
                .dependsOn(dependsOn)
                .qualityGate("sourceUrls or explicit evidence gap")
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build()
                .normalized();
    }
}
