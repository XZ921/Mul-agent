package cn.bugstack.competitoragent.agent.citation;

import cn.bugstack.competitoragent.workflow.contract.CitationClaim;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CitationClaimExtractorTest {

    private final CitationClaimExtractor extractor = new CitationClaimExtractor();

    @Test
    void shouldIgnoreFormattingOnlyFragmentsAndKeepDowngradedMarkdownSentenceTogether() {
        List<CitationClaim> claims = extractor.extract("""
                ## 策略性建议
                1.**生态互补性探索：**
                *   **推测，当前公开资料未能验证，需补充证据。** 可能存在审核机制复杂、竞争激烈导致流量成本上升、规则变动快等通用平台风险。
                *   **潜在短板与风险：**
                **
                """);

        assertThat(claims).hasSize(1);
        CitationClaim claim = claims.get(0);
        assertThat(claim.getSectionTitle()).isEqualTo("策略性建议");
        assertThat(claim.getClaimText()).contains("推测，当前公开资料未能验证");
        assertThat(claim.getClaimText()).contains("可能存在审核机制复杂");
        assertThat(claim.isExplicitlyDowngraded()).isTrue();
    }

    @Test
    void shouldExtractEvidenceIdsFromChineseCitationMarker() {
        String report = "## 结论\n"
                + "Notion AI 在企业知识管理场景中更适合作为统一工作台。"
                + "[\u8BC1\u636E\uFF1AE999]";

        List<CitationClaim> claims = extractor.extract(report);

        assertThat(claims).hasSize(1);
        CitationClaim claim = claims.get(0);
        assertThat(claim.getEvidenceIds()).containsExactly("E999");
        assertThat(claim.getClaimText()).doesNotContain("\u8BC1\u636E");
        assertThat(claim.isTraceabilitySensitive()).isTrue();
    }
}
