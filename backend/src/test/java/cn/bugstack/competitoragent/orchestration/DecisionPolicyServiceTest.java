package cn.bugstack.competitoragent.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionPolicyServiceTest {

    private final DecisionPolicyService service = new DecisionPolicyService(
            new OrchestrationDecisionActionMatrix());

    @Test
    void shouldAllowSupplementEvidenceWhenSourceGapIsExplicit() {
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-001")
                .decisionType("APPEND_DYNAMIC_BRANCH")
                .actionType("SUPPLEMENT_EVIDENCE")
                .targetNode("collect_sources")
                .affectedScope("CURRENT_NODE_AND_DOWNSTREAM")
                .priority("HIGH")
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .suggestedQueries(List.of("Notion AI pricing official"))
                .build()
                .normalized();

        DecisionPolicyResult result = service.evaluate(
                decision,
                DecisionPolicyRuleSet.builder().maxAutoDecisions(2).build(),
                0,
                "RUNNING",
                "SUCCESS");

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getNormalizedAction()).isEqualTo("CREATE_SUPPLEMENT_BRANCH");
        assertThat(result.getPolicyRuleRefs()).contains(
                "allowedDecisionTypes",
                "requireSourceUrlsOrEvidenceGap",
                "maxSearchQueriesPerDecision");
    }

    @Test
    void shouldBlockUnknownDecisionType() {
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-002")
                .decisionType("FREE_FORM_DAG")
                .actionType("SUPPLEMENT_EVIDENCE")
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build()
                .normalized();

        DecisionPolicyResult result = service.evaluate(
                decision,
                DecisionPolicyRuleSet.builder().build(),
                0,
                "RUNNING",
                "SUCCESS");

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getBlockedReasons()).contains("decisionType 不在允许列表：FREE_FORM_DAG");
    }

    @Test
    void shouldKeepLegacyRerunOutsideLlmMatrix() {
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-003")
                .decisionType("RERUN_NODE")
                .actionType("RERUN_NODE")
                .targetNode("extract_schema")
                .affectedScope("CURRENT_NODE_AND_DOWNSTREAM")
                .priority("MEDIUM")
                .decisionOrigin(OrchestrationDecisionOrigin.LEGACY_ADAPTER)
                .sourceUrls(List.of("https://example.com/source"))
                .evidenceState(EvidenceState.FULL_SOURCE)
                .build()
                .normalized();

        DecisionPolicyResult result = service.evaluate(
                decision,
                DecisionPolicyRuleSet.builder().build(),
                0,
                "RUNNING",
                "SUCCESS");

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.isRequiresConfirmation()).isTrue();
        assertThat(result.getRiskLevel()).isEqualTo("HIGH");
        assertThat(result.getNormalizedAction()).isEqualTo("CREATE_RERUN_BRANCH");
        assertThat(result.getDecisionOrigin()).isEqualTo(OrchestrationDecisionOrigin.LEGACY_ADAPTER);
        assertThat(result.getDecisionContract()).isEqualTo("LEGACY_RULE_SET");
        assertThat(result.getPolicyRuleRefs()).contains("rerun_downstream_requires_confirmation");
    }

    @Test
    void shouldExposeLlmDecisionContractWithoutApplyingLegacyOrigin() {
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-llm-primary")
                .decisionOrigin(OrchestrationDecisionOrigin.LLM_PRIMARY)
                .triggerNodeName("quality_check_final")
                .decisionType("NO_ACTION")
                .actionType("NO_ACTION")
                .targetNode("quality_check_final")
                .affectedScope("CURRENT_NODE_ONLY")
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build()
                .normalized();

        DecisionPolicyResult result = service.evaluate(
                decision,
                DecisionPolicyRuleSet.builder().build(),
                0,
                "RUNNING",
                "SUCCESS");

        assertThat(result.getDecisionOrigin()).isEqualTo(OrchestrationDecisionOrigin.LLM_PRIMARY);
        assertThat(result.getDecisionContract()).isEqualTo("LLM_ACTION_MATRIX");
    }

    @Test
    void shouldAllowValidLlmSupplementDecisionThroughMatrix() {
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-llm-supplement")
                .decisionOrigin(OrchestrationDecisionOrigin.LLM_PRIMARY)
                .triggerNodeName("quality_check_final")
                .decisionType("APPEND_DYNAMIC_BRANCH")
                .actionType("SUPPLEMENT_EVIDENCE")
                .targetNode("collect_sources")
                .affectedScope("CURRENT_NODE_AND_DOWNSTREAM")
                .reason("补充定价来源")
                .suggestedQueries(List.of("Notion pricing official"))
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build();

        DecisionPolicyResult result = evaluate(decision, DecisionPolicyRuleSet.builder().build());

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getNormalizedAction()).isEqualTo("CREATE_SUPPLEMENT_BRANCH");
        assertThat(result.getPolicyRuleRefs()).contains("llmDecisionActionMatrix", "LLM_SUPPLEMENT_EVIDENCE");
        assertThat(result.getTavilyQueryMode()).isEqualTo("EVIDENCE_REPAIR");
    }

    @Test
    void shouldAllowValidLlmManualReviewThroughDefaultDynamicActionWhitelist() {
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-llm-manual")
                .decisionOrigin(OrchestrationDecisionOrigin.LLM_PRIMARY)
                .triggerNodeName("quality_check_final")
                .decisionType("WAIT_FOR_HUMAN")
                .actionType("MANUAL_REVIEW")
                .targetNode("quality_check_final")
                .affectedScope("CURRENT_NODE_ONLY")
                .sourceUrls(List.of("https://example.com/evidence"))
                .evidenceState(EvidenceState.FULL_SOURCE)
                .build();

        DecisionPolicyResult result = evaluate(decision, DecisionPolicyRuleSet.builder().build());

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getNormalizedAction()).isEqualTo("MANUAL_ONLY");
        assertThat(result.getPolicyRuleRefs()).contains("LLM_MANUAL_REVIEW");
    }

    @Test
    void shouldAllowValidLlmNoActionThroughDefaultDynamicActionWhitelist() {
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-llm-no-action")
                .decisionOrigin(OrchestrationDecisionOrigin.LLM_PRIMARY)
                .triggerNodeName("quality_check_final")
                .decisionType("NO_ACTION")
                .actionType("NO_ACTION")
                .targetNode("quality_check_final")
                .affectedScope("CURRENT_NODE_ONLY")
                .sourceUrls(List.of("https://example.com/evidence"))
                .evidenceState(EvidenceState.FULL_SOURCE)
                .build();

        DecisionPolicyResult result = evaluate(decision, DecisionPolicyRuleSet.builder().build());

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getNormalizedAction()).isEqualTo("NO_ACTION");
        assertThat(result.getPolicyRuleRefs()).contains("LLM_NO_ACTION");
    }

    @Test
    void shouldBlockValidLlmManualReviewWhenCustomRuleSetDisallowsManualOnly() {
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-llm-manual-blocked")
                .decisionOrigin(OrchestrationDecisionOrigin.LLM_PRIMARY)
                .triggerNodeName("quality_check_final")
                .decisionType("WAIT_FOR_HUMAN")
                .actionType("MANUAL_REVIEW")
                .targetNode("quality_check_final")
                .affectedScope("CURRENT_NODE_ONLY")
                .sourceUrls(List.of("https://example.com/evidence"))
                .evidenceState(EvidenceState.FULL_SOURCE)
                .build();
        DecisionPolicyRuleSet ruleSet = DecisionPolicyRuleSet.builder()
                .allowedDynamicActions(List.of(
                        "CREATE_SUPPLEMENT_BRANCH",
                        "CREATE_RERUN_BRANCH",
                        "CREATE_REWRITE_BRANCH",
                        "NO_ACTION"))
                .build();

        DecisionPolicyResult result = evaluate(decision, ruleSet);

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getBlockedReasons()).contains("normalizedAction 不在允许列表：MANUAL_ONLY");
    }

    @Test
    void shouldBlockInvalidLlmPairBeforeDerivingExecutableAction() {
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-llm-invalid-pair")
                .decisionOrigin(OrchestrationDecisionOrigin.LLM_PRIMARY)
                .triggerNodeName("quality_check_final")
                .decisionType("REWRITE_ONLY")
                .actionType("SUPPLEMENT_EVIDENCE")
                .targetNode("rewrite_report")
                .affectedScope("CURRENT_NODE_ONLY")
                .sourceUrls(List.of("https://example.com/evidence"))
                .evidenceState(EvidenceState.FULL_SOURCE)
                .build();

        DecisionPolicyResult result = evaluate(decision, DecisionPolicyRuleSet.builder().build());

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getNormalizedAction()).isEqualTo("MANUAL_ONLY");
        assertThat(result.getBlockedReasons())
                .contains("INVALID_DECISION_ACTION_PAIR: REWRITE_ONLY 不允许搭配 SUPPLEMENT_EVIDENCE");
        assertThat(result.getPolicyRuleRefs()).contains("llmDecisionActionMatrix");
        assertThat(result.getPreferredSearchProvider()).isNull();
    }

    @Test
    void shouldBlockLlmRewriteWhenSourceIsMissing() {
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-llm-rewrite-missing-source")
                .decisionOrigin(OrchestrationDecisionOrigin.LLM_PRIMARY)
                .triggerNodeName("quality_check_final")
                .decisionType("REWRITE_ONLY")
                .actionType("REWRITE_SECTION")
                .targetNode("rewrite_report")
                .affectedScope("CURRENT_NODE_ONLY")
                .priority("HIGH")
                .confidence(1.0d)
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build();

        DecisionPolicyResult result = evaluate(decision, DecisionPolicyRuleSet.builder().build());

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getNormalizedAction()).isEqualTo("CREATE_REWRITE_BRANCH");
        assertThat(result.getBlockedReasons())
                .contains("MISSING_SOURCE_FOR_LLM_REWRITE: LLM rewrite 缺少可追溯 sourceUrls");
        assertThat(result.getPolicyRuleRefs()).contains("LLM_REWRITE_SECTION");
    }

    @Test
    void shouldApplySameMatrixToLlmShadow() {
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-llm-shadow-invalid")
                .decisionOrigin(OrchestrationDecisionOrigin.LLM_SHADOW)
                .triggerNodeName("quality_check_final")
                .decisionType("APPEND_DYNAMIC_BRANCH")
                .actionType("REWRITE_CLAIM")
                .targetNode("rewrite_report")
                .affectedScope("CURRENT_NODE_ONLY")
                .sourceUrls(List.of("https://example.com/evidence"))
                .evidenceState(EvidenceState.FULL_SOURCE)
                .build();

        DecisionPolicyResult result = evaluate(decision, DecisionPolicyRuleSet.builder().build());

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getDecisionContract()).isEqualTo("LLM_ACTION_MATRIX");
        assertThat(result.getBlockedReasons())
                .contains("INVALID_DECISION_ACTION_PAIR: APPEND_DYNAMIC_BRANCH 不允许搭配 REWRITE_CLAIM");
    }

    @Test
    void shouldDefaultNullDecisionToLegacyAdapterContract() {
        DecisionPolicyResult result = service.evaluate(
                null,
                DecisionPolicyRuleSet.builder().build(),
                0,
                "RUNNING",
                "SUCCESS");

        assertThat(result.getDecisionOrigin()).isEqualTo(OrchestrationDecisionOrigin.defaultOrigin());
        assertThat(result.getDecisionContract()).isEqualTo("LEGACY_RULE_SET");
        assertThat(result.getNormalizedAction()).isEqualTo("MANUAL_ONLY");
    }

    @Test
    void shouldBlockWhenAutoDecisionLimitIsReached() {
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-004")
                .decisionType("APPEND_DYNAMIC_BRANCH")
                .actionType("SUPPLEMENT_EVIDENCE")
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build()
                .normalized();

        DecisionPolicyResult result = service.evaluate(
                decision,
                DecisionPolicyRuleSet.builder().maxAutoDecisions(1).build(),
                1,
                "RUNNING",
                "SUCCESS");

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getBlockedReasons()).contains("自动编排次数已达到上限：1/1");
    }

    @Test
    void shouldBlockLlmRewriteWhenAutoDecisionLimitIsReached() {
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-llm-rewrite-limit")
                .decisionOrigin(OrchestrationDecisionOrigin.LLM_PRIMARY)
                .triggerNodeName("quality_check_final")
                .decisionType("REWRITE_ONLY")
                .actionType("REWRITE_CLAIM")
                .targetNode("rewrite_report")
                .affectedScope("CURRENT_NODE_ONLY")
                .sourceUrls(List.of("https://example.com/evidence"))
                .evidenceState(EvidenceState.FULL_SOURCE)
                .build();

        DecisionPolicyResult result = service.evaluate(
                decision,
                DecisionPolicyRuleSet.builder().maxAutoDecisions(1).build(),
                1,
                "RUNNING",
                "SUCCESS");

        assertThat(result.getNormalizedAction()).isEqualTo("CREATE_REWRITE_BRANCH");
        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getBlockedReasons()).contains("自动编排次数已达到上限：1/1");
    }

    @Test
    void shouldAllowNoActionAndManualOnlyAfterAutoDecisionLimit() {
        OrchestrationDecision noAction = OrchestrationDecision.builder()
                .decisionId("od-limit-stop")
                .decisionOrigin(OrchestrationDecisionOrigin.LLM_PRIMARY)
                .triggerNodeName("quality_check_final")
                .decisionType("NO_ACTION")
                .actionType("NO_ACTION")
                .targetNode("quality_check_final")
                .affectedScope("CURRENT_NODE_ONLY")
                .sourceUrls(List.of("https://example.com/evidence"))
                .evidenceState(EvidenceState.FULL_SOURCE)
                .build();
        OrchestrationDecision manual = noAction.toBuilder()
                .decisionId("od-limit-manual")
                .decisionType("WAIT_FOR_HUMAN")
                .actionType("MANUAL_REVIEW")
                .build();
        DecisionPolicyRuleSet rules = DecisionPolicyRuleSet.builder().maxAutoDecisions(1).build();

        DecisionPolicyResult noActionResult = service.evaluate(noAction, rules, 1, "RUNNING", "SUCCESS");
        DecisionPolicyResult manualResult = service.evaluate(manual, rules, 1, "RUNNING", "SUCCESS");

        assertThat(noActionResult.getNormalizedAction()).isEqualTo("NO_ACTION");
        assertThat(noActionResult.isAllowed()).isTrue();
        assertThat(manualResult.getNormalizedAction()).isEqualTo("MANUAL_ONLY");
        assertThat(manualResult.isAllowed()).isTrue();
        assertThat(noActionResult.getBlockedReasons()).noneMatch(reason -> reason.startsWith("自动编排次数已达到上限"));
        assertThat(manualResult.getBlockedReasons()).noneMatch(reason -> reason.startsWith("自动编排次数已达到上限"));
    }

    @Test
    void shouldElevateRewriteOnlyDecisionWhenSourceIsMissing() {
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-005")
                .decisionType("REWRITE_ONLY")
                .actionType("REWRITE_SECTION")
                .targetNode("rewrite_report")
                .priority("MEDIUM")
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build()
                .normalized();

        DecisionPolicyResult result = service.evaluate(
                decision,
                DecisionPolicyRuleSet.builder().build(),
                0,
                "RUNNING",
                "SUCCESS");

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.isRequiresConfirmation()).isTrue();
        assertThat(result.getRiskLevel()).isEqualTo("HIGH");
        assertThat(result.getPolicyRuleRefs()).contains("missing_source_requires_supplement");
    }

    @Test
    void shouldBlockWhenSuggestedQueriesExceedConfiguredLimit() {
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-006")
                .decisionType("APPEND_DYNAMIC_BRANCH")
                .actionType("SUPPLEMENT_EVIDENCE")
                .targetNode("collect_sources")
                .priority("HIGH")
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .suggestedQueries(List.of("q1", "q2", "q3", "q4", "q5", "q6"))
                .build()
                .normalized();

        DecisionPolicyResult result = service.evaluate(
                decision,
                DecisionPolicyRuleSet.builder().maxSearchQueriesPerDecision(5).build(),
                0,
                "RUNNING",
                "SUCCESS");

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getBlockedReasons()).contains("搜索补证 query 数量超过上限：6/5");
    }
    @Test
    void shouldMarkSupplementEvidenceAsTavilyEvidenceRepairForOfficialGap() {
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-007")
                .decisionType("APPEND_DYNAMIC_BRANCH")
                .actionType("SUPPLEMENT_EVIDENCE")
                .targetNode("collect_sources")
                .targetSection("pricing")
                .reason("补充抖音开放平台官方定价与规则证据")
                .sourceUrls(List.of("https://open.douyin.com/platform/pricing"))
                .evidenceState(EvidenceState.PARTIAL_SOURCE)
                .suggestedQueries(List.of("抖音 开放平台 定价 官方"))
                .build()
                .normalized();

        DecisionPolicyResult result = service.evaluate(
                decision,
                DecisionPolicyRuleSet.builder().build(),
                0,
                "RUNNING",
                "SUCCESS");

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getPreferredSearchProvider()).isEqualTo("tavily");
        assertThat(result.getTavilyQueryMode()).isEqualTo("EVIDENCE_REPAIR");
        assertThat(result.getIncludeDomainPolicy()).isEqualTo("NARROW_OFFICIAL");
        assertThat(result.getSuggestedQueries()).containsExactly("抖音 开放平台 定价 官方");
        assertThat(result.getPreferredDomains()).containsExactly("open.douyin.com");
        assertThat(result.getIncludeDomains()).containsExactly("open.douyin.com");
    }

    @Test
    void shouldUseDecisionReasonAsRepairQueryWhenSuggestedQueriesAreMissing() {
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-008")
                .decisionType("APPEND_DYNAMIC_BRANCH")
                .actionType("SUPPLEMENT_EVIDENCE")
                .targetNode("collect_sources")
                .targetSection("pricing")
                .reason("unsupported claim: 抖音推荐算法支持跨域投放")
                .sourceUrls(List.of("https://open.douyin.com/docs"))
                .evidenceState(EvidenceState.PARTIAL_SOURCE)
                .suggestedQueries(List.of())
                .build()
                .normalized();

        DecisionPolicyResult result = service.evaluate(
                decision,
                DecisionPolicyRuleSet.builder().build(),
                0,
                "RUNNING",
                "SUCCESS");

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getSuggestedQueries())
                .containsExactly("unsupported claim: 抖音推荐算法支持跨域投放");
        assertThat(result.getTavilyQueryMode()).isEqualTo("EVIDENCE_REPAIR");
    }

    @Test
    void shouldKeepLegacyDomainHintDiscoveryManualOnly() {
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .decisionId("od-009")
                .decisionType("APPEND_DYNAMIC_BRANCH")
                .actionType("DOMAIN_HINT_DISCOVERY")
                .targetNode("collect_sources")
                .reason("需要先补齐官方域名线索")
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build()
                .normalized();

        DecisionPolicyResult result = service.evaluate(
                decision,
                DecisionPolicyRuleSet.builder().build(),
                0,
                "RUNNING",
                "SUCCESS");

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getNormalizedAction()).isEqualTo("MANUAL_ONLY");
        assertThat(result.getPreferredSearchProvider()).isNull();
    }

    private DecisionPolicyResult evaluate(OrchestrationDecision decision, DecisionPolicyRuleSet ruleSet) {
        return service.evaluate(decision, ruleSet, 0, "RUNNING", "SUCCESS");
    }
}
