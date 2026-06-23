package cn.bugstack.competitoragent.agent.extractor;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.extractor.input.ExtractorCompetitorInput;
import cn.bugstack.competitoragent.extractor.input.ExtractorInputPackage;
import cn.bugstack.competitoragent.extractor.input.ExtractorInputProvider;
import cn.bugstack.competitoragent.extractor.input.RepositoryExtractorInputProvider;
import cn.bugstack.competitoragent.context.AgentContextAssembler;
import cn.bugstack.competitoragent.context.TaskRagContextBundle;
import cn.bugstack.competitoragent.llm.LlmClient;
import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.model.entity.CompetitorKnowledge;
import cn.bugstack.competitoragent.workflow.contract.DownstreamEvidenceQuality;
import cn.bugstack.competitoragent.workflow.contract.DownstreamEvidenceView;
import cn.bugstack.competitoragent.workflow.contract.DownstreamEvidenceViewAssembler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchemaExtractorAgentTest {

    private final AgentExecutionLogRepository logRepository = mock(AgentExecutionLogRepository.class);
    private final EvidenceSourceRepository evidenceRepository = mock(EvidenceSourceRepository.class);
    private final CompetitorKnowledgeRepository knowledgeRepository = mock(CompetitorKnowledgeRepository.class);
    private final LlmClient llmClient = mock(LlmClient.class);
    private final PromptTemplateService promptService = mock(PromptTemplateService.class);
    private final AgentContextAssembler agentContextAssembler = mock(AgentContextAssembler.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExtractorInputProvider inputProvider = mock(ExtractorInputProvider.class);
    private final SchemaExtractorAgent extractorAgent = new SchemaExtractorAgent(
            logRepository,
            evidenceRepository,
            knowledgeRepository,
            llmClient,
            promptService,
            agentContextAssembler,
            objectMapper,
            new DownstreamEvidenceViewAssembler(objectMapper),
            new RepositoryExtractorInputProvider(
                    evidenceRepository,
                    new DownstreamEvidenceViewAssembler(objectMapper),
                    objectMapper)
    );

    @Test
    void shouldConsumeExtractorInputProviderInsteadOfReadingRepositoryDirectly() throws Exception {
        SchemaExtractorAgent providerOnlyAgent = new SchemaExtractorAgent(
                logRepository,
                knowledgeRepository,
                llmClient,
                promptService,
                agentContextAssembler,
                objectMapper,
                inputProvider
        );
        DownstreamEvidenceView providerEvidence = DownstreamEvidenceView.builder()
                .evidenceId("P001")
                .competitorName("Provider AI")
                .sourceType("DOCS")
                .title("Provider Pricing")
                .content("Provider AI offers team pricing and workspace automation for enterprise teams.")
                .sourceUrls(List.of("https://provider.example.com/pricing"))
                .qualitySignals(List.of("STRUCTURED_BLOCK_HIT"))
                .issueFlags(List.of())
                .structuredBlocks(List.of())
                .structuredPayload(Map.of())
                .quality(DownstreamEvidenceQuality.builder()
                        .qualityScore(0.91)
                        .build())
                .build()
                .normalized();
        Map<String, Object> budget = new LinkedHashMap<>();
        budget.put("maxPromptEvidenceChars", 4000);
        budget.put("usedPromptEvidenceChars", 96);
        budget.put("truncated", false);
        when(inputProvider.provide(any(AgentContext.class))).thenReturn(ExtractorInputPackage.builder()
                .taskId(1L)
                .nodeName("extract_schema")
                .planVersionId(8L)
                .branchKey("root/p1a")
                .schemaId(12L)
                .dimensions(List.of("产品功能", "价格策略"))
                .competitors(List.of(ExtractorCompetitorInput.builder()
                        .competitorName("Provider AI")
                        .evidenceCatalog(List.of(providerEvidence))
                        .structuredEvidence(List.of(providerEvidence))
                        .readableEvidence(List.of(providerEvidence))
                        .skippedEvidence(List.of())
                        .sourceUrls(List.of("https://provider.example.com/pricing"))
                        .issueFlags(List.of())
                        .budget(budget)
                        .build()))
                .build());
        when(promptService.render(eq("extractor"), any())).thenReturn("prompt");
        when(llmClient.chatForJson(any(), any(), eq("ExtractedSchema")))
                .thenReturn("""
                        {
                          "officialUrl": "https://provider.example.com",
                          "summary": "enterprise workspace assistant",
                          "positioning": "workspace ai",
                          "targetUsers": ["enterprise teams"],
                          "coreFeatures": [{
                            "name": "AI search",
                            "description": "search workspace knowledge",
                            "category": "knowledge"
                          }],
                          "pricing": {"model": "team plan", "evidenceIds": ["P001"]},
                          "strengths": [{
                            "point": "strong workspace integration",
                            "description": "model returned an extra explanation field"
                          }],
                          "weaknesses": [],
                          "sources": [],
                          "sourceUrls": ["https://provider.example.com/pricing"]
                        }
                        """);

        AgentResult result = providerOnlyAgent.execute(AgentContext.builder()
                .taskId(1L)
                .taskName("task")
                .currentNodeName("extract_schema")
                .currentNodeConfig("""
                        {
                          "schemaId": 12,
                          "dimensions": ["产品功能", "价格策略"]
                        }
                        """)
                .planVersionId(8L)
                .branchKey("root/p1a")
                .build());

        assertEquals("SUCCESS", result.getStatus().name());
        verify(inputProvider).provide(any(AgentContext.class));
        verify(evidenceRepository, never()).findByTaskIdOrderByEvidenceIdAsc(any());
        verify(promptService).render(eq("extractor"), argThat(variables ->
                variables.get("schemaGuidance") != null
                        && variables.get("schemaGuidance").contains("schemaId=12")
                        && variables.get("readableContent") != null
                        && variables.get("readableContent").contains("Provider AI offers team pricing")
        ));
    }

    @Test
    void shouldUseProviderReadableEvidenceInsteadOfDumpingContentGapIntoReadablePrompt() {
        SchemaExtractorAgent providerOnlyAgent = new SchemaExtractorAgent(
                logRepository,
                knowledgeRepository,
                llmClient,
                promptService,
                agentContextAssembler,
                objectMapper,
                inputProvider
        );
        DownstreamEvidenceView contentGapView = DownstreamEvidenceView.builder()
                .evidenceId("G001")
                .competitorName("Acme")
                .sourceType("DOCS")
                .title("Broken Docs")
                .content("this content should stay out of readable prompt")
                .sourceUrls(List.of("https://acme.example.com/broken"))
                .issueFlags(List.of("CONTENT_GAP"))
                .qualitySignals(List.of("LIGHTWEIGHT_CONTENT_READY"))
                .structuredBlocks(List.of())
                .structuredPayload(Map.of())
                .quality(DownstreamEvidenceQuality.builder().qualityScore(0.62).build())
                .build()
                .normalized();
        DownstreamEvidenceView readableView = DownstreamEvidenceView.builder()
                .evidenceId("G002")
                .competitorName("Acme")
                .sourceType("DOCS")
                .title("Product Docs")
                .content("Acme supports workflow automation, admin controls, and team pricing for enterprise teams.")
                .sourceUrls(List.of("https://acme.example.com/docs"))
                .issueFlags(List.of())
                .qualitySignals(List.of("LIGHTWEIGHT_CONTENT_READY"))
                .structuredBlocks(List.of())
                .structuredPayload(Map.of())
                .quality(DownstreamEvidenceQuality.builder().qualityScore(0.84).build())
                .build()
                .normalized();
        when(inputProvider.provide(any(AgentContext.class))).thenReturn(ExtractorInputPackage.builder()
                .taskId(1L)
                .nodeName("extract_schema")
                .competitors(List.of(ExtractorCompetitorInput.builder()
                        .competitorName("Acme")
                        .evidenceCatalog(List.of(contentGapView, readableView))
                        .structuredEvidence(List.of())
                        .readableEvidence(List.of(readableView))
                        .skippedEvidence(List.of())
                        .sourceUrls(List.of("https://acme.example.com/docs"))
                        .issueFlags(List.of("CONTENT_GAP"))
                        .budget(Map.of(
                                "maxPromptEvidenceChars", 4000,
                                "usedPromptEvidenceChars", 88,
                                "truncated", false
                        ))
                        .build()))
                .build());
        when(promptService.render(eq("extractor"), any())).thenReturn("prompt");
        when(llmClient.chatForJson(any(), any(), eq("ExtractedSchema")))
                .thenReturn("""
                        {
                          "officialUrl": "https://acme.example.com",
                          "summary": "workflow platform",
                          "positioning": "enterprise workflow",
                          "targetUsers": ["enterprise teams"],
                          "coreFeatures": [],
                          "pricing": {},
                          "strengths": [],
                          "weaknesses": [],
                          "sources": [],
                          "sourceUrls": ["https://acme.example.com/docs"]
                        }
                        """);

        AgentResult result = providerOnlyAgent.execute(AgentContext.builder()
                .taskId(1L)
                .taskName("task")
                .currentNodeName("extract_schema")
                .build());

        assertEquals("SUCCESS", result.getStatus().name());
        verify(promptService).render(eq("extractor"), argThat(variables ->
                variables.get("readableContent") != null
                        && variables.get("readableContent").contains("workflow automation")
                        && !variables.get("readableContent").contains("this content should stay out of readable prompt")
                        && variables.get("collectedContent") != null
                        && variables.get("collectedContent").contains("CONTENT_GAP")
        ));
    }

    @Test
    void shouldPassUnifiedTaskRagContextIntoExtractorPrompt() throws Exception {
        // 统一上下文由基类入口注入，提取阶段只验证 prompt 消费到同一份检索摘要。
        when(agentContextAssembler.assemble(any(AgentContext.class))).thenAnswer(invocation -> {
            AgentContext originalContext = invocation.getArgument(0);
            return originalContext.toBuilder()
                    .taskRagContextBundle(TaskRagContextBundle.builder()
                            .query("Feishu pricing evidence")
                            .retrievalSummary("命中飞书定价页与帮助中心摘要")
                            .gapSummary("企业版公开折扣说明仍不足")
                            .sourceUrls(List.of("https://example.com/docs"))
                            .build())
                    .build();
        });
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(1L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(1L)
                        .competitorName("Feishu")
                        .evidenceId("T0001-COLLECT-001")
                        .title("Docs")
                        .url("https://example.com/docs")
                        .fullContent("usable content")
                        .build()
        ));
        when(promptService.render(eq("extractor"), any())).thenReturn("prompt");
        when(llmClient.chatForJson(any(), any(), eq("ExtractedSchema")))
                .thenReturn("""
                        {
                          "officialUrl": "https://example.com",
                          "summary": "ok",
                          "positioning": "team collaboration",
                          "targetUsers": ["teams"],
                          "coreFeatures": [],
                          "pricing": {},
                          "strengths": [],
                          "weaknesses": [],
                          "sources": [],
                          "sourceUrls": ["https://example.com/docs"]
                        }
                        """);

        AgentResult result = extractorAgent.execute(AgentContext.builder()
                .taskId(1L)
                .taskName("task")
                .currentNodeName("extract_schema")
                .build());
        JsonNode output = new ObjectMapper().readTree(result.getOutputData());

        assertEquals("SUCCESS", result.getStatus().name());
        assertTrue(output.path("taskRagContext").asText().contains("Feishu pricing evidence"));
        assertTrue(output.path("taskRagContext").asText().contains("https://example.com/docs"));
        verify(promptService).render(eq("extractor"), argThat(variables ->
                variables.get("taskRagContext") != null
                        && variables.get("taskRagContext").contains("检索查询")
                        && variables.get("taskRagContext").contains("企业版公开折扣说明仍不足")
        ));
    }

    @Test
    void shouldRetryWhenExtractorReturnsBrokenJson() {
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(1L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(1L)
                        .competitorName("Feishu")
                        .evidenceId("T0001-COLLECT-001")
                        .title("Docs")
                        .url("https://example.com/docs")
                        .fullContent("usable content")
                        .build(),
                EvidenceSource.builder()
                        .taskId(1L)
                        .competitorName("Feishu")
                        .evidenceId("T0001-COLLECT-002")
                        .title("Broken")
                        .url("https://example.com/broken")
                        .fullContent("")
                        .contentSnippet("")
                        .build()
        ));
        when(promptService.render(eq("extractor"), any())).thenReturn("prompt");
        when(llmClient.chatForJson(any(), any(), eq("ExtractedSchema")))
                .thenReturn("{\"officialUrl\":\"https://example.com\",\"summary\":\"broken")
                .thenReturn("""
                        {
                          "officialUrl": "https://example.com",
                          "summary": "ok",
                          "positioning": "team collaboration",
                          "targetUsers": ["teams"],
                          "coreFeatures": [],
                          "pricing": {},
                          "strengths": [],
                          "weaknesses": [],
                          "sources": [],
                          "sourceUrls": ["https://example.com/docs"]
                        }
                        """);

        AgentResult result = extractorAgent.execute(AgentContext.builder()
                .taskId(1L)
                .taskName("task")
                .currentNodeName("extract_schema")
                .build());

        assertEquals("SUCCESS", result.getStatus().name());
        verify(llmClient, times(2)).chatForJson(any(), any(), eq("ExtractedSchema"));
        verify(knowledgeRepository, times(1)).save(any());
    }

    @Test
    void shouldBackfillSourceUrlsAndMarkCoverageGapWhenModelDropsTraceabilityFields() {
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(1L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(1L)
                        .competitorName("Feishu")
                        .evidenceId("T0001-COLLECT-001")
                        .title("Docs")
                        .url("https://example.com/docs")
                        .fullContent("usable content")
                        .build()
                ,
                EvidenceSource.builder()
                        .taskId(1L)
                        .competitorName("Feishu")
                        .evidenceId("T0001-COLLECT-002")
                        .title("Pricing")
                        .url("https://example.com/pricing")
                        .fullContent("pricing content")
                        .build()
        ));
        when(promptService.render(eq("extractor"), any())).thenReturn("prompt");
        when(llmClient.chatForJson(any(), any(), eq("ExtractedSchema")))
                .thenReturn("""
                        {
                          "officialUrl": "https://example.com",
                          "summary": "ok",
                          "positioning": "team collaboration",
                          "targetUsers": ["teams"],
                          "coreFeatures": [
                            {
                              "name": "Docs",
                              "description": "Structured docs",
                              "evidenceIds": ["T0001-COLLECT-001"]
                            }
                          ],
                          "pricing": {},
                          "strengths": [],
                          "weaknesses": []
                        }
                        """);

        AgentResult result = extractorAgent.execute(AgentContext.builder()
                .taskId(1L)
                .taskName("task")
                .currentNodeName("extract_schema")
                .build());

        ArgumentCaptor<CompetitorKnowledge> knowledgeCaptor = ArgumentCaptor.forClass(CompetitorKnowledge.class);
        verify(knowledgeRepository).save(knowledgeCaptor.capture());
        assertEquals("SUCCESS", result.getStatus().name());
        assertTrue(knowledgeCaptor.getValue().getSourceUrls().contains("https://example.com/docs"));
        assertTrue(knowledgeCaptor.getValue().getEvidenceCoverage().contains("MISSING_EVIDENCE"));
        assertTrue(result.getOutputData().contains("SOURCE_URLS_BACKFILLED"));
        assertTrue(result.getOutputData().contains("MISSING_EVIDENCE"));
    }

    @Test
    void shouldBackfillSingleSourceTraceabilityWhenOnlyOneEvidenceWasConsumed() throws Exception {
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(1L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(1L)
                        .competitorName("Notion AI")
                        .evidenceId("E001")
                        .title("Product Docs")
                        .url("https://docs.example.com/notion-ai")
                        .fullContent("Notion AI helps teams write docs, search workspace knowledge, and collaborate in one place.")
                        .contentSnippet("helps teams write docs and search workspace knowledge")
                        .build()
        ));
        when(promptService.render(eq("extractor"), any())).thenReturn("prompt");
        when(llmClient.chatForJson(any(), any(), eq("ExtractedSchema")))
                .thenReturn("""
                        {
                          "officialUrl": "https://docs.example.com/notion-ai",
                          "summary": "Notion AI is a workspace assistant for docs and collaboration.",
                          "positioning": "workspace ai",
                          "targetUsers": ["teams", "knowledge workers"],
                          "coreFeatures": [],
                          "pricing": {},
                          "strengths": [],
                          "weaknesses": [],
                          "sources": [],
                          "sourceUrls": ["https://docs.example.com/notion-ai"]
                        }
                        """);

        AgentResult result = extractorAgent.execute(AgentContext.builder()
                .taskId(1L)
                .taskName("task")
                .currentNodeName("extract_schema")
                .build());
        JsonNode coverage = objectMapper.readTree(result.getOutputData())
                .path("drafts").get(0).path("evidenceCoverage");

        assertEquals("SUCCESS", result.getStatus().name());
        assertEquals("TRACEABLE", coverage.path("summary").path("status").asText());
        assertEquals("TRACEABLE", coverage.path("positioning").path("status").asText());
        assertEquals("TRACEABLE", coverage.path("targetUsers").path("status").asText());
        assertTrue(coverage.path("summary").path("sourceUrls").toString().contains("https://docs.example.com/notion-ai"));
        assertTrue(coverage.path("positioning").path("sourceUrls").toString().contains("https://docs.example.com/notion-ai"));
        assertTrue(coverage.path("targetUsers").path("sourceUrls").toString().contains("https://docs.example.com/notion-ai"));
    }

    @Test
    void shouldBuildExtractorInputFromStructuredEvidenceViewInsteadOfRawFullContentOnly() throws Exception {
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(1L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(1L)
                        .competitorName("Acme")
                        .evidenceId("E001")
                        .title("Pricing Docs")
                        .url("https://docs.example.com/pricing")
                        .sourceType("DOCS")
                        .fullContent("公开定价页正文")
                        .pageMetadata("""
                                {
                                  "sourceUrls": ["https://docs.example.com/pricing"],
                                  "qualitySignals": ["STRUCTURED_BLOCK_HIT", "PRICING_BLOCK_HIT"],
                                  "structuredBlocks": [{"blockType": "PRICING_BLOCK", "summary": "Pro 199 / 月"}],
                                  "qualityScore": 0.82
                                }
                                """)
                        .build()
        ));
        when(promptService.render(eq("extractor"), any())).thenReturn("prompt");
        when(llmClient.chatForJson(any(), any(), eq("ExtractedSchema")))
                .thenReturn("""
                        {
                          "officialUrl": "https://example.com",
                          "summary": "ok",
                          "positioning": "team collaboration",
                          "targetUsers": ["teams"],
                          "coreFeatures": [],
                          "pricing": {"model": "Pro 199 / 月", "evidenceIds": ["E001"]},
                          "strengths": [],
                          "weaknesses": [],
                          "sources": [],
                          "sourceUrls": ["https://docs.example.com/pricing"]
                        }
                        """);

        AgentResult result = extractorAgent.execute(AgentContext.builder()
                .taskId(1L)
                .taskName("task")
                .currentNodeName("extract_schema")
                .build());
        JsonNode output = new ObjectMapper().readTree(result.getOutputData());

        assertEquals("SUCCESS", result.getStatus().name());
        // extractor 的正式输入必须先经过 DownstreamEvidenceView，结构化块和质量信号要出现在 prompt 变量里。
        verify(promptService).render(eq("extractor"), argThat(variables ->
                variables.get("evidenceCatalog") != null
                        && variables.get("evidenceCatalog").contains("sourceUrls")
                        && variables.get("evidenceCatalog").contains("qualitySignals")
                        && variables.get("evidenceCatalog").contains("PRICING_BLOCK")
                        && variables.get("collectedContent") != null
                        && variables.get("collectedContent").contains("structuredBlocks")
                        && variables.get("collectedContent").contains("PRICING_BLOCK_HIT")
        ));
        assertTrue(output.path("downstreamEvidenceViews").isArray());
        assertTrue(output.path("downstreamEvidenceViews").toString().contains("PRICING_BLOCK"));
        assertTrue(output.path("drafts").get(0).path("downstreamEvidenceViews").toString().contains("STRUCTURED_BLOCK_HIT"));
    }

    @Test
    void shouldRetryWhenFirstPassReturnsZeroBusinessFieldsButReadableEvidenceExists() {
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(1L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(1L)
                        .competitorName("Notion AI")
                        .evidenceId("E001")
                        .title("Pricing")
                        .url("https://www.notion.so/pricing")
                        .fullContent("Notion AI provides pricing and workspace plan details for teams, docs, search, and project collaboration.")
                        .contentSnippet("workspace plan details for teams")
                        .build()
        ));
        when(promptService.render(eq("extractor"), any())).thenReturn("prompt");
        when(llmClient.chatForJson(any(), any(), eq("ExtractedSchema")))
                .thenReturn("""
                        {
                          "officialUrl": "https://www.notion.so",
                          "summary": "",
                          "positioning": "",
                          "targetUsers": [],
                          "coreFeatures": [],
                          "pricing": {},
                          "strengths": [],
                          "weaknesses": [],
                          "sources": [],
                          "sourceUrls": []
                        }
                        """)
                .thenReturn("""
                        {
                          "officialUrl": "https://www.notion.so",
                          "summary": "workspace assistant",
                          "positioning": "workspace ai",
                          "targetUsers": ["teams"],
                          "coreFeatures": [],
                          "pricing": {"model": "team workspace plan", "evidenceIds": ["E001"]},
                          "strengths": [],
                          "weaknesses": [],
                          "sources": [],
                          "sourceUrls": ["https://www.notion.so/pricing"]
                        }
                        """);

        AgentResult result = extractorAgent.execute(AgentContext.builder()
                .taskId(1L)
                .taskName("task")
                .currentNodeName("extract_schema")
                .build());

        assertEquals("SUCCESS", result.getStatus().name());
        verify(llmClient, times(2)).chatForJson(any(), any(), eq("ExtractedSchema"));
        verify(knowledgeRepository).save(any(CompetitorKnowledge.class));
    }

    @Test
    void shouldAcceptStructuredOnlyEvidenceWhenContentIsBlank() {
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(1L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(1L)
                        .competitorName("Acme")
                        .evidenceId("E001")
                        .title("Pricing API")
                        .url("https://api.example.com/pricing")
                        .sourceType("API_DATA")
                        .fullContent("")
                        .contentSnippet("")
                        .pageMetadata("""
                                {
                                  "sourceUrls": ["https://api.example.com/pricing"],
                                  "qualitySignals": ["STRUCTURED_BLOCK_HIT", "PRICING_BLOCK_HIT"],
                                  "structuredBlocks": [{"blockType": "PRICING_BLOCK", "summary": "Pro 199 / 月"}],
                                  "structuredPayload": {"plans": [{"name": "Pro", "price": 199}]}
                                }
                                """)
                        .build()
        ));
        when(promptService.render(eq("extractor"), any())).thenReturn("prompt");
        when(llmClient.chatForJson(any(), any(), eq("ExtractedSchema")))
                .thenReturn("""
                        {
                          "officialUrl": "https://example.com",
                          "summary": "pricing api",
                          "positioning": "",
                          "targetUsers": [],
                          "coreFeatures": [],
                          "pricing": {"model": "Pro 199 / 月", "evidenceIds": ["E001"]},
                          "strengths": [],
                          "weaknesses": [],
                          "sources": [],
                          "sourceUrls": ["https://api.example.com/pricing"]
                        }
                        """);

        AgentResult result = extractorAgent.execute(AgentContext.builder()
                .taskId(1L)
                .taskName("task")
                .currentNodeName("extract_schema")
                .build());

        assertEquals("SUCCESS", result.getStatus().name());
        verify(promptService).render(eq("extractor"), argThat(variables ->
                variables.toString().contains("PRICING_BLOCK")
                        && variables.toString().contains("STRUCTURED_BLOCK_HIT")
        ));
    }

    @Test
    void shouldMarkStructuredOnlyPricingCoverageAsStructuredBlockDirect() throws Exception {
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(1L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(1L)
                        .competitorName("Acme")
                        .evidenceId("E001")
                        .title("Pricing API")
                        .url("https://api.example.com/pricing")
                        .sourceType("API_DATA")
                        .fullContent("")
                        .contentSnippet("")
                        .pageMetadata("""
                                {
                                  "sourceUrls": ["https://api.example.com/pricing"],
                                  "qualitySignals": ["STRUCTURED_BLOCK_HIT", "PRICING_BLOCK_HIT"],
                                  "structuredBlocks": [{"blockType": "PRICING_BLOCK", "summary": "Pro 199 / 月"}],
                                  "structuredPayload": {"plans": [{"name": "Pro", "price": 199}]}
                                }
                                """)
                        .build()
        ));
        when(promptService.render(eq("extractor"), any())).thenReturn("prompt");
        when(llmClient.chatForJson(any(), any(), eq("ExtractedSchema")))
                .thenReturn("""
                        {
                          "officialUrl": "https://example.com",
                          "summary": "pricing api",
                          "positioning": "",
                          "targetUsers": [],
                          "coreFeatures": [],
                          "pricing": {"model": "Pro 199 / 月", "evidenceIds": ["E001"]},
                          "strengths": [],
                          "weaknesses": [],
                          "sources": [],
                          "sourceUrls": ["https://api.example.com/pricing"]
                        }
                        """);

        AgentResult result = extractorAgent.execute(AgentContext.builder()
                .taskId(1L)
                .taskName("task")
                .currentNodeName("extract_schema")
                .build());
        JsonNode output = objectMapper.readTree(result.getOutputData());
        JsonNode draft = output.path("drafts").get(0);
        JsonNode pricingBundle = findBundle(draft.path("sectionEvidenceBundles"), "pricing");

        assertEquals("SUCCESS", result.getStatus().name());
        assertEquals("STRUCTURED_BLOCK_DIRECT", draft.path("evidenceCoverage").path("pricing").path("status").asText());
        assertEquals("STRUCTURED_BLOCK_DIRECT",
                pricingBundle.path("evidenceFragments").get(0).path("coverageStatus").asText());
    }

    @Test
    void shouldInjectSchemaDimensionsFromCurrentNodeConfigIntoExtractorPrompt() {
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(1L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(1L)
                        .competitorName("Notion AI")
                        .evidenceId("E001")
                        .title("Product")
                        .url("https://www.notion.so/product")
                        .fullContent("Notion AI helps teams manage docs, projects, search, and workspace knowledge.")
                        .build()
        ));
        when(promptService.render(eq("extractor"), any())).thenReturn("prompt");
        when(llmClient.chatForJson(any(), any(), eq("ExtractedSchema")))
                .thenReturn("""
                        {
                          "officialUrl": "https://www.notion.so",
                          "summary": "workspace assistant",
                          "positioning": "team workspace",
                          "targetUsers": ["teams"],
                          "coreFeatures": [],
                          "pricing": {},
                          "strengths": [],
                          "weaknesses": [],
                          "sources": [],
                          "sourceUrls": ["https://www.notion.so/product"]
                        }
                        """);

        AgentResult result = extractorAgent.execute(AgentContext.builder()
                .taskId(1L)
                .taskName("task")
                .currentNodeName("extract_schema")
                .currentNodeConfig("""
                        {
                          "schemaId": 7,
                          "dimensions": ["产品功能", "价格策略", "目标用户"]
                        }
                        """)
                .build());

        assertEquals("SUCCESS", result.getStatus().name());
        verify(promptService).render(eq("extractor"), argThat(variables ->
                variables.get("schemaGuidance") != null
                        && variables.get("schemaGuidance").contains("schemaId=7")
                        && variables.get("schemaGuidance").contains("产品功能")
                        && variables.get("schemaGuidance").contains("价格策略")
        ));
    }

    @Test
    void shouldRenderSeparatedPromptInputsForStructuredQualityAndReadableEvidence() {
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(1L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(1L)
                        .competitorName("Acme")
                        .evidenceId("E001")
                        .title("Docs")
                        .url("https://docs.example.com")
                        .fullContent("Acme offers workflow automation, collaboration features, and team billing details.")
                        .pageMetadata("""
                                {
                                  "sourceUrls": ["https://docs.example.com"],
                                  "qualitySignals": ["LIGHTWEIGHT_CONTENT_READY", "PRICING_BLOCK_HIT"],
                                  "structuredBlocks": [{"blockType": "FEATURE_LIST", "summary": "workflow automation"}]
                                }
                                """)
                        .build()
        ));
        when(promptService.render(eq("extractor"), any())).thenReturn("prompt");
        when(llmClient.chatForJson(any(), any(), eq("ExtractedSchema")))
                .thenReturn("""
                        {
                          "officialUrl": "https://example.com",
                          "summary": "workflow automation",
                          "positioning": "team workflow platform",
                          "targetUsers": ["teams"],
                          "coreFeatures": [{"name": "automation", "evidenceIds": ["E001"]}],
                          "pricing": {},
                          "strengths": [],
                          "weaknesses": [],
                          "sources": [],
                          "sourceUrls": ["https://docs.example.com"]
                        }
                        """);

        AgentResult result = extractorAgent.execute(AgentContext.builder()
                .taskId(1L)
                .taskName("task")
                .currentNodeName("extract_schema")
                .build());

        assertEquals("SUCCESS", result.getStatus().name());
        verify(promptService).render(eq("extractor"), argThat(variables ->
                variables.get("structuredEvidence") != null
                        && variables.get("structuredEvidence").contains("FEATURE_LIST")
                        && variables.get("qualitySignalGuidance") != null
                        && variables.get("qualitySignalGuidance").contains("PRICING_BLOCK_HIT")
                        && variables.get("readableContent") != null
                        && variables.get("readableContent").contains("workflow automation")
        ));
    }

    @Test
    void shouldNotCrashWhenEvidenceContentFallsBetweenFourAndEightThousandChars() {
        String longContent = "A".repeat(4010);
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(1L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(1L)
                        .competitorName("Acme")
                        .evidenceId("E001")
                        .title("Long Docs")
                        .url("https://docs.example.com/long")
                        .fullContent(longContent)
                        .build()
        ));
        when(promptService.render(eq("extractor"), any())).thenReturn("prompt");
        when(llmClient.chatForJson(any(), any(), eq("ExtractedSchema")))
                .thenReturn("""
                        {
                          "officialUrl": "https://example.com",
                          "summary": "ok",
                          "positioning": "team collaboration",
                          "targetUsers": ["teams"],
                          "coreFeatures": [],
                          "pricing": {},
                          "strengths": [],
                          "weaknesses": [],
                          "sources": [],
                          "sourceUrls": ["https://docs.example.com/long"]
                        }
                        """);

        AgentResult result = extractorAgent.execute(AgentContext.builder()
                .taskId(1L)
                .taskName("task")
                .currentNodeName("extract_schema")
                .build());

        // 锁定真实链路里的截断回归：正文处于 4k~8k 区间时不能因为二次 substring 直接失败。
        assertEquals("SUCCESS", result.getStatus().name());
        verify(promptService).render(eq("extractor"), argThat(variables ->
                variables.get("collectedContent") != null
                        && variables.get("collectedContent").contains("...(truncated)")
        ));
    }

    @Test
    void shouldPreferTaskScopedKnowledgeBoundaryOverDefaultDomainMemoryLayer() {
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(1L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(1L)
                        .competitorName("Acme")
                        .evidenceId("E001")
                        .title("Docs")
                        .url("https://docs.example.com")
                        .fullContent("usable content")
                        .build()
        ));
        when(promptService.render(eq("extractor"), any())).thenReturn("prompt");
        when(llmClient.chatForJson(any(), any(), eq("ExtractedSchema")))
                .thenReturn("""
                        {
                          "officialUrl": "https://example.com",
                          "summary": "ok",
                          "positioning": "team collaboration",
                          "targetUsers": ["teams"],
                          "coreFeatures": [],
                          "pricing": {},
                          "strengths": [],
                          "weaknesses": [],
                          "sources": [],
                          "sourceUrls": ["https://docs.example.com"]
                        }
                        """);

        AgentResult result = extractorAgent.execute(AgentContext.builder()
                .taskId(1L)
                .taskName("task")
                .currentNodeName("extract_schema")
                .planVersionId(27L)
                .branchKey("root/eighth")
                .build());

        ArgumentCaptor<CompetitorKnowledge> knowledgeCaptor = ArgumentCaptor.forClass(CompetitorKnowledge.class);
        verify(knowledgeRepository).save(knowledgeCaptor.capture());
        CompetitorKnowledge savedKnowledge = knowledgeCaptor.getValue();

        assertEquals("SUCCESS", result.getStatus().name());
        // extract_schema 产生的是任务现场快照，默认不能再沉入长期 DOMAIN 记忆层。
        assertEquals("TASK", savedKnowledge.getMemoryLayer());
        assertEquals("TASK", savedKnowledge.getSnapshotScope());
        assertEquals("extract_schema", savedKnowledge.getProducerNodeName());
        assertEquals(27L, savedKnowledge.getPlanVersionId());
        assertEquals("root/eighth", savedKnowledge.getBranchKey());
    }

    @Test
    void shouldAcceptSingleObjectWrappedByJsonArray() {
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(1L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(1L)
                        .competitorName("哔哩哔哩")
                        .evidenceId("T0001-COLLECT-001")
                        .title("Docs")
                        .url("https://www.bilibili.com")
                        .fullContent("usable content")
                        .build()
        ));
        when(promptService.render(eq("extractor"), any())).thenReturn("prompt");
        when(llmClient.chatForJson(any(), any(), eq("ExtractedSchema")))
                .thenReturn("""
                        [
                          {
                            "officialUrl": "https://www.bilibili.com",
                            "summary": "视频社区平台",
                            "positioning": "内容社区",
                            "targetUsers": ["年轻用户"],
                            "coreFeatures": [],
                            "pricing": {},
                            "strengths": [],
                            "weaknesses": [],
                            "sources": [],
                            "sourceUrls": ["https://www.bilibili.com"]
                          }
                        ]
                        """);

        AgentResult result = extractorAgent.execute(AgentContext.builder()
                .taskId(1L)
                .taskName("task")
                .currentNodeName("extract_schema")
                .build());

        assertEquals("SUCCESS", result.getStatus().name());
        verify(knowledgeRepository, times(1)).save(any());
    }

    @Test
    void shouldRejectRecoveredEmptyJsonArrayWithoutBusinessFields() {
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(1L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(1L)
                        .competitorName("哔哩哔哩")
                        .evidenceId("T0001-COLLECT-001")
                        .title("官网")
                        .url("https://www.bilibili.com")
                        .fullContent("哔哩哔哩是面向年轻用户的视频社区。")
                        .contentSnippet("年轻用户视频社区")
                        .build()
        ));
        when(promptService.render(eq("extractor"), any())).thenReturn("prompt");
        when(llmClient.chatForJson(any(), any(), eq("ExtractedSchema")))
                .thenReturn("[]");

        AgentResult result = extractorAgent.execute(AgentContext.builder()
                .taskId(1L)
                .taskName("task")
                .currentNodeName("extract_schema")
                .build());

        assertEquals("FAILED", result.getStatus().name());
        assertTrue(result.getErrorMessage().contains("未能抽取出任何业务字段"));
        verify(knowledgeRepository, never()).save(any());
    }

    @Test
    void shouldFailWhenModelReturnsNoBusinessFieldsDespiteUsableEvidence() {
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(1L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(1L)
                        .competitorName("Notion AI")
                        .evidenceId("T0001-COLLECT-001")
                        .title("Product")
                        .url("https://www.notion.so/product")
                        .fullContent("Notion AI is a workspace assistant for notes, docs, search, and project collaboration.")
                        .contentSnippet("workspace assistant for notes, docs, search, and project collaboration")
                        .build(),
                EvidenceSource.builder()
                        .taskId(1L)
                        .competitorName("Notion AI")
                        .evidenceId("T0001-COLLECT-002")
                        .title("Pricing")
                        .url("https://www.notion.so/pricing")
                        .fullContent("Business plan pricing and AI add-on information are available for team workspaces.")
                        .contentSnippet("Business plan pricing and AI add-on information")
                        .build()
        ));
        when(promptService.render(eq("extractor"), any())).thenReturn("prompt");
        when(llmClient.chatForJson(any(), any(), eq("ExtractedSchema")))
                .thenReturn("""
                        {
                          "officialUrl": "https://www.notion.so",
                          "summary": "",
                          "positioning": "",
                          "targetUsers": [],
                          "coreFeatures": [],
                          "pricing": {},
                          "strengths": [],
                          "weaknesses": [],
                          "sources": [],
                          "sourceUrls": []
                        }
                        """);

        AgentResult result = extractorAgent.execute(AgentContext.builder()
                .taskId(1L)
                .taskName("task")
                .currentNodeName("extract_schema")
                .build());

        // 有可用采集正文时，sourceUrls 回填只能说明可追溯，不能把 0 个业务字段伪装成抽取成功。
        assertEquals("FAILED", result.getStatus().name());
        assertTrue(result.getErrorMessage().contains("未能抽取出任何业务字段"));
        verify(knowledgeRepository, never()).save(any());
    }

    @Test
    void shouldMarkEmptyFieldAsEvidenceNotCoveringWhenConsumedEvidenceLacksRelevantSignal() throws Exception {
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(1L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(1L)
                        .competitorName("Notion AI")
                        .evidenceId("E001")
                        .title("Help Docs")
                        .url("https://docs.example.com/help")
                        .fullContent("Notion AI help center explains AI features, agents, docs, and team collaboration.")
                        .contentSnippet("help center explains ai features and team collaboration")
                        .build()
        ));
        when(promptService.render(eq("extractor"), any())).thenReturn("prompt");
        when(llmClient.chatForJson(any(), any(), eq("ExtractedSchema")))
                .thenReturn("""
                        {
                          "officialUrl": "https://docs.example.com/help",
                          "summary": "workspace ai",
                          "positioning": "collaboration assistant",
                          "targetUsers": ["teams"],
                          "coreFeatures": [],
                          "pricing": {},
                          "strengths": [],
                          "weaknesses": [],
                          "sources": [],
                          "sourceUrls": ["https://docs.example.com/help"]
                        }
                        """);

        AgentResult result = extractorAgent.execute(AgentContext.builder()
                .taskId(1L)
                .taskName("task")
                .currentNodeName("extract_schema")
                .build());
        JsonNode coverage = objectMapper.readTree(result.getOutputData())
                .path("drafts").get(0).path("evidenceCoverage");

        assertEquals("SUCCESS", result.getStatus().name());
        assertEquals("EVIDENCE_NOT_COVERING", coverage.path("pricing").path("status").asText());
        assertEquals("EVIDENCE_NOT_COVERING", coverage.path("weaknesses").path("status").asText());
    }

    @Test
    void shouldMarkRefusalTextAsLlmRefusedCoverage() throws Exception {
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(1L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(1L)
                        .competitorName("Acme")
                        .evidenceId("E001")
                        .title("Docs")
                        .url("https://docs.example.com/acme")
                        .fullContent("Acme offers admin controls and workflow automation for enterprise teams.")
                        .contentSnippet("admin controls and workflow automation")
                        .build()
        ));
        when(promptService.render(eq("extractor"), any())).thenReturn("prompt");
        when(llmClient.chatForJson(any(), any(), eq("ExtractedSchema")))
                .thenReturn("""
                        {
                          "officialUrl": "https://docs.example.com/acme",
                          "summary": "当前公开资料未能验证",
                          "positioning": "",
                          "targetUsers": [],
                          "coreFeatures": [{
                            "name": "admin controls",
                            "description": "enterprise controls",
                            "evidenceIds": ["E001"]
                          }],
                          "pricing": {},
                          "strengths": [],
                          "weaknesses": [],
                          "sources": [],
                          "sourceUrls": ["https://docs.example.com/acme"]
                        }
                        """);

        AgentResult result = extractorAgent.execute(AgentContext.builder()
                .taskId(1L)
                .taskName("task")
                .currentNodeName("extract_schema")
                .build());
        JsonNode output = objectMapper.readTree(result.getOutputData());
        JsonNode coverage = output.path("drafts").get(0).path("evidenceCoverage");
        JsonNode summaryBundle = findBundle(output.path("drafts").get(0).path("sectionEvidenceBundles"), "summary");

        assertEquals("SUCCESS", result.getStatus().name());
        assertEquals("LLM_REFUSED", coverage.path("summary").path("status").asText());
        assertEquals("LLM_REFUSED", summaryBundle.path("evidenceFragments").get(0).path("coverageStatus").asText());
    }

    @Test
    void shouldEmitSectionEvidenceBundlesAndGapMarkersForPartialCoverage() throws Exception {
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(1L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(1L)
                        .competitorName("Notion AI")
                        .evidenceId("E001")
                        .title("Pricing Docs")
                        .url("https://docs.notion.so/pricing")
                        .fullContent("pricing content")
                        .contentSnippet("pricing snippet")
                        .build()
                ,
                EvidenceSource.builder()
                        .taskId(1L)
                        .competitorName("Notion AI")
                        .evidenceId("E002")
                        .title("Product Docs")
                        .url("https://docs.notion.so/product")
                        .fullContent("product overview content")
                        .contentSnippet("product overview snippet")
                        .build()
        ));
        when(promptService.render(eq("extractor"), any())).thenReturn("prompt");
        when(llmClient.chatForJson(any(), any(), eq("ExtractedSchema")))
                .thenReturn("""
                        {
                          "officialUrl": "https://www.notion.so",
                          "summary": "ok",
                          "positioning": "workspace ai",
                          "targetUsers": ["teams"],
                          "coreFeatures": [],
                          "pricing": {
                            "model": "custom quote"
                          },
                          "strengths": [],
                          "weaknesses": [],
                          "sources": [],
                          "sourceUrls": ["https://docs.notion.so/pricing"]
                        }
                        """);

        AgentResult result = extractorAgent.execute(AgentContext.builder()
                .taskId(1L)
                .taskName("task")
                .currentNodeName("extract_schema")
                .build());
        JsonNode output = new ObjectMapper().readTree(result.getOutputData());
        JsonNode pricingBundle = findBundle(output.path("drafts").get(0).path("sectionEvidenceBundles"), "pricing");

        assertEquals("SUCCESS", result.getStatus().name());
        assertEquals("pricing", pricingBundle.path("sectionKey").asText());
        assertTrue(pricingBundle.path("issueFlags").toString().contains("SECTION_EVIDENCE_GAP"));
        assertTrue(pricingBundle.path("evidenceFragments").get(0).path("coverageStatus").asText().contains("MISSING_EVIDENCE"));
        assertTrue(pricingBundle.path("gapSummary").asText().contains("pricing"));
    }

    @Test
    void shouldSlimExtractorOutputByRemovingLongEvidenceContentFromSharedFields() throws Exception {
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(1L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(1L)
                        .competitorName("Acme")
                        .evidenceId("E001")
                        .title("Pricing Docs")
                        .url("https://docs.example.com/pricing")
                        .fullContent("这里是很长的完整定价页正文，包含套餐、价格、折扣、限制说明，不应该继续出现在 extract_schema 的跨节点输出中。")
                        .contentSnippet("价格说明摘要")
                        .pageMetadata("""
                                {
                                  "sourceUrls": ["https://docs.example.com/pricing"],
                                  "qualitySignals": ["STRUCTURED_BLOCK_HIT", "PRICING_BLOCK_HIT"],
                                  "structuredBlocks": [{
                                    "blockType": "PRICING_BLOCK",
                                    "summary": "Pro 199 / 月",
                                    "content": "完整结构块正文也不应该继续透传"
                                  }],
                                  "structuredPayload": {
                                    "plans": [{"name": "Pro", "price": 199}]
                                  }
                                }
                                """)
                        .build()
        ));
        when(promptService.render(eq("extractor"), any())).thenReturn("prompt");
        when(llmClient.chatForJson(any(), any(), eq("ExtractedSchema")))
                .thenReturn("""
                        {
                          "officialUrl": "https://docs.example.com",
                          "summary": "workspace pricing",
                          "positioning": "collaboration suite",
                          "targetUsers": ["teams"],
                          "coreFeatures": [],
                          "pricing": {
                            "model": "seat based",
                            "evidenceIds": ["E001"]
                          },
                          "strengths": [],
                          "weaknesses": [],
                          "sources": [],
                          "sourceUrls": ["https://docs.example.com/pricing"]
                        }
                        """);

        AgentResult result = extractorAgent.execute(AgentContext.builder()
                .taskId(1L)
                .taskName("task")
                .currentNodeName("extract_schema")
                .build());
        JsonNode output = objectMapper.readTree(result.getOutputData());
        String serialized = output.toString();

        assertEquals("SUCCESS", result.getStatus().name());
        assertTrue(output.path("downstreamEvidenceViews").isArray());
        assertTrue(output.path("drafts").get(0).path("downstreamEvidenceViews").isArray());
        assertTrue(output.path("extractorInput").path("competitors").isArray());
        assertTrue(serialized.contains("PRICING_BLOCK"));
        assertTrue(serialized.contains("STRUCTURED_BLOCK_HIT"));
        assertTrue(serialized.contains("https://docs.example.com/pricing"));
        assertTrue(serialized.contains("\"content\":\"\"")
                || !serialized.contains("这里是很长的完整定价页正文"));
        assertTrue(!serialized.contains("这里是很长的完整定价页正文"));
        assertTrue(!serialized.contains("完整结构块正文也不应该继续透传"));
    }

    @Test
    void shouldIgnoreUnknownPricingFieldsWhenBuildingKnowledgeDraft() {
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(1L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(1L)
                        .competitorName("Feishu")
                        .evidenceId("T0001-COLLECT-001")
                        .title("Pricing Docs")
                        .url("https://example.com/pricing")
                        .fullContent("pricing content")
                        .contentSnippet("pricing snippet")
                        .build()
        ));
        when(promptService.render(eq("extractor"), any())).thenReturn("prompt");
        when(llmClient.chatForJson(any(), any(), eq("ExtractedSchema")))
                .thenReturn("""
                        {
                          "officialUrl": "https://example.com",
                          "summary": "ok",
                          "positioning": "team collaboration",
                          "targetUsers": ["teams"],
                          "coreFeatures": [],
                          "pricing": {
                            "model": "seat-based",
                            "hasFreeTier": true,
                            "plans": ["free", "pro"],
                            "sourceUrls": ["https://example.com/pricing"]
                          },
                          "strengths": [],
                          "weaknesses": [],
                          "sources": [],
                          "sourceUrls": ["https://example.com/pricing"]
                        }
                        """);

        // 真实链路里 LLM 可能为 pricing 附带当前 DTO 尚未声明的字段，提取阶段不应因此整节点失败。
        AgentResult result = extractorAgent.execute(AgentContext.builder()
                .taskId(1L)
                .taskName("task")
                .currentNodeName("extract_schema")
                .build());

        assertEquals("SUCCESS", result.getStatus().name());
        verify(knowledgeRepository, times(1)).save(any());
    }

    @Test
    void shouldNormalizeArrayPricingWhenBuildingKnowledgeDraft() throws Exception {
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(1L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(1L)
                        .competitorName("Notion AI")
                        .evidenceId("T0050-COLLECT-001")
                        .title("Notion AI Pricing")
                        .url("https://www.notion.so/pricing")
                        .fullContent("Notion AI pricing evidence describes business plans and AI add-on options for team workspaces.")
                        .contentSnippet("business plans and AI add-on options")
                        .build()
        ));
        when(promptService.render(eq("extractor"), any())).thenReturn("prompt");
        when(llmClient.chatForJson(any(), any(), eq("ExtractedSchema")))
                .thenReturn("""
                        {
                          "officialUrl": "https://www.notion.so/product/ai",
                          "summary": "workspace AI assistant",
                          "positioning": "team knowledge assistant",
                          "targetUsers": ["teams"],
                          "coreFeatures": [],
                          "pricing": [[{
                            "model": "business plan with AI add-on",
                            "plans": [{"name": "Business", "price": "$20 per member"}],
                            "evidenceIds": ["T0050-COLLECT-001"],
                            "sourceUrls": ["https://www.notion.so/pricing"]
                          }]],
                          "strengths": [],
                          "weaknesses": [],
                          "sources": [],
                          "sourceUrls": ["https://www.notion.so/pricing"]
                        }
                        """);

        // live 链路里模型可能把 pricing 包成数组甚至嵌套数组，抽取节点应规范化后继续向下游传递。
        AgentResult result = extractorAgent.execute(AgentContext.builder()
                .taskId(1L)
                .taskName("task")
                .currentNodeName("extract_schema")
                .build());

        assertEquals("SUCCESS", result.getStatus().name());
        JsonNode output = objectMapper.readTree(result.getOutputData());
        assertEquals("business plan with AI add-on",
                output.path("drafts").get(0).path("pricing").path("model").asText());
        assertTrue(output.path("drafts").get(0).path("pricing").path("plans").toString().contains("Business"));
        verify(knowledgeRepository, times(1)).save(any());
    }

    @Test
    void shouldNormalizeStringStrengthsAndWeaknessesInsteadOfFailingDraftConversion() throws Exception {
        SchemaExtractorAgent providerOnlyAgent = new SchemaExtractorAgent(
                logRepository,
                knowledgeRepository,
                llmClient,
                promptService,
                agentContextAssembler,
                objectMapper,
                inputProvider
        );
        DownstreamEvidenceView pricingEvidence = DownstreamEvidenceView.builder()
                .evidenceId("T0050-COLLECT-PRICING")
                .competitorName("Notion AI")
                .sourceType("PRICING")
                .title("Notion AI Pricing")
                .content("Notion AI offers AI features for team workspaces, with business pricing and enterprise options.")
                .sourceUrls(List.of("https://www.notion.so/pricing"))
                .qualitySignals(List.of("LIGHTWEIGHT_CONTENT_READY"))
                .issueFlags(List.of())
                .structuredBlocks(List.of())
                .structuredPayload(Map.of())
                .quality(DownstreamEvidenceQuality.builder()
                        .qualityScore(0.88)
                        .build())
                .build()
                .normalized();
        when(inputProvider.provide(any(AgentContext.class))).thenReturn(ExtractorInputPackage.builder()
                .taskId(50L)
                .nodeName("extract_schema")
                .competitors(List.of(ExtractorCompetitorInput.builder()
                        .competitorName("Notion AI")
                        .evidenceCatalog(List.of(pricingEvidence))
                        .structuredEvidence(List.of())
                        .readableEvidence(List.of(pricingEvidence))
                        .skippedEvidence(List.of())
                        .sourceUrls(List.of("https://www.notion.so/pricing"))
                        .issueFlags(List.of())
                        .budget(Map.of(
                                "maxPromptEvidenceChars", 4000,
                                "usedPromptEvidenceChars", 92,
                                "truncated", false
                        ))
                        .build()))
                .build());
        when(promptService.render(eq("extractor"), any())).thenReturn("prompt");
        when(llmClient.chatForJson(any(), any(), eq("ExtractedSchema")))
                .thenReturn("""
                        {
                          "officialUrl": "https://www.notion.so/product/ai",
                          "summary": "Notion AI is a workspace AI assistant for docs and collaboration.",
                          "positioning": "workspace AI assistant",
                          "targetUsers": ["teams", "knowledge workers"],
                          "coreFeatures": [],
                          "pricing": {
                            "model": "business pricing with AI options",
                            "evidenceIds": ["T0050-COLLECT-PRICING"],
                            "sourceUrls": ["https://www.notion.so/pricing"]
                          },
                          "strengths": ["深度集成 AI 协作能力"],
                          "weaknesses": ["企业采购价格透明度有限"],
                          "sources": [],
                          "sourceUrls": ["https://www.notion.so/pricing"]
                        }
                        """);

        // 真实链路里模型可能把优势/短板返回为字符串数组，抽取器应先归一化成 DTO 对象再交给下游。
        AgentResult result = providerOnlyAgent.execute(AgentContext.builder()
                .taskId(50L)
                .taskName("task")
                .currentNodeName("extract_schema")
                .build());

        assertEquals("SUCCESS", result.getStatus().name());
        JsonNode draft = objectMapper.readTree(result.getOutputData()).path("drafts").get(0);

        assertEquals("深度集成 AI 协作能力", draft.path("strengths").get(0).path("point").asText());
        assertEquals("企业采购价格透明度有限", draft.path("weaknesses").get(0).path("point").asText());
        assertEquals("https://www.notion.so/pricing",
                draft.path("strengths").get(0).path("sourceUrls").get(0).asText());
        assertEquals("https://www.notion.so/pricing",
                draft.path("weaknesses").get(0).path("sourceUrls").get(0).asText());
        verify(knowledgeRepository, times(1)).save(any());
    }

    @Test
    void shouldBackfillScalarCoverageAndStrengthPointFromDescription() throws Exception {
        SchemaExtractorAgent providerOnlyAgent = new SchemaExtractorAgent(
                logRepository,
                knowledgeRepository,
                llmClient,
                promptService,
                agentContextAssembler,
                objectMapper,
                inputProvider
        );
        DownstreamEvidenceView officialEvidence = DownstreamEvidenceView.builder()
                .evidenceId("T0050-COLLECT-OFFICIAL")
                .competitorName("Notion AI")
                .sourceType("OFFICIAL")
                .title("Meet your AI team")
                .content("Notion AI is a workspace AI assistant for teams, docs, search, and automation.")
                .sourceUrls(List.of("https://www.notion.so/product/ai"))
                .qualitySignals(List.of("LIGHTWEIGHT_CONTENT_READY"))
                .issueFlags(List.of())
                .structuredBlocks(List.of())
                .structuredPayload(Map.of())
                .quality(DownstreamEvidenceQuality.builder()
                        .qualityScore(0.9)
                        .build())
                .build()
                .normalized();
        DownstreamEvidenceView docsEvidence = DownstreamEvidenceView.builder()
                .evidenceId("T0050-COLLECT-DOCS")
                .competitorName("Notion AI")
                .sourceType("DOCS")
                .title("Notion AI Help")
                .content("Notion help documents describe AI writing, enterprise search, automations, and team workflows.")
                .sourceUrls(List.of("https://www.notion.so/help"))
                .qualitySignals(List.of("LIGHTWEIGHT_CONTENT_READY"))
                .issueFlags(List.of())
                .structuredBlocks(List.of())
                .structuredPayload(Map.of())
                .quality(DownstreamEvidenceQuality.builder()
                        .qualityScore(0.88)
                        .build())
                .build()
                .normalized();
        when(inputProvider.provide(any(AgentContext.class))).thenReturn(ExtractorInputPackage.builder()
                .taskId(50L)
                .nodeName("extract_schema")
                .competitors(List.of(ExtractorCompetitorInput.builder()
                        .competitorName("Notion AI")
                        .evidenceCatalog(List.of(officialEvidence, docsEvidence))
                        .structuredEvidence(List.of())
                        .readableEvidence(List.of(officialEvidence, docsEvidence))
                        .skippedEvidence(List.of())
                        .sourceUrls(List.of("https://www.notion.so/product/ai", "https://www.notion.so/help"))
                        .issueFlags(List.of())
                        .budget(Map.of(
                                "maxPromptEvidenceChars", 4000,
                                "usedPromptEvidenceChars", 88,
                                "truncated", false
                        ))
                        .build()))
                .build());
        when(promptService.render(eq("extractor"), any())).thenReturn("prompt");
        when(llmClient.chatForJson(any(), any(), eq("ExtractedSchema")))
                .thenReturn("""
                        {
                          "officialUrl": "https://www.notion.so/product/ai",
                          "summary": "Notion AI is a workspace AI assistant for teams.",
                          "positioning": "workspace AI collaboration platform",
                          "targetUsers": ["teams", "knowledge workers"],
                          "coreFeatures": [],
                          "pricing": {},
                          "strengths": [{
                            "description": "深度集成 AI、企业搜索和自动化能力",
                            "evidenceIds": ["T0050-COLLECT-OFFICIAL"],
                            "sourceUrls": ["https://www.notion.so/product/ai"]
                          }],
                          "weaknesses": [],
                          "sources": [],
                          "sourceUrls": ["https://www.notion.so/product/ai", "https://www.notion.so/help"]
                        }
                        """);

        // live task 50 中标量字段有业务值但缺字段级引用，优势项也可能只给 description；归一化后不应继续制造质量缺口。
        AgentResult result = providerOnlyAgent.execute(AgentContext.builder()
                .taskId(50L)
                .taskName("task")
                .currentNodeName("extract_schema")
                .build());
        JsonNode draft = objectMapper.readTree(result.getOutputData()).path("drafts").get(0);
        JsonNode coverage = draft.path("evidenceCoverage");

        assertEquals("SUCCESS", result.getStatus().name());
        assertEquals("TRACEABLE", coverage.path("summary").path("status").asText());
        assertEquals("TRACEABLE", coverage.path("positioning").path("status").asText());
        assertEquals("TRACEABLE", coverage.path("targetUsers").path("status").asText());
        assertEquals("https://www.notion.so/product/ai",
                coverage.path("summary").path("sourceUrls").get(0).asText());
        assertEquals("深度集成 AI、企业搜索和自动化能力",
                draft.path("strengths").get(0).path("point").asText());
        verify(knowledgeRepository, times(1)).save(any());
    }

    @Test
    void shouldBackfillStrengthPointFromDetailField() throws Exception {
        SchemaExtractorAgent providerOnlyAgent = new SchemaExtractorAgent(
                logRepository,
                knowledgeRepository,
                llmClient,
                promptService,
                agentContextAssembler,
                objectMapper,
                inputProvider
        );
        DownstreamEvidenceView officialEvidence = DownstreamEvidenceView.builder()
                .evidenceId("T0050-COLLECT-OFFICIAL")
                .competitorName("Notion AI")
                .sourceType("OFFICIAL")
                .title("Meet your AI team")
                .content("Notion AI is a workspace AI assistant for teams, docs, search, and automation.")
                .sourceUrls(List.of("https://www.notion.so/product/ai"))
                .qualitySignals(List.of("LIGHTWEIGHT_CONTENT_READY"))
                .issueFlags(List.of())
                .structuredBlocks(List.of())
                .structuredPayload(Map.of())
                .quality(DownstreamEvidenceQuality.builder()
                        .qualityScore(0.9)
                        .build())
                .build()
                .normalized();
        when(inputProvider.provide(any(AgentContext.class))).thenReturn(ExtractorInputPackage.builder()
                .taskId(50L)
                .nodeName("extract_schema")
                .competitors(List.of(ExtractorCompetitorInput.builder()
                        .competitorName("Notion AI")
                        .evidenceCatalog(List.of(officialEvidence))
                        .structuredEvidence(List.of())
                        .readableEvidence(List.of(officialEvidence))
                        .skippedEvidence(List.of())
                        .sourceUrls(List.of("https://www.notion.so/product/ai"))
                        .issueFlags(List.of())
                        .budget(Map.of(
                                "maxPromptEvidenceChars", 4000,
                                "usedPromptEvidenceChars", 88,
                                "truncated", false
                        ))
                        .build()))
                .build());
        when(promptService.render(eq("extractor"), any())).thenReturn("prompt");
        when(llmClient.chatForJson(any(), any(), eq("ExtractedSchema")))
                .thenReturn("""
                        {
                          "officialUrl": "https://www.notion.so/product/ai",
                          "summary": "Notion AI is a workspace AI assistant for teams.",
                          "strengths": [{
                            "detail": "深度集成 AI 工作流，团队无需切换上下文。",
                            "evidenceIds": ["T0050-COLLECT-OFFICIAL"],
                            "sourceUrls": ["https://www.notion.so/product/ai"]
                          }],
                          "weaknesses": [],
                          "sources": [],
                          "sourceUrls": ["https://www.notion.so/product/ai"]
                        }
                        """);

        // 模型会把优势描述写到 detail/content/text 等字段中；这里锁定 detail 变体也必须补齐 point。
        AgentResult result = providerOnlyAgent.execute(AgentContext.builder()
                .taskId(50L)
                .taskName("task")
                .currentNodeName("extract_schema")
                .build());
        JsonNode draft = objectMapper.readTree(result.getOutputData()).path("drafts").get(0);

        assertEquals("SUCCESS", result.getStatus().name());
        assertEquals("深度集成 AI 工作流，团队无需切换上下文。",
                draft.path("strengths").get(0).path("point").asText());
        verify(knowledgeRepository, times(1)).save(any());
    }

    private JsonNode findBundle(JsonNode bundles, String sectionKey) {
        for (JsonNode bundle : bundles) {
            if (sectionKey.equals(bundle.path("sectionKey").asText())) {
                return bundle;
            }
        }
        throw new AssertionError("bundle not found: " + sectionKey);
    }
}
