package cn.bugstack.competitoragent.agent.reviewer;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.llm.LlmClient;
import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.llm.TokenUsage;
import cn.bugstack.competitoragent.model.entity.CompetitorKnowledge;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.model.entity.Report;
import cn.bugstack.competitoragent.repository.AgentExecutionLogRepository;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.repository.ReportRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
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

class QualityReviewAgentTest {

    private final AgentExecutionLogRepository logRepository = mock(AgentExecutionLogRepository.class);
    private final ReportRepository reportRepository = mock(ReportRepository.class);
    private final EvidenceSourceRepository evidenceRepository = mock(EvidenceSourceRepository.class);
    private final CompetitorKnowledgeRepository knowledgeRepository = mock(CompetitorKnowledgeRepository.class);
    private final LlmClient llmClient = mock(LlmClient.class);
    private final PromptTemplateService promptService = mock(PromptTemplateService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final QualityReviewAgent agent = new QualityReviewAgent(
            logRepository,
            reportRepository,
            evidenceRepository,
            knowledgeRepository,
            llmClient,
            promptService,
            objectMapper
    );

    @Test
    void shouldInjectEvidenceCoverageSummaryIntoReviewerPrompt() {
        when(reportRepository.findByTaskId(1L)).thenReturn(Optional.of(
                Report.builder().taskId(1L).content("# report").build()
        ));
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(1L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(1L)
                        .competitorName("Notion AI")
                        .evidenceId("E001")
                        .title("Docs")
                        .url("https://docs.notion.so")
                        .build()
        ));
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(1L)).thenReturn(List.of(
                CompetitorKnowledge.builder()
                        .taskId(1L)
                        .competitorName("Notion AI")
                        .evidenceCoverage("""
                                {
                                  "summary": {"status":"TRACEABLE","hasValue":true},
                                  "positioning": {"status":"MISSING_EVIDENCE","hasValue":true}
                                }
                                """)
                        .build()
        ));
        when(promptService.render(eq("reviewer"), any())).thenAnswer(invocation -> {
            Map<String, String> variables = invocation.getArgument(1);
            return variables.get("evidenceCoverageSummary");
        });
        when(llmClient.chatForJson(any(), any(), eq("QualityReview"))).thenReturn("""
                {
                  "score": 78,
                  "passed": false,
                  "issues": [
                    {
                      "type": "证据不足",
                      "section": "市场定位",
                      "severity": "ERROR",
                      "suggestion": "请补充证据"
                    }
                  ],
                  "summary": "存在缺证据章节"
                }
                """);
        when(llmClient.getModelName()).thenReturn("mock-model");
        when(llmClient.getLastTokenUsage()).thenReturn(new TokenUsage(10, 20, 30));

        AgentResult result = agent.execute(AgentContext.builder()
                .taskId(1L)
                .taskName("task")
                .currentNodeName("quality_check")
                .build());

