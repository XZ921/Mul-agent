package cn.bugstack.competitoragent.agent.extractor;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.context.AgentContextAssembler;
import cn.bugstack.competitoragent.context.TaskRagContextBundle;
import cn.bugstack.competitoragent.llm.LlmClient;
import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.model.entity.CompetitorKnowledge;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
    private final SchemaExtractorAgent extractorAgent = new SchemaExtractorAgent(
            logRepository,
            evidenceRepository,
            knowledgeRepository,
            llmClient,
            promptService,
            agentContextAssembler,
            new ObjectMapper()
    );

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
    void shouldRecoverWhenExtractorReturnsEmptyJsonArray() {
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

        assertEquals("SUCCESS", result.getStatus().name());
        assertTrue(result.getOutputData().contains("MODEL_OUTPUT_RECOVERED"));
        assertTrue(result.getOutputData().contains("https://www.bilibili.com"));
        verify(knowledgeRepository, times(1)).save(any());
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

    private JsonNode findBundle(JsonNode bundles, String sectionKey) {
        for (JsonNode bundle : bundles) {
            if (sectionKey.equals(bundle.path("sectionKey").asText())) {
                return bundle;
            }
        }
        throw new AssertionError("bundle not found: " + sectionKey);
    }
}
