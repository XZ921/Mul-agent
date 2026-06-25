package cn.bugstack.competitoragent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WriterSuggestionAssemblerTest {

    private final WriterSuggestionAssembler assembler = new WriterSuggestionAssembler(new ObjectMapper());

    @Test
    void shouldBuildCitationGapSuggestionFromWriterOutput() {
        List<AgentSuggestion> suggestions = assembler.fromWriterOutput(77L, "write_report", """
                {
                  "citationGapSeverity": "ERROR",
                  "writerEvidenceState": "MISSING_SOURCE",
                  "missingCitationSections": ["report_conclusion"],
                  "sectionCitationGaps": [
                    {
                      "targetSection": "report_conclusion",
                      "sectionTitle": "报告结论",
                      "summary": "当前章节暂无可用证据来源",
                      "severity": "ERROR",
                      "sourceUrls": [],
                      "evidenceState": "MISSING_SOURCE",
                      "missingFields": ["recommendations"],
                      "suggestedQueries": ["report_conclusion recommendations official source"]
                    }
                  ]
                }
                """);

        assertThat(suggestions).hasSize(1);
        AgentSuggestion suggestion = suggestions.get(0);
        assertThat(suggestion.getSuggestionId()).isEqualTo("as-task-77-write_report-1");
        assertThat(suggestion.getProducerAgentType()).isEqualTo("WRITER");
        assertThat(suggestion.getSuggestionType()).isEqualTo("CITATION_GAP");
        assertThat(suggestion.getTargetSection()).isEqualTo("report_conclusion");
        assertThat(suggestion.getSeverity()).isEqualTo("ERROR");
        assertThat(suggestion.getEvidenceState()).isEqualTo(EvidenceState.MISSING_SOURCE);
        assertThat(suggestion.getSuggestedTargetNode()).isEqualTo("collect_sources");
    }

    @Test
    void shouldReturnEmptyWhenWriterHasNoCitationGap() {
        List<AgentSuggestion> suggestions = assembler.fromWriterOutput(77L, "write_report", """
                {
                  "citationGapSeverity": "NONE",
                  "writerEvidenceState": "FULL_SOURCE",
                  "sectionCitationGaps": []
                }
                """);

        assertThat(suggestions).isEmpty();
    }

    @Test
    void shouldPreferRewriteTargetWhenGapHasSources() {
        List<AgentSuggestion> suggestions = assembler.fromWriterOutput(77L, "rewrite_report", """
                {
                  "citationGapSeverity": "HIGH",
                  "writerEvidenceState": "PARTIAL_SOURCE",
                  "sectionCitationGaps": [
                    {
                      "targetSection": "pricing",
                      "sectionTitle": "定价策略",
                      "summary": "定价章节已有来源但缺逐句引用",
                      "severity": "HIGH",
                      "sourceUrls": ["https://www.notion.so/pricing"],
                      "evidenceState": "PARTIAL_SOURCE",
                      "missingFields": ["pricingComparison"]
                    }
                  ]
                }
                """);

        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.get(0).getSuggestedTargetNode()).isEqualTo("rewrite_report");
        assertThat(suggestions.get(0).getSourceUrls()).containsExactly("https://www.notion.so/pricing");
    }
}
