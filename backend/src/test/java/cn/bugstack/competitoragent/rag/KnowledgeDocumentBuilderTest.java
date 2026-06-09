package cn.bugstack.competitoragent.rag;

import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.model.entity.KnowledgeDocument;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeDocumentBuilderTest {

    private final KnowledgeDocumentBuilder builder = new KnowledgeDocumentBuilder();

    @Test
    void shouldBuildTraceableKnowledgeDocumentFromEvidenceSource() {
        EvidenceSource evidence = EvidenceSource.builder()
                .taskId(1L)
                .competitorName("Feishu")
                .evidenceId("T0001-COLLECT-001")
                .title("Feishu Docs")
                .url("https://example.com/docs")
                .contentSnippet("api reference")
                .fullContent("  Feishu docs support api reference and pricing details.  ")
                .sourceType("DOCS")
                .sourceCategory("USER_PROVIDED")
                .discoveryMethod("CONFIG")
                .sourceDomain("example.com")
                .collectedAt(LocalDateTime.of(2026, 6, 5, 20, 0))
                .build();

        KnowledgeDocument document = builder.build(evidence, 1);

        assertEquals(1L, document.getTaskId());
        assertEquals("T0001-COLLECT-001", document.getEvidenceId());
        assertEquals("USER_PROVIDED", document.getSourceCategory());
        assertEquals("https://example.com/docs", document.getSourceUrls().get(0));
        assertTrue(document.getCleanedText().contains("Feishu docs support api reference"));
        assertEquals("PROCESSING", document.getStatus());
    }
}
