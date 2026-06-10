package cn.bugstack.competitoragent.report;

import cn.bugstack.competitoragent.model.dto.ReportResponse.EvidenceInfo;
import cn.bugstack.competitoragent.model.dto.ReportResponse.EvidenceReference;
import cn.bugstack.competitoragent.model.dto.ReportResponse.FieldEvidenceDetail;
import cn.bugstack.competitoragent.model.dto.ReportResponse.SectionEvidenceBundleInfo;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.workflow.contract.EvidenceFragment;
import cn.bugstack.competitoragent.workflow.contract.SectionEvidenceBundle;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvidenceQueryServiceTest {

    private final EvidenceSourceRepository evidenceRepository = mock(EvidenceSourceRepository.class);
    private final EvidenceQueryService service = new EvidenceQueryService(evidenceRepository, new ObjectMapper());

    @Test
    void should_list_task_evidence_in_repository_order() {
        EvidenceSource first = EvidenceSource.builder()
                .taskId(12L)
                .evidenceId("T0012-COLLECT_SOURCES-001")
                .competitorName("A")
                .title("A")
                .url("https://a.com")
                .build();
        EvidenceSource second = EvidenceSource.builder()
                .taskId(12L)
                .evidenceId("T0012-WRITE_REPORT-001")
                .competitorName("B")
                .title("B")
                .url("https://b.com")
                .build();
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(12L)).thenReturn(List.of(first, second));

        List<EvidenceInfo> actual = invokeListTaskEvidence(12L);

        assertEquals(2, actual.size());
        assertEquals("T0012-COLLECT_SOURCES-001", actual.get(0).getEvidenceId());
        assertEquals("T0012-WRITE_REPORT-001", actual.get(1).getEvidenceId());
        verify(evidenceRepository).findByTaskIdOrderByEvidenceIdAsc(12L);
    }

    @Test
    void should_filter_evidence_by_node_prefix() {
        EvidenceSource first = EvidenceSource.builder()
                .taskId(12L)
                .evidenceId("T0012-COLLECT_SOURCES-001")
                .competitorName("A")
                .title("A")
                .url("https://a.com")
                .build();
        EvidenceSource second = EvidenceSource.builder()
                .taskId(12L)
                .evidenceId("T0012-WRITE_REPORT-001")
                .competitorName("B")
                .title("B")
                .url("https://b.com")
                .build();
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(12L)).thenReturn(List.of(first, second));

        List<EvidenceInfo> actual = invokeListEvidencesByNode(12L, "collect_sources");

        assertEquals(1, actual.size());
        assertEquals("T0012-COLLECT_SOURCES-001", actual.get(0).getEvidenceId());
    }

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

    @Test
    void shouldResolveEvidenceReferencesByIdAndSourceUrl() {
        EvidenceInfo evidence = new EvidenceInfo(
                "E001",
                "Docs",
                "https://docs.example.com",
                "snippet",
                "Notion AI",
                LocalDateTime.now(),
                "DOCS",
                "SEARCH",
                "docs.example.com",
                "reason",
                null,
                0.91,
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                java.util.Map.of()
        );

        List<EvidenceReference> references = service.resolveEvidenceReferences(
                List.of(evidence),
                List.of("E001"),
                List.of("https://missing.example.com", "https://docs.example.com")
        );

        assertEquals(2, references.size());
        assertEquals("E001", references.get(0).getEvidenceId());
        assertEquals("https://missing.example.com", references.get(1).getUrl());
    }

    @Test
    void shouldProjectSectionEvidenceBundleWithResolvedEvidenceAndGapDetails() {
        EvidenceInfo evidence = new EvidenceInfo(
                "E001",
                "Docs",
                "https://docs.example.com",
                "snippet",
                "Notion AI",
                LocalDateTime.now(),
                "DOCS",
                "SEARCH",
                "docs.example.com",
                "reason",
                null,
                0.91,
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                java.util.Map.of()
        );
        SectionEvidenceBundle bundle = SectionEvidenceBundle.builder()
                .stage("WRITE")
                .sectionType("CONCLUSION")
                .sectionKey("conclusion")
                .sectionTitle("结论")
                .gapSummary("recommendations 缺少证据")
                .missingFields(List.of("recommendations"))
                .issueFlags(List.of("SECTION_EVIDENCE_GAP"))
                .evidenceFragments(List.of(
                        EvidenceFragment.builder()
                                .fieldName("recommendations")
                                .fieldLabel("结论建议")
                                .coverageStatus("TRACEABLE")
                                .evidenceId("E001")
                                .sourceUrl("https://docs.example.com")
                                .build(),
                        EvidenceFragment.builder()
                                .fieldName("risks")
                                .fieldLabel("风险判断")
                                .coverageStatus("MISSING_EVIDENCE")
                                .gapComment("缺少稳定来源")
                                .issueFlags(List.of("MISSING_EVIDENCE"))
                                .build()
                ))
                .build();

        SectionEvidenceBundleInfo info = service.toSectionEvidenceBundleInfo(List.of(evidence), bundle);

        assertEquals("conclusion", info.getSectionKey());
        assertEquals(2, info.getFields().size());
        assertEquals("E001", info.getFields().get(0).getEvidence().getEvidenceId());
        assertEquals("MISSING_EVIDENCE", info.getFields().get(1).getCoverageStatus());
        assertTrue(info.getGapSummary().contains("recommendations"));
    }

    @SuppressWarnings("unchecked")
    private List<EvidenceInfo> invokeListTaskEvidence(Long taskId) {
        try {
            Method method = EvidenceQueryService.class.getMethod("listTaskEvidence", Long.class);
            return (List<EvidenceInfo>) method.invoke(service, taskId);
        } catch (ReflectiveOperationException e) {
            fail("phase3b Task 1 要求 EvidenceQueryService 暴露 listTaskEvidence(Long) 以支撑 CollectionEvidenceFacade", e);
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<EvidenceInfo> invokeListEvidencesByNode(Long taskId, String nodeName) {
        try {
            Method method = EvidenceQueryService.class.getMethod("listEvidencesByNode", Long.class, String.class);
            return (List<EvidenceInfo>) method.invoke(service, taskId, nodeName);
        } catch (ReflectiveOperationException e) {
            fail("phase3b Task 1 要求 EvidenceQueryService 暴露 listEvidencesByNode(Long, String) 以支撑节点级证据读取", e);
            return List.of();
        }
    }
}
