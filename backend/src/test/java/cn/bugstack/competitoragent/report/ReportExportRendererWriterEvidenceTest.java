package cn.bugstack.competitoragent.report;

import cn.bugstack.competitoragent.model.dto.ReportExportResponse;
import cn.bugstack.competitoragent.model.dto.ReportResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportExportRendererWriterEvidenceTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void shouldRenderWriterEvidenceSummaryInMarkdownHtmlAndJsonPackages() throws Exception {
        ReportResponse report = ReportResponse.builder()
                .taskId(920L)
                .title("ReportWriting 证据摘要")
                .summary("summary")
                .content("# report")
                .sourceUrls(List.of())
                .writerEvidenceSummary(ReportResponse.WriterEvidenceSummaryInfo.builder()
                        .writerEvidenceState("PARTIAL_SOURCE")
                        .citationGapSeverity("HIGH")
                        .missingCitationSections(List.of("pricing"))
                        .sourceUrls(List.of("https://www.notion.so/pricing"))
                        .sectionCitationGaps(List.of(ReportResponse.WriterCitationGapInfo.builder()
                                .targetSection("pricing")
                                .sectionTitle("定价策略")
                                .summary("定价章节已有来源但缺逐句引用")
                                .severity("HIGH")
                                .evidenceState("PARTIAL_SOURCE")
                                .sourceUrls(List.of("https://www.notion.so/pricing"))
                                .missingFields(List.of("pricingComparison"))
                                .build()))
                        .issueFlags(List.of("WRITER_CITATION_GAP"))
                        .build())
                .build();
        ReportExportResponse record = ReportExportResponse.builder()
                .taskId(920L)
                .exportVersion(1)
                .exportFormat("JSON")
                .exportSummary("导出含写作证据摘要")
                .sourceUrls(List.of())
                .build();

        ReportExportRenderer.RenderedExportPackage markdownPackage =
                new MarkdownReportExportRenderer().render(report, record, objectMapper);
        String markdown = new String(markdownPackage.content(), StandardCharsets.UTF_8);
        assertTrue(markdown.contains("## 写作证据摘要"));
        assertTrue(markdown.contains("PARTIAL_SOURCE"));
        assertTrue(markdown.contains("定价策略"));

        ReportExportRenderer.RenderedExportPackage htmlPackage =
                new HtmlReportExportRenderer().render(report, record, objectMapper);
        String html = new String(htmlPackage.content(), StandardCharsets.UTF_8);
        assertTrue(html.contains("写作证据摘要"));
        assertTrue(html.contains("PARTIAL_SOURCE"));
        assertTrue(html.contains("定价策略"));

        ReportExportRenderer.RenderedExportPackage jsonPackage =
                new JsonEvidencePackageExportRenderer().render(report, record, objectMapper);
        JsonNode jsonNode = objectMapper.readTree(jsonPackage.content());
        assertEquals("PARTIAL_SOURCE", jsonNode.at("/writerEvidenceSummary/writerEvidenceState").asText());
        assertEquals("pricing", jsonNode.at("/writerEvidenceSummary/sectionCitationGaps/0/targetSection").asText());
        assertEquals("https://www.notion.so/pricing",
                jsonNode.at("/writerEvidenceSummary/sectionCitationGaps/0/sourceUrls/0").asText());
        assertEquals(List.of("https://www.notion.so/pricing"),
                ReportExportRenderSupport.collectSourceUrls(report));
    }
}
