package cn.bugstack.competitoragent.agent.collector;

import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceSourceSanitizerTest {

    private final EvidenceSourceSanitizer sanitizer = new EvidenceSourceSanitizer();

    @Test
    void shouldTrimLengthLimitedFieldsBeforePersistence() {
        EvidenceSource source = EvidenceSource.builder()
                .taskId(53L)
                .competitorName("哔哩哔哩")
                .evidenceId("T0053-COLLECT_SOURCES_01_01-001")
                .title("T".repeat(600))
                .url("https://example.com/" + "a".repeat(2200))
                .sourceType("OFFICIAL".repeat(20))
                .discoveryMethod("BROWSER".repeat(20))
                .sourceCategory("AI_DISCOVERED".repeat(20))
                .sourceDomain("sub." + "example".repeat(80) + ".com")
                .discoveryReason("R".repeat(900))
                .publishedAt("2026-06-22T20:05:35.373+08:00-too-long")
                .build();

        EvidenceSource sanitized = sanitizer.sanitize(source);

        assertEquals(500, sanitized.getTitle().length());
        assertEquals(2048, sanitized.getUrl().length());
        assertTrue(sanitized.getSourceType().length() <= 50);
        assertTrue(sanitized.getDiscoveryMethod().length() <= 50);
        assertTrue(sanitized.getSourceCategory().length() <= 50);
        assertEquals(255, sanitized.getSourceDomain().length());
        assertEquals(900, sanitized.getDiscoveryReason().length());
        assertEquals(30, sanitized.getPublishedAt().length());
    }
}
