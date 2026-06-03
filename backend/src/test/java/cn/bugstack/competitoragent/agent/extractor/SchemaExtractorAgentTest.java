package cn.bugstack.competitoragent.agent.extractor;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.llm.LlmClient;
import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.model.entity.CompetitorKnowledge;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
}
