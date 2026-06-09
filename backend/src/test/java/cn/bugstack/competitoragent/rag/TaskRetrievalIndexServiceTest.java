package cn.bugstack.competitoragent.rag;

import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.model.entity.KnowledgeDocument;
import cn.bugstack.competitoragent.model.entity.RetrievalChunk;
import cn.bugstack.competitoragent.model.entity.RetrievalIndex;
import cn.bugstack.competitoragent.repository.KnowledgeDocumentRepository;
import cn.bugstack.competitoragent.repository.RetrievalChunkRepository;
import cn.bugstack.competitoragent.repository.RetrievalIndexRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskRetrievalIndexServiceTest {

    private final KnowledgeDocumentRepository knowledgeDocumentRepository = mock(KnowledgeDocumentRepository.class);
    private final RetrievalChunkRepository retrievalChunkRepository = mock(RetrievalChunkRepository.class);
    private final RetrievalIndexRepository retrievalIndexRepository = mock(RetrievalIndexRepository.class);
    private final TaskRetrievalIndexService taskRetrievalIndexService = new TaskRetrievalIndexService(
            knowledgeDocumentRepository,
            retrievalChunkRepository,
            retrievalIndexRepository,
            new KnowledgeDocumentBuilder(),
            new RetrievalChunkingService(32, 8)
    );

    @Test
    void shouldReplaceOldChunksAndBumpDocumentVersionWhenReindexingSameEvidence() {
        KnowledgeDocument existingDocument = KnowledgeDocument.builder()
                .id(11L)
                .taskId(1L)
                .competitorName("Feishu")
                .evidenceId("T0001-COLLECT-001")
                .documentKey("TASK-1-T0001-COLLECT-001")
                .sourceType("DOCS")
                .sourceCategory("USER_PROVIDED")
                .documentVersion(2)
                .status("READY")
                .sourceUrls(List.of("https://example.com/docs"))
                .issueFlags(List.of())
                .build();
        EvidenceSource evidence = EvidenceSource.builder()
                .taskId(1L)
                .competitorName("Feishu")
                .evidenceId("T0001-COLLECT-001")
                .title("Feishu Docs")
                .url("https://example.com/docs")
                .contentSnippet("api reference")
                .fullContent("Feishu docs support api reference and pricing details for enterprise teamwork.")
                .sourceType("DOCS")
                .sourceCategory("USER_PROVIDED")
                .discoveryMethod("CONFIG")
                .sourceDomain("example.com")
                .collectedAt(LocalDateTime.of(2026, 6, 5, 20, 0))
                .build();

        when(knowledgeDocumentRepository.findByTaskIdAndEvidenceId(1L, "T0001-COLLECT-001"))
                .thenReturn(Optional.of(existingDocument));
        when(knowledgeDocumentRepository.save(any(KnowledgeDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(retrievalChunkRepository.saveAll(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(retrievalIndexRepository.save(any(RetrievalIndex.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TaskRetrievalIndexingResult result = taskRetrievalIndexService.indexEvidence(evidence);

        assertTrue(result.succeeded());
        assertEquals(3, result.knowledgeDocument().getDocumentVersion());
        assertTrue(result.retrievalChunks().size() >= 2);
        verify(retrievalChunkRepository, times(1)).deleteByKnowledgeDocumentId(11L);
        verify(retrievalIndexRepository, times(1)).deleteByKnowledgeDocumentId(11L);
    }

    @Test
    void shouldPersistFailedStatusWhenChunkingCannotProduceAnyChunk() {
        EvidenceSource evidence = EvidenceSource.builder()
                .taskId(1L)
                .competitorName("Feishu")
                .evidenceId("T0001-COLLECT-002")
                .title("Blank")
                .url("https://example.com/blank")
                .sourceType("DOCS")
                .sourceCategory("AI_DISCOVERED")
                .discoveryMethod("SEARCH")
                .collectedAt(LocalDateTime.of(2026, 6, 5, 20, 0))
                .build();

        when(knowledgeDocumentRepository.findByTaskIdAndEvidenceId(1L, "T0001-COLLECT-002"))
                .thenReturn(Optional.empty());
        when(knowledgeDocumentRepository.save(any(KnowledgeDocument.class)))
                .thenAnswer(invocation -> {
                    KnowledgeDocument document = invocation.getArgument(0);
                    if (document.getId() == null) {
                        document.setId(21L);
                    }
                    return document;
                });
        when(retrievalIndexRepository.save(any(RetrievalIndex.class)))
                .thenAnswer(invocation -> {
                    RetrievalIndex retrievalIndex = invocation.getArgument(0);
                    if (retrievalIndex.getId() == null) {
                        retrievalIndex.setId(31L);
                    }
                    return retrievalIndex;
                });

        TaskRetrievalIndexingResult result = taskRetrievalIndexService.indexEvidence(evidence);

        assertFalse(result.succeeded());
        assertEquals("FAILED", result.knowledgeDocument().getStatus());
        assertEquals("FAILED", result.retrievalIndex().getStatus());
        assertTrue(result.issueFlags().contains("KNOWLEDGE_INDEX_FAILED"));
        assertTrue(result.failureReason().contains("知识文档缺少可切片正文"));
        verify(retrievalChunkRepository, never()).saveAll(any());
    }

    @Test
    void shouldBuildDomainAndOrganizationScopedArtifactsForOrganizationKnowledgeDocument() throws Exception {
        // 组织级资料必须进入统一 Retrieval 主链，
        // 并且显式带上 Domain / Organization 作用域字段，后续三层召回才能成立。
        Method indexKnowledgeDocument = requiredMethod(
                TaskRetrievalIndexService.class,
                "indexKnowledgeDocument",
                KnowledgeDocument.class
        );
        Method getRetrievalScope = requiredMethod(RetrievalChunk.class, "getRetrievalScope");
        Method getScopeRefKey = requiredMethod(RetrievalChunk.class, "getScopeRefKey");
        Method getKnowledgeDomainKey = requiredMethod(RetrievalChunk.class, "getKnowledgeDomainKey");
        Method getIndexRetrievalScope = requiredMethod(RetrievalIndex.class, "getRetrievalScope");
        Method getIndexScopeRefKey = requiredMethod(RetrievalIndex.class, "getScopeRefKey");
        Method getIndexKnowledgeDomainKey = requiredMethod(RetrievalIndex.class, "getKnowledgeDomainKey");

        KnowledgeDocument organizationDocument = KnowledgeDocument.builder()
                .id(301L)
                .taskId(null)
                .competitorName("Notion AI")
                .evidenceId("ORG-org-product-docs-launch-guide")
                .documentKey("ORG-ORG-PRODUCT-DOCS-UPLOADED-DOCUMENTS-LAUNCH-GUIDE")
                .knowledgeScope("ORGANIZATION")
                .knowledgeDomainId(17L)
                .knowledgeDomainKey("org-product-docs")
                .sourceType("UPLOAD")
                .sourceCategory("UPLOADED_DOCUMENTS")
                .discoveryMethod("UPLOAD")
                .sourceDomain("docs.example.com")
                .sourceLifecycle("ACTIVE")
                .trustLevel("VERIFIED")
                .title("Notion AI launch guide")
                .url("https://docs.example.com/notion-launch-guide.pdf")
                .snippet("launch guide")
                .cleanedText("Notion AI launch guide covers enterprise rollout governance pricing and enablement workflows.")
                .sourceUrls(List.of("https://docs.example.com/notion-launch-guide.pdf"))
                .issueFlags(List.of())
                .documentVersion(1)
                .status("PROCESSING")
                .collectedAt(LocalDateTime.of(2026, 6, 7, 10, 0))
                .build();
        when(knowledgeDocumentRepository.save(any(KnowledgeDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(retrievalChunkRepository.saveAll(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(retrievalIndexRepository.save(any(RetrievalIndex.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TaskRetrievalIndexingResult result = (TaskRetrievalIndexingResult) indexKnowledgeDocument.invoke(
                taskRetrievalIndexService,
                organizationDocument
        );

        assertNotNull(result);
        assertTrue(result.succeeded());
        assertEquals("READY", result.knowledgeDocument().getStatus());
        assertTrue(result.retrievalChunks().stream()
                .anyMatch(chunk -> "DOMAIN".equals(invokeStringGetter(getRetrievalScope, chunk))
                        && "org-product-docs".equals(invokeStringGetter(getScopeRefKey, chunk))
                        && "org-product-docs".equals(invokeStringGetter(getKnowledgeDomainKey, chunk))));
        assertTrue(result.retrievalChunks().stream()
                .anyMatch(chunk -> "ORGANIZATION".equals(invokeStringGetter(getRetrievalScope, chunk))
                        && "ORGANIZATION".equals(invokeStringGetter(getScopeRefKey, chunk))));
        assertEquals("ORGANIZATION", invokeStringGetter(getIndexRetrievalScope, result.retrievalIndex()));
        assertEquals("ORGANIZATION", invokeStringGetter(getIndexScopeRefKey, result.retrievalIndex()));
        assertEquals("org-product-docs", invokeStringGetter(getIndexKnowledgeDomainKey, result.retrievalIndex()));
    }

    private Method requiredMethod(Class<?> type, String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        return type.getMethod(name, parameterTypes);
    }

    private String invokeStringGetter(Method method, Object target) {
        try {
            Object value = method.invoke(target);
            return value == null ? null : String.valueOf(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to invoke getter: " + method.getName(), e);
        }
    }
}
