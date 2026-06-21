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

import java.util.List;

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

    private JsonNode findBundle(JsonNode bundles, String sectionKey) {
        for (JsonNode bundle : bundles) {
            if (sectionKey.equals(bundle.path("sectionKey").asText())) {
                return bundle;
            }
        }
        throw new AssertionError("bundle not found: " + sectionKey);
    }
}
