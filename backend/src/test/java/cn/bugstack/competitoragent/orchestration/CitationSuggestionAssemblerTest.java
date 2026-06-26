package cn.bugstack.competitoragent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CitationSuggestionAssemblerTest {

    private final CitationSuggestionAssembler assembler = new CitationSuggestionAssembler(new ObjectMapper());

    @Test
    void shouldCreateMissingCitationSuggestionFromCitationOutput() {
        String output = """
                {
                  "citationRiskSeverity": "ERROR",
                  "citationEvidenceState": "MISSING_SOURCE",
                  "citationIssues": [{
                    "issueId": "ci-1",
                    "issueType": "MISSING_CITATION",
                    "severity": "ERROR",
                    "targetSection": "action_suggestion",
                    "claimId": "claim-1",
                    "summary": "行动建议缺少引用",
                    "sourceUrls": [],
                    "evidenceState": "MISSING_SOURCE",
                    "suggestedQueries": ["action_suggestion official evidence"]
                  }]
                }
                """;

        List<AgentSuggestion> suggestions = assembler.fromCitationOutput(90L, "citation_check", output);

        assertThat(suggestions).hasSize(1);
        AgentSuggestion suggestion = suggestions.get(0);
        assertThat(suggestion.getProducerAgentType()).isEqualTo("CITATION");
        assertThat(suggestion.getSuggestionType()).isEqualTo("CITATION_VERIFICATION_GAP");
        assertThat(suggestion.getEvidenceState()).isEqualTo(EvidenceState.MISSING_SOURCE);
        assertThat(suggestion.getSuggestedTargetNode()).isEqualTo("rewrite_report");
    }
}
