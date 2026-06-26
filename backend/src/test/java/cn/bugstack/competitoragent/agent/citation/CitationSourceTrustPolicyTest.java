package cn.bugstack.competitoragent.agent.citation;

import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.workflow.contract.CitationSourceTrustFinding;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CitationSourceTrustPolicyTest {

    private final CitationSourceTrustPolicy policy = new CitationSourceTrustPolicy();

    @Test
    void shouldTreatOfficialDocsPricingAsHighTrust() {
        EvidenceSource evidence = EvidenceSource.builder()
                .evidenceId("E001")
                .url("https://www.notion.so/pricing")
                .sourceDomain("www.notion.so")
                .sourceType("PRICING")
                .sourceCategory("OFFICIAL")
                .sourceScore(0.91)
                .contentSnippet("Notion pricing plans include Plus, Business and Enterprise.")
                .build();

        CitationSourceTrustFinding finding = policy.evaluate(evidence);

        assertThat(finding.getTrustTier()).isEqualTo("HIGH_TRUST");
        assertThat(finding.getIssueFlags()).isEmpty();
        assertThat(finding.getSourceUrls()).containsExactly("https://www.notion.so/pricing");
    }

    @Test
    void shouldFlagUnknownOrThinSourceAsLowTrust() {
        EvidenceSource evidence = EvidenceSource.builder()
                .evidenceId("E009")
                .url("https://mirror.example.net/notion-ai")
                .sourceDomain("mirror.example.net")
                .sourceType("UNKNOWN")
                .sourceCategory("AI_DISCOVERED")
                .sourceScore(0.31)
                .contentSnippet("")
                .fullContent("")
                .build();

        CitationSourceTrustFinding finding = policy.evaluate(evidence);

        assertThat(finding.getTrustTier()).isEqualTo("LOW_TRUST");
        assertThat(finding.getIssueFlags()).contains("LOW_SOURCE_SCORE", "THIN_SOURCE_CONTENT", "UNKNOWN_SOURCE_TYPE");
    }
}
