package cn.bugstack.competitoragent.rag;

import cn.bugstack.competitoragent.model.entity.KnowledgeDocument;
import cn.bugstack.competitoragent.model.entity.RetrievalChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalChunkingServiceTest {

    private final RetrievalChunkingService chunkingService = new RetrievalChunkingService(32, 8);

    @Test
    void shouldSplitKnowledgeDocumentIntoTraceableChunks() {
        KnowledgeDocument document = KnowledgeDocument.builder()
                .id(11L)
                .taskId(1L)
                .competitorName("Feishu")
                .evidenceId("T0001-COLLECT-001")
                .documentKey("TASK-0001-T0001-COLLECT-001")
                .documentVersion(1)
                .sourceCategory("USER_PROVIDED")
                .sourceUrls(List.of("https://example.com/docs"))
                .issueFlags(List.of("SOURCE_URLS_BACKFILLED"))
                .cleanedText("Feishu docs support api reference and pricing details for team collaboration workflows.")
                .build();

        List<RetrievalChunk> chunks = chunkingService.chunk(document);

        assertTrue(chunks.size() >= 2);
        assertEquals(11L, chunks.get(0).getKnowledgeDocumentId());
        assertEquals("TASK-0001-T0001-COLLECT-001", chunks.get(0).getDocumentKey());
        assertEquals("https://example.com/docs", chunks.get(0).getSourceUrls().get(0));
        assertTrue(chunks.get(0).getContent().startsWith("Feishu docs"));
    }
}
