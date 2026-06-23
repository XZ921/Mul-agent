package cn.bugstack.competitoragent.extractor.input;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.task.SharedNodeOutputEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RepositoryExtractorInputProviderTest {

    private final EvidenceSourceRepository evidenceRepository = mock(EvidenceSourceRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RepositoryExtractorInputProvider provider = new RepositoryExtractorInputProvider(
            new RepositoryExtractorEvidenceSourcePort(
                    evidenceRepository,
                    new ExtractorEvidenceInputAssembler(objectMapper)),
            objectMapper
    );

    @Test
    void shouldPrioritizeStructuredAndOfficialEvidenceWithinBudgetAndKeepSkippedTraceability() {
        EvidenceSource pricingBlock = EvidenceSource.builder()
                .taskId(1L)
                .competitorName("Acme")
                .evidenceId("E001")
                .title("Pricing")
                .url("https://acme.com/pricing")
                .sourceType("PRICING")
                .fullContent("A".repeat(3200))
                .pageMetadata("""
                        {
                          "sourceUrls": ["https://acme.com/pricing"],
                          "qualitySignals": ["STRUCTURED_BLOCK_HIT", "PRICING_BLOCK_HIT"],
                          "structuredBlocks": [{"blockType": "PRICING_BLOCK", "summary": "Pro 199 / 月"}],
                          "qualityScore": 0.99
                        }
                        """)
                .build();
        EvidenceSource officialDocs = EvidenceSource.builder()
                .taskId(1L)
                .competitorName("Acme")
                .evidenceId("E002")
                .title("Official Docs")
                .url("https://acme.com/docs")
                .sourceType("DOCS")
                .fullContent("B".repeat(1600))
                .pageMetadata("""
                        {
                          "sourceUrls": ["https://acme.com/docs"],
                          "qualitySignals": ["LIGHTWEIGHT_CONTENT_READY"],
                          "qualityScore": 0.88
                        }
                        """)
                .build();
        EvidenceSource news = EvidenceSource.builder()
                .taskId(1L)
                .competitorName("Acme")
                .evidenceId("E003")
                .title("News")
                .url("https://news.example.com/acme")
                .sourceType("NEWS")
                .fullContent("C".repeat(1800))
                .pageMetadata("""
                        {
                          "sourceUrls": ["https://news.example.com/acme"],
                          "qualitySignals": ["LIGHTWEIGHT_CONTENT_READY"],
                          "qualityScore": 0.55
                        }
                        """)
                .build();
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(1L))
                .thenReturn(List.of(pricingBlock, officialDocs, news));

        ExtractorInputPackage inputPackage = provider.provide(AgentContext.builder()
                .taskId(1L)
                .taskName("task")
                .currentNodeName("extract_schema")
                .build());

        ExtractorCompetitorInput competitorInput = inputPackage.getCompetitors().get(0);
        assertThat(competitorInput.getEvidenceCatalog())
                .extracting(ExtractorEvidenceInput::getEvidenceId)
                .containsExactly("E001", "E002");
        assertThat(competitorInput.getStructuredEvidence())
                .extracting(ExtractorEvidenceInput::getEvidenceId)
                .containsExactly("E001");
        assertThat(competitorInput.getReadableEvidence())
                .extracting(ExtractorEvidenceInput::getEvidenceId)
                .containsExactly("E001", "E002");
        assertThat(competitorInput.getSkippedEvidence()).hasSize(1);
        assertThat(competitorInput.getSkippedEvidence().get(0).getEvidenceId()).isEqualTo("E003");
        assertThat(competitorInput.getSkippedEvidence().get(0).getIssueFlags()).contains("PROMPT_BUDGET_SKIPPED");
        assertThat(competitorInput.getSkippedEvidence().get(0).getContent()).isBlank();
        assertThat(competitorInput.getBudget())
                .containsEntry("maxPromptEvidenceChars", 4000)
                .containsEntry("usedPromptEvidenceChars", 4000)
                .containsEntry("truncated", true);
    }

    @Test
    void shouldKeepPricingEvidenceWhenLargeStructuredDocsWouldConsumePromptBudget() {
        EvidenceSource largeHelpDocs = EvidenceSource.builder()
                .taskId(3L)
                .competitorName("Notion")
                .evidenceId("E201")
                .title("Help, Support, and Documentation for Notion")
                .url("https://notion.so/help")
                .sourceType("DOCS")
                .fullContent("Notion help center structured metadata. ".repeat(180))
                .pageMetadata("""
                        {
                          "sourceUrls": ["https://notion.so/help"],
                          "qualitySignals": ["JSON_LD_METADATA_HIT"],
                          "structuredBlocks": [{"blockType": "JSON_LD_METADATA", "summary": "Help center metadata"}],
                          "qualityScore": 0.98
                        }
                        """)
                .build();
        EvidenceSource pricingPage = EvidenceSource.builder()
                .taskId(3L)
                .competitorName("Notion")
                .evidenceId("E202")
                .title("Notion Pricing")
                .url("https://notion.so/pricing")
                .sourceType("PRICING")
                .fullContent("Free plan, Plus plan, Business plan, Enterprise plan. Pricing is billed per seat.")
                .pageMetadata("""
                        {
                          "sourceUrls": ["https://notion.so/pricing"],
                          "qualitySignals": ["LIGHTWEIGHT_CONTENT_READY"],
                          "qualityScore": 0.91
                        }
                        """)
                .build();
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(3L))
                .thenReturn(List.of(largeHelpDocs, pricingPage));

        ExtractorInputPackage inputPackage = provider.provide(AgentContext.builder()
                .taskId(3L)
                .taskName("task")
                .currentNodeName("extract_schema")
                .build());

        ExtractorCompetitorInput competitorInput = inputPackage.getCompetitors().get(0);
        assertThat(competitorInput.getEvidenceCatalog())
                .extracting(ExtractorEvidenceInput::getEvidenceId)
                .contains("E201", "E202");
        assertThat(competitorInput.getReadableEvidence())
                .extracting(ExtractorEvidenceInput::getEvidenceId)
                .contains("E202");
        assertThat(competitorInput.getSourceUrls())
                .contains("https://notion.so/help", "https://notion.so/pricing");
        ExtractorEvidenceInput helpView = competitorInput.getEvidenceCatalog().stream()
                .filter(view -> "E201".equals(view.getEvidenceId()))
                .findFirst()
                .orElseThrow();
        assertThat(helpView.getIssueFlags()).contains("PROMPT_CONTENT_TRUNCATED");
        assertThat(competitorInput.getBudget())
                .containsEntry("maxPromptEvidenceChars", 4000)
                .containsEntry("usedPromptEvidenceChars", 4000)
                .containsEntry("truncated", true);
    }

    @Test
    void shouldExcludeContentGapFromReadableEvidenceAndMarkThinContentOnly() {
        EvidenceSource contentGap = EvidenceSource.builder()
                .taskId(1L)
                .competitorName("Acme")
                .evidenceId("E101")
                .title("Broken Docs")
                .url("https://acme.com/broken")
                .sourceType("DOCS")
                .fullContent("this content should not enter readable area")
                .pageMetadata("""
                        {
                          "sourceUrls": ["https://acme.com/broken"],
                          "issueFlags": ["CONTENT_GAP"],
                          "qualitySignals": ["LIGHTWEIGHT_CONTENT_READY"],
                          "qualityScore": 0.67
                        }
                        """)
                .build();
        EvidenceSource thinContent = EvidenceSource.builder()
                .taskId(1L)
                .competitorName("Acme")
                .evidenceId("E102")
                .title("Thin FAQ")
                .url("https://acme.com/faq")
                .sourceType("DOCS")
                .fullContent("short text only")
                .pageMetadata("""
                        {
                          "sourceUrls": ["https://acme.com/faq"],
                          "qualitySignals": ["LIGHTWEIGHT_CONTENT_READY"],
                          "qualityScore": 0.61
                        }
                        """)
                .build();
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(2L))
                .thenReturn(List.of(contentGap, thinContent));

        ExtractorInputPackage inputPackage = provider.provide(AgentContext.builder()
                .taskId(2L)
                .taskName("task")
                .currentNodeName("extract_schema")
                .build());

        ExtractorCompetitorInput competitorInput = inputPackage.getCompetitors().get(0);
        assertThat(competitorInput.getReadableEvidence())
                .extracting(ExtractorEvidenceInput::getEvidenceId)
                .doesNotContain("E101");
        assertThat(competitorInput.getEvidenceCatalog())
                .extracting(ExtractorEvidenceInput::getEvidenceId)
                .containsExactly("E101", "E102");
        ExtractorEvidenceInput thinView = competitorInput.getEvidenceCatalog().stream()
                .filter(view -> "E102".equals(view.getEvidenceId()))
                .findFirst()
                .orElseThrow();
        assertThat(thinView.getIssueFlags()).contains("THIN_CONTENT_ONLY");
        assertThat(competitorInput.getIssueFlags()).contains("CONTENT_GAP", "THIN_CONTENT_ONLY");
    }

    @Test
    void shouldExposeAuditRefsFromCollectorSharedEnvelope() {
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(8L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(8L)
                        .competitorName("Acme")
                        .evidenceId("E801")
                        .title("Docs")
                        .url("https://docs.acme.com")
                        .sourceType("DOCS")
                        .fullContent("docs body")
                        .build()
        ));

        AgentContext context = AgentContext.builder()
                .taskId(8L)
                .taskName("task")
                .currentNodeName("extract_schema")
                .build();
        context.putSharedOutputEnvelope("collect_sources_01_01", SharedNodeOutputEnvelope.builder()
                .taskId(8L)
                .nodeName("collect_sources_01_01")
                .projectionType("SEARCH_SHARED_PROJECTION_V1")
                .payloadJson("{\"projectionType\":\"SEARCH_SHARED_PROJECTION_V1\"}")
                .sourceUrls(List.of("https://docs.acme.com"))
                .build());

        ExtractorInputPackage inputPackage = provider.provide(context);

        assertThat(inputPackage.getInputSource()).isEqualTo("REPOSITORY_BACKED_PORT");
        assertThat(inputPackage.getAuditRefs()).containsKey("searchAudit");
        assertThat(String.valueOf(inputPackage.getAuditRefs().get("projectionTypes")))
                .contains("SEARCH_SHARED_PROJECTION_V1");
    }

    @Test
    void shouldMarkAuditRefsUnavailableReasonWhenCollectorEnvelopeMissing() {
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(9L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(9L)
                        .competitorName("Acme")
                        .evidenceId("E901")
                        .title("Docs")
                        .url("https://docs.acme.com")
                        .sourceType("DOCS")
                        .fullContent("docs body")
                        .build()
        ));

        ExtractorInputPackage inputPackage = provider.provide(AgentContext.builder()
                .taskId(9L)
                .taskName("task")
                .currentNodeName("extract_schema")
                .build());

        assertThat(String.valueOf(inputPackage.getAuditRefs().get("searchAudit")))
                .contains("available=false")
                .contains("COLLECTOR_SHARED_ENVELOPE_MISSING");
    }
}