        assertEquals("SUCCESS", result.getStatus().name());
        verify(promptService).render(eq("reviewer"), any());
    }

    @Test
    void shouldInjectClaimAuditChecklistIntoReviewerPrompt() {
        when(reportRepository.findByTaskId(3L)).thenReturn(Optional.of(
                Report.builder()
                        .taskId(3L)
                        .content("""
                                # 建议
                                建议优先面向企业知识库场景推进，因为该方向商业转化更快。
                                """)
                        .build()
        ));
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(3L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(3L)
                        .competitorName("Notion AI")
                        .evidenceId("E003")
                        .title("Docs")
                        .url("https://docs.notion.so")
                        .build()
        ));
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(3L)).thenReturn(List.of(
                CompetitorKnowledge.builder()
                        .taskId(3L)
                        .competitorName("Notion AI")
                        .evidenceCoverage("{}")
                        .build()
        ));
        when(promptService.render(eq("reviewer"), any())).thenAnswer(invocation -> {
            Map<String, String> variables = invocation.getArgument(1);
            return variables.get("claimAuditChecklist");
        });
        when(llmClient.chatForJson(any(), any(), eq("QualityReview"))).thenReturn("""
                {
                  "score": 70,
                  "passed": false,
                  "issues": [],
                  "summary": "需要进一步补证据"
                }
                """);
        when(llmClient.getModelName()).thenReturn("mock-model");
        when(llmClient.getLastTokenUsage()).thenReturn(new TokenUsage(10, 20, 30));

        AgentResult result = agent.execute(AgentContext.builder()
                .taskId(3L)
                .taskName("task")
                .currentNodeName("quality_check")
                .build());

        assertEquals("SUCCESS", result.getStatus().name());
        assertTrue(result.getOutputData().contains("建议"));
        verify(promptService).render(eq("reviewer"), any());
    }

    @Test
    void shouldAppendUnsupportedClaimIssueWhenConclusionLacksEvidenceCitation() {
        when(reportRepository.findByTaskId(2L)).thenReturn(Optional.of(
                Report.builder()
                        .taskId(2L)
                        .content("""
                                # 结论
                                Notion AI 在企业知识管理场景明显优于同类产品，并且落地风险更低。
                                """)
                        .build()
        ));
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(2L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(2L)
                        .competitorName("Notion AI")
                        .evidenceId("E002")
                        .title("Docs")
                        .url("https://docs.notion.so")
                        .build()
        ));
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(2L)).thenReturn(List.of(
                CompetitorKnowledge.builder()
                        .taskId(2L)
                        .competitorName("Notion AI")
                        .evidenceCoverage("""
                                {
                                  "summary": {"status":"TRACEABLE","hasValue":true},
                                  "positioning": {"status":"TRACEABLE","hasValue":true},
                                  "targetUsers": {"status":"TRACEABLE","hasValue":true},
                                  "coreFeatures": {"status":"TRACEABLE","hasValue":true},
                                  "pricing": {"status":"TRACEABLE","hasValue":true},
                                  "strengths": {"status":"TRACEABLE","hasValue":true},
                                  "weaknesses": {"status":"TRACEABLE","hasValue":true}
                                }
                                """)
                        .build()
        ));
        when(promptService.render(eq("reviewer"), any())).thenReturn("review-prompt");
        when(llmClient.chatForJson(any(), any(), eq("QualityReview"))).thenReturn("""
                {
                  "score": 90,
                  "passed": true,
                  "issues": [],
                  "summary": "整体较好"
                }
                """);
        when(llmClient.getModelName()).thenReturn("mock-model");
        when(llmClient.getLastTokenUsage()).thenReturn(new TokenUsage(10, 20, 30));

        AgentResult result = agent.execute(AgentContext.builder()
                .taskId(2L)
                .taskName("task")
                .currentNodeName("quality_check")
                .build());

        assertEquals("SUCCESS", result.getStatus().name());
        assertTrue(result.getOutputData().contains("unsupported_claim"));
        assertTrue(result.getOutputData().contains("结论"));
        assertTrue(result.getOutputData().contains("[证据：EID]"));
    }

    @Test
    void shouldDetectClaimLevelGapWithinFeatureComparisonSection() {
        when(reportRepository.findByTaskId(4L)).thenReturn(Optional.of(
                Report.builder()
                        .taskId(4L)
                        .content("""
                                # 功能对比
                                1. Notion AI 在知识沉淀和检索体验上明显领先。
                                2. Notion AI 的企业级权限能力更成熟 [证据：E004]。
                                """)
                        .build()
        ));
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(4L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(4L)
                        .competitorName("Notion AI")
                        .evidenceId("E004")
                        .title("Docs")
                        .url("https://docs.notion.so")
                        .build()
        ));
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(4L)).thenReturn(List.of(
                CompetitorKnowledge.builder()
                        .taskId(4L)
                        .competitorName("Notion AI")
                        .evidenceCoverage("""
                                {
                                  "coreFeatures": {"status":"TRACEABLE","hasValue":true}
                                }
                                """)
                        .build()
        ));
        when(promptService.render(eq("reviewer"), any())).thenReturn("review-prompt");
        when(llmClient.chatForJson(any(), any(), eq("QualityReview"))).thenReturn("""
                {
                  "score": 88,
                  "passed": true,
                  "issues": [],
                  "summary": "整体较好"
                }
                """);
        when(llmClient.getModelName()).thenReturn("mock-model");
        when(llmClient.getLastTokenUsage()).thenReturn(new TokenUsage(10, 20, 30));

        AgentResult result = agent.execute(AgentContext.builder()
                .taskId(4L)
                .taskName("task")
                .currentNodeName("quality_check")
                .build());

        assertEquals("SUCCESS", result.getStatus().name());
        assertTrue(result.getOutputData().contains("功能对比"));
        assertTrue(result.getOutputData().contains("知识沉淀和检索体验上明显领先"));
        assertTrue(result.getOutputData().contains("unsupported_claim"));
    }

    @Test
    void shouldDetectSentenceLevelGapEvenWhenSameParagraphContainsAnotherEvidenceCitation() {
        when(reportRepository.findByTaskId(5L)).thenReturn(Optional.of(
                Report.builder()
                        .taskId(5L)
                        .content("""
                                # 结论
                                Notion AI 在企业知识沉淀场景更适合大型团队落地；其权限审计能力更成熟 [证据：E005]。
                                """)
                        .build()
        ));
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(5L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(5L)
                        .competitorName("Notion AI")
                        .evidenceId("E005")
                        .title("Security Docs")
                        .url("https://docs.notion.so/security")
                        .build()
        ));
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(5L)).thenReturn(List.of(
                CompetitorKnowledge.builder()
                        .taskId(5L)
                        .competitorName("Notion AI")
                        .evidenceCoverage("""
                                {
                                  "summary": {"status":"TRACEABLE","hasValue":true},
                                  "positioning": {"status":"TRACEABLE","hasValue":true},
                                  "targetUsers": {"status":"TRACEABLE","hasValue":true},
                                  "coreFeatures": {"status":"TRACEABLE","hasValue":true},
                                  "pricing": {"status":"TRACEABLE","hasValue":true},
                                  "strengths": {"status":"TRACEABLE","hasValue":true},
                                  "weaknesses": {"status":"TRACEABLE","hasValue":true}
                                }
                                """)
                        .build()
        ));
        when(promptService.render(eq("reviewer"), any())).thenReturn("review-prompt");
        when(llmClient.chatForJson(any(), any(), eq("QualityReview"))).thenReturn("""
                {
                  "score": 92,
                  "passed": true,
                  "issues": [],
                  "summary": "整体较好"
                }
                """);
        when(llmClient.getModelName()).thenReturn("mock-model");
        when(llmClient.getLastTokenUsage()).thenReturn(new TokenUsage(10, 20, 30));

        AgentResult result = agent.execute(AgentContext.builder()
                .taskId(5L)
                .taskName("task")
                .currentNodeName("quality_check")
                .build());

        assertEquals("SUCCESS", result.getStatus().name());
        assertTrue(result.getOutputData().contains("企业知识沉淀场景更适合大型团队落地"));
        assertTrue(result.getOutputData().contains("unsupported_claim"));
    }

    @Test
    void shouldAppendSectionLevelMissingEvidenceIssueForKeySectionWithoutExplicitClaimKeyword() {
        when(reportRepository.findByTaskId(6L)).thenReturn(Optional.of(
                Report.builder()
                        .taskId(6L)
                        .content("""
                                # 建议
                                下一阶段应先补齐定价透明度，再决定是否进入大客户推进。
                                """)
                        .build()
        ));
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(6L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(6L)
                        .competitorName("Notion AI")
                        .evidenceId("E006")
                        .title("Pricing")
                        .url("https://www.notion.so/pricing")
                        .build()
        ));
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(6L)).thenReturn(List.of(
                CompetitorKnowledge.builder()
                        .taskId(6L)
                        .competitorName("Notion AI")
                        .evidenceCoverage("""
                                {
                                  "pricing": {"status":"MISSING_EVIDENCE","hasValue":true}
                                }
                                """)
                        .build()
        ));
        when(promptService.render(eq("reviewer"), any())).thenReturn("review-prompt");
        when(llmClient.chatForJson(any(), any(), eq("QualityReview"))).thenReturn("""
                {
                  "score": 80,
                  "passed": true,
                  "issues": [],
                  "summary": "整体较好"
                }
                """);
        when(llmClient.getModelName()).thenReturn("mock-model");
        when(llmClient.getLastTokenUsage()).thenReturn(new TokenUsage(10, 20, 30));

        AgentResult result = agent.execute(AgentContext.builder()
                .taskId(6L)
                .taskName("task")
                .currentNodeName("quality_check")
                .build());

        assertEquals("SUCCESS", result.getStatus().name());
        assertTrue(result.getOutputData().contains("missing_evidence"));
        assertTrue(result.getOutputData().contains("建议"));
        assertTrue(result.getOutputData().contains("补齐定价透明度"));
    }

    @Test
    void shouldProduceExplainableDimensionsAndDiagnosesForUnsupportedClaims() throws Exception {
        when(reportRepository.findByTaskId(7L)).thenReturn(Optional.of(
                Report.builder()
                        .taskId(7L)
                        .content("""
                                # 结论
                                Notion AI 更适合大型企业统一知识门户建设。

                                # 建议
                                建议优先推进安全审计场景，因为采购决策链路更短。
                                """)
                        .build()
        ));
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(7L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(7L)
                        .competitorName("Notion AI")
                        .evidenceId("E007")
                        .title("Security Docs")
                        .url("https://docs.notion.so/security")
                        .build()
        ));
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(7L)).thenReturn(List.of(
                CompetitorKnowledge.builder()
                        .taskId(7L)
                        .competitorName("Notion AI")
                        .evidenceCoverage("""
                                {
                                  "summary": {"status":"MISSING_EVIDENCE","hasValue":true},
                                  "positioning": {"status":"MISSING_EVIDENCE","hasValue":true},
                                  "coreFeatures": {"status":"TRACEABLE","hasValue":true},
                                  "pricing": {"status":"EMPTY","hasValue":false}
                                }
                                """)
                        .build()
        ));
        when(promptService.render(eq("reviewer"), any())).thenReturn("review-prompt");
        when(llmClient.chatForJson(any(), any(), eq("QualityReview"))).thenReturn("""
                {
                  "score": 96,
                  "passed": true,
                  "issues": [],
                  "summary": "模型初步认为报告质量较高"
                }
                """);
        when(llmClient.getModelName()).thenReturn("mock-model");
        when(llmClient.getLastTokenUsage()).thenReturn(new TokenUsage(10, 20, 30));

        AgentResult result = agent.execute(AgentContext.builder()
                .taskId(7L)
                .taskName("task")
                .currentNodeName("quality_check")
                .build());

        assertEquals("SUCCESS", result.getStatus().name());
        JsonNode output = objectMapper.readTree(result.getOutputData());
        assertTrue(output.has("dimensions"));
        assertTrue(output.has("diagnoses"));
        assertTrue(output.path("dimensions").toString().contains("EVIDENCE_TRACEABILITY"));
        assertTrue(output.path("diagnoses").toString().contains("sourceUrls"));
        assertTrue(output.path("diagnoses").toString().contains("repairSuggestion"));
        assertTrue(output.path("diagnoses").toString().contains("BLOCKER"));
        assertTrue(output.path("summary").asText().contains("证据"));
        assertTrue(output.path("passed").isBoolean());
    }

    @Test
    void shouldRequireHumanInterventionFromDiagnosisSeverityInsteadOfHardcodedScoreOnly() throws Exception {
        when(reportRepository.findByTaskId(8L)).thenReturn(Optional.of(
                Report.builder()
                        .taskId(8L)
                        .content("""
                                # 结论
                                Notion AI 在企业治理场景明显领先。
                                """)
                        .build()
        ));
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(8L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(8L)
                        .competitorName("Notion AI")
                        .evidenceId("E008")
                        .title("Product Overview")
                        .url("https://www.notion.so/product/ai")
                        .build()
        ));
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(8L)).thenReturn(List.of(
                CompetitorKnowledge.builder()
                        .taskId(8L)
                        .competitorName("Notion AI")
                        .evidenceCoverage("""
                                {
                                  "summary": {"status":"MISSING_EVIDENCE","hasValue":true},
                                  "positioning": {"status":"MISSING_EVIDENCE","hasValue":true},
                                  "targetUsers": {"status":"MISSING_EVIDENCE","hasValue":true},
                                  "coreFeatures": {"status":"MISSING_EVIDENCE","hasValue":true},
                                  "pricing": {"status":"MISSING_EVIDENCE","hasValue":true}
                                }
                                """)
                        .build()
        ));
        when(promptService.render(eq("reviewer"), any())).thenReturn("review-prompt");
        when(llmClient.chatForJson(any(), any(), eq("QualityReview"))).thenReturn("""
                {
                  "score": 93,
                  "passed": true,
                  "issues": [],
                  "summary": "模型初步认为可以发布"
                }
                """);
        when(llmClient.getModelName()).thenReturn("mock-model");
        when(llmClient.getLastTokenUsage()).thenReturn(new TokenUsage(10, 20, 30));

        AgentResult result = agent.execute(AgentContext.builder()
                .taskId(8L)
                .taskName("task")
                .currentNodeName("quality_check_final")
                .currentNodeConfig("{\"qualityPolicy\":\"final pass after revision\"}")
                .build());

        assertEquals("SUCCESS", result.getStatus().name());
        JsonNode output = objectMapper.readTree(result.getOutputData());
        assertTrue(output.path("score").asInt() > 20);
        assertTrue(output.path("requiresHumanIntervention").asBoolean());
        assertTrue(output.path("diagnoses").toString().contains("BLOCKER"));
    }
}
