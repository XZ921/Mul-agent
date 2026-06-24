package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.workflow.contract.QualityDiagnosis;
import cn.bugstack.competitoragent.workflow.contract.RevisionDirective;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestrationDecisionServiceTest {

    private final OrchestrationDecisionService service =
            new OrchestrationDecisionService(new OrchestrationDecisionAdapter());

    @Test
    void shouldGenerateSupplementDecisionForFinalReviewEvidenceGap() {
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
        assertThat(decisions.get(0).getDecisionType()).isEqualTo("APPEND_DYNAMIC_BRANCH");
        assertThat(decisions.get(0).getActionType()).isEqualTo("SUPPLEMENT_EVIDENCE");
        assertThat(decisions.get(0).getEvidenceState()).isEqualTo(EvidenceState.MISSING_SOURCE);
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
    void shouldFallbackToNoActionWhenNoDirectiveAndNoBlockingDiagnosis() {
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
        assertThat(decisions.get(0).getDecisionType()).isEqualTo("NO_ACTION");
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
}
