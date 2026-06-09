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
import static org.mockito.Mockito.when;

class OrganizationRetrievalServiceTest {

    private final RetrievalIndexRepository retrievalIndexRepository = mock(RetrievalIndexRepository.class);
    private final RetrievalChunkRepository retrievalChunkRepository = mock(RetrievalChunkRepository.class);
    private final EmbeddingClient embeddingClient = mock(EmbeddingClient.class);

    @Test
    void shouldKeepOnlyReadyOrganizationChunksAndPreserveSourceUrls() {
        // Task 5.3.e 需要把组织级召回的基础治理语义补成自动化测试：
        // 1. 只消费 READY 的组织级索引与切片；
        // 2. 即使某个切片暂时缺少 sourceUrls，也要从索引级来源补齐对外可追溯链路。
        when(retrievalIndexRepository.findByRetrievalScopeAndScopeRefKeyOrderByIdAsc("ORGANIZATION", "ORGANIZATION"))
                .thenReturn(List.of(
                        RetrievalIndex.builder()
                                .knowledgeDocumentId(701L)
                                .documentKey("ORG-DOC-001")
                                .retrievalScope("ORGANIZATION")
                                .scopeRefKey("ORGANIZATION")
                                .status("READY")
                                .sourceUrls(List.of("https://org.example.com/playbook"))
                                .build(),
                        RetrievalIndex.builder()
                                .knowledgeDocumentId(702L)
                                .documentKey("ORG-DOC-002")
                                .retrievalScope("ORGANIZATION")
                                .scopeRefKey("ORGANIZATION")
                                .status("READY")
                                .sourceUrls(List.of("https://org.example.com/policy"))
                                .build(),
                        RetrievalIndex.builder()
                                .knowledgeDocumentId(799L)
                                .documentKey("ORG-DOC-FAILED")
                                .retrievalScope("ORGANIZATION")
                                .scopeRefKey("ORGANIZATION")
                                .status("FAILED")
                                .sourceUrls(List.of("https://org.example.com/failed"))
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
                        .knowledgeDomainKey("enterprise-governance")
                        .chunkKey("ORG-DOC-001#O-001")
                        .competitorName("GitHub Copilot")
                        .evidenceId("ORG-E-001")
                        .sourceCategory("ORGANIZATION_PLAYBOOK")
                        .snippet("organization governance playbook")
                        .content("organization governance playbook for GitHub Copilot")
                        .sourceUrls(List.of("https://org.example.com/playbook"))
                        .build(),
                RetrievalChunk.builder()
                        .knowledgeDocumentId(702L)
                        .documentKey("ORG-DOC-002")
                        .retrievalScope("ORGANIZATION")
                        .scopeRefKey("ORGANIZATION")
                        .knowledgeDomainKey("enterprise-governance")
                        .chunkKey("ORG-DOC-002#O-002")
                        .competitorName("GitHub Copilot")
                        .evidenceId("ORG-E-002")
                        .sourceCategory("ORGANIZATION_POLICY")
                        .snippet("organization policy fallback")
                        .content("organization policy fallback for GitHub Copilot")
                        .sourceUrls(List.of())
                        .build(),
                RetrievalChunk.builder()
                        .knowledgeDocumentId(799L)
                        .documentKey("ORG-DOC-FAILED")
                        .retrievalScope("ORGANIZATION")
                        .scopeRefKey("ORGANIZATION")
                        .knowledgeDomainKey("enterprise-governance")
                        .chunkKey("ORG-DOC-FAILED#O-999")
                        .competitorName("GitHub Copilot")
                        .evidenceId("ORG-E-999")
                        .sourceCategory("ORGANIZATION_ARCHIVE")
                        .snippet("failed organization archive")
                        .content("failed organization archive should be ignored")
                        .sourceUrls(List.of("https://org.example.com/failed"))
                        .build()
        ));
        when(embeddingClient.embed(anyString())).thenThrow(new IllegalStateException("embedding timeout"));

        OrganizationRetrievalService service = new OrganizationRetrievalService(
                retrievalIndexRepository,
                retrievalChunkRepository,
                embeddingClient,
                2,
                4
        );

        TaskRetrievalService.RetrievalResult result = service.retrieve(
                "GitHub Copilot enterprise governance playbook",
                "write_report"
        );

        assertEquals(2, result.getChunks().size());
        assertEquals("ORG-DOC-001#O-001", result.getChunks().get(0).getChunkKey());
        assertTrue(result.getChunks().stream().allMatch(chunk -> "ORGANIZATION".equals(chunk.getRetrievalScope())));
        assertTrue(result.getChunks().stream().noneMatch(chunk -> "ORG-DOC-FAILED".equals(chunk.getDocumentKey())));
        assertTrue(result.getSourceUrls().contains("https://org.example.com/playbook"));
        assertTrue(result.getSourceUrls().contains("https://org.example.com/policy"));
        assertTrue(result.getIssueFlags().contains("EMBEDDING_RETRIEVAL_DEGRADED"));
        assertTrue(result.getGapSummary().contains("组织级"));
    }
}
