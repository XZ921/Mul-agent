package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.workflow.contract.RevisionDirective;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestrationDecisionAdapterTest {

    private final OrchestrationDecisionAdapter adapter = new OrchestrationDecisionAdapter();

    @Test
    void shouldConvertEvidenceGapDirectiveToAppendDynamicBranchDecision() {
        RevisionDirective directive = RevisionDirective.builder()
                .category("EVIDENCE_GAP")
                .actionType("SUPPLEMENT_EVIDENCE")
                .targetSection("pricing")
                .summary("补充定价页证据")
                .searchQueries(List.of("Notion AI pricing official"))
                .sourceUrls(List.of())
                .build();

        OrchestrationDecision decision = adapter.fromRevisionDirective(50L, "quality_check_final", directive, 1);

        assertThat(decision.getDecisionId()).isEqualTo("od-50-quality_check_final-1");
        assertThat(decision.getDecisionType()).isEqualTo("APPEND_DYNAMIC_BRANCH");
        assertThat(decision.getActionType()).isEqualTo("SUPPLEMENT_EVIDENCE");
        assertThat(decision.getTargetNode()).isEqualTo("collect_sources");
        assertThat(decision.getAffectedScope()).isEqualTo("CURRENT_NODE_AND_DOWNSTREAM");
        assertThat(decision.getPriority()).isEqualTo("HIGH");
        assertThat(decision.getTargetSection()).isEqualTo("pricing");
        assertThat(decision.getEvidenceState()).isEqualTo(EvidenceState.MISSING_SOURCE);
        assertThat(decision.getInputRefs()).containsEntry("triggerNodeName", "quality_check_final");
        assertThat(decision.getSuggestedQueries()).containsExactly("Notion AI pricing official");
    }

    @Test
    void shouldConvertExpressionDirectiveToRewriteOnlyDecision() {
        RevisionDirective directive = RevisionDirective.builder()
                .category("EXPRESSION_ISSUE")
                .targetSection("结论")
                .summary("收紧绝对化表述")
                .sourceUrls(List.of("https://example.com/report"))
                .build();

        OrchestrationDecision decision = adapter.fromRevisionDirective(50L, "quality_check_final", directive, 2);

        assertThat(decision.getDecisionType()).isEqualTo("REWRITE_ONLY");
        assertThat(decision.getActionType()).isEqualTo("REWRITE_SECTION");
        assertThat(decision.getTargetNode()).isEqualTo("rewrite_report");
        assertThat(decision.getEvidenceState()).isEqualTo(EvidenceState.FULL_SOURCE);
    }
}
