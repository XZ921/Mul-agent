package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.workflow.WorkflowPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestrationContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldNormalizeDecisionWithExplicitEvidenceGapWhenSourceUrlsAreEmpty() throws Exception {
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-001")
                .taskId(50L)
                .triggerNodeName("quality_check_final")
                .decisionType("APPEND_DYNAMIC_BRANCH")
                .actionType("SUPPLEMENT_EVIDENCE")
                .targetNode("collect_sources")
                .affectedScope("current_node_and_downstream")
                .priority("high")
                .targetSection("pricing")
                .reason("终审发现 pricing 缺少可追溯来源")
                .confidence(0.84d)
                .inputRefs(Map.of(
                        "qualityDiagnosisIds", List.of("qd-001"),
                        "agentSuggestionIds", List.of(),
                        "triggerNodeName", "quality_check_final"))
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .suggestedQueries(List.of("Notion AI pricing official"))
                .build()
                .normalized();

        assertThat(decision.getDecisionType()).isEqualTo("APPEND_DYNAMIC_BRANCH");
        assertThat(decision.getActionType()).isEqualTo("SUPPLEMENT_EVIDENCE");
        assertThat(decision.getAffectedScope()).isEqualTo("CURRENT_NODE_AND_DOWNSTREAM");
        assertThat(decision.getPriority()).isEqualTo("HIGH");
        assertThat(decision.getConfidence()).isEqualTo(0.84d);
        assertThat(decision.getInputRefs()).containsEntry("triggerNodeName", "quality_check_final");
        assertThat(decision.getEvidenceState()).isEqualTo(EvidenceState.MISSING_SOURCE);
        assertThat(decision.getSourceUrls()).isEmpty();
        assertThat(objectMapper.writeValueAsString(decision))
                .contains("evidenceState")
                .contains("affectedScope")
                .contains("inputRefs");
    }

    @Test
    void shouldCarryDecisionTraceAndCheckpointSeparately() {
        DecisionTrace trace = DecisionTrace.builder()
                .decisionId("od-001")
                .taskId(50L)
                .triggerNodeName("quality_check_final")
                .decisionType("APPEND_DYNAMIC_BRANCH")
                .policyAllowed(true)
                .executionStatus("APPLIED")
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build()
                .normalized();
        OrchestratorCheckpoint checkpoint = OrchestratorCheckpoint.builder()
                .checkpointId("oc-001")
                .taskId(50L)
                .lastDecisionId("od-001")
                .lastMutationId("dpm-001")
                .pendingActions(List.of("WAITING_FOR_SUPPLEMENT_RESULT"))
                .decisionCount(1)
                .maxAutoDecisions(2)
                .resumeAfterNodeName("collect_revision_evidence_v2_1")
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build()
                .normalized();

        assertThat(trace.getExecutionStatus()).isEqualTo("APPLIED");
        assertThat(checkpoint.getPendingActions()).containsExactly("WAITING_FOR_SUPPLEMENT_RESULT");
        assertThat(checkpoint.getDecisionCount()).isEqualTo(1);
        assertThat(checkpoint.getResumeAfterNodeName()).isEqualTo("collect_revision_evidence_v2_1");
    }

    @Test
    void shouldNormalizePolicyResultAndMutationWithSourceBoundary() {
        DecisionPolicyRuleSet ruleSet = DecisionPolicyRuleSet.builder()
                .policyVersion("ORCHESTRATION_POLICY_V1")
                .allowedDecisionTypes(List.of("APPEND_DYNAMIC_BRANCH"))
                .allowedDynamicActions(List.of("CREATE_SUPPLEMENT_BRANCH"))
                .riskRules(List.of(DecisionPolicyRuleSet.PolicyRiskRule.builder()
                        .ruleId("missing_source_requires_supplement")
                        .when("evidenceState == 'MISSING_SOURCE' && actionType != 'SUPPLEMENT_EVIDENCE'")
                        .riskLevel("HIGH")
                        .requiresConfirmation(true)
                        .build()))
                .maxAutoDecisions(2)
                .maxDynamicBranchesPerSection(1)
                .maxSearchQueriesPerDecision(3)
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.NOT_APPLICABLE)
                .build()
                .normalized();
        DecisionPolicyResult policyResult = DecisionPolicyResult.builder()
                .decisionId("od-001")
                .allowed(true)
                .normalizedAction("CREATE_SUPPLEMENT_BRANCH")
                .policyVersion(ruleSet.getPolicyVersion())
                .policyRuleRefs(ruleSet.getRiskRules().stream()
                        .map(DecisionPolicyRuleSet.PolicyRiskRule::getRuleId)
                        .toList())
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build()
                .normalized();
        DynamicPlanMutation mutation = DynamicPlanMutation.builder()
                .mutationId("dpm-001")
                .decisionId("od-001")
                .mutationType("APPEND_NODES")
                .branchReason("ORCHESTRATOR_DECISION")
                .dynamicAction("CREATE_SUPPLEMENT_BRANCH")
                .expectedResumeNodeName("collect_revision_evidence_v2_1")
                .nodeTemplates(List.of(WorkflowPlan.WorkflowPlanNode.builder()
                        .agentType("COLLECTOR")
                        .nodeName("collect_revision_evidence_v2_1")
                        .build()))
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build()
                .normalized();

        assertThat(ruleSet.getEvidenceState()).isEqualTo(EvidenceState.NOT_APPLICABLE);
        assertThat(policyResult.getEvidenceState()).isEqualTo(EvidenceState.MISSING_SOURCE);
        assertThat(policyResult.getPolicyRuleRefs()).contains("missing_source_requires_supplement");
        assertThat(mutation.getMutationType()).isEqualTo("APPEND_NODES");
        assertThat(mutation.getNodeTemplates()).hasSize(1);
        assertThat(mutation.getEvidenceState()).isEqualTo(EvidenceState.MISSING_SOURCE);
    }

    @Test
    void shouldCarryOrchestrationContextInputsWithoutLosingDiagnosisReferences() {
        OrchestrationContext context = OrchestrationContext.builder()
                .taskId(50L)
                .triggerNodeName("quality_check_final")
                .taskStatus("FAILED")
                .reviewStage("final")
                .passed(false)
                .sourceUrls(List.of("https://www.notion.so/pricing"))
                .evidenceState(EvidenceState.PARTIAL_SOURCE)
                .build()
                .normalized();

        assertThat(context.getTriggerNodeName()).isEqualTo("quality_check_final");
        assertThat(context.getTaskStatus()).isEqualTo("FAILED");
        assertThat(context.getReviewStage()).isEqualTo("final");
        assertThat(context.isPassed()).isFalse();
        assertThat(context.getSourceUrls()).containsExactly("https://www.notion.so/pricing");
        assertThat(context.getEvidenceState()).isEqualTo(EvidenceState.PARTIAL_SOURCE);
    }
}
