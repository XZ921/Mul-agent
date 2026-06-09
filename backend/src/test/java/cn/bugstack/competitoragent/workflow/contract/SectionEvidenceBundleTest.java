package cn.bugstack.competitoragent.workflow.contract;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionEvidenceBundleTest {

    @Test
    void shouldBackfillSectionSourceUrlsAndFieldNamesFromEvidenceFragments() {
        SectionEvidenceBundle bundle = SectionEvidenceBundle.builder()
                .stage("ANALYZE")
                .sectionType("SECTION")
                .sectionKey("pricing")
                .sectionTitle("定价策略")
                .evidenceFragments(List.of(
                        EvidenceFragment.builder()
                                .fieldName("pricingComparison")
                                .fieldLabel("定价结论")
                                .sourceUrl("https://docs.example.com/pricing")
                                .build(),
                        EvidenceFragment.builder()
                                .fieldName("pricingComparison")
                                .fieldLabel("定价结论")
                                .sourceUrl("https://docs.example.com/pricing")
                                .build()
                ))
                .build();

        SectionEvidenceBundle normalized = bundle.normalized();

        assertEquals(List.of("https://docs.example.com/pricing"), normalized.getSourceUrls());
        assertEquals(List.of("pricingComparison"), normalized.getFieldNames());
        assertTrue(normalized.getIssueFlags().stream().noneMatch("NO_USABLE_EVIDENCE"::equals));
    }

    @Test
    void shouldMarkGapWhenSectionHasNoUsableEvidence() {
        SectionEvidenceBundle bundle = SectionEvidenceBundle.builder()
                .stage("WRITE")
                .sectionType("CONCLUSION")
                .sectionKey("conclusion")
                .sectionTitle("结论与建议")
                .missingFields(List.of("recommendations"))
                .issueFlags(List.of("SECTION_EVIDENCE_GAP", "", "SECTION_EVIDENCE_GAP"))
                .build();

        SectionEvidenceBundle normalized = bundle.normalized();

        assertTrue(normalized.getIssueFlags().contains("SECTION_EVIDENCE_GAP"));
        assertTrue(normalized.getIssueFlags().contains("NO_USABLE_EVIDENCE"));
        assertTrue(normalized.getGapSummary().contains("recommendations"));
    }
}
