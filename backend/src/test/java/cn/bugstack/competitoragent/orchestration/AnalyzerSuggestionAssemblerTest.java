package cn.bugstack.competitoragent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyzerSuggestionAssemblerTest {

    private final AnalyzerSuggestionAssembler assembler = new AnalyzerSuggestionAssembler(new ObjectMapper());

    @Test
    void shouldBuildEvidenceGapSuggestionFromAnalyzerMissingDimensions() {
        String output = """
                {
                  "analysisConfidence": "LOW",
                  "analysisGapSeverity": "HIGH",
                  "analysisEvidenceState": "PARTIAL_SOURCE",
                  "missingAnalysisDimensions": ["pricingComparison", "targetUserComparison"],
                  "sourceUrls": ["https://www.notion.so/product/ai"]
                }
                """;

        List<AgentSuggestion> suggestions = assembler.fromAnalyzerOutput(88L, "analyze_competitors", output);

        assertThat(suggestions).hasSize(1);
        AgentSuggestion suggestion = suggestions.get(0);
        assertThat(suggestion.getSuggestionId()).isEqualTo("as-task-88-analyze_competitors-1");
        assertThat(suggestion.getProducerAgentType()).isEqualTo("ANALYZER");
        assertThat(suggestion.getSuggestionType()).isEqualTo("ANALYSIS_GAP");
        assertThat(suggestion.getTargetSection()).isEqualTo("analysis");
        assertThat(suggestion.getSeverity()).isEqualTo("HIGH");
        assertThat(suggestion.getEvidenceState()).isEqualTo(EvidenceState.PARTIAL_SOURCE);
        assertThat(suggestion.getSourceUrls()).containsExactly("https://www.notion.so/product/ai");
        assertThat(suggestion.getSuggestedQueries())
                .contains("pricingComparison official source", "targetUserComparison official source");
        assertThat(suggestion.getSuggestedTargetNode()).isEqualTo("collect_sources");
    }

    @Test
    void shouldReturnEmptyWhenAnalyzerHasNoGap() {
        String output = """
                {
                  "analysisConfidence": "HIGH",
                  "analysisGapSeverity": "NONE",
                  "analysisEvidenceState": "FULL_SOURCE",
                  "missingAnalysisDimensions": [],
                  "sourceUrls": ["https://www.notion.so/product/ai"]
                }
                """;

        List<AgentSuggestion> suggestions = assembler.fromAnalyzerOutput(88L, "analyze_competitors", output);

        assertThat(suggestions).isEmpty();
    }

    @Test
    void shouldMarkMissingSourceWhenAnalyzerGapHasNoUrls() {
        String output = """
                {
                  "analysisConfidence": "LOW",
                  "analysisGapSeverity": "HIGH",
                  "analysisEvidenceState": "MISSING_SOURCE",
                  "missingAnalysisDimensions": ["pricingComparison"],
                  "sourceUrls": []
                }
                """;

        List<AgentSuggestion> suggestions = assembler.fromAnalyzerOutput(88L, "analyze_competitors", output);

        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.get(0).getEvidenceState()).isEqualTo(EvidenceState.MISSING_SOURCE);
        assertThat(suggestions.get(0).getSourceUrls()).isEmpty();
    }
}
