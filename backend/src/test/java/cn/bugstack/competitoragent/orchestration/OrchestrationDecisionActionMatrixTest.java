package cn.bugstack.competitoragent.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrchestrationDecisionActionMatrixTest {

    private final OrchestrationDecisionActionMatrix matrix = new OrchestrationDecisionActionMatrix();

    @Test
    void shouldResolveAllAllowedLlmDecisionActionRules() {
        assertThat(matrix.rules())
                .extracting(OrchestrationDecisionActionMatrix.ActionRule::ruleId)
                .containsExactly(
                        "LLM_NO_ACTION",
                        "LLM_SUPPLEMENT_EVIDENCE",
                        "LLM_RERUN_EXTRACT_SCHEMA",
                        "LLM_REWRITE_SECTION",
                        "LLM_REWRITE_CLAIM",
                        "LLM_MANUAL_REVIEW");

        assertRule("NO_ACTION", "NO_ACTION", "NO_ACTION",
                OrchestrationDecisionActionMatrix.TargetNodePolicy.TRIGGER_NODE,
                null, "CURRENT_NODE_ONLY");
        assertRule("APPEND_DYNAMIC_BRANCH", "SUPPLEMENT_EVIDENCE", "CREATE_SUPPLEMENT_BRANCH",
                OrchestrationDecisionActionMatrix.TargetNodePolicy.FIXED_NODE,
                "collect_sources", "CURRENT_NODE_AND_DOWNSTREAM");
        assertRule("REWRITE_ONLY", "REWRITE_SECTION", "CREATE_REWRITE_BRANCH",
                OrchestrationDecisionActionMatrix.TargetNodePolicy.FIXED_NODE,
                "rewrite_report", "CURRENT_NODE_ONLY");
        assertRule("REWRITE_ONLY", "REWRITE_CLAIM", "CREATE_REWRITE_BRANCH",
                OrchestrationDecisionActionMatrix.TargetNodePolicy.FIXED_NODE,
                "rewrite_report", "CURRENT_NODE_ONLY");
        assertRule("WAIT_FOR_HUMAN", "MANUAL_REVIEW", "MANUAL_ONLY",
                OrchestrationDecisionActionMatrix.TargetNodePolicy.TRIGGER_NODE,
                null, "CURRENT_NODE_ONLY");
    }

    @Test
    void shouldValidateAllowedLlmDecisionShapes() {
        List<OrchestrationDecision> decisions = List.of(
                completeDecision("quality_check_final", "NO_ACTION", "NO_ACTION",
                        "quality_check_final", "CURRENT_NODE_ONLY"),
                completeDecision("quality_check_final", "APPEND_DYNAMIC_BRANCH", "SUPPLEMENT_EVIDENCE",
                        "collect_sources", "CURRENT_NODE_AND_DOWNSTREAM"),
                completeDecision("quality_check_final", "RERUN_NODE", "RERUN_NODE",
                        "extract_schema", "CURRENT_NODE_ONLY"),
                completeDecision("quality_check_final", "REWRITE_ONLY", "REWRITE_SECTION",
                        "rewrite_report", "CURRENT_NODE_ONLY"),
                completeDecision("quality_check_final", "REWRITE_ONLY", "REWRITE_CLAIM",
                        "rewrite_report", "CURRENT_NODE_ONLY"),
                completeDecision("quality_check_final", "WAIT_FOR_HUMAN", "MANUAL_REVIEW",
                        "quality_check_final", "CURRENT_NODE_ONLY")
        );

        assertThat(decisions)
                .extracting(matrix::validate)
                .allMatch(OrchestrationDecisionActionMatrix.ActionMatrixValidation::valid);
    }

    @Test
    void shouldExposeDefaultsForFutureParserWithoutMutatingDecision() {
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionType(" rewrite_only ")
                .actionType(" rewrite_section ")
                .build();

        OrchestrationDecisionActionMatrix.ActionRule rule = matrix
                .findRule(decision.getDecisionType(), decision.getActionType())
                .orElseThrow();

        assertThat(rule.fixedTargetNode()).isEqualTo("rewrite_report");
        assertThat(rule.affectedScope()).isEqualTo("CURRENT_NODE_ONLY");
        assertThat(decision.getTargetNode()).isNull();
        assertThat(decision.getAffectedScope()).isNull();
    }

    @Test
    void shouldRejectCrossedDecisionActionPairs() {
        List<List<String>> invalidPairs = List.of(
                List.of("REWRITE_ONLY", "SUPPLEMENT_EVIDENCE"),
                List.of("APPEND_DYNAMIC_BRANCH", "REWRITE_SECTION"),
                List.of("NO_ACTION", "MANUAL_REVIEW"),
                List.of("WAIT_FOR_HUMAN", "NO_ACTION")
        );

        for (List<String> pair : invalidPairs) {
            OrchestrationDecisionActionMatrix.ActionMatrixValidation validation = matrix.validate(
                    completeDecision("quality_check_final", pair.get(0), pair.get(1),
                            "quality_check_final", "CURRENT_NODE_ONLY"));

            assertThat(validation.valid()).isFalse();
            assertThat(validation.violationCodes()).containsExactly("INVALID_DECISION_ACTION_PAIR");
            assertThat(validation.normalizedAction()).isNull();
        }
    }

    @Test
    void shouldAllowControlledRerunAndRejectOtherLegacyOnlyActionsForLlmContract() {
        assertThat(matrix.validate(completeDecision("extract_schema", "RERUN_NODE", "RERUN_NODE",
                "extract_schema", "CURRENT_NODE_ONLY")).valid()).isTrue();
        assertThat(matrix.validate(completeDecision("quality_check_final", "APPEND_DYNAMIC_BRANCH",
                "DOMAIN_HINT_DISCOVERY", "collect_sources", "CURRENT_NODE_AND_DOWNSTREAM")).violationCodes())
                .containsExactly("INVALID_DECISION_ACTION_PAIR");
    }

    @Test
    void shouldRejectUnknownDecisionOrActionWithoutNormalizingToNoAction() {
        OrchestrationDecisionActionMatrix.ActionMatrixValidation validation = matrix.validate(
                completeDecision("quality_check_final", "FREE_FORM_DAG", "FREE_FORM_ACTION",
                        "quality_check_final", "CURRENT_NODE_ONLY"));

        assertThat(validation.valid()).isFalse();
        assertThat(validation.ruleId()).isNull();
        assertThat(validation.normalizedAction()).isNull();
        assertThat(validation.violationCodes()).containsExactly("INVALID_DECISION_ACTION_PAIR");
        assertThat(matrix.findRule("FREE_FORM_DAG", "FREE_FORM_ACTION")).isEmpty();
    }

    @Test
    void shouldRejectLlmDecisionWithWrongTargetNode() {
        OrchestrationDecisionActionMatrix.ActionMatrixValidation validation = matrix.validate(
                completeDecision("quality_check_final", "APPEND_DYNAMIC_BRANCH", "SUPPLEMENT_EVIDENCE",
                        "rewrite_report", "CURRENT_NODE_AND_DOWNSTREAM"));

        assertThat(validation.valid()).isFalse();
        assertThat(validation.violationCodes()).containsExactly("INVALID_LLM_TARGET_NODE");
    }

    @Test
    void shouldRejectLlmDecisionWithWrongAffectedScope() {
        OrchestrationDecisionActionMatrix.ActionMatrixValidation validation = matrix.validate(
                completeDecision("quality_check_final", "REWRITE_ONLY", "REWRITE_CLAIM",
                        "rewrite_report", "CURRENT_NODE_AND_DOWNSTREAM"));

        assertThat(validation.valid()).isFalse();
        assertThat(validation.violationCodes()).containsExactly("INVALID_LLM_AFFECTED_SCOPE");
    }

    @Test
    void shouldRejectLlmDecisionWithMissingTargetNode() {
        OrchestrationDecisionActionMatrix.ActionMatrixValidation validation = matrix.validate(
                completeDecision("quality_check_final", "REWRITE_ONLY", "REWRITE_SECTION",
                        null, "CURRENT_NODE_ONLY"));

        assertThat(validation.valid()).isFalse();
        assertThat(validation.violationCodes()).containsExactly("INVALID_LLM_TARGET_NODE");
    }

    @Test
    void shouldRejectTriggerNodeRuleWhenTriggerAndTargetAreBothMissing() {
        OrchestrationDecisionActionMatrix.ActionMatrixValidation validation = matrix.validate(
                completeDecision(null, "NO_ACTION", "NO_ACTION", null, "CURRENT_NODE_ONLY"));

        assertThat(validation.valid()).isFalse();
        assertThat(validation.violationCodes()).containsExactly("INVALID_LLM_TARGET_NODE");
    }

    @Test
    void shouldExposeImmutableRulesAndViolations() {
        OrchestrationDecisionActionMatrix.ActionMatrixValidation validation = matrix.validate(
                completeDecision("quality_check_final", "REWRITE_ONLY", "REWRITE_SECTION",
                        "collect_sources", "CURRENT_NODE_AND_DOWNSTREAM"));

        assertThatThrownBy(() -> matrix.rules().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> validation.violationCodes().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private void assertRule(String decisionType,
                            String actionType,
                            String normalizedAction,
                            OrchestrationDecisionActionMatrix.TargetNodePolicy targetNodePolicy,
                            String fixedTargetNode,
                            String affectedScope) {
        OrchestrationDecisionActionMatrix.ActionRule rule = matrix.findRule(decisionType, actionType).orElseThrow();
        assertThat(rule.normalizedAction()).isEqualTo(normalizedAction);
        assertThat(rule.targetNodePolicy()).isEqualTo(targetNodePolicy);
        assertThat(rule.fixedTargetNode()).isEqualTo(fixedTargetNode);
        assertThat(rule.affectedScope()).isEqualTo(affectedScope);
    }

    private OrchestrationDecision completeDecision(String triggerNodeName,
                                                   String decisionType,
                                                   String actionType,
                                                   String targetNode,
                                                   String affectedScope) {
        return OrchestrationDecision.builder()
                .triggerNodeName(triggerNodeName)
                .decisionType(decisionType)
                .actionType(actionType)
                .targetNode(targetNode)
                .affectedScope(affectedScope)
                .build();
    }
}
