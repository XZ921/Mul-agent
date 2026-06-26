package cn.bugstack.competitoragent.agent.citation;

import cn.bugstack.competitoragent.workflow.contract.CitationClaim;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CitationClaimExtractorTest {

    private final CitationClaimExtractor extractor = new CitationClaimExtractor();

    @Test
    void shouldExtractEvidenceIdsFromChineseEvidenceMarks() {
        String report = """
                # 竞品分析报告
                ## 定价策略
                Notion AI 采用按席位计费，并在企业版中提供高级安全能力。[证据：E001][证据:E002]
                """;

        List<CitationClaim> claims = extractor.extract(report);

        assertThat(claims).hasSize(1);
        assertThat(claims.get(0).getClaimText()).contains("按席位计费");
        assertThat(claims.get(0).getEvidenceIds()).containsExactly("E001", "E002");
        assertThat(claims.get(0).isTraceabilitySensitive()).isTrue();
        assertThat(claims.get(0).isExplicitlyDowngraded()).isFalse();
    }

    @Test
    void shouldFlagSensitiveClaimWithoutCitationUnlessExplicitlyDowngraded() {
        String report = """
                ## 行动建议
                建议优先学习 Notion AI 的企业权限设计。
                该判断为推测，当前公开资料未能验证，需补充证据。
                """;

        List<CitationClaim> claims = extractor.extract(report);

        assertThat(claims).hasSize(2);
        assertThat(claims.get(0).getEvidenceIds()).isEmpty();
        assertThat(claims.get(0).isTraceabilitySensitive()).isTrue();
        assertThat(claims.get(0).isExplicitlyDowngraded()).isFalse();
        assertThat(claims.get(1).isExplicitlyDowngraded()).isTrue();
    }
}
