package cn.bugstack.competitoragent.extractor.input;

import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.workflow.contract.DownstreamEvidenceBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractorEvidenceInputAssemblerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ExtractorEvidenceInputAssembler assembler = new ExtractorEvidenceInputAssembler(objectMapper);

    @Test
    void shouldNormalizeKeyIdentifiersToEmptyStrings() {
        ExtractorEvidenceInput normalized = ExtractorEvidenceInput.builder()
                .evidenceId(null)
                .competitorName("  ")
                .sourceType(null)
                .title(" ")
                .content(null)
                .structuredPayload(Map.of("plans", List.of(Map.of("name", "Pro"))))
                .build()
                .normalized();

        assertThat(normalized.getEvidenceId()).isEqualTo("");
        assertThat(normalized.getCompetitorName()).isEqualTo("");
        assertThat(normalized.getSourceType()).isEqualTo("");
        assertThat(normalized.getTitle()).isEqualTo("");
        assertThat(normalized.getContent()).isEqualTo("");
        assertThat(normalized.getStructuredPayload()).containsKey("plans");
    }

    @Test
    void shouldAssembleExtractorEvidenceInputFromEvidenceSource() {
        EvidenceSource pricingApi = EvidenceSource.builder()
                .taskId(7L)
                .competitorName("Acme")
                .evidenceId("E701")
                .title("Pricing API")
                .url("https://api.acme.com/pricing")
                .sourceType("API_DATA")
                .fullContent("Pro 199 / 月，包含席位、计费周期、试用信息。")
                .pageMetadata("""
                        {
                          "sourceUrls": ["https://api.acme.com/pricing"],
                          "qualitySignals": ["STRUCTURED_BLOCK_HIT", "PRICING_BLOCK_HIT"],
                          "structuredBlocks": [{"blockType": "PRICING_BLOCK", "summary": "Pro 199 / 月"}],
                          "structuredPayload": {"plans": [{"name": "Pro", "price": 199}]},
                          "qualityScore": 0.98
                        }
                        """)
                .build();

        ExtractorEvidenceInput input = assembler.fromEvidenceSource(pricingApi);

        assertThat(input.getEvidenceId()).isEqualTo("E701");
        assertThat(input.getContent()).contains("Pro 199 / 月");
        assertThat(input.getStructuredPayload()).containsKey("plans");
        assertThat(input.getStructuredBlocks())
                .extracting(DownstreamEvidenceBlock::getBlockType)
                .containsExactly("PRICING_BLOCK");
    }
}
