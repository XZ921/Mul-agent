package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.workflow.contract.RevisionDirective;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 10 Day 1 的 Reviewer 运行时闭环契约测试。
 *
 * 本测试跨越 ActionMatrix、Policy 和 Executor，避免各层单测分别通过，
 * 组合后却出现 normalizedAction 与实际 mutation 不一致的接缝。
 */
class ReviewerRuntimeClosureDay1ContractTest {

    private static final Long TARGET_PLAN_VERSION_ID = 8L;
    private static final int NEXT_PLAN_VERSION = 2;

    private final OrchestrationDecisionActionMatrix actionMatrix = new OrchestrationDecisionActionMatrix();
    private final DecisionPolicyService policyService = new DecisionPolicyService(actionMatrix);
    private final DecisionExecutorAdapter executorAdapter = new DecisionExecutorAdapter();
    private final OrchestrationDecisionAdapter legacyAdapter = new OrchestrationDecisionAdapter();

    @Test
    void shouldConfirmPolicyApprovedMutationAsTargetBaselineForSupportedMappings() {
        assertRuntimeMapping(
                llmDecision(
                        "day1-pass",
                        "NO_ACTION",
                        "NO_ACTION",
                        "quality_check",
                        "quality_check",
                        "CURRENT_NODE_ONLY",
                        List.of("https://example.com/report-source")),
                "NO_ACTION",
                "NO_MUTATION",
                "NO_ACTION");

        assertRuntimeMapping(
                llmDecision(
                        "day1-supplement",
                        "APPEND_DYNAMIC_BRANCH",
                        "SUPPLEMENT_EVIDENCE",
                        "quality_check",
                        "collect_sources",
                        "CURRENT_NODE_AND_DOWNSTREAM",
                        List.of("https://example.com/pricing")),
                "CREATE_SUPPLEMENT_BRANCH",
                "APPEND_NODES",
                "CREATE_SUPPLEMENT_BRANCH");

        assertRuntimeMapping(
                llmDecision(
                        "day1-rewrite",
                        "REWRITE_ONLY",
                        "REWRITE_SECTION",
                        "quality_check",
                        "rewrite_report",
                        "CURRENT_NODE_ONLY",
                        List.of("https://example.com/report")),
                "CREATE_REWRITE_BRANCH",
                "APPEND_NODES",
                "CREATE_REWRITE_BRANCH");

        assertRuntimeMapping(
                llmDecision(
                        "day1-manual",
                        "WAIT_FOR_HUMAN",
                        "MANUAL_REVIEW",
                        "quality_check",
                        "quality_check",
                        "CURRENT_NODE_ONLY",
                        List.of()),
                "MANUAL_ONLY",
                "MARK_WAITING_INTERVENTION",
                "MANUAL_ONLY");
    }

    @Test
    void shouldKeepReviewerDirectiveAsDiagnosticInputInsteadOfExecutableCommand() {
        RevisionDirective directive = RevisionDirective.builder()
                .category("EXPRESSION_ISSUE")
                .actionType("REWRITE_SECTION")
                .targetNode("rewrite_report")
                .targetSection("summary")
                .summary("收紧没有证据支持的绝对化表达")
                .sourceUrls(List.of("https://example.com/report"))
                .build();

        OrchestrationDecision decision = legacyAdapter.fromRevisionDirective(
                112L,
                "quality_check",
                directive,
                0);
        DecisionPolicyResult policy = evaluate(decision);
        DynamicPlanMutation mutation = toMutation(decision, policy);

        // Reviewer directive 只能提供兼容期诊断输入；真正可执行的动作必须来自 Policy 结果和 mutation。
        assertThat(directive.getOrchestrationAction()).isNull();
        assertThat(policy.isAllowed()).isTrue();
        assertThat(policy.getNormalizedAction()).isEqualTo("CREATE_REWRITE_BRANCH");
        assertThat(mutation.getDecisionId()).isEqualTo(policy.getDecisionId());
        assertThat(mutation.getMutationType()).isEqualTo("APPEND_NODES");
        assertThat(mutation.getDynamicAction()).isEqualTo(policy.getNormalizedAction());
    }

    @Test
    void shouldProduceControlledRerunMutationAfterDayTwoMigration() {
        RevisionDirective directive = RevisionDirective.builder()
                .category("STRUCTURE_ISSUE")
                .actionType("RERUN_NODE")
                .targetNode("extract_schema")
                .targetSection("pricing")
                .summary("已有来源，但 pricing 结构化字段缺失")
                .sourceUrls(List.of("https://example.com/pricing"))
                .build();

        OrchestrationDecision decision = legacyAdapter.fromRevisionDirective(
                112L,
                "quality_check",
                directive,
                1);
        DecisionPolicyResult policy = evaluate(decision);
        DynamicPlanMutation mutation = toMutation(decision, policy);

        // Day 2 后 RERUN 只能指向白名单 extract_schema，并真实生成单个 Extractor 起点。
        assertThat(actionMatrix.findRule("RERUN_NODE", "RERUN_NODE")).isPresent();
        assertThat(decision.getDecisionType()).isEqualTo("RERUN_NODE");
        assertThat(decision.getTargetNode()).isEqualTo("extract_schema");
        assertThat(policy.isAllowed()).isTrue();
        assertThat(policy.getNormalizedAction()).isEqualTo("CREATE_RERUN_BRANCH");
        assertThat(policy.isRequiresConfirmation()).isFalse();
        assertThat(mutation.getMutationType()).isEqualTo("APPEND_NODES");
        assertThat(mutation.getProjectedNextAction()).isEqualTo("RERUN_NODE");
        assertThat(mutation.getNodeTemplates()).singleElement()
                .satisfies(node -> assertThat(node.getAgentType()).isEqualTo("EXTRACTOR"));
    }

