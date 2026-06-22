package cn.bugstack.competitoragent.agent.analyzer;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.context.AgentContextAssembler;
import cn.bugstack.competitoragent.context.TaskRagContextBundle;
import cn.bugstack.competitoragent.llm.LlmClient;
import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.llm.TokenUsage;
import cn.bugstack.competitoragent.model.entity.CompetitorKnowledge;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompetitorAnalysisAgentTest {

    private final AgentExecutionLogRepository logRepository = mock(AgentExecutionLogRepository.class);
    private final CompetitorKnowledgeRepository knowledgeRepository = mock(CompetitorKnowledgeRepository.class);
    private final LlmClient llmClient = mock(LlmClient.class);
    private final PromptTemplateService promptService = mock(PromptTemplateService.class);
    private final AgentContextAssembler agentContextAssembler = mock(AgentContextAssembler.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CompetitorAnalysisAgent agent = new CompetitorAnalysisAgent(
            logRepository,
            knowledgeRepository,
            llmClient,
            promptService,
            agentContextAssembler,
            objectMapper
    );

    @Test
    void shouldNormalizeDriftedAnalysisFieldsAndCarryTraceabilityFlags() throws Exception {
        when(agentContextAssembler.assemble(any(AgentContext.class))).thenAnswer(invocation -> {
            AgentContext originalContext = invocation.getArgument(0);
            return originalContext.toBuilder()
                    .taskRagContextBundle(TaskRagContextBundle.builder()
                            .query("Notion AI pricing governance")
                            .retrievalSummary("命中企业治理说明与部分定价线索。")
                            .gapSummary("公开企业定价页仍不足。")
                            .sourceUrls(List.of("https://docs.notion.so"))
                            .build())
                    .build();
        });
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(1L)).thenReturn(List.of(
                CompetitorKnowledge.builder()
                        .taskId(1L)
                        .competitorName("Notion AI")
                        .summary("Knowledge assistant")
                        .positioning("workspace ai")
                        .targetUsers("[\"pm\"]")
                        .coreFeatures("[]")
                        .pricing("{}")
                        .strengths("[]")
                        .weaknesses("[]")
                        .sources("[{\"evidenceId\":\"E001\",\"title\":\"Docs\",\"url\":\"https://docs.notion.so\"}]")
                        .sourceUrls("[\"https://docs.notion.so\"]")
                        .evidenceCoverage("""
                                {
                                  "pricing": {
                                    "status": "MISSING_EVIDENCE"
                                  }
                                }
                                """)
                        .build()
        ));
        when(promptService.render(eq("analyzer"), any())).thenReturn("prompt");
        when(llmClient.chatForJson(any(), any(), eq("Analysis"))).thenReturn("""
                {
                  "overview": "分析完成",
                  "featureHighlights": "功能覆盖广",
                  "marketPositioning": "面向知识型团队",
                  "pricingInsights": "公开定价信息不足",
                  "recommendations": ["继续观察"]
                }
                """);
        when(llmClient.getModelName()).thenReturn("mock-model");
        when(llmClient.getLastTokenUsage()).thenReturn(new TokenUsage(10, 20, 30));

        AgentResult result = agent.execute(AgentContext.builder()
                .taskId(1L)
                .taskName("task")
                .subjectProduct("Our Product")
                .analysisDimensions("功能,定位,定价")
                .currentNodeName("analyze_competitors")
                .build());
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("SUCCESS", result.getStatus().name());
        assertEquals("面向知识型团队", output.path("positioningComparison").asText());
        assertEquals("功能覆盖广", output.path("featureComparison").asText());
        assertEquals("https://docs.notion.so", output.path("sourceUrls").get(0).asText());
        assertTrue(output.path("taskRagContext").asText().contains("Notion AI pricing governance"));
        assertTrue(output.path("taskRagContext").asText().contains("https://docs.notion.so"));
        assertTrue(output.path("issueFlags").toString().contains("FIELD_DRIFT_CORRECTED"));
        assertTrue(output.path("issueFlags").toString().contains("MISSING_EVIDENCE"));
        assertTrue(findBundle(output.path("sectionEvidenceBundles"), "pricing").path("issueFlags").toString().contains("SECTION_EVIDENCE_GAP"));
        assertEquals("CONCLUSION", findBundle(output.path("sectionEvidenceBundles"), "conclusion").path("sectionType").asText());
        assertTrue(findBundle(output.path("sectionEvidenceBundles"), "conclusion").path("sourceUrls").toString().contains("https://docs.notion.so"));
        verify(promptService).render(eq("analyzer"), argThat(variables ->
                variables.get("taskRagContext") != null
                        && variables.get("taskRagContext").contains("检索查询")
                        && variables.get("taskRagContext").contains("公开企业定价页仍不足")
        ));
    }

    @Test
    void shouldCarryDownstreamEvidenceViewsFromExtractorToAnalyzer() throws Exception {
        when(agentContextAssembler.assemble(any(AgentContext.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(1L)).thenReturn(List.of());
        when(promptService.render(eq("analyzer"), any())).thenReturn("prompt");
        when(llmClient.chatForJson(any(), any(), eq("Analysis"))).thenReturn("""
                {
                  "overview": "分析完成",
                  "featureComparison": "功能完整",
                  "positioningComparison": "协作平台",
                  "pricingComparison": "公开 Pro 199 / 月",
                  "targetUserComparison": "团队用户",
                  "strengthsSummary": "文档清晰",
                  "weaknessesSummary": "暂无",
                  "recommendations": ["持续观察"]
                }
                """);
        when(llmClient.getModelName()).thenReturn("mock-model");
        when(llmClient.getLastTokenUsage()).thenReturn(new TokenUsage(10, 20, 30));

        AgentContext context = AgentContext.builder()
                .taskId(1L)
                .taskName("task")
                .subjectProduct("Our Product")
                .analysisDimensions("功能,定位,定价")
                .currentNodeName("analyze_competitors")
                .build();
        context.putSharedOutput("extract_schema", """
                {
                  "drafts": [
                    {
                      "competitorName": "Acme",
                      "summary": "ok",
                      "sourceUrls": ["https://docs.example.com/pricing"],
                      "issueFlags": [],
                      "downstreamEvidenceViews": [
                        {
                          "evidenceId": "E001",
                          "title": "Pricing Docs",
                          "sourceType": "DOCS",
                          "content": "公开定价页正文",
                          "sourceUrls": ["https://docs.example.com/pricing"],
                          "qualitySignals": ["STRUCTURED_BLOCK_HIT", "PRICING_BLOCK_HIT"],
                          "structuredBlocks": [{"blockType": "PRICING_BLOCK", "summary": "Pro 199 / 月"}]
                        }
                      ]
                    }
                  ],
                  "downstreamEvidenceViews": [
                    {
                      "evidenceId": "E001",
                      "sourceUrls": ["https://docs.example.com/pricing"],
                      "qualitySignals": ["STRUCTURED_BLOCK_HIT"],
                      "structuredBlocks": [{"blockType": "PRICING_BLOCK", "summary": "Pro 199 / 月"}]
                    }
                  ]
                }
                """);

        AgentResult result = agent.execute(context);
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("SUCCESS", result.getStatus().name());
        // analyzer 必须优先消费 extract_schema 的统一证据视图，历史仓库快照只作为缺失上游视图时的回退。
        verify(promptService).render(eq("analyzer"), argThat(variables ->
                variables.get("competitorData") != null
                        && variables.get("competitorData").contains("downstreamEvidenceViews")
                        && variables.get("competitorData").contains("PRICING_BLOCK")
                        && variables.get("competitorData").contains("STRUCTURED_BLOCK_HIT")
        ));
        assertTrue(output.path("downstreamEvidenceViews").toString().contains("PRICING_BLOCK"));
    }

    @Test
    void shouldPreferExtractorDraftsOverTaskSnapshotAndOnlyBackfillMissingFields() throws Exception {
        when(agentContextAssembler.assemble(any(AgentContext.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(1L)).thenReturn(List.of(
                CompetitorKnowledge.builder()
                        .taskId(1L)
                        .competitorName("Acme")
                        .summary("old snapshot summary")
                        .positioning("old positioning")
                        .targetUsers("[\"old users\"]")
                        .coreFeatures("[{\"name\":\"legacy feature\"}]")
                        .pricing("{\"model\":\"legacy pricing\"}")
                        .strengths("[{\"name\":\"legacy strength\"}]")
                        .weaknesses("[{\"name\":\"legacy weakness\"}]")
                        .sources("[{\"evidenceId\":\"S001\",\"title\":\"Legacy\",\"url\":\"https://legacy.example.com\"}]")
                        .sourceUrls("[\"https://legacy.example.com\"]")
                        .evidenceCoverage("""
                                {
                                  "summary": {"status": "TRACEABLE", "sourceUrls": ["https://legacy.example.com"]}
                                }
                                """)
                        .build()
        ));
        when(promptService.render(eq("analyzer"), any())).thenReturn("prompt");
        when(llmClient.chatForJson(any(), any(), eq("Analysis"))).thenReturn("""
                {
                  "overview": "分析完成",
                  "featureComparison": "新功能更强",
                  "positioningComparison": "新定位",
                  "pricingComparison": "新价格",
                  "targetUserComparison": "团队用户",
                  "strengthsSummary": "新优势",
                  "weaknessesSummary": "新短板",
                  "recommendations": ["继续推进"]
                }
                """);
        when(llmClient.getModelName()).thenReturn("mock-model");
        when(llmClient.getLastTokenUsage()).thenReturn(new TokenUsage(10, 20, 30));

        AgentContext context = AgentContext.builder()
                .taskId(1L)
                .taskName("task")
                .subjectProduct("Our Product")
                .analysisDimensions("功能,定位,定价")
                .currentNodeName("analyze_competitors")
                .build();
        context.putSharedOutput("extract_schema", """
                {
                  "drafts": [
                    {
                      "competitorName": "Acme",
                      "summary": "fresh runtime summary",
                      "positioning": "fresh positioning",
                      "targetUsers": ["runtime teams"],
                      "coreFeatures": [{"name": "runtime feature"}],
                      "pricing": {},
                      "strengths": [],
                      "weaknesses": [],
                      "sourceUrls": ["https://runtime.example.com"],
                      "issueFlags": [],
                      "evidenceCoverage": {
                        "summary": {
                          "status": "TRACEABLE",
                          "sourceUrls": ["https://runtime.example.com"]
                        }
                      },
                      "downstreamEvidenceViews": [
                        {
                          "evidenceId": "E001",
                          "competitorName": "Acme",
                          "title": "Runtime Docs",
                          "sourceType": "DOCS",
                          "sourceUrls": ["https://runtime.example.com"],
                          "qualitySignals": ["STRUCTURED_BLOCK_HIT"],
                          "structuredBlocks": [{"blockType": "FEATURE_LIST", "summary": "runtime feature"}]
                        }
                      ]
                    }
                  ],
                  "downstreamEvidenceViews": [
                    {
                      "evidenceId": "E001",
                      "competitorName": "Acme",
                      "sourceUrls": ["https://runtime.example.com"],
                      "qualitySignals": ["STRUCTURED_BLOCK_HIT"],
                      "structuredBlocks": [{"blockType": "FEATURE_LIST", "summary": "runtime feature"}]
                    }
                  ]
                }
                """);

        AgentResult result = agent.execute(context);

        assertEquals("SUCCESS", result.getStatus().name());
        verify(promptService).render(eq("analyzer"), argThat(variables -> {
            try {
                JsonNode competitorData = objectMapper.readTree(variables.get("competitorData"));
                JsonNode payload = competitorData.get(0);
                return "fresh runtime summary".equals(payload.path("summary").asText())
                        && "fresh positioning".equals(payload.path("positioning").asText())
                        && "legacy pricing".equals(payload.path("pricing").path("model").asText())
                        && "EXTRACT_RESULT_DRAFT".equals(payload.path("inputPriority").asText())
                        && payload.path("downstreamEvidenceViews").toString().contains("runtime feature")
                        && payload.path("inputConflicts").isArray()
                        && payload.path("inputConflicts").toString().contains("summary")
                        && payload.path("inputConflicts").toString().contains("EXTRACT_RESULT_DRAFT_WINS");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }));
    }

    @Test
    void shouldFallbackToTaskSnapshotWhenExtractorDraftsMissingAndMarkFallbackIssueFlag() throws Exception {
        when(agentContextAssembler.assemble(any(AgentContext.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(1L)).thenReturn(List.of(
                CompetitorKnowledge.builder()
                        .taskId(1L)
                        .competitorName("Fallback AI")
                        .summary("snapshot summary")
                        .positioning("snapshot positioning")
                        .targetUsers("[\"teams\"]")
                        .coreFeatures("[]")
                        .pricing("{}")
                        .strengths("[]")
                        .weaknesses("[]")
                        .sources("[{\"evidenceId\":\"S001\",\"title\":\"Docs\",\"url\":\"https://fallback.example.com\"}]")
                        .sourceUrls("[\"https://fallback.example.com\"]")
                        .evidenceCoverage("{}")
                        .build()
        ));
        when(promptService.render(eq("analyzer"), any())).thenReturn("prompt");
        when(llmClient.chatForJson(any(), any(), eq("Analysis"))).thenReturn("""
                {
                  "overview": "分析完成",
                  "featureComparison": "功能完整",
                  "positioningComparison": "定位清晰",
                  "pricingComparison": "价格未知",
                  "targetUserComparison": "团队用户",
                  "strengthsSummary": "稳定",
                  "weaknessesSummary": "待补",
                  "recommendations": ["继续观察"]
                }
                """);
        when(llmClient.getModelName()).thenReturn("mock-model");
        when(llmClient.getLastTokenUsage()).thenReturn(new TokenUsage(10, 20, 30));

        AgentContext context = AgentContext.builder()
                .taskId(1L)
                .taskName("task")
                .subjectProduct("Our Product")
                .analysisDimensions("功能,定位,定价")
                .currentNodeName("analyze_competitors")
                .build();
        context.putSharedOutput("extract_schema", """
                {
                  "downstreamEvidenceViews": [
                    {
                      "evidenceId": "E009",
                      "competitorName": "Fallback AI",
                      "sourceUrls": ["https://fallback.example.com"],
                      "qualitySignals": ["STRUCTURED_BLOCK_HIT"]
                    }
                  ]
                }
                """);

        AgentResult result = agent.execute(context);

        assertEquals("SUCCESS", result.getStatus().name());
        verify(promptService).render(eq("analyzer"), argThat(variables -> {
            try {
                JsonNode competitorData = objectMapper.readTree(variables.get("competitorData"));
                JsonNode payload = competitorData.get(0);
                return "TASK_SNAPSHOT_FALLBACK".equals(payload.path("inputPriority").asText())
                        && payload.path("issueFlags").toString().contains("EXTRACT_OUTPUT_FALLBACK_TO_TASK_SNAPSHOT")
                        && payload.path("downstreamEvidenceViews").toString().contains("fallback.example.com");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }));
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
