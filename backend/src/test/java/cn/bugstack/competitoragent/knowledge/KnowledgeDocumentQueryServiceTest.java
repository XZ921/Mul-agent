package cn.bugstack.competitoragent.knowledge;

import cn.bugstack.competitoragent.model.dto.KnowledgeDocumentResponse;
import cn.bugstack.competitoragent.model.entity.KnowledgeDocument;
import cn.bugstack.competitoragent.repository.KnowledgeDocumentRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeDocumentQueryServiceTest {

    private final KnowledgeDocumentRepository knowledgeDocumentRepository = mock(KnowledgeDocumentRepository.class);
    private final KnowledgeDocumentQueryService knowledgeDocumentQueryService =
            new KnowledgeDocumentQueryService(knowledgeDocumentRepository);

    @Test
    void should_list_task_documents_in_repository_order() {
        KnowledgeDocument firstDocument = buildTaskDocument(
                101L,
                "TASK-DOC-001",
                "T0005-COLLECT_SOURCES-001",
                "治理手册",
                List.of("https://docs.example.com/governance")
        );
        KnowledgeDocument secondDocument = buildTaskDocument(
                102L,
                "TASK-DOC-002",
                "T0005-COLLECT_SOURCES-002",
                "发布规范",
                List.of("https://docs.example.com/release")
        );

        when(knowledgeDocumentRepository.findByTaskIdOrderByIdAsc(5L))
                .thenReturn(List.of(firstDocument, secondDocument));
        when(knowledgeDocumentRepository.findTaskDocumentsBySourceUrlLikeOrderByIdAsc("https://docs.example.com/governance"))
                .thenReturn(List.of());
        when(knowledgeDocumentRepository.findTaskDocumentsBySourceUrlLikeOrderByIdAsc("https://docs.example.com/release"))
                .thenReturn(List.of());

        List<KnowledgeDocumentResponse> actual = invokeListByTaskId(5L);

        assertEquals(2, actual.size());
        assertEquals("治理手册", actual.get(0).getTitle());
        assertEquals("发布规范", actual.get(1).getTitle());
        assertEquals(List.of("https://docs.example.com/governance"), actual.get(0).getSourceUrls());
    }

    @Test
    void shouldBuildTraceableResponseWithSourceUrlsAndTaskConsumptionChain() {
        KnowledgeDocument organizationDocument = buildOrganizationDocument(
                11L,
                "ORG-ORG-PRODUCT-DOCS-UPLOADED-DOCUMENTS-LAUNCH-GUIDE",
                "ORG-org-product-docs-launch-guide",
                List.of(
                        "https://docs.example.com/launch-guide.pdf",
                        "https://docs.example.com/pricing"
                )
        );
        KnowledgeDocument consumedTaskDocument = KnowledgeDocument.builder()
                .id(21L)
                .taskId(88L)
                .competitorName("Feishu")
                .evidenceId("T0088-COLLECT-001")
                .documentKey("TASK-88-T0088-COLLECT-001")
                .knowledgeScope("TASK")
                .sourceType("DOCS")
                .sourceCategory("USER_PROVIDED")
                .sourceLifecycle("ACTIVE")
                .trustLevel("USER_CONFIRMED")
                .title("Feishu launch guide")
                .url("https://docs.example.com/launch-guide.pdf")
                .sourceUrls(List.of("https://docs.example.com/launch-guide.pdf"))
                .issueFlags(List.of())
                .documentVersion(1)
                .status("READY")
                .build();

        when(knowledgeDocumentRepository.findTaskDocumentsBySourceUrlLikeOrderByIdAsc("https://docs.example.com/launch-guide.pdf"))
                .thenReturn(List.of(consumedTaskDocument));
        when(knowledgeDocumentRepository.findTaskDocumentsBySourceUrlLikeOrderByIdAsc("https://docs.example.com/pricing"))
                .thenReturn(List.of());

        KnowledgeDocumentResponse response = knowledgeDocumentQueryService.toResponse(organizationDocument);

        assertEquals(11L, response.getId());
        assertEquals("org-product-docs", response.getKnowledgeDomainKey());
        assertEquals(List.of(
                "https://docs.example.com/launch-guide.pdf",
                "https://docs.example.com/pricing"
        ), response.getSourceUrls());
        assertEquals(List.of(88L), response.getConsumedTaskIds());
        assertEquals(List.of("T0088-COLLECT-001"), response.getConsumedEvidenceIds());
        assertTrue(response.getTraceSummary().contains("https://docs.example.com/launch-guide.pdf"));
        assertTrue(response.getTraceSummary().contains("task-88"));
    }

    @Test
    void shouldListDomainDocumentsInAscendingOrderAndKeepTraceSummaryReadable() {
        KnowledgeDocument firstDocument = buildOrganizationDocument(
                11L,
                "ORG-ORG-PRODUCT-DOCS-UPLOADED-DOCUMENTS-LAUNCH-GUIDE",
                "ORG-org-product-docs-launch-guide",
                List.of("https://docs.example.com/launch-guide.pdf")
        );
        KnowledgeDocument secondDocument = buildOrganizationDocument(
                12L,
                "ORG-ORG-PRODUCT-DOCS-USER-PROVIDED-PRICING-NOTE",
                "ORG-org-product-docs-pricing-note",
                List.of("https://docs.example.com/pricing-note")
        );

        when(knowledgeDocumentRepository.findByKnowledgeDomainKeyOrderByIdAsc("org-product-docs"))
                .thenReturn(List.of(firstDocument, secondDocument));
        when(knowledgeDocumentRepository.findTaskDocumentsBySourceUrlLikeOrderByIdAsc("https://docs.example.com/launch-guide.pdf"))
                .thenReturn(List.of());
        when(knowledgeDocumentRepository.findTaskDocumentsBySourceUrlLikeOrderByIdAsc("https://docs.example.com/pricing-note"))
                .thenReturn(List.of());

        List<KnowledgeDocumentResponse> responses = knowledgeDocumentQueryService.listByDomainKey("org-product-docs");

        assertEquals(2, responses.size());
        assertEquals("ORG-ORG-PRODUCT-DOCS-UPLOADED-DOCUMENTS-LAUNCH-GUIDE", responses.get(0).getDocumentKey());
        assertEquals("ORG-ORG-PRODUCT-DOCS-USER-PROVIDED-PRICING-NOTE", responses.get(1).getDocumentKey());
        assertTrue(responses.get(0).getTraceSummary().contains("launch-guide.pdf"));
        assertTrue(responses.get(1).getTraceSummary().contains("尚未发现后续任务消费记录"));
    }

    /**
     * 这里统一构造组织级知识文档测试样本，
     * 让断言聚焦在“证据回指摘要”本身，而不是被无关字段噪音打散。
     */
    private KnowledgeDocument buildOrganizationDocument(Long id,
                                                        String documentKey,
                                                        String evidenceId,
                                                        List<String> sourceUrls) {
        return KnowledgeDocument.builder()
                .id(id)
                .taskId(null)
                .competitorName("Feishu")
                .evidenceId(evidenceId)
                .documentKey(documentKey)
                .knowledgeScope("ORGANIZATION")
                .knowledgeDomainId(7L)
                .knowledgeDomainKey("org-product-docs")
                .sourceType("UPLOAD")
                .sourceCategory("UPLOADED_DOCUMENTS")
                .discoveryMethod("UPLOAD")
                .sourceDomain("docs.example.com")
                .sourceLifecycle("ACTIVE")
                .trustLevel("VERIFIED")
                .title("组织产品资料")
                .url(sourceUrls.get(0))
                .sourceUrls(sourceUrls)
                .issueFlags(List.of())
                .documentVersion(1)
                .status("READY")
                .build();
    }

    private KnowledgeDocument buildTaskDocument(Long id,
                                                String documentKey,
                                                String evidenceId,
                                                String title,
                                                List<String> sourceUrls) {
        return KnowledgeDocument.builder()
                .id(id)
                .taskId(5L)
                .competitorName("Notion AI")
                .evidenceId(evidenceId)
                .documentKey(documentKey)
                .knowledgeScope("TASK")
                .knowledgeDomainId(9L)
                .knowledgeDomainKey("task-notion-governance")
                .sourceType("DOCS")
                .sourceCategory("TASK_EVIDENCE")
                .discoveryMethod("AI_DISCOVERED")
                .sourceDomain("docs.example.com")
                .sourceLifecycle("ACTIVE")
                .trustLevel("VERIFIED")
                .title(title)
                .url(sourceUrls.get(0))
                .sourceUrls(sourceUrls)
                .issueFlags(List.of())
                .documentVersion(1)
                .status("READY")
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<KnowledgeDocumentResponse> invokeListByTaskId(Long taskId) {
        try {
            Method method = KnowledgeDocumentQueryService.class.getMethod("listByTaskId", Long.class);
            return (List<KnowledgeDocumentResponse>) method.invoke(knowledgeDocumentQueryService, taskId);
        } catch (ReflectiveOperationException e) {
            fail("phase4a Task 1 要求 KnowledgeDocumentQueryService 暴露 listByTaskId(Long) 供 KnowledgeRetrievalFacade 复用", e);
            return List.of();
        }
    }
}
