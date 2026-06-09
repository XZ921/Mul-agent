package cn.bugstack.competitoragent.rag;

import cn.bugstack.competitoragent.llm.EmbeddingClient;
import cn.bugstack.competitoragent.model.entity.RetrievalChunk;
import cn.bugstack.competitoragent.model.entity.RetrievalIndex;
import cn.bugstack.competitoragent.repository.RetrievalChunkRepository;
import cn.bugstack.competitoragent.repository.RetrievalIndexRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskRetrievalServiceTest {

    private final RetrievalIndexRepository retrievalIndexRepository = mock(RetrievalIndexRepository.class);
    private final RetrievalChunkRepository retrievalChunkRepository = mock(RetrievalChunkRepository.class);
    private final EmbeddingClient embeddingClient = mock(EmbeddingClient.class);

    @Test
    void shouldFillRemainingHitsByTaskThenDomainThenOrganizationWithinTopK() {
        // Task 5.3.c 要锁定的行为是：
        // 当任务级命中数量不足时，检索链路必须按 Task -> Domain -> Organization 的顺序逐层补齐，
        // 并且最终返回的命中总数必须受 topK 限制，避免组织级资料无限扩张结果集。
        when(retrievalIndexRepository.findByTaskIdOrderByIdAsc(5L)).thenReturn(List.of(
                RetrievalIndex.builder()
                        .taskId(5L)
                        .knowledgeDocumentId(501L)
                        .documentKey("TASK-DOC-001")
                        .status("READY")
                        .sourceUrls(List.of("https://task.example.com/notion-task"))
                        .build()
        ));
        when(retrievalChunkRepository.findByTaskIdOrderByKnowledgeDocumentIdAscChunkIndexAsc(5L)).thenReturn(List.of(
                RetrievalChunk.builder()
                        .taskId(5L)
                        .knowledgeDocumentId(501L)
                        .documentKey("TASK-DOC-001")
                        .chunkKey("TASK-DOC-001#T-001")
                        .competitorName("Notion AI")
                        .evidenceId("TASK-E-001")
                        .sourceCategory("TASK_EVIDENCE")
                        .snippet("task scoped rollout playbook")
                        .content("task scoped rollout playbook for notion ai")
                        .sourceUrls(List.of("https://task.example.com/notion-task"))
                        .build()
        ));
        when(retrievalIndexRepository.findAll()).thenReturn(List.of(
                RetrievalIndex.builder()
                        .knowledgeDocumentId(601L)
                        .documentKey("DOMAIN-DOC-001")
                        .retrievalScope("DOMAIN")
                        .scopeRefKey("product-governance")
                        .knowledgeDomainKey("product-governance")
                        .status("READY")
                        .sourceUrls(List.of("https://domain.example.com/governance"))
                        .build(),
                RetrievalIndex.builder()
                        .knowledgeDocumentId(999L)
                        .documentKey("IGNORED-DOMAIN-DOC")
                        .retrievalScope("DOMAIN")
                        .scopeRefKey("other-domain")
                        .knowledgeDomainKey("other-domain")
                        .status("FAILED")
                        .sourceUrls(List.of("https://domain.example.com/ignored"))
                        .build()
        ));
        when(retrievalIndexRepository.findByRetrievalScopeAndScopeRefKeyOrderByIdAsc("ORGANIZATION", "ORGANIZATION"))
                .thenReturn(List.of(
                        RetrievalIndex.builder()
                                .knowledgeDocumentId(701L)
                                .documentKey("ORG-DOC-001")
                                .retrievalScope("ORGANIZATION")
                                .scopeRefKey("ORGANIZATION")
                                .knowledgeDomainKey("product-governance")
                                .status("READY")
                                .sourceUrls(List.of("https://org.example.com/playbook"))
                                .build(),
                        RetrievalIndex.builder()
                                .knowledgeDocumentId(702L)
                                .documentKey("ORG-DOC-002")
                                .retrievalScope("ORGANIZATION")
                                .scopeRefKey("ORGANIZATION")
                                .knowledgeDomainKey("product-governance")
                                .status("READY")
                                .sourceUrls(List.of("https://org.example.com/archive"))
                                .build()
                ));
        when(retrievalChunkRepository.findAll()).thenReturn(List.of(
                RetrievalChunk.builder()
                        .knowledgeDocumentId(601L)
                        .documentKey("DOMAIN-DOC-001")
                        .retrievalScope("DOMAIN")
                        .scopeRefKey("product-governance")
                        .knowledgeDomainKey("product-governance")
                        .chunkKey("DOMAIN-DOC-001#D-001")
                        .competitorName("Notion AI")
                        .evidenceId("DOMAIN-E-001")
                        .sourceCategory("DOMAIN_KNOWLEDGE")
                        .snippet("domain governance checklist")
                        .content("domain governance checklist for notion ai")
                        .sourceUrls(List.of("https://domain.example.com/governance"))
                        .build(),
                RetrievalChunk.builder()
                        .knowledgeDocumentId(999L)
                        .documentKey("IGNORED-DOMAIN-DOC")
                        .retrievalScope("DOMAIN")
                        .scopeRefKey("other-domain")
                        .knowledgeDomainKey("other-domain")
                        .chunkKey("IGNORED-DOMAIN-DOC#D-999")
                        .competitorName("Notion AI")
                        .evidenceId("IGNORED-DOMAIN-E-999")
                        .sourceCategory("DOMAIN_KNOWLEDGE")
                        .snippet("ignored domain fallback")
                        .content("ignored domain fallback")
                        .sourceUrls(List.of("https://domain.example.com/ignored"))
                        .build()
        ));
        when(retrievalChunkRepository.findByRetrievalScopeAndScopeRefKeyOrderByKnowledgeDocumentIdAscChunkIndexAsc(
                "ORGANIZATION",
                "ORGANIZATION"
        )).thenReturn(List.of(
                RetrievalChunk.builder()
                        .knowledgeDocumentId(701L)
                        .documentKey("ORG-DOC-001")
                        .retrievalScope("ORGANIZATION")
                        .scopeRefKey("ORGANIZATION")
                        .knowledgeDomainKey("product-governance")
                        .chunkKey("ORG-DOC-001#O-001")
                        .competitorName("Notion AI")
                        .evidenceId("ORG-E-001")
                        .sourceCategory("ORGANIZATION_PLAYBOOK")
                        .snippet("organization launch playbook")
                        .content("organization launch playbook for notion ai")
                        .sourceUrls(List.of("https://org.example.com/playbook"))
                        .build(),
                RetrievalChunk.builder()
                        .knowledgeDocumentId(702L)
                        .documentKey("ORG-DOC-002")
                        .retrievalScope("ORGANIZATION")
                        .scopeRefKey("ORGANIZATION")
                        .knowledgeDomainKey("product-governance")
                        .chunkKey("ORG-DOC-002#O-002")
                        .competitorName("Notion AI")
                        .evidenceId("ORG-E-002")
                        .sourceCategory("ORGANIZATION_PLAYBOOK")
                        .snippet("organization archive fallback")
                        .content("organization archive fallback for notion ai")
                        .sourceUrls(List.of("https://org.example.com/archive"))
                        .build()
        ));
        when(embeddingClient.embed(anyString())).thenThrow(new IllegalStateException("embedding timeout"));

        TaskRetrievalService service = new TaskRetrievalService(
                retrievalIndexRepository,
                retrievalChunkRepository,
                embeddingClient,
                3,
                5
        );

        TaskRetrievalService.RetrievalResult result = service.retrieve(
                5L,
                "notion ai governance playbook",
                "analyze_competitors"
        );

        assertEquals(3, result.getChunks().size());
        assertEquals("TASK-DOC-001#T-001", result.getChunks().get(0).getChunkKey());
        assertEquals("DOMAIN-DOC-001#D-001", result.getChunks().get(1).getChunkKey());
        assertEquals("ORG-DOC-001#O-001", result.getChunks().get(2).getChunkKey());
        assertTrue(result.getSourceUrls().contains("https://task.example.com/notion-task"));
        assertTrue(result.getSourceUrls().contains("https://domain.example.com/governance"));
        assertTrue(result.getSourceUrls().contains("https://org.example.com/playbook"));
        assertTrue(result.getIssueFlags().contains("DOMAIN_RETRIEVAL_FALLBACK_USED"));
        assertTrue(result.getIssueFlags().contains("ORGANIZATION_RETRIEVAL_FALLBACK_USED"));
    }

    @Test
    void shouldFallbackToDomainKnowledgeWhenTaskScopedContextIsInsufficient() {
        // Task 5.3.b 的完成标志是：
        // 当任务级上下文不足时，检索主链应稳定补上领域级知识，
        // 而不是直接把“没有任务级索引”暴露给下游 Agent。
        when(retrievalIndexRepository.findByTaskIdOrderByIdAsc(3L)).thenReturn(List.of());
        when(retrievalIndexRepository.findAll()).thenReturn(List.of(
                RetrievalIndex.builder()
                        .taskId(null)
                        .knowledgeDocumentId(301L)
                        .documentKey("DOMAIN-DOC-001")
                        .retrievalScope("DOMAIN")
                        .scopeRefKey("org-product-docs")
                        .knowledgeDomainKey("org-product-docs")
                        .status("READY")
                        .sourceUrls(List.of("https://docs.example.com/notion-governance"))
                        .build()
        ));
        when(retrievalChunkRepository.findAll()).thenReturn(List.of(
                RetrievalChunk.builder()
                        .taskId(null)
                        .knowledgeDocumentId(301L)
                        .documentKey("DOMAIN-DOC-001")
                        .retrievalScope("DOMAIN")
                        .scopeRefKey("org-product-docs")
                        .knowledgeDomainKey("org-product-docs")
                        .chunkKey("DOMAIN-DOC-001#D-001")
                        .competitorName("Notion AI")
                        .evidenceId("ORG-DOC-001")
                        .sourceCategory("UPLOADED_DOCUMENTS")
                        .snippet("领域知识补充了企业治理与发布规范。")
                        .content("组织产品资料中的领域知识补充了企业治理、发布规范与上线流程。")
                        .sourceUrls(List.of("https://docs.example.com/notion-governance"))
                        .build()
        ));
        when(embeddingClient.embed(anyString())).thenThrow(new IllegalStateException("embedding timeout"));

        TaskRetrievalService service = new TaskRetrievalService(
                retrievalIndexRepository,
                retrievalChunkRepository,
                embeddingClient,
                5,
                8
        );

        TaskRetrievalService.RetrievalResult result = service.retrieve(
                3L,
                "Notion AI enterprise governance",
                "analyze_competitors"
        );

        assertEquals(1, result.getChunks().size());
        assertEquals("DOMAIN-DOC-001#D-001", result.getChunks().get(0).getChunkKey());
        assertEquals("UPLOADED_DOCUMENTS", result.getChunks().get(0).getSourceCategory());
        assertEquals(List.of("https://docs.example.com/notion-governance"), result.getSourceUrls());
        assertTrue(result.getIssueFlags().contains("DOMAIN_RETRIEVAL_FALLBACK_USED"));
        assertTrue(result.getGapSummary().contains("领域"));
    }

    @Test
    void shouldAvoidMixingOtherDomainChunksWhenDomainFallbackChoosesRelevantKnowledgeDomain() {
        when(retrievalIndexRepository.findByTaskIdOrderByIdAsc(8L)).thenReturn(List.of());
        when(retrievalIndexRepository.findAll()).thenReturn(List.of(
                RetrievalIndex.builder()
                        .knowledgeDocumentId(801L)
                        .documentKey("DOMAIN-GOV-001")
                        .retrievalScope("DOMAIN")
                        .scopeRefKey("product-governance")
                        .knowledgeDomainKey("product-governance")
                        .status("READY")
                        .sourceUrls(List.of("https://domain.example.com/governance"))
                        .build(),
                RetrievalIndex.builder()
                        .knowledgeDocumentId(802L)
                        .documentKey("DOMAIN-PRICE-001")
                        .retrievalScope("DOMAIN")
                        .scopeRefKey("pricing-ops")
                        .knowledgeDomainKey("pricing-ops")
                        .status("READY")
                        .sourceUrls(List.of("https://domain.example.com/pricing"))
                        .build()
        ));
        when(retrievalChunkRepository.findAll()).thenReturn(List.of(
                RetrievalChunk.builder()
                        .knowledgeDocumentId(801L)
                        .documentKey("DOMAIN-GOV-001")
                        .retrievalScope("DOMAIN")
                        .scopeRefKey("product-governance")
                        .knowledgeDomainKey("product-governance")
                        .chunkKey("DOMAIN-GOV-001#D-001")
                        .competitorName("Notion AI")
                        .evidenceId("DOMAIN-GOV-001")
                        .sourceCategory("DOMAIN_KNOWLEDGE")
                        .snippet("enterprise governance review checklist")
                        .content("enterprise governance review checklist for notion ai launches")
                        .sourceUrls(List.of("https://domain.example.com/governance"))
                        .build(),
                RetrievalChunk.builder()
                        .knowledgeDocumentId(802L)
                        .documentKey("DOMAIN-PRICE-001")
                        .retrievalScope("DOMAIN")
                        .scopeRefKey("pricing-ops")
                        .knowledgeDomainKey("pricing-ops")
                        .chunkKey("DOMAIN-PRICE-001#D-001")
                        .competitorName("Notion AI")
                        .evidenceId("DOMAIN-PRICE-001")
                        .sourceCategory("DOMAIN_KNOWLEDGE")
                        .snippet("pricing discount approval matrix")
                        .content("pricing discount approval matrix for enterprise deals")
                        .sourceUrls(List.of("https://domain.example.com/pricing"))
                        .build()
        ));
        when(retrievalIndexRepository.findByRetrievalScopeAndScopeRefKeyOrderByIdAsc("ORGANIZATION", "ORGANIZATION"))
                .thenReturn(List.of());
        when(embeddingClient.embed(anyString())).thenThrow(new IllegalStateException("embedding timeout"));

        TaskRetrievalService service = new TaskRetrievalService(
                retrievalIndexRepository,
                retrievalChunkRepository,
                embeddingClient,
                3,
                5
        );

        TaskRetrievalService.RetrievalResult result = service.retrieve(
                8L,
                "notion ai enterprise governance review",
                "quality_check"
        );

        /**
         * 5.3 要求领域级回退先在当前相关知识域内收束，再决定是否继续跨层回退。
         * 因此当查询已经明显命中 governance 领域时，pricing 领域片段不应混入同一轮 DOMAIN 回退结果。
         */
        assertEquals(1, result.getChunks().size());
        assertEquals("DOMAIN-GOV-001#D-001", result.getChunks().get(0).getChunkKey());
        assertEquals(List.of("https://domain.example.com/governance"), result.getSourceUrls());
        assertTrue(result.getIssueFlags().contains("DOMAIN_RETRIEVAL_FALLBACK_USED"));
    }

    @Test
    void shouldFallbackToLexicalRecallAndPreserveTraceabilityWhenEmbeddingFails() {
        // 当前测试用于锁定 Task 5.1 的治理边界：
        // embedding 失败后应立即走词法召回降级，不能在业务层叠加第二轮重试。
        when(retrievalIndexRepository.findByTaskIdOrderByIdAsc(1L)).thenReturn(List.of(
                RetrievalIndex.builder()
                        .taskId(1L)
                        .knowledgeDocumentId(11L)
                        .documentKey("DOC-001")
                        .status("READY")
                        .sourceUrls(List.of("https://www.notion.so/product/ai"))
                        .build(),
                RetrievalIndex.builder()
                        .taskId(1L)
                        .knowledgeDocumentId(12L)
                        .documentKey("DOC-002")
                        .status("FAILED")
                        .sourceUrls(List.of("https://stale.example.com"))
                        .build()
        ));
        when(retrievalChunkRepository.findByTaskIdOrderByKnowledgeDocumentIdAscChunkIndexAsc(1L)).thenReturn(List.of(
                RetrievalChunk.builder()
                        .taskId(1L)
                        .knowledgeDocumentId(11L)
                        .documentKey("DOC-001")
                        .chunkKey("DOC-001#CHUNK-001")
                        .competitorName("Notion AI")
                        .evidenceId("E001")
                        .sourceCategory("AI_DISCOVERED")
                        .snippet("Enterprise governance and pricing overview")
                        .content("Notion AI provides enterprise governance capabilities and partial pricing guidance.")
                        .sourceUrls(List.of("https://www.notion.so/product/ai"))
                        .build(),
                RetrievalChunk.builder()
                        .taskId(1L)
                        .knowledgeDocumentId(12L)
                        .documentKey("DOC-002")
                        .chunkKey("DOC-002#CHUNK-001")
                        .competitorName("Notion AI")
                        .evidenceId("E002")
                        .sourceCategory("AI_DISCOVERED")
                        .snippet("Stale page")
                        .content("This stale page should be ignored because its index is failed.")
                        .sourceUrls(List.of("https://stale.example.com"))
                        .build()
        ));
        when(embeddingClient.embed(anyString())).thenThrow(new IllegalStateException("embedding timeout"));

        // 这里把重试次数设为 2，便于直接验证失败后的重试和降级路径。
        TaskRetrievalService service = new TaskRetrievalService(
                retrievalIndexRepository,
                retrievalChunkRepository,
                embeddingClient,
                5,
                8
        );

        TaskRetrievalService.RetrievalResult result = service.retrieve(
                1L,
                "enterprise pricing governance",
                "analyze_competitors"
        );

        assertEquals(1, result.getChunks().size());
        assertEquals("DOC-001#CHUNK-001", result.getChunks().get(0).getChunkKey());
        assertEquals("AI_DISCOVERED", result.getChunks().get(0).getSourceCategory());
        assertEquals("https://www.notion.so/product/ai", result.getSourceUrls().get(0));
        assertTrue(result.getIssueFlags().contains("EMBEDDING_RETRIEVAL_DEGRADED"));
        assertTrue(result.getGapSummary().contains("召回"));

        // 外部 embedding 失败后必须有限次重试，而不是无限重放或静默吞掉异常。
        verify(embeddingClient, times(1)).embed(anyString());
    }

    @Test
    void shouldKeepIndexLevelSourceUrlsWhenChunkTraceabilityIsMissing() {
        when(retrievalIndexRepository.findByTaskIdOrderByIdAsc(2L)).thenReturn(List.of(
                RetrievalIndex.builder()
                        .taskId(2L)
                        .knowledgeDocumentId(21L)
                        .documentKey("DOC-TRACE-001")
                        .status("READY")
                        .sourceUrls(List.of("https://docs.example.com/pricing"))
                        .build()
        ));
        when(retrievalChunkRepository.findByTaskIdOrderByKnowledgeDocumentIdAscChunkIndexAsc(2L)).thenReturn(List.of(
                RetrievalChunk.builder()
                        .taskId(2L)
                        .knowledgeDocumentId(21L)
                        .documentKey("DOC-TRACE-001")
                        .chunkKey("DOC-TRACE-001#001")
                        .competitorName("Notion AI")
                        .evidenceId("E-TRACE-001")
                        .sourceCategory("AI_DISCOVERED")
                        .snippet("pricing note")
                        .content("pricing note with missing chunk urls")
                        .sourceUrls(List.of())
                        .build()
        ));
        when(embeddingClient.embed(anyString())).thenThrow(new IllegalStateException("embedding timeout"));

        TaskRetrievalService service = new TaskRetrievalService(
                retrievalIndexRepository,
                retrievalChunkRepository,
                embeddingClient,
                5,
                8
        );

        TaskRetrievalService.RetrievalResult result = service.retrieve(
                2L,
                "pricing note",
                "write_report"
        );

        /**
         * 就算切片级 sourceUrls 暂时缺失，召回结果也不能丢掉索引级来源链接。
         * 否则下游 Agent 和审计接口会看到“命中了证据，但没有来源”的伪不可追溯结果。
         */
        assertEquals(List.of("https://docs.example.com/pricing"), result.getSourceUrls());
        assertEquals("AI_DISCOVERED", result.getChunks().get(0).getSourceCategory());
    }
}
