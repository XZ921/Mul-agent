package cn.bugstack.competitoragent.agent.writer;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.llm.LlmClient;
import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.llm.TokenUsage;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.model.entity.Report;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.repository.ReportRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportWriterAgentTest {

    private final AgentExecutionLogRepository logRepository = mock(AgentExecutionLogRepository.class);
    private final ReportRepository reportRepository = mock(ReportRepository.class);
    private final EvidenceSourceRepository evidenceRepository = mock(EvidenceSourceRepository.class);
    private final LlmClient llmClient = mock(LlmClient.class);
    private final PromptTemplateService promptService = mock(PromptTemplateService.class);
    private final ReportWriterAgent agent = new ReportWriterAgent(
            logRepository,
            reportRepository,
            evidenceRepository,
            llmClient,
            promptService,
            new ObjectMapper()
    );

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
}
