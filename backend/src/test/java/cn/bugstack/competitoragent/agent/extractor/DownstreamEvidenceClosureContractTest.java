package cn.bugstack.competitoragent.agent.extractor;

import cn.bugstack.competitoragent.workflow.contract.DownstreamEvidenceBlock;
import cn.bugstack.competitoragent.workflow.contract.DownstreamEvidenceQuality;
import cn.bugstack.competitoragent.workflow.contract.DownstreamEvidenceView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DownstreamEvidenceClosureContractTest {

    @Test
    void shouldExposeStructuredEvidenceViewWithQualitySignalsBlocksAndSourceUrls() {
        // 下游统一证据视图必须同时承载来源、质量信号与结构化块，避免 extractor/analyzer 各自解析 metadata。
        DownstreamEvidenceView evidenceView = DownstreamEvidenceView.builder()
                .evidenceId("E001")
                .sourceType("DOCS")
                .title("Pricing Docs")
                .content("公开定价页正文")
                .sourceUrls(List.of("https://docs.example.com/pricing"))
                .qualitySignals(List.of("STRUCTURED_BLOCK_HIT", "PRICING_BLOCK_HIT"))
                .structuredBlocks(List.of(
                        DownstreamEvidenceBlock.builder()
                                .blockType("PRICING_BLOCK")
                                .summary("Pro 199 / 月")
                                .sourceUrls(List.of("https://docs.example.com/pricing"))
                                .build()
                ))
                .quality(DownstreamEvidenceQuality.builder()
                        .qualityScore(0.82D)
                        .failureKind(null)
                        .build())
                .build()
                .normalized();

        assertThat(evidenceView.getSourceUrls()).containsExactly("https://docs.example.com/pricing");
        assertThat(evidenceView.getQualitySignals()).contains("STRUCTURED_BLOCK_HIT", "PRICING_BLOCK_HIT");
        assertThat(evidenceView.getStructuredBlocks()).extracting(DownstreamEvidenceBlock::getBlockType)
                .containsExactly("PRICING_BLOCK");
        assertThat(evidenceView.getQuality().getQualityScore()).isEqualTo(0.82D);
    }
}
