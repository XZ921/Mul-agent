package cn.bugstack.competitoragent.report;

import cn.bugstack.competitoragent.model.dto.ReportResponse.EvidenceInfo;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvidenceQueryServiceTest {

    private final EvidenceSourceRepository evidenceRepository = mock(EvidenceSourceRepository.class);
    private final EvidenceQueryService service = new EvidenceQueryService(evidenceRepository, new ObjectMapper());

    @Test
    void shouldMergeStructuredMetadataIntoEvidenceInfo() {
        EvidenceSource evidence = EvidenceSource.builder()
                .taskId(1L)
                .competitorName("Notion AI")
                .evidenceId("E001")
                .title("Docs")
                .url("https://docs.example.com")
                .contentSnippet("snippet")
                .sourceType("DOCS")
                .discoveryMethod("SEARCH")
                .sourceDomain("docs.example.com")
                .discoveryReason("搜索补源命中文档入口")
                .publishedAt("2026-05-20")
                .sourceScore(0.913)
                .pageMetadata("{\"collector\":\"mock\",\"verified\":true,\"verificationReason\":\"命中 docs 信号\",\"searchQuery\":\"Notion AI documentation\",\"searchEngine\":\"bing\",\"resultRank\":1,\"browserTraceId\":\"trace-evidence-001\",\"selectionReason\":\"验证通过后选中\",\"selectionStage\":\"SELECTED\",\"matchedSignals\":[\"docs\",\"api\"]}")
                .collectedAt(LocalDateTime.now())
                .build();

        EvidenceInfo info = service.toEvidenceInfo(evidence);

        assertEquals("E001", info.getEvidenceId());
        assertEquals("DOCS", info.getSourceType());
        assertEquals("SEARCH", info.getDiscoveryMethod());
        assertEquals("docs.example.com", info.getSourceDomain());
        assertEquals("搜索补源命中文档入口", info.getDiscoveryReason());
        assertEquals("2026-05-20", info.getPublishedAt());
        assertEquals(0.913, info.getSourceScore());
        assertEquals(true, info.getVerified());
        assertEquals("命中 docs 信号", info.getVerificationReason());
        assertEquals("Notion AI documentation", info.getSearchQuery());
        assertEquals("bing", info.getSearchEngine());
        assertEquals(1, info.getResultRank());
        assertEquals("trace-evidence-001", info.getBrowserTraceId());
        assertEquals("验证通过后选中", info.getSelectionReason());
        assertEquals("SELECTED", info.getSelectionStage());
        assertEquals(List.of("docs", "api"), info.getMatchedSignals());
        assertEquals("DOCS", info.getPageMetadata().get("sourceType"));
        assertEquals("SEARCH", info.getPageMetadata().get("discoveryMethod"));
        assertEquals("docs.example.com", info.getPageMetadata().get("domain"));
        assertEquals(0.913, info.getPageMetadata().get("totalScore"));
        assertNotNull(info.getPageMetadata().get("collector"));
    }

    @Test
    void shouldQueryWithSpecificationAndStableSort() {
        when(evidenceRepository.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of());

        List<EvidenceInfo> result = service.listEvidences(1L, "Notion AI", "DOCS", "SEARCH");

        assertEquals(List.of(), result);
        verify(evidenceRepository).findAll(any(Specification.class), any(Sort.class));
    }
}
