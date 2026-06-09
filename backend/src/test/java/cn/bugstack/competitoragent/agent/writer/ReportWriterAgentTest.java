package cn.bugstack.competitoragent.agent.writer;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.context.AgentContextAssembler;
import cn.bugstack.competitoragent.context.TaskRagContextBundle;
import cn.bugstack.competitoragent.llm.LlmClient;
import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.llm.TokenUsage;
import cn.bugstack.competitoragent.memory.MemoryWritebackService;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.model.entity.Report;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.repository.ReportRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportWriterAgentTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AgentExecutionLogRepository logRepository = mock(AgentExecutionLogRepository.class);
    private final ReportRepository reportRepository = mock(ReportRepository.class);
    private final EvidenceSourceRepository evidenceRepository = mock(EvidenceSourceRepository.class);
    private final LlmClient llmClient = mock(LlmClient.class);
    private final PromptTemplateService promptService = mock(PromptTemplateService.class);
    private final AgentContextAssembler agentContextAssembler = mock(AgentContextAssembler.class);
    private final MemoryWritebackService memoryWritebackService = mock(MemoryWritebackService.class);
    private final ReportWriterAgent agent = new ReportWriterAgent(
            logRepository,
            reportRepository,
            evidenceRepository,
            llmClient,
            promptService,
            agentContextAssembler,
            memoryWritebackService,
            objectMapper
    );

    @Test
    void shouldPassUnifiedTaskRagContextIntoWriterPrompt() throws Exception {
        // Writer 必须消费统一上下文，确保撰写结论时知道检索命中范围和仍然存在的证据缺口。
        when(agentContextAssembler.assemble(any(AgentContext.class))).thenAnswer(invocation -> {
            AgentContext originalContext = invocation.getArgument(0);
            return originalContext.toBuilder()
                    .taskRagContextBundle(TaskRagContextBundle.builder()
                            .query("Notion AI enterprise pricing")
                            .retrievalSummary("命中定价页与帮助中心摘要")
                            .gapSummary("企业版公开合同条款仍不足")
                            .sourceUrls(List.of("https://docs.notion.so"))
                            .build())
                    .build();
        });
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(1L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(1L)
                        .competitorName("Notion AI")
                        .evidenceId("E001")
                        .title("Docs")
                        .url("https://docs.notion.so")
                        .build()
        ));
        when(reportRepository.findByTaskId(1L)).thenReturn(Optional.empty());
        when(promptService.render(eq("writer"), any())).thenReturn("writer-prompt");
        when(llmClient.chat(any(), any())).thenReturn("# report");
        when(llmClient.getModelName()).thenReturn("mock-model");
        when(llmClient.getLastTokenUsage()).thenReturn(new TokenUsage(10, 20, 30));

        AgentContext context = AgentContext.builder()
                .taskId(1L)
                .taskName("task")
                .subjectProduct("Our Product")
                .reportLanguage("中文")
                .currentNodeName("write_report")
                .build();
        context.putSharedOutput("analyze_competitors", "{\"overview\":\"分析完成\"}");

        AgentResult result = agent.execute(context);
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("SUCCESS", result.getStatus().name());
        assertTrue(output.path("taskRagContext").asText().contains("Notion AI enterprise pricing"));
        assertTrue(output.path("taskRagContext").asText().contains("https://docs.notion.so"));
        verify(promptService).render(eq("writer"), argThat(variables ->
                variables.get("taskRagContext") != null
                        && variables.get("taskRagContext").contains("检索查询")
                        && variables.get("taskRagContext").contains("企业版公开合同条款仍不足")
        ));
    }

    @Test
    void shouldUseRevisionFocusWhenRunningInRevisionMode() {
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(1L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(1L)
                        .competitorName("Notion AI")
                        .evidenceId("E001")
                        .title("Docs")
                        .url("https://docs.notion.so")
                        .build()
        ));
        when(reportRepository.findByTaskId(1L)).thenReturn(Optional.of(
                Report.builder()
                        .taskId(1L)
                        .content("# 初版\n原始结论")
                        .build()
        ));
        when(promptService.render(eq("writer"), any())).thenAnswer(invocation -> {
            Map<String, String> variables = invocation.getArgument(1);
            return variables.get("revisionFocus") + "\n" + variables.get("revisionPlan");
        });
        when(llmClient.chat(any(), any())).thenReturn("""
                # 修订报告
                ## 结论
                已根据评审意见补充 [证据：E001] 并调整结论措辞。
                """);
        when(llmClient.getModelName()).thenReturn("mock-model");
        when(llmClient.getLastTokenUsage()).thenReturn(new TokenUsage(10, 20, 30));

        AgentContext context = AgentContext.builder()
                .taskId(1L)
                .taskName("task")
                .subjectProduct("Our Product")
                .reportLanguage("中文")
                .currentNodeName("rewrite_report")
                .currentNodeConfig("{\"mode\":\"revision\"}")
                .build();
        context.putSharedOutput("analyze_competitors", "{\"overview\":\"分析完成\"}");
        context.putSharedOutput("quality_check", """
                {
                  "revisionPlan": {
                    "rewriteRequired": true,
                    "summary": "需要补足结论证据",
                    "items": [
                      {
                        "type": "unsupported_claim",
                        "section": "结论",
                        "severity": "ERROR",
                        "suggestion": "补充证据并收紧结论"
                      }
                    ],
                    "rewriteGuidelines": [
                      "结论: 请补充 [证据：E001] 引用。"
                    ]
                  }
                }
                """);

        AgentResult result = agent.execute(context);

        assertEquals("SUCCESS", result.getStatus().name());
        assertTrue(result.getOutputData().contains("[证据：E001]"));
        verify(promptService).render(eq("writer"), any());
        verify(reportRepository).save(any());
    }

    @Test
    void shouldBackfillWriterSourceUrlsWhenAnalyzerOutputDropsThem() throws Exception {
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(1L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(1L)
                        .competitorName("Notion AI")
                        .evidenceId("E001")
                        .title("Docs")
                        .url("https://docs.notion.so")
                        .contentSnippet("documentation snippet")
                        .build()
        ));
        when(reportRepository.findByTaskId(1L)).thenReturn(Optional.empty());
        when(promptService.render(eq("writer"), any())).thenAnswer(invocation -> {
            Map<String, String> variables = invocation.getArgument(1);
            return variables.get("analysisResult");
        });
        when(llmClient.chat(any(), any())).thenReturn("""
                # 竞品报告
                基于现有证据整理结论。
                """);
        when(llmClient.getModelName()).thenReturn("mock-model");
        when(llmClient.getLastTokenUsage()).thenReturn(new TokenUsage(10, 20, 30));

        AgentContext context = AgentContext.builder()
                .taskId(1L)
                .taskName("task")
                .subjectProduct("Our Product")
                .reportLanguage("中文")
                .currentNodeName("write_report")
                .build();
        context.putSharedOutput("analyze_competitors", """
                {
                  "overview": "分析完成",
                  "issueFlags": ["MISSING_EVIDENCE"]
                }
                """);

        AgentResult result = agent.execute(context);
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("SUCCESS", result.getStatus().name());
        assertEquals("https://docs.notion.so", output.path("sourceUrls").get(0).asText());
        assertTrue(output.path("issueFlags").toString().contains("MISSING_EVIDENCE"));
        assertTrue(output.path("issueFlags").toString().contains("SOURCE_URLS_BACKFILLED"));
        assertTrue(output.path("evidenceFragments").isArray());
        assertTrue(output.path("sectionEvidenceBundles").isArray());
        assertEquals("CONCLUSION", findBundle(output.path("sectionEvidenceBundles"), "report_conclusion").path("sectionType").asText());
        assertTrue(findBundle(output.path("sectionEvidenceBundles"), "report_conclusion").path("sourceUrls").toString().contains("https://docs.notion.so"));
        assertTrue(findBundle(output.path("sectionEvidenceBundles"), "report_conclusion").path("issueFlags").toString().contains("SECTION_EVIDENCE_GAP"));
    }

    @Test
    void shouldWriteTraceableReportConclusionBackToStructuredMemory() {
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(1L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(1L)
                        .competitorName("Notion AI")
                        .evidenceId("E001")
                        .title("Docs")
                        .url("https://docs.notion.so")
                        .build()
        ));
        when(reportRepository.findByTaskId(1L)).thenReturn(Optional.empty());
        when(promptService.render(eq("writer"), any())).thenReturn("writer-prompt");
        when(llmClient.chat(any(), any())).thenReturn("# 竞品报告\n可追溯结论");
        when(llmClient.getModelName()).thenReturn("mock-model");
        when(llmClient.getLastTokenUsage()).thenReturn(new TokenUsage(10, 20, 30));

        AgentContext context = AgentContext.builder()
                .taskId(1L)
                .taskName("task")
                .subjectProduct("Our Product")
                .reportLanguage("中文")
                .planVersionId(22L)
                .branchKey("analysis")
                .currentNodeName("write_report")
                .build();
        context.putSharedOutput("analyze_competitors", """
                {
                  "overview": "分析完成",
                  "sourceUrls": ["https://docs.notion.so"]
                }
                """);

        AgentResult result = agent.execute(context);

        assertEquals("SUCCESS", result.getStatus().name());
        verify(memoryWritebackService).writeback(argThat(request ->
                request != null
                        && Long.valueOf(1L).equals(request.getTaskId())
                        && "write_report".equals(request.getNodeName())
                        && "VERIFIED_TASK_CONCLUSION".equals(request.getWritebackCategory())
                        && "TRACEABLE".equals(request.getQualitySignal())
                        && request.getSourceUrls() != null
                        && request.getSourceUrls().contains("https://docs.notion.so")));
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
