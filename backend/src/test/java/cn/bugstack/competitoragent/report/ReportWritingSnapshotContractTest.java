package cn.bugstack.competitoragent.report;

import cn.bugstack.competitoragent.model.dto.ReportResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportWritingSnapshotContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldKeepWriterEvidenceSummaryDeliveryContractStable() {
        ReportResponse.WriterCitationGapInfo gap = ReportResponse.WriterCitationGapInfo.builder()
                .targetSection("report_conclusion")
                .sectionTitle("报告结论")
                .summary("当前章节暂无可用证据来源")
                .severity("ERROR")
                .evidenceState("MISSING_SOURCE")
                .sourceUrls(List.of())
                .missingFields(List.of("recommendations"))
                .suggestedQueries(List.of("report_conclusion recommendations official source"))
                .build();

        ReportResponse response = ReportResponse.builder()
                .taskId(900L)
                .writerEvidenceSummary(ReportResponse.WriterEvidenceSummaryInfo.builder()
                        .writerEvidenceState("MISSING_SOURCE")
                        .citationGapSeverity("ERROR")
                        .missingCitationSections(List.of("report_conclusion"))
                        .sectionCitationGaps(List.of(gap))
                        .issueFlags(List.of("WRITER_CITATION_GAP", "WRITER_MISSING_SOURCE"))
                        .sourceUrls(List.of())
                        .build())
                .sourceUrls(List.of())
                .build();

        JsonNode node = objectMapper.valueToTree(response);

        assertThat(node.at("/writerEvidenceSummary/writerEvidenceState").asText()).isEqualTo("MISSING_SOURCE");
        assertThat(node.at("/writerEvidenceSummary/citationGapSeverity").asText()).isEqualTo("ERROR");
        assertThat(node.at("/writerEvidenceSummary/missingCitationSections/0").asText())
                .isEqualTo("report_conclusion");
        assertThat(node.at("/writerEvidenceSummary/sectionCitationGaps/0/evidenceState").asText())
                .isEqualTo("MISSING_SOURCE");
        assertThat(node.at("/writerEvidenceSummary/sectionCitationGaps/0/sourceUrls").isArray()).isTrue();
        assertThat(node.toString()).contains("sourceUrls");
    }
}
