package cn.bugstack.competitoragent.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchAuditSnapshotCompatibilityTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void shouldReadHistoricalSnapshotWithoutNewFields() throws Exception {
        String historicalJson = """
                {
                  "executionTrace":{"recoveryCheckpoint":"SELECT_TARGETS"},
                  "latestProgress":{"currentStep":"SELECT_TARGETS","status":"SUCCESS"}
                }
                """;

        SearchAuditSnapshot snapshot = objectMapper.readValue(historicalJson, SearchAuditSnapshot.class);

        assertEquals("SELECT_TARGETS", snapshot.getExecutionTrace().getRecoveryCheckpoint());
        assertNull(snapshot.getSourceUrls());
        assertNull(snapshot.getSelectedTargets());
    }

    @Test
    void shouldIgnoreFutureUnknownFieldsWhenReadingSnapshot() throws Exception {
        String futureJson = """
                {
                  "executionTrace":{"recoveryCheckpoint":"VERIFY_TOP_CANDIDATES"},
                  "sourceUrls":["https://docs.notion.so/reference"],
                  "selectedTargets":[{"candidate":{"url":"https://docs.notion.so/reference"}}],
                  "futureField":{"unexpected":true}
                }
                """;

        SearchAuditSnapshot snapshot = objectMapper.readValue(futureJson, SearchAuditSnapshot.class);

        assertEquals("VERIFY_TOP_CANDIDATES", snapshot.getExecutionTrace().getRecoveryCheckpoint());
        assertTrue(snapshot.getSourceUrls().contains("https://docs.notion.so/reference"));
        assertEquals("https://docs.notion.so/reference",
                snapshot.getSelectedTargets().get(0).getCandidate().getUrl());
    }
}
