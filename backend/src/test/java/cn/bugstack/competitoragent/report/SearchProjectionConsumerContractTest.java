package cn.bugstack.competitoragent.report;

import cn.bugstack.competitoragent.model.dto.ReportResponse;
import cn.bugstack.competitoragent.model.dto.SearchAuditSummary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchProjectionConsumerContractTest {

    @Test
    void shouldExposeSearchAuditSummaryWithoutFullSearchAuditSnapshot() {
        ReportResponse.SearchAuditOverview overview = ReportResponse.SearchAuditOverview.builder()
                .collectorNodeCount(1)
                .selectedCandidateCount(1)
                .collectors(List.of(ReportResponse.CollectorSearchAudit.builder()
                        .nodeName("collect_sources_01_01")
                        .selectedUrls(List.of("https://docs.example.com/reference"))
                        .build()))
                .searchAuditSummary(SearchAuditSummary.builder()
                        .selectedCount(1)
                        .sourceUrls(List.of("https://docs.example.com/reference"))
                        .build())
                .build();

        assertThat(overview.getSearchAuditSummary().getSourceUrls())
                .containsExactly("https://docs.example.com/reference");
        assertThat(overview.getCollectors()).hasSize(1);
    }
}