    @Test
    void shouldRejectIllegalMatrixPairBeforeExecutorCanCreateMutation() {
        OrchestrationDecision decision = llmDecision(
                "day1-invalid-pair",
                "REWRITE_ONLY",
                "SUPPLEMENT_EVIDENCE",
                "quality_check",
                "rewrite_report",
                "CURRENT_NODE_ONLY",
                List.of("https://example.com/evidence"));

        DecisionPolicyResult policy = evaluate(decision);
        DynamicPlanMutation mutation = toMutation(decision, policy);

        assertThat(policy.isAllowed()).isFalse();
        assertThat(policy.getBlockedReasons())
                .contains("INVALID_DECISION_ACTION_PAIR: REWRITE_ONLY 不允许搭配 SUPPLEMENT_EVIDENCE");
        assertThat(mutation.getMutationType()).isEqualTo("NO_MUTATION");
        assertThat(mutation.getNodeTemplates()).isEmpty();
    }

    @Test
    void shouldNotTreatPassWithoutTraceableSourceAsExecutableNoAction() {
        OrchestrationDecision decision = llmDecision(
                "day1-pass-missing-source",
                "NO_ACTION",
                "NO_ACTION",
                "quality_check",
                "quality_check",
                "CURRENT_NODE_ONLY",
                List.of());

        DecisionPolicyResult policy = evaluate(decision);
        DynamicPlanMutation mutation = toMutation(decision, policy);

        // PASS 不能绕过 sourceUrls 红线。当前 Policy 把无来源 NO_ACTION 升级为确认停点，
        // 因此不会形成“成功收口”的 NO_MUTATION。
        assertThat(policy.isAllowed()).isTrue();
        assertThat(policy.getNormalizedAction()).isEqualTo("NO_ACTION");
        assertThat(policy.isRequiresConfirmation()).isTrue();
        assertThat(policy.getPolicyRuleRefs()).contains("missing_source_requires_supplement");
        assertThat(mutation.getMutationType()).isEqualTo("MARK_WAITING_INTERVENTION");
        assertThat(mutation.getRuntimeCommand()).isEqualTo("AWAIT_CONFIRMATION");
    }

    private void assertRuntimeMapping(OrchestrationDecision decision,
                                      String expectedNormalizedAction,
                                      String expectedMutationType,
                                      String expectedDynamicAction) {
        DecisionPolicyResult policy = evaluate(decision);
        DynamicPlanMutation mutation = toMutation(decision, policy);

        assertThat(policy.isAllowed()).isTrue();
        assertThat(policy.getNormalizedAction()).isEqualTo(expectedNormalizedAction);
        assertThat(mutation.getDecisionId()).isEqualTo(decision.getDecisionId());
        assertThat(mutation.getMutationType()).isEqualTo(expectedMutationType);
        assertThat(mutation.getDynamicAction()).isEqualTo(expectedDynamicAction);
    }

    private DecisionPolicyResult evaluate(OrchestrationDecision decision) {
        return policyService.evaluate(
                decision,
                DecisionPolicyRuleSet.builder().build(),
                0,
                "RUNNING",
                "SUCCESS");
    }

    private DynamicPlanMutation toMutation(OrchestrationDecision decision, DecisionPolicyResult policy) {
        return executorAdapter.toMutation(
                decision,
                policy,
                TARGET_PLAN_VERSION_ID,
                NEXT_PLAN_VERSION);
    }

    private OrchestrationDecision llmDecision(String decisionId,
                                              String decisionType,
                                              String actionType,
                                              String triggerNode,
                                              String targetNode,
                                              String affectedScope,
                                              List<String> sourceUrls) {
        return OrchestrationDecision.builder()
                .decisionId(decisionId)
                .taskId(112L)
                .decisionOrigin(OrchestrationDecisionOrigin.LLM_PRIMARY)
                .triggerNodeName(triggerNode)
                .decisionType(decisionType)
                .actionType(actionType)
                .targetNode(targetNode)
                .affectedScope(affectedScope)
                .reason("Day 1 contract fixture")
                .sourceUrls(sourceUrls)
                .evidenceState(sourceUrls.isEmpty() ? EvidenceState.MISSING_SOURCE : EvidenceState.FULL_SOURCE)
                .build()
                .normalized();
    }
}
