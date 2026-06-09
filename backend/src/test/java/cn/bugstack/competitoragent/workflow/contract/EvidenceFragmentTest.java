package cn.bugstack.competitoragent.workflow.contract;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceFragmentTest {

    @Test
    void shouldNormalizeIssueFlagsAndAutoMarkMissingSourceUrl() {
        EvidenceFragment fragment = EvidenceFragment.builder()
                .evidenceId("E001")
                .sourceUrl(" ")
                .issueFlags(List.of("MISSING_EVIDENCE", "", "MISSING_EVIDENCE"))
                .build();

        EvidenceFragment normalized = fragment.normalized();

        assertEquals(List.of("MISSING_EVIDENCE", "MISSING_SOURCE_URL"), normalized.getIssueFlags());
    }

    @Test
    void shouldCollectDistinctSourceUrlsFromFragments() {
        List<String> sourceUrls = EvidenceFragment.collectSourceUrls(List.of(
                EvidenceFragment.builder().sourceUrl("https://example.com/docs").build(),
                EvidenceFragment.builder().sourceUrl("https://example.com/docs").build(),
                EvidenceFragment.builder().sourceUrl("https://example.com/pricing").build(),
                EvidenceFragment.builder().sourceUrl(" ").build()
        ));

        assertEquals(List.of("https://example.com/docs", "https://example.com/pricing"), sourceUrls);
        assertTrue(sourceUrls.stream().noneMatch(String::isBlank));
    }
}
