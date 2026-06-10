package cn.bugstack.competitoragent.collection.application;

import cn.bugstack.competitoragent.model.dto.ReportResponse;
import cn.bugstack.competitoragent.report.EvidenceQueryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CollectionEvidenceFacadeImplTest {

    private final EvidenceQueryService evidenceQueryService = mock(EvidenceQueryService.class);
    private final CollectionEvidenceFacadeImpl facade = new CollectionEvidenceFacadeImpl(evidenceQueryService);

    @Test
    void should_delegate_task_evidence_queries_to_evidence_query_service() {
        List<ReportResponse.EvidenceInfo> expected = List.of(
                new ReportResponse.EvidenceInfo(
                        "T0012-COLLECT_SOURCES-001",
                        "Docs",
                        "https://docs.example.com",
                        "snippet",
                        "Notion AI",
                        null,
                        "DOCS",
                        "SEARCH",
                        "docs.example.com",
                        "reason",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        java.util.Map.of()
                )
        );
        when(evidenceQueryService.listTaskEvidence(12L)).thenReturn(expected);

        List<ReportResponse.EvidenceInfo> actual = facade.listTaskEvidence(12L);

        assertSame(expected, actual);
        verify(evidenceQueryService).listTaskEvidence(12L);
    }

    @Test
    void should_delegate_node_evidence_queries_to_evidence_query_service() {
        List<ReportResponse.EvidenceInfo> expected = List.of();
        when(evidenceQueryService.listEvidencesByNode(12L, "collect_sources")).thenReturn(expected);

        List<ReportResponse.EvidenceInfo> actual = facade.listNodeEvidence(12L, "collect_sources");

        assertSame(expected, actual);
        verify(evidenceQueryService).listEvidencesByNode(12L, "collect_sources");
    }
}
