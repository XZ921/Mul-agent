package cn.bugstack.competitoragent.workflow.contract;

import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DownstreamEvidenceViewAssemblerTest {

    @Test
    void shouldBuildEvidenceViewsFromEvidenceSourceMetadata() {
        EvidenceSource evidence = EvidenceSource.builder()
                .evidenceId("E001")
                .competitorName("Acme")
                .sourceType("DOCS")
                .title("Pricing Docs")
                .url("https://docs.example.com/pricing")
                .fullContent("公开定价页正文")
                .pageMetadata("""
                        {
                          "sourceUrls": ["https://docs.example.com/pricing"],
                          "qualitySignals": ["STRUCTURED_BLOCK_HIT", "PRICING_BLOCK_HIT"],
                          "structuredBlocks": [{"blockType": "PRICING_BLOCK", "summary": "Pro 199 / 月"}],
                          "qualityScore": 0.82
                        }
                        """)
                .build();

        // pageMetadata 只允许通过统一装配器变成下游视图，后续节点不能再散落重复解析逻辑。
        DownstreamEvidenceViewAssembler assembler = new DownstreamEvidenceViewAssembler(new ObjectMapper());
        List<DownstreamEvidenceView> views = assembler.fromEvidenceSources(List.of(evidence));

        assertThat(views).hasSize(1);
        assertThat(views.get(0).getSourceUrls()).containsExactly("https://docs.example.com/pricing");
        assertThat(views.get(0).getQualitySignals()).contains("STRUCTURED_BLOCK_HIT", "PRICING_BLOCK_HIT");
        assertThat(views.get(0).getStructuredBlocks()).extracting(DownstreamEvidenceBlock::getBlockType)
                .containsExactly("PRICING_BLOCK");
    }
}
