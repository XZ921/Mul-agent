package cn.bugstack.competitoragent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractorSuggestionAssemblerTest {

    private final ExtractorSuggestionAssembler assembler = new ExtractorSuggestionAssembler(new ObjectMapper());

    @Test
    void shouldCreateBlockingSuggestionForNoBusinessFields() {
        Map<String, Object> output = Map.of(
                "sourceUrls", List.of("https://www.notion.so/pricing"),
                "issueFlags", List.of("NO_BUSINESS_FIELDS_EXTRACTED"),
                "evidenceCoverage", Map.of()
        );

        List<AgentSuggestion> suggestions = assembler.fromExtractorOutput(88L, "extract_schema", output);

        assertThat(suggestions).hasSize(1);
        AgentSuggestion suggestion = suggestions.get(0);
        assertThat(suggestion.getSuggestionType()).isEqualTo("EVIDENCE_GAP");
        assertThat(suggestion.getSeverity()).isEqualTo("ERROR");
        assertThat(suggestion.getSummary()).contains("没有抽出任何业务字段");
        assertThat(suggestion.getEvidenceState()).isEqualTo(EvidenceState.FULL_SOURCE);
        assertThat(suggestion.getSourceUrls()).contains("https://www.notion.so/pricing");
    }

    @Test
    void shouldCreateSectionSuggestionFromEvidenceCoverageGap() {
        Map<String, Object> pricingCoverage = Map.of(
                "status", "EVIDENCE_NOT_COVERING",
                "sourceUrls", List.of(),
                "missingFields", List.of("price", "plan")
        );
        Map<String, Object> output = Map.of(
                "sourceUrls", List.of(),
                "issueFlags", List.of("SECTION_EVIDENCE_GAP"),
                "evidenceCoverage", Map.of("pricing", pricingCoverage)
        );

        List<AgentSuggestion> suggestions = assembler.fromExtractorOutput(88L, "extract_schema", output);

        assertThat(suggestions).anySatisfy(suggestion -> {
            assertThat(suggestion.getTargetSection()).isEqualTo("pricing");
            assertThat(suggestion.getSuggestionType()).isEqualTo("EVIDENCE_GAP");
            assertThat(suggestion.getEvidenceState()).isEqualTo(EvidenceState.MISSING_SOURCE);
            assertThat(suggestion.getSuggestedTargetNode()).isEqualTo("collect_sources");
        });
    }
}
