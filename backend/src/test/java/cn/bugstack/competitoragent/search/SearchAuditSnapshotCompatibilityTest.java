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
        assertNull(snapshot.getExecutionTrace().getTavilyFastLaneAudit());
        assertNull(snapshot.getTavilyFastLaneAudit());
        assertNull(snapshot.getSourceUrls());
        assertNull(snapshot.getSelectedTargets());
    }

    @Test
    void shouldIgnoreFutureUnknownFieldsWhenReadingSnapshot() throws Exception {
        String futureJson = """
                {
                  "executionTrace":{
                    "recoveryCheckpoint":"VERIFY_TOP_CANDIDATES",
                    "tavilyFastLaneAudit":{
                      "queryModes":["OFFICIAL_DOCS"],
                      "queryOrigins":["BOOTSTRAP"],
                      "queriesSent":1,
                      "totalResults":5,
                      "fastLaneUsableCount":3,
                      "fastLaneRejectedCount":2,
                      "bootstrapTriggered":true,
                      "fallbackTriggered":true,
                      "rejectionReasons":{"SEARCH_PAGE":1,"WEAK_CONTENT":1},
                      "tavilyRequestIds":["req-1"],
                      "playwrightInvocationBaselineHint":1
                    }
                  },
                  "tavilyFastLaneAudit":{
                    "queryModes":["OFFICIAL_DOCS"],
                    "queryOrigins":["BOOTSTRAP"],
                    "queriesSent":1,
                    "totalResults":5,
                    "fastLaneUsableCount":3,
                    "fastLaneRejectedCount":2,
                    "bootstrapTriggered":true,
                    "fallbackTriggered":true,
                    "rejectionReasons":{"SEARCH_PAGE":1,"WEAK_CONTENT":1},
                    "tavilyRequestIds":["req-1"],
                    "playwrightInvocationBaselineHint":1
                  },
                  "sourceUrls":["https://docs.notion.so/reference"],
                  "selectedTargets":[{"candidate":{"url":"https://docs.notion.so/reference"}}],
                  "futureField":{"unexpected":true}
                }
                """;

        SearchAuditSnapshot snapshot = objectMapper.readValue(futureJson, SearchAuditSnapshot.class);

        assertEquals("VERIFY_TOP_CANDIDATES", snapshot.getExecutionTrace().getRecoveryCheckpoint());
        assertEquals(3, snapshot.getTavilyFastLaneAudit().getFastLaneUsableCount());
        assertEquals(2, snapshot.getExecutionTrace().getTavilyFastLaneAudit().getFastLaneRejectedCount());
        assertTrue(snapshot.getTavilyFastLaneAudit().getQueryModes().contains("OFFICIAL_DOCS"));
        assertTrue(snapshot.getTavilyFastLaneAudit().getQueryOrigins().contains("BOOTSTRAP"));
        assertTrue(Boolean.TRUE.equals(snapshot.getTavilyFastLaneAudit().getBootstrapTriggered()));
        assertTrue(snapshot.getSourceUrls().contains("https://docs.notion.so/reference"));
        assertEquals("https://docs.notion.so/reference",
                snapshot.getSelectedTargets().get(0).getCandidate().getUrl());
    }
}
