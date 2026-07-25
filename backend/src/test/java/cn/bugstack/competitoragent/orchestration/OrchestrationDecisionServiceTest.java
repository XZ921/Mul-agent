package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.workflow.contract.QualityDiagnosis;
import cn.bugstack.competitoragent.workflow.contract.RevisionDirective;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrchestrationDecisionServiceTest {

    private final OrchestrationDecisionService service =
            new OrchestrationDecisionService(new RuleBasedOrchestratorDecisionBrain(
                    new OrchestrationDecisionAdapter()));

    @Test
    void shouldNotInvokeRuleBrainWhenRawContextIsNull() {
        RuleBasedOrchestratorDecisionBrain ruleBrain = mock(RuleBasedOrchestratorDecisionBrain.class);
        OrchestrationDecisionService delegationService = new OrchestrationDecisionService(ruleBrain);

        List<OrchestrationDecision> decisions = delegationService.decide(null);

        assertThat(decisions).isEmpty();
        verifyNoInteractions(ruleBrain);
    }

    @Test
    void shouldNormalizeRawContextBeforeDelegatingToRuleBrain() {
        RuleBasedOrchestratorDecisionBrain ruleBrain = mock(RuleBasedOrchestratorDecisionBrain.class);
        OrchestrationDecisionService delegationService = new OrchestrationDecisionService(ruleBrain);
        OrchestrationContext rawContext = OrchestrationContext.builder()
                .taskId(49L)
                .triggerNodeName("  quality_check_final  ")
                .currentDecisionCount(-1)
                .diagnoses(null)
                .legacyRevisionDirectives(null)
                .agentSuggestions(null)
                .sourceUrls(List.of(" https://example.com/source ", "https://example.com/source"))
                .build();
        when(ruleBrain.decide(org.mockito.ArgumentMatchers.any(OrchestrationContext.class)))
                .thenReturn(List.of());

        delegationService.decide(rawContext);

        ArgumentCaptor<OrchestrationContext> contextCaptor =
                ArgumentCaptor.forClass(OrchestrationContext.class);
        verify(ruleBrain).decide(contextCaptor.capture());
        OrchestrationContext normalizedContext = contextCaptor.getValue();
        assertThat(normalizedContext).isNotSameAs(rawContext);
        assertThat(normalizedContext.getTriggerNodeName()).isEqualTo("quality_check_final");
        assertThat(normalizedContext.getCurrentDecisionCount()).isZero();
        assertThat(normalizedContext.getDiagnoses()).isEmpty();
        assertThat(normalizedContext.getLegacyRevisionDirectives()).isEmpty();
        assertThat(normalizedContext.getAgentSuggestions()).isEmpty();
        assertThat(normalizedContext.getSourceUrls()).containsExactly("https://example.com/source");
        assertThat(normalizedContext.getEvidenceState()).isEqualTo(EvidenceState.FULL_SOURCE);
    }

    @Test
    void shouldReturnRuleBrainOutputWithoutRewritingDecisionFields() {
        RuleBasedOrchestratorDecisionBrain ruleBrain = mock(RuleBasedOrchestratorDecisionBrain.class);
        OrchestrationDecisionService delegationService = new OrchestrationDecisionService(ruleBrain);
        OrchestrationDecision brainDecision = OrchestrationDecision.builder()
                .decisionId("brain-output-1")
                .taskId(48L)
                .triggerNodeName("quality_check_final")
                .decisionOrigin(OrchestrationDecisionOrigin.LEGACY_ADAPTER)
                .decisionType("REWRITE_ONLY")
                .actionType("REWRITE_SECTION")
                .targetNode("rewrite_report")
                .affectedScope("CURRENT_NODE_ONLY")
                .reason("保留 Brain 原始输出")
                .sourceUrls(List.of("https://example.com/source"))
                .build();
        List<OrchestrationDecision> brainOutput = List.of(brainDecision);
        when(ruleBrain.decide(org.mockito.ArgumentMatchers.any(OrchestrationContext.class)))
                .thenReturn(brainOutput);

        List<OrchestrationDecision> actual = delegationService.decide(OrchestrationContext.builder()
                .taskId(48L)
                .triggerNodeName("quality_check_final")
                .build());

        assertThat(actual).isSameAs(brainOutput);
        assertThat(actual.get(0)).isSameAs(brainDecision);
        assertThat(actual.get(0).getDecisionOrigin())
                .isEqualTo(OrchestrationDecisionOrigin.LEGACY_ADAPTER);
    }

    @Test
    void shouldWaitForHumanWhenFinalReviewEvidenceGapHasNoSources() {
        OrchestrationContext context = OrchestrationContext.builder()
                .taskId(50L)
                .triggerNodeName("quality_check_final")
                .reviewStage("final")
                .passed(false)
                .requiresHumanIntervention(false)
                .diagnoses(List.of(QualityDiagnosis.builder()
                        .type("missing_evidence")
                        .section("pricing")
                        .severity("ERROR")
                        .sourceUrls(List.of())
                        .repairSuggestion("补充官方定价页")
                        .build()))
                .legacyRevisionDirectives(List.of(RevisionDirective.builder()
                        .category("EVIDENCE_GAP")
                        .targetSection("pricing")
                        .summary("补充官方定价页")
                        .searchQueries(List.of("Notion AI pricing official"))
                        .sourceUrls(List.of())
                        .build()))
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build();

        List<OrchestrationDecision> decisions = service.decide(context);

        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).getDecisionType()).isEqualTo("WAIT_FOR_HUMAN");
        assertThat(decisions.get(0).getActionType()).isEqualTo("MANUAL_REVIEW");
        assertThat(decisions.get(0).getEvidenceState()).isEqualTo(EvidenceState.MISSING_SOURCE);
        assertThat(decisions.get(0).getDecisionOrigin()).isEqualTo(OrchestrationDecisionOrigin.RULE_ONLY);
        assertThat(decisions.get(0).isRequiresHumanIntervention()).isTrue();
        assertThat(decisions.get(0).isRequiresConfirmation()).isTrue();
        assertThat(decisions.get(0).getSourceUrls()).isEmpty();
        assertThat(decisions.get(0).getReason()).contains("缺少 sourceUrls");
    }

    @Test
    void shouldWaitForHumanWhenReviewRequiresHumanIntervention() {
        OrchestrationContext context = OrchestrationContext.builder()
                .taskId(50L)
                .triggerNodeName("quality_check_final")
                .reviewStage("final")
                .passed(false)
                .requiresHumanIntervention(true)
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build();

        List<OrchestrationDecision> decisions = service.decide(context);

        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).getDecisionType()).isEqualTo("WAIT_FOR_HUMAN");
        assertThat(decisions.get(0).isRequiresConfirmation()).isTrue();
    }

    @Test
    void shouldReturnNoActionWhenReviewPassed() {
        OrchestrationContext context = OrchestrationContext.builder()
                .taskId(50L)
                .triggerNodeName("quality_check_final")
                .reviewStage("final")
                .passed(true)
                .sourceUrls(List.of("https://example.com"))
                .evidenceState(EvidenceState.FULL_SOURCE)
                .build();

        List<OrchestrationDecision> decisions = service.decide(context);

        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).getDecisionType()).isEqualTo("NO_ACTION");
        assertThat(decisions.get(0).getDecisionOrigin()).isEqualTo(OrchestrationDecisionOrigin.RULE_ONLY);
    }

    @Test
    void shouldWaitForHumanWhenBlockingDiagnosisExistsWithoutDirectives() {
        OrchestrationContext context = OrchestrationContext.builder()
                .taskId(50L)
                .triggerNodeName("quality_check_final")
                .reviewStage("final")
                .passed(false)
                .requiresHumanIntervention(false)
                .diagnoses(List.of(QualityDiagnosis.builder()
                        .type("missing_evidence")
                        .section("pricing")
                        .severity("ERROR")
                        .build()))
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build();

        List<OrchestrationDecision> decisions = service.decide(context);

        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).getDecisionType()).isEqualTo("WAIT_FOR_HUMAN");
        assertThat(decisions.get(0).isRequiresHumanIntervention()).isTrue();
    }

    @Test
    void shouldStopWhenFailedReviewHasNoStructuredDirective() {
        OrchestrationContext context = OrchestrationContext.builder()
                .taskId(50L)
                .triggerNodeName("quality_check_final")
                .reviewStage("final")
                .passed(false)
                .requiresHumanIntervention(false)
                .diagnoses(List.of(QualityDiagnosis.builder()
                        .type("wording_issue")
                        .section("结论")
                        .severity("WARN")
                        .build()))
                .sourceUrls(List.of("https://example.com"))
                .evidenceState(EvidenceState.FULL_SOURCE)
                .build();

        List<OrchestrationDecision> decisions = service.decide(context);

        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).getDecisionType()).isEqualTo("WAIT_FOR_HUMAN");
        assertThat(decisions.get(0).getReason()).contains("没有完整的结构化修订诊断");
    }

    @Test
    void shouldCreateSupplementDecisionFromExtractorSuggestionWithSources() {
        AgentSuggestion suggestion = AgentSuggestion.builder()
                .suggestionId("as-task-50-extract_schema-1")
                .taskId(50L)
                .producerNodeName("extract_schema")
                .producerAgentType("EXTRACTOR")
                .suggestionType("EVIDENCE_GAP")
                .targetSection("pricing")
                .summary("pricing 字段缺少可验证来源")
                .severity("HIGH")
                .confidence(0.75d)
                .sourceUrls(List.of("https://www.notion.so/pricing"))
                .suggestedTargetNode("collect_sources")
                .build()
                .normalized();
        OrchestrationContext context = OrchestrationContext.builder()
                .taskId(50L)
                .triggerNodeName("extract_schema")
                .passed(false)
                .agentSuggestions(List.of(suggestion))
                .sourceUrls(List.of("https://www.notion.so/pricing"))
                .evidenceState(EvidenceState.FULL_SOURCE)
                .build();

        List<OrchestrationDecision> decisions = service.decide(context);

        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).getDecisionType()).isEqualTo("APPEND_DYNAMIC_BRANCH");
        assertThat(decisions.get(0).getActionType()).isEqualTo("SUPPLEMENT_EVIDENCE");
        assertThat(decisions.get(0).getInputRefs()).containsEntry("agentSuggestionIds", List.of("as-task-50-extract_schema-1"));
    }

    @Test
    void shouldWaitForHumanFromExtractorSuggestionWithoutSources() {
        AgentSuggestion suggestion = AgentSuggestion.builder()
                .suggestionId("as-task-50-extract_schema-1")
                .taskId(50L)
                .producerNodeName("extract_schema")
                .producerAgentType("EXTRACTOR")
                .suggestionType("EVIDENCE_GAP")
                .targetSection("pricing")
                .summary("pricing 字段缺少可验证来源")
                .severity("HIGH")
                .confidence(0.75d)
                .sourceUrls(List.of())
                .suggestedTargetNode("collect_sources")
                .build()
                .normalized();
        OrchestrationContext context = OrchestrationContext.builder()
                .taskId(50L)
                .triggerNodeName("extract_schema")
                .passed(false)
                .agentSuggestions(List.of(suggestion))
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build();

        List<OrchestrationDecision> decisions = service.decide(context);

        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).getDecisionType()).isEqualTo("WAIT_FOR_HUMAN");
        assertThat(decisions.get(0).isRequiresHumanIntervention()).isTrue();
    }

    @Test
    void shouldCreateSupplementDecisionFromAnalyzerSuggestionWithSources() {
        AgentSuggestion suggestion = AgentSuggestion.builder()
                .suggestionId("as-task-88-analyze_competitors-1")
                .taskId(88L)
                .producerNodeName("analyze_competitors")
                .producerAgentType("ANALYZER")
                .suggestionType("ANALYSIS_GAP")
                .targetSection("analysis")
                .summary("Analyzer 缺少 pricingComparison")
                .severity("HIGH")
                .confidence(0.35d)
                .sourceUrls(List.of("https://www.notion.so/product/ai"))
                .evidenceState(EvidenceState.PARTIAL_SOURCE)
                .suggestedQueries(List.of("pricingComparison official source"))
                .suggestedTargetNode("collect_sources")
                .build()
                .normalized();
        OrchestrationContext context = OrchestrationContext.builder()
                .taskId(88L)
                .triggerNodeName("analyze_competitors")
                .passed(false)
                .agentSuggestions(List.of(suggestion))
                .sourceUrls(List.of("https://www.notion.so/product/ai"))
                .evidenceState(EvidenceState.PARTIAL_SOURCE)
                .build();

        List<OrchestrationDecision> decisions = service.decide(context);

        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).getDecisionType()).isEqualTo("APPEND_DYNAMIC_BRANCH");
        assertThat(decisions.get(0).getActionType()).isEqualTo("SUPPLEMENT_EVIDENCE");
        assertThat(decisions.get(0).getInputRefs())
                .containsEntry("agentSuggestionIds", List.of("as-task-88-analyze_competitors-1"));
    }

    @Test
    void shouldWaitForHumanFromAnalyzerSuggestionWithoutSources() {
        AgentSuggestion suggestion = AgentSuggestion.builder()
                .suggestionId("as-task-88-analyze_competitors-1")
                .taskId(88L)
                .producerNodeName("analyze_competitors")
                .producerAgentType("ANALYZER")
                .suggestionType("ANALYSIS_GAP")
                .targetSection("analysis")
                .summary("Analyzer 缺少 pricingComparison")
                .severity("HIGH")
                .confidence(0.35d)
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .suggestedQueries(List.of("pricingComparison official source"))
                .suggestedTargetNode("collect_sources")
                .build()
                .normalized();
        OrchestrationContext context = OrchestrationContext.builder()
                .taskId(88L)
                .triggerNodeName("analyze_competitors")
                .passed(false)
                .agentSuggestions(List.of(suggestion))
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build();

        List<OrchestrationDecision> decisions = service.decide(context);

        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).getDecisionType()).isEqualTo("WAIT_FOR_HUMAN");
        assertThat(decisions.get(0).isRequiresHumanIntervention()).isTrue();
    }

    @Test
    void shouldWaitForHumanFromWriterCitationGapWithoutSources() {
        AgentSuggestion suggestion = AgentSuggestion.builder()
                .suggestionId("as-task-77-write_report-1")
                .taskId(77L)
                .producerNodeName("write_report")
                .producerAgentType("WRITER")
                .suggestionType("CITATION_GAP")
                .targetSection("report_conclusion")
                .summary("报告结论缺少可用来源")
                .severity("ERROR")
                .confidence(0.25d)
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .suggestedQueries(List.of("report_conclusion official citation evidence"))
                .suggestedTargetNode("collect_sources")
                .build()
                .normalized();

        List<OrchestrationDecision> decisions = service.decide(OrchestrationContext.builder()
                .taskId(77L)
                .triggerNodeName("write_report")
                .agentSuggestions(List.of(suggestion))
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build());

        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).getDecisionType()).isEqualTo("WAIT_FOR_HUMAN");
        assertThat(decisions.get(0).isRequiresHumanIntervention()).isTrue();
        assertThat(decisions.get(0).getInputRefs())
                .containsEntry("agentSuggestionIds", List.of("as-task-77-write_report-1"));
    }

    @Test
    void shouldRecordRewriteDecisionFromWriterCitationGapWithSources() {
        AgentSuggestion suggestion = AgentSuggestion.builder()
                .suggestionId("as-task-77-write_report-1")
                .taskId(77L)
                .producerNodeName("write_report")
                .producerAgentType("WRITER")
                .suggestionType("CITATION_GAP")
                .targetSection("pricing")
                .summary("定价章节已有来源但缺逐句引用")
                .severity("HIGH")
                .confidence(0.70d)
                .sourceUrls(List.of("https://www.notion.so/pricing"))
                .evidenceState(EvidenceState.PARTIAL_SOURCE)
                .suggestedTargetNode("rewrite_report")
                .build()
                .normalized();

        List<OrchestrationDecision> decisions = service.decide(OrchestrationContext.builder()
                .taskId(77L)
                .triggerNodeName("write_report")
                .agentSuggestions(List.of(suggestion))
                .sourceUrls(List.of("https://www.notion.so/pricing"))
                .evidenceState(EvidenceState.PARTIAL_SOURCE)
                .build());

        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).getDecisionType()).isEqualTo("REWRITE_ONLY");
        assertThat(decisions.get(0).getActionType()).isEqualTo("REWRITE_SECTION");
        assertThat(decisions.get(0).getTargetNode()).isEqualTo("rewrite_report");
        assertThat(decisions.get(0).getTargetSection()).isEqualTo("pricing");
        assertThat(decisions.get(0).getDecisionOrigin()).isEqualTo(OrchestrationDecisionOrigin.RULE_ONLY);
    }

    @Test
    void shouldWaitForHumanWhenCitationSuggestionHasMissingSource() {
        AgentSuggestion suggestion = AgentSuggestion.builder()
                .suggestionId("as-task-90-citation_check-citation-1")
                .taskId(90L)
                .producerNodeName("citation_check")
                .producerAgentType("CITATION")
                .suggestionType("CITATION_VERIFICATION_GAP")
                .targetSection("action_suggestion")
                .summary("行动建议缺少引用")
                .severity("ERROR")
                .confidence(0.25d)
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .suggestedQueries(List.of("action_suggestion official evidence"))
                .suggestedTargetNode("rewrite_report")
                .build()
                .normalized();

        List<OrchestrationDecision> decisions = service.decide(OrchestrationContext.builder()
                .taskId(90L)
                .triggerNodeName("citation_check")
                .agentSuggestions(List.of(suggestion))
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .build());

        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).getDecisionType()).isEqualTo("WAIT_FOR_HUMAN");
        assertThat(decisions.get(0).getActionType()).isEqualTo("MANUAL_REVIEW");
    }

    @Test
    void shouldRewriteClaimWhenCitationSuggestionHasSourceBackedWeakSupport() {
        AgentSuggestion suggestion = AgentSuggestion.builder()
                .suggestionId("as-task-90-citation_check-citation-1")
                .taskId(90L)
                .producerNodeName("citation_check")
                .producerAgentType("CITATION")
                .suggestionType("CITATION_VERIFICATION_GAP")
                .targetSection("pricing")
                .summary("定价结论引用来源可信度不足")
                .severity("HIGH")
                .confidence(0.70d)
                .sourceUrls(List.of("https://mirror.example.net/notion-ai"))
                .evidenceState(EvidenceState.PARTIAL_SOURCE)
                .suggestedQueries(List.of("pricing official docs"))
                .suggestedTargetNode("rewrite_report")
                .build()
                .normalized();

        List<OrchestrationDecision> decisions = service.decide(OrchestrationContext.builder()
                .taskId(90L)
                .triggerNodeName("citation_check")
                .agentSuggestions(List.of(suggestion))
                .sourceUrls(List.of("https://mirror.example.net/notion-ai"))
                .evidenceState(EvidenceState.PARTIAL_SOURCE)
                .build());

        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).getDecisionType()).isEqualTo("REWRITE_ONLY");
        assertThat(decisions.get(0).getActionType()).isEqualTo("REWRITE_CLAIM");
        assertThat(decisions.get(0).getTargetNode()).isEqualTo("rewrite_report");
        assertThat(decisions.get(0).getTargetSection()).isEqualTo("pricing");
    }
    @Test
    void shouldCreateSupplementDecisionFromCitationSuggestionWhenEvidenceRepairIsRequested() {
        AgentSuggestion suggestion = AgentSuggestion.builder()
                .suggestionId("as-task-91-citation_check-citation-1")
                .taskId(91L)
                .producerNodeName("citation_check")
                .producerAgentType("CITATION")
                .suggestionType("CITATION_VERIFICATION_GAP")
                .targetSection("pricing")
                .summary("unsupported claim: 抖音推荐算法支持跨域投放")
                .severity("HIGH")
                .confidence(0.72d)
                .sourceUrls(List.of("https://open.douyin.com/docs"))
                .evidenceState(EvidenceState.PARTIAL_SOURCE)
                .suggestedQueries(List.of())
                .suggestedTargetNode("collect_sources")
                .build()
                .normalized();

        List<OrchestrationDecision> decisions = service.decide(OrchestrationContext.builder()
                .taskId(91L)
                .triggerNodeName("citation_check")
                .agentSuggestions(List.of(suggestion))
                .sourceUrls(List.of("https://open.douyin.com/docs"))
                .evidenceState(EvidenceState.PARTIAL_SOURCE)
                .build());

        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).getDecisionType()).isEqualTo("APPEND_DYNAMIC_BRANCH");
        assertThat(decisions.get(0).getActionType()).isEqualTo("SUPPLEMENT_EVIDENCE");
        assertThat(decisions.get(0).getTargetNode()).isEqualTo("collect_sources");
    }

    @Test
    void shouldReturnEmptyFromServiceWhenRawContextIsNull() {
        assertThat(service.decide(null)).isEmpty();
    }

    @Test
    void shouldReturnRuleOnlyNoActionForUnknownTrigger() {
        OrchestrationContext context = OrchestrationContext.builder()
                .taskId(60L)
                .triggerNodeName("quality_check_draft")
                .sourceUrls(List.of("https://example.com/draft"))
                .evidenceState(EvidenceState.FULL_SOURCE)
                .build();

        List<OrchestrationDecision> decisions = service.decide(context);

        assertThat(decisions).hasSize(1);
        OrchestrationDecision decision = decisions.get(0);
        assertThat(decision.getDecisionId()).isEqualTo("od-60-quality_check_draft-noop");
        assertThat(decision.getDecisionOrigin()).isEqualTo(OrchestrationDecisionOrigin.RULE_ONLY);
        assertThat(decision.getDecisionType()).isEqualTo("NO_ACTION");
        assertThat(decision.getActionType()).isEqualTo("NO_ACTION");
        assertThat(decision.getTargetNode()).isEqualTo("quality_check_draft");
        assertThat(decision.getAffectedScope()).isEqualTo("CURRENT_NODE_ONLY");
        assertThat(decision.getReason())
                .isEqualTo("P1/P2/P3 当前仅处理 extract_schema、analyze_competitors、write_report/rewrite_report、citation_check 和 quality_check_final 反馈。");
        assertThat(decision.getSourceUrls()).containsExactly("https://example.com/draft");
    }

    @Test
    void shouldNormalizeReviewerDirectivesToSingleHighestPriorityCandidate() {
        OrchestrationContext context = OrchestrationContext.builder()
                .taskId(61L)
                .triggerNodeName("quality_check_final")
                .passed(false)
                .legacyRevisionDirectives(List.of(
                        RevisionDirective.builder()
                                .category("EVIDENCE_GAP")
                                .actionType("SUPPLEMENT_EVIDENCE")
                                .targetSection("pricing")
                                .competitor("Notion")
                                .targetField("pricing")
                                .requiredSourceType("OFFICIAL_PRICING")
                                .summary("补充定价证据")
                                .sourceUrls(List.of("https://example.com/pricing"))
                                .build(),
                        RevisionDirective.builder()
                                .category("EXPRESSION_ISSUE")
                                .actionType("REWRITE_SECTION")
                                .targetSection("conclusion")
                                .summary("改写结论")
                                .sourceUrls(List.of("https://example.com/conclusion"))
                                .build()))
                .sourceUrls(List.of("https://example.com/pricing", "https://example.com/conclusion"))
                .evidenceState(EvidenceState.FULL_SOURCE)
                .build();

        List<OrchestrationDecision> decisions = service.decide(context);

        assertThat(decisions).hasSize(1);
        assertThat(decisions)
                .extracting(OrchestrationDecision::getDecisionId)
                .containsExactly("od-61-quality_check_final-review-cycle");
        assertThat(decisions)
                .extracting(OrchestrationDecision::getActionType)
                .containsExactly("SUPPLEMENT_EVIDENCE");
        assertThat(decisions)
                .extracting(OrchestrationDecision::getDecisionOrigin)
                .containsOnly(OrchestrationDecisionOrigin.RULE_ONLY);
    }

    @Test
    void shouldPrioritizeBlockingExtractorSuggestionOverExecutableGap() {
        AgentSuggestion executableGap = AgentSuggestion.builder()
                .suggestionId("as-task-62-extract_schema-1")
                .suggestionType("EVIDENCE_GAP")
                .summary("存在可补证字段")
                .severity("HIGH")
                .sourceUrls(List.of("https://example.com/evidence"))
                .suggestedTargetNode("collect_sources")
                .build();
        AgentSuggestion blockingSuggestion = AgentSuggestion.builder()
                .suggestionId("as-task-62-extract_schema-2")
                .suggestionType("SCHEMA_CONFLICT")
                .summary("字段结构冲突")
                .severity("ERROR")
                .sourceUrls(List.of("https://example.com/schema"))
                .suggestedTargetNode("extract_schema")
                .build();

        List<OrchestrationDecision> decisions = service.decide(OrchestrationContext.builder()
                .taskId(62L)
                .triggerNodeName("extract_schema")
                .agentSuggestions(List.of(executableGap, blockingSuggestion))
                .sourceUrls(List.of("https://example.com/evidence", "https://example.com/schema"))
                .evidenceState(EvidenceState.FULL_SOURCE)
                .build());

        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).getDecisionType()).isEqualTo("WAIT_FOR_HUMAN");
        assertThat(decisions.get(0).getReason()).contains("字段结构冲突");
    }

    @Test
    void shouldPrioritizeMissingSourceGapAcrossSuggestionList() {
        AgentSuggestion sourceBackedGap = AgentSuggestion.builder()
                .suggestionId("as-task-63-analyze_competitors-1")
                .suggestionType("ANALYSIS_GAP")
                .summary("有来源的分析缺口")
                .severity("HIGH")
                .sourceUrls(List.of("https://example.com/analysis"))
                .evidenceState(EvidenceState.PARTIAL_SOURCE)
                .suggestedTargetNode("collect_sources")
                .build();
        AgentSuggestion missingSourceGap = AgentSuggestion.builder()
                .suggestionId("as-task-63-analyze_competitors-2")
                .suggestionType("ANALYSIS_GAP")
                .summary("无来源的分析缺口")
                .severity("HIGH")
                .sourceUrls(List.of())
                .evidenceState(EvidenceState.MISSING_SOURCE)
                .suggestedTargetNode("collect_sources")
                .build();

        List<OrchestrationDecision> decisions = service.decide(OrchestrationContext.builder()
                .taskId(63L)
                .triggerNodeName("analyze_competitors")
                .agentSuggestions(List.of(sourceBackedGap, missingSourceGap))
                .sourceUrls(List.of("https://example.com/analysis"))
                .evidenceState(EvidenceState.PARTIAL_SOURCE)
                .build());

        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).getDecisionType()).isEqualTo("WAIT_FOR_HUMAN");
        assertThat(decisions.get(0).getReason()).contains("缺少 sourceUrls");
    }

    @Test
    void shouldSupportWriterAndCitationRevisionTriggerAliases() {
        AgentSuggestion writerSuggestion = AgentSuggestion.builder()
                .suggestionId("as-task-64-rewrite_report-1")
                .suggestionType("CITATION_GAP")
                .targetSection("pricing")
                .summary("改写定价引用")
                .severity("HIGH")
                .sourceUrls(List.of("https://example.com/pricing"))
                .evidenceState(EvidenceState.PARTIAL_SOURCE)
                .suggestedTargetNode("rewrite_report")
                .build();
        AgentSuggestion citationSuggestion = AgentSuggestion.builder()
                .suggestionId("as-task-65-citation_check_revision-1")
                .suggestionType("CITATION_VERIFICATION_GAP")
                .targetSection("conclusion")
                .summary("修订结论引用")
                .severity("HIGH")
                .sourceUrls(List.of("https://example.com/conclusion"))
                .evidenceState(EvidenceState.PARTIAL_SOURCE)
                .suggestedTargetNode("rewrite_report")
                .build();

        OrchestrationDecision writerDecision = service.decide(OrchestrationContext.builder()
                .taskId(64L)
                .triggerNodeName("rewrite_report")
                .agentSuggestions(List.of(writerSuggestion))
                .sourceUrls(writerSuggestion.getSourceUrls())
                .evidenceState(EvidenceState.PARTIAL_SOURCE)
                .build()).get(0);
        OrchestrationDecision citationDecision = service.decide(OrchestrationContext.builder()
                .taskId(65L)
                .triggerNodeName("citation_check_revision")
                .agentSuggestions(List.of(citationSuggestion))
                .sourceUrls(citationSuggestion.getSourceUrls())
                .evidenceState(EvidenceState.PARTIAL_SOURCE)
                .build()).get(0);

        assertThat(writerDecision.getActionType()).isEqualTo("REWRITE_SECTION");
        assertThat(citationDecision.getActionType()).isEqualTo("REWRITE_CLAIM");
    }

    @Test
    void shouldNotLetRawHumanSuggestionOverrideTraceablePassedReview() {
        OrchestrationDecision decision = service.decide(OrchestrationContext.builder()
                .taskId(66L)
                .triggerNodeName("quality_check_final")
                .passed(true)
                .requiresHumanIntervention(true)
                .sourceUrls(List.of("https://example.com/review"))
                .evidenceState(EvidenceState.FULL_SOURCE)
                .build()).get(0);

        assertThat(decision.getDecisionType()).isEqualTo("NO_ACTION");
        assertThat(decision.getActionType()).isEqualTo("NO_ACTION");
        assertThat(decision.isRequiresHumanIntervention()).isFalse();
        assertThat(decision.isRequiresConfirmation()).isFalse();
        assertThat(decision.getDecisionOrigin()).isEqualTo(OrchestrationDecisionOrigin.RULE_ONLY);
    }

    @Test
    void shouldPreserveCompleteDecisionAuditShape() {
        AgentSuggestion suggestion = AgentSuggestion.builder()
                .suggestionId("as-task-67-analyze_competitors-1")
                .taskId(67L)
                .producerNodeName("analyze_competitors")
                .producerAgentType("ANALYZER")
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
        OrchestrationContext context = OrchestrationContext.builder()
                .taskId(67L)
                .triggerNodeName("analyze_competitors")
                .agentSuggestions(List.of(suggestion))
                .sourceUrls(List.of("https://example.com/pricing"))
                .evidenceState(EvidenceState.PARTIAL_SOURCE)
                .build();

        OrchestrationDecision decision = service.decide(context).get(0);

        assertThat(decision.getDecisionId()).isEqualTo("od-67-analyze_competitors-suggestion-1");
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
                .containsEntry("agentSuggestionIds", List.of("as-task-67-analyze_competitors-1"))
                .containsEntry("triggerNodeName", "analyze_competitors");
        assertThat(decision.getSourceUrls()).containsExactly("https://example.com/pricing");
        assertThat(decision.getEvidenceState()).isEqualTo(EvidenceState.PARTIAL_SOURCE);
    }
}
