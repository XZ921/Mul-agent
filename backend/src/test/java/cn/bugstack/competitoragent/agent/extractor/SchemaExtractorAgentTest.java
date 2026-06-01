package cn.bugstack.competitoragent.agent.extractor;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.llm.LlmClient;
import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
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
    private final SchemaExtractorAgent extractorAgent = new SchemaExtractorAgent(
            logRepository,
            evidenceRepository,
            knowledgeRepository,
            llmClient,
            promptService,
            new ObjectMapper()
    );

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
}
