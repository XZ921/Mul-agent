package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.workflow.contract.QualityDiagnosis;
import cn.bugstack.competitoragent.workflow.contract.RevisionDirective;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedOrchestratorDecisionBrainTest {

    private final RuleBasedOrchestratorDecisionBrain brain =
            new RuleBasedOrchestratorDecisionBrain(new OrchestrationDecisionAdapter());

    @Test
    void shouldPreserveFinalReviewRuleDecisions() {
        OrchestrationDecision passed = decide(OrchestrationContext.builder()
                .taskId(70L)
                .triggerNodeName("quality_check_final")
                .passed(true)
                .sourceUrls(List.of("https://example.com/review"))
                .evidenceState(EvidenceState.FULL_SOURCE)
                .build()).get(0);
        OrchestrationDecision human = decide(OrchestrationContext.builder()
                .taskId(71L)
                .triggerNodeName("quality_check_final")
                .passed(false)
                .requiresHumanIntervention(true)
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build()).get(0);
        OrchestrationDecision blocking = decide(OrchestrationContext.builder()
                .taskId(72L)
                .triggerNodeName("quality_check_final")
                .passed(false)
                .diagnoses(List.of(QualityDiagnosis.builder()
                        .severity("ERROR")
                        .level("BLOCKER")
                        .build()))
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build()).get(0);
        OrchestrationDecision nonBlocking = decide(OrchestrationContext.builder()
                .taskId(73L)
                .triggerNodeName("quality_check_final")
                .passed(false)
                .diagnoses(List.of(QualityDiagnosis.builder().severity("WARN").build()))
                .sourceUrls(List.of("https://example.com/review"))
                .evidenceState(EvidenceState.FULL_SOURCE)
                .build()).get(0);
        OrchestrationDecision legacy = decide(OrchestrationContext.builder()
                .taskId(74L)
                .triggerNodeName("quality_check_final")
                .passed(false)
                .legacyRevisionDirectives(List.of(RevisionDirective.builder()
                        .category("EVIDENCE_GAP")
                        .summary("补充证据")
                        .sourceUrls(List.of())
                        .build()))
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build()).get(0);

        assertThat(passed.getDecisionType()).isEqualTo("NO_ACTION");
        assertThat(human.getDecisionType()).isEqualTo("WAIT_FOR_HUMAN");
        assertThat(blocking.getDecisionType()).isEqualTo("WAIT_FOR_HUMAN");
        assertThat(nonBlocking.getDecisionType()).isEqualTo("NO_ACTION");
        assertThat(legacy.getActionType()).isEqualTo("SUPPLEMENT_EVIDENCE");
        assertThat(List.of(passed, human, blocking, nonBlocking))
                .extracting(OrchestrationDecision::getDecisionOrigin)
                .containsOnly(OrchestrationDecisionOrigin.RULE_ONLY);
        assertThat(legacy.getDecisionOrigin()).isEqualTo(OrchestrationDecisionOrigin.LEGACY_ADAPTER);
    }

    @Test
    void shouldPreserveExtractorRulePriority() {
        AgentSuggestion sourceBacked = suggestion(
                "as-extractor-source",
                "EVIDENCE_GAP",
                "有来源字段缺口",
                "HIGH",
                List.of("https://example.com/source"),
                EvidenceState.FULL_SOURCE,
                "collect_sources");
        AgentSuggestion missingSource = suggestion(
                "as-extractor-missing",
                "EVIDENCE_GAP",
                "无来源字段缺口",
                "HIGH",
                List.of(),
                EvidenceState.MISSING_SOURCE,
                "collect_sources");
        AgentSuggestion blocking = suggestion(
                "as-extractor-blocking",
                "SCHEMA_CONFLICT",
                "字段结构冲突",
                "ERROR",
                List.of("https://example.com/schema"),
                EvidenceState.FULL_SOURCE,
                "extract_schema");

        OrchestrationDecision supplement = decide(suggestionContext(
                75L, "extract_schema", List.of(sourceBacked), sourceBacked.getSourceUrls(), EvidenceState.FULL_SOURCE)).get(0);
        OrchestrationDecision waitForMissing = decide(suggestionContext(
                76L, "extract_schema", List.of(missingSource), List.of(), EvidenceState.MISSING_SOURCE)).get(0);
        OrchestrationDecision waitForBlocking = decide(suggestionContext(
                77L,
                "extract_schema",
                List.of(sourceBacked, blocking),
                List.of("https://example.com/source", "https://example.com/schema"),
                EvidenceState.FULL_SOURCE)).get(0);

        assertThat(supplement.getActionType()).isEqualTo("SUPPLEMENT_EVIDENCE");
        assertThat(waitForMissing.getDecisionType()).isEqualTo("WAIT_FOR_HUMAN");
        assertThat(waitForBlocking.getDecisionType()).isEqualTo("WAIT_FOR_HUMAN");
        assertThat(waitForBlocking.getReason()).contains("字段结构冲突");
    }

    @Test
    void shouldPreserveAnalyzerRulePriority() {
        AgentSuggestion sourceBacked = suggestion(
                "as-analyzer-source",
                "ANALYSIS_GAP",
                "有来源分析缺口",
                "HIGH",
                List.of("https://example.com/analysis"),
                EvidenceState.PARTIAL_SOURCE,
                "collect_sources");
        AgentSuggestion missingSource = suggestion(
                "as-analyzer-missing",
                "ANALYSIS_GAP",
                "无来源分析缺口",
                "HIGH",
                List.of(),
                EvidenceState.MISSING_SOURCE,
                "collect_sources");

        OrchestrationDecision supplement = decide(suggestionContext(
                78L, "analyze_competitors", List.of(sourceBacked), sourceBacked.getSourceUrls(), EvidenceState.PARTIAL_SOURCE)).get(0);
        OrchestrationDecision waitForMissing = decide(suggestionContext(
                79L, "analyze_competitors", List.of(missingSource), List.of(), EvidenceState.MISSING_SOURCE)).get(0);
        OrchestrationDecision waitAcrossList = decide(suggestionContext(
                80L,
                "analyze_competitors",
                List.of(sourceBacked, missingSource),
                sourceBacked.getSourceUrls(),
                EvidenceState.PARTIAL_SOURCE)).get(0);

        assertThat(supplement.getActionType()).isEqualTo("SUPPLEMENT_EVIDENCE");
        assertThat(waitForMissing.getDecisionType()).isEqualTo("WAIT_FOR_HUMAN");
        assertThat(waitAcrossList.getDecisionType()).isEqualTo("WAIT_FOR_HUMAN");
        assertThat(waitAcrossList.getReason()).contains("缺少 sourceUrls");
    }

    @Test
    void shouldPreserveWriterRulesAndAlias() {
        AgentSuggestion sourceBacked = suggestion(
                "as-writer-source",
                "CITATION_GAP",
                "改写定价引用",
                "HIGH",
                List.of("https://example.com/pricing"),
                EvidenceState.PARTIAL_SOURCE,
                "rewrite_report");
        sourceBacked.setTargetSection("pricing");
        AgentSuggestion missingSource = suggestion(
                "as-writer-missing",
                "CITATION_GAP",
                "报告缺少引用",
                "ERROR",
                List.of(),
                EvidenceState.MISSING_SOURCE,
                "collect_sources");

        OrchestrationDecision rewrite = decide(suggestionContext(
                81L, "write_report", List.of(sourceBacked), sourceBacked.getSourceUrls(), EvidenceState.PARTIAL_SOURCE)).get(0);
        OrchestrationDecision aliasRewrite = decide(suggestionContext(
                82L, "rewrite_report", List.of(sourceBacked), sourceBacked.getSourceUrls(), EvidenceState.PARTIAL_SOURCE)).get(0);
        OrchestrationDecision wait = decide(suggestionContext(
                83L, "write_report", List.of(missingSource), List.of(), EvidenceState.MISSING_SOURCE)).get(0);

        assertThat(rewrite.getActionType()).isEqualTo("REWRITE_SECTION");
        assertThat(rewrite.getTargetSection()).isEqualTo("pricing");
        assertThat(aliasRewrite.getActionType()).isEqualTo("REWRITE_SECTION");
        assertThat(wait.getDecisionType()).isEqualTo("WAIT_FOR_HUMAN");
    }

    @Test
    void shouldPreserveCitationRulesAndAlias() {
        AgentSuggestion rewrite = suggestion(
                "as-citation-rewrite",
                "CITATION_VERIFICATION_GAP",
                "弱引用需要改写",
                "HIGH",
                List.of("https://example.com/citation"),
                EvidenceState.PARTIAL_SOURCE,
                "rewrite_report");
        AgentSuggestion repair = suggestion(
                "as-citation-repair",
                "CITATION_VERIFICATION_GAP",
                "需要重新补证",
                "HIGH",
                List.of("https://example.com/citation"),
                EvidenceState.PARTIAL_SOURCE,
                "collect_sources");
        AgentSuggestion missing = suggestion(
                "as-citation-missing",
                "CITATION_VERIFICATION_GAP",
                "引用缺少来源",
                "ERROR",
                List.of(),
                EvidenceState.MISSING_SOURCE,
                "rewrite_report");

        OrchestrationDecision rewriteDecision = decide(suggestionContext(
                84L, "citation_check", List.of(rewrite), rewrite.getSourceUrls(), EvidenceState.PARTIAL_SOURCE)).get(0);
        OrchestrationDecision repairDecision = decide(suggestionContext(
                85L, "citation_check", List.of(repair), repair.getSourceUrls(), EvidenceState.PARTIAL_SOURCE)).get(0);
        OrchestrationDecision aliasDecision = decide(suggestionContext(
                86L, "citation_check_revision", List.of(rewrite), rewrite.getSourceUrls(), EvidenceState.PARTIAL_SOURCE)).get(0);
        OrchestrationDecision waitDecision = decide(suggestionContext(
                87L, "citation_check", List.of(missing), List.of(), EvidenceState.MISSING_SOURCE)).get(0);

        assertThat(rewriteDecision.getActionType()).isEqualTo("REWRITE_CLAIM");
        assertThat(repairDecision.getActionType()).isEqualTo("SUPPLEMENT_EVIDENCE");
        assertThat(aliasDecision.getActionType()).isEqualTo("REWRITE_CLAIM");
        assertThat(waitDecision.getDecisionType()).isEqualTo("WAIT_FOR_HUMAN");
    }

    @Test
    void shouldPreserveLegacyDirectiveOrderIdsAndOrigin() {
        List<OrchestrationDecision> decisions = decide(OrchestrationContext.builder()
                .taskId(88L)
                .triggerNodeName("quality_check_final")
                .passed(false)
                .legacyRevisionDirectives(List.of(
                        RevisionDirective.builder()
                                .category("EVIDENCE_GAP")
                                .actionType("SUPPLEMENT_EVIDENCE")
                                .summary("补证")
                                .build(),
                        RevisionDirective.builder()
                                .category("EXPRESSION_ISSUE")
                                .actionType("REWRITE_SECTION")
                                .summary("改写")
                                .build()))
                .build());

        assertThat(decisions)
                .extracting(OrchestrationDecision::getDecisionId)
                .containsExactly("od-88-quality_check_final-1", "od-88-quality_check_final-2");
        assertThat(decisions)
                .extracting(OrchestrationDecision::getActionType)
                .containsExactly("SUPPLEMENT_EVIDENCE", "REWRITE_SECTION");
        assertThat(decisions)
                .extracting(OrchestrationDecision::getDecisionOrigin)
                .containsOnly(OrchestrationDecisionOrigin.LEGACY_ADAPTER);
    }

    @Test
    void shouldPreserveUnknownTriggerAndPassedHumanParity() {
        OrchestrationDecision unknown = decide(OrchestrationContext.builder()
                .taskId(89L)
                .triggerNodeName("quality_check_draft")
                .sourceUrls(List.of("https://example.com/draft"))
                .evidenceState(EvidenceState.FULL_SOURCE)
                .build()).get(0);
        OrchestrationDecision passedAndHuman = decide(OrchestrationContext.builder()
                .taskId(90L)
                .triggerNodeName("quality_check_final")
                .passed(true)
                .requiresHumanIntervention(true)
                .sourceUrls(List.of("https://example.com/review"))
                .evidenceState(EvidenceState.FULL_SOURCE)
                .build()).get(0);

        assertThat(unknown.getDecisionType()).isEqualTo("NO_ACTION");
        assertThat(unknown.getDecisionOrigin()).isEqualTo(OrchestrationDecisionOrigin.RULE_ONLY);
        assertThat(unknown.getTargetNode()).isEqualTo("quality_check_draft");
        assertThat(passedAndHuman.getDecisionType()).isEqualTo("NO_ACTION");
    }

    @Test
    void shouldPreserveCompleteDecisionAuditShape() {
        AgentSuggestion suggestion = AgentSuggestion.builder()
                .suggestionId("as-task-91-analyze_competitors-1")
                .suggestionType("ANALYSIS_GAP")
                .targetSection("pricing")
                .summary("补充定价对比证据")
                .severity("HIGH")
                .confidence(0.42d)
                .suggestedQueries(List.of("pricing comparison official"))
                .sourceUrls(List.of("https://example.com/pricing"))
                .evidenceState(EvidenceState.PARTIAL_SOURCE)
                .suggestedTargetNode("collect_sources")
                .build();

        OrchestrationDecision decision = decide(suggestionContext(
                91L,
                "analyze_competitors",
                List.of(suggestion),
                suggestion.getSourceUrls(),
                EvidenceState.PARTIAL_SOURCE)).get(0);

        assertThat(decision.getDecisionId()).isEqualTo("od-91-analyze_competitors-suggestion-1");
        assertThat(decision.getDecisionOrigin()).isEqualTo(OrchestrationDecisionOrigin.RULE_ONLY);
        assertThat(decision.getDecisionType()).isEqualTo("APPEND_DYNAMIC_BRANCH");
        assertThat(decision.getActionType()).isEqualTo("SUPPLEMENT_EVIDENCE");
        assertThat(decision.getTargetNode()).isEqualTo("collect_sources");
        assertThat(decision.getAffectedScope()).isEqualTo("CURRENT_NODE_AND_DOWNSTREAM");
        assertThat(decision.getPriority()).isEqualTo("HIGH");
        assertThat(decision.getTargetSection()).isEqualTo("pricing");
        assertThat(decision.getReason()).isEqualTo("补充定价对比证据");
        assertThat(decision.getConfidence()).isEqualTo(0.42d);
        assertThat(decision.getSuggestedQueries()).containsExactly("pricing comparison official");
        assertThat(decision.getInputRefs())
                .containsEntry("qualityDiagnosisIds", List.of())
                .containsEntry("agentSuggestionIds", List.of("as-task-91-analyze_competitors-1"))
                .containsEntry("triggerNodeName", "analyze_competitors");
        assertThat(decision.getSourceUrls()).containsExactly("https://example.com/pricing");
        assertThat(decision.getEvidenceState()).isEqualTo(EvidenceState.PARTIAL_SOURCE);
    }

    private List<OrchestrationDecision> decide(OrchestrationContext rawContext) {
        return brain.decide(rawContext.normalized());
    }

    private OrchestrationContext suggestionContext(Long taskId,
                                                   String triggerNodeName,
                                                   List<AgentSuggestion> suggestions,
                                                   List<String> sourceUrls,
                                                   EvidenceState evidenceState) {
        return OrchestrationContext.builder()
                .taskId(taskId)
                .triggerNodeName(triggerNodeName)
                .agentSuggestions(suggestions)
                .sourceUrls(sourceUrls)
                .evidenceState(evidenceState)
                .build();
    }

    private AgentSuggestion suggestion(String suggestionId,
                                       String suggestionType,
                                       String summary,
                                       String severity,
                                       List<String> sourceUrls,
                                       EvidenceState evidenceState,
                                       String suggestedTargetNode) {
        return AgentSuggestion.builder()
                .suggestionId(suggestionId)
                .suggestionType(suggestionType)
                .summary(summary)
                .severity(severity)
                .confidence(0.75d)
                .sourceUrls(sourceUrls)
                .evidenceState(evidenceState)
                .suggestedTargetNode(suggestedTargetNode)
                .build();
    }
}
