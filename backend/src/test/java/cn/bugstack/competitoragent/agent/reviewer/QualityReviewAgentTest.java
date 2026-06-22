package cn.bugstack.competitoragent.agent.reviewer;

import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.context.AgentContextAssembler;
import cn.bugstack.competitoragent.context.TaskRagContextBundle;
import cn.bugstack.competitoragent.llm.LlmClient;
import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.llm.TokenUsage;
import cn.bugstack.competitoragent.memory.MemoryWritebackService;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QualityReviewAgentTest {

    private final AgentExecutionLogRepository logRepository = mock(AgentExecutionLogRepository.class);
    private final ReportRepository reportRepository = mock(ReportRepository.class);
    private final EvidenceSourceRepository evidenceRepository = mock(EvidenceSourceRepository.class);
    private final CompetitorKnowledgeRepository knowledgeRepository = mock(CompetitorKnowledgeRepository.class);
    private final LlmClient llmClient = mock(LlmClient.class);
    private final PromptTemplateService promptService = mock(PromptTemplateService.class);
    private final AgentContextAssembler agentContextAssembler = mock(AgentContextAssembler.class);
    private final MemoryWritebackService memoryWritebackService = mock(MemoryWritebackService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final QualityReviewAgent agent = new QualityReviewAgent(
            logRepository,
            reportRepository,
            evidenceRepository,
            knowledgeRepository,
            llmClient,
            promptService,
            agentContextAssembler,
            memoryWritebackService,
            objectMapper
    );

    @Test
    void shouldPassUnifiedTaskRagContextIntoReviewerPrompt() throws Exception {
        // Reviewer 也应读取统一上下文，避免在质检时忽略检索盲区和来源边界。
        when(agentContextAssembler.assemble(any(AgentContext.class))).thenAnswer(invocation -> {
            AgentContext originalContext = invocation.getArgument(0);
            return originalContext.toBuilder()
                    .taskRagContextBundle(TaskRagContextBundle.builder()
                            .query("Notion AI security evidence")
                            .retrievalSummary("命中安全文档与帮助中心摘要")
                            .gapSummary("企业合规审计条款仍不足")
                            .sourceUrls(List.of("https://docs.notion.so"))
                            .build())
                    .build();
        });
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
                        .evidenceCoverage("{}")
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
                .taskId(1L)
                .taskName("task")
                .currentNodeName("quality_check")
                .build());
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("SUCCESS", result.getStatus().name());
        assertTrue(output.path("taskRagContext").asText().contains("Notion AI security evidence"));
        assertTrue(output.path("taskRagContext").asText().contains("https://docs.notion.so"));
        verify(promptService).render(eq("reviewer"), argThat(variables ->
                variables.get("taskRagContext") != null
                        && variables.get("taskRagContext").contains("检索查询")
                        && variables.get("taskRagContext").contains("企业合规审计条款仍不足")
        ));
    }

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
    void shouldExposeExpandedCoverageStatusesInReviewerPromptSummary() {
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
                                  "summary": {"status":"LLM_REFUSED","hasValue":false},
                                  "positioning": {"status":"TRACEABLE","hasValue":true},
                                  "targetUsers": {"status":"STRUCTURED_BLOCK_DIRECT","hasValue":true},
                                  "pricing": {"status":"EVIDENCE_NOT_COVERING","hasValue":false}
                                }
                                """)
                        .build()
        ));
        when(promptService.render(eq("reviewer"), any())).thenReturn("prompt");
        when(llmClient.chatForJson(any(), any(), eq("QualityReview"))).thenReturn("""
                {
                  "score": 88,
                  "passed": true,
                  "issues": [],
                  "summary": "ok"
                }
                """);

        AgentResult result = agent.execute(AgentContext.builder()
                .taskId(1L)
                .taskName("task")
                .currentNodeName("quality_check")
                .build());

        assertEquals("SUCCESS", result.getStatus().name());
        verify(promptService).render(eq("reviewer"), argThat(variables ->
                variables.get("evidenceCoverageSummary").contains("模型拒答章节=产品概览")
                        && variables.get("evidenceCoverageSummary").contains("结构块直出章节=目标用户")
                        && variables.get("evidenceCoverageSummary").contains("证据不覆盖章节=定价策略")
                        && variables.get("evidenceCoverageSummary").contains("已可追溯章节=市场定位")
        ));
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
    void shouldRetryWhenReviewerReturnsBrokenJsonBeforeSuccessfulQualityReview() throws Exception {
        when(reportRepository.findByTaskId(12L)).thenReturn(Optional.of(
                Report.builder()
                        .taskId(12L)
                        .content("""
                                # 结论
                                Notion AI 在企业知识协作场景中更适合作为统一工作台。
                                """)
                        .build()
        ));
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(12L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(12L)
                        .competitorName("Notion AI")
                        .evidenceId("E012")
                        .title("Product")
                        .url("https://www.notion.so/product/ai")
                        .build()
        ));
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(12L)).thenReturn(List.of(
                CompetitorKnowledge.builder()
                        .taskId(12L)
                        .competitorName("Notion AI")
                        .evidenceCoverage("""
                                {
                                  "summary": {"status":"TRACEABLE","hasValue":true}
                                }
                                """)
                        .build()
        ));
        when(promptService.render(eq("reviewer"), any())).thenReturn("review-prompt");
        when(llmClient.chatForJson(any(), any(), eq("QualityReview")))
                .thenReturn("""
                        {
                          "score": 67,
                          "passed": false,
                          "issues": [
                            {
                              "type": "unsupported_claim",
                              "section": "结论",
                              "severity": "ERROR",
                              "suggestion": "请补充证据"
                            }
                        """)
                .thenReturn("""
                        {
                          "score": 84,
                          "passed": true,
                          "issues": [],
                          "summary": "终审通过"
                        }
                        """);
        when(llmClient.getModelName()).thenReturn("mock-model");
        when(llmClient.getLastTokenUsage()).thenReturn(new TokenUsage(10, 20, 30));

        AgentResult result = agent.execute(AgentContext.builder()
                .taskId(12L)
                .taskName("task")
                .currentNodeName("quality_check_final")
                .currentNodeConfig("""
                        {
                          "qualityPolicy": "final pass after revision"
                        }
                        """)
                .build());
        assertEquals("SUCCESS", result.getStatus().name());
        assertTrue(result.getOutputData().contains("\"reviewStage\":\"final\""), result.getOutputData());
        verify(llmClient, times(2)).chatForJson(any(), any(), eq("QualityReview"));
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
    void shouldAcceptExplicitConservativeDowngradeWhenAdviceClaimLacksCitation() throws Exception {
        when(reportRepository.findByTaskId(11L)).thenReturn(Optional.of(
                Report.builder()
                        .taskId(11L)
                        .content("""
                                # 建议
                                建议优先推进连接器生态（推测，当前公开资料未能验证，需补充证据后再进入正式路线图）。
                                """)
                        .build()
        ));
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(11L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(11L)
                        .competitorName("Notion AI")
                        .evidenceId("E011")
                        .title("Docs")
                        .url("https://docs.notion.so")
                        .build()
        ));
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(11L)).thenReturn(List.of(
                CompetitorKnowledge.builder()
                        .taskId(11L)
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
                  "summary": "建议已显式降级为待验证假设"
                }
                """);
        when(llmClient.getModelName()).thenReturn("mock-model");
        when(llmClient.getLastTokenUsage()).thenReturn(new TokenUsage(10, 20, 30));

        AgentResult result = agent.execute(AgentContext.builder()
                .taskId(11L)
                .taskName("task")
                .currentNodeName("quality_check_final")
                .currentNodeConfig("{\"qualityPolicy\":\"final pass after revision\"}")
                .build());
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("SUCCESS", result.getStatus().name());
        assertTrue(output.path("passed").asBoolean());
        assertFalse(output.path("diagnoses").toString().contains("missing_evidence"));
        assertFalse(output.path("diagnoses").toString().contains("unsupported_claim"));
    }

    @Test
    void shouldSuppressGenericLlmIssueWhenAdviceSectionAlreadyUsesExplicitDowngrade() throws Exception {
        when(reportRepository.findByTaskId(13L)).thenReturn(Optional.of(
                Report.builder()
                        .taskId(13L)
                        .content("""
                                # 5. 启示与行动建议
                                建议优先推进连接器生态（推测，当前公开资料未能验证，需补充证据后再进入正式路线图）。
                                """)
                        .build()
        ));
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(13L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(13L)
                        .competitorName("Notion AI")
                        .evidenceId("E013")
                        .title("Docs")
                        .url("https://docs.notion.so")
                        .build()
        ));
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(13L)).thenReturn(List.of(
                CompetitorKnowledge.builder()
                        .taskId(13L)
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
                  "score": 70,
                  "passed": false,
                  "issues": [
                    {
                      "type": "UNKNOWN",
                      "section": "5. 启示与行动建议",
                      "severity": "ERROR",
                      "suggestion": "请删除无法验证的建议"
                    }
                  ],
                  "summary": "建议段落仍需收紧"
                }
                """);
        when(llmClient.getModelName()).thenReturn("mock-model");
        when(llmClient.getLastTokenUsage()).thenReturn(new TokenUsage(10, 20, 30));

        AgentResult result = agent.execute(AgentContext.builder()
                .taskId(13L)
                .taskName("task")
                .currentNodeName("quality_check_final")
                .currentNodeConfig("{\"qualityPolicy\":\"final pass after revision\"}")
                .build());
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("SUCCESS", result.getStatus().name());
        assertFalse(output.path("diagnoses").toString().contains("\"section\":\"5. 启示与行动建议\""));
        assertFalse(output.path("diagnoses").toString().contains("\"type\":\"UNKNOWN\""));
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

    @Test
    void shouldEmitRevisionDirectivesAndSearchQualityFeedbackForSearchIssues() throws Exception {
        when(reportRepository.findByTaskId(9L)).thenReturn(Optional.of(
                Report.builder()
                        .taskId(9L)
                        .content("""
                                # 定价对比
                                当前公开资料无法支撑 Notion AI 的企业版阶梯定价判断。
                                """)
                        .build()
        ));
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(9L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(9L)
                        .competitorName("Notion AI")
                        .evidenceId("E009")
                        .title("Overview")
                        .url("https://www.notion.so/product/ai")
                        .build()
        ));
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(9L)).thenReturn(List.of(
                CompetitorKnowledge.builder()
                        .taskId(9L)
                        .competitorName("Notion AI")
                        .evidenceCoverage("""
                                {
                                  "pricing": {"status":"MISSING_EVIDENCE","hasValue":true},
                                  "summary": {"status":"TRACEABLE","hasValue":true}
                                }
                                """)
                        .build()
        ));
        when(promptService.render(eq("reviewer"), any())).thenReturn("review-prompt");
        when(llmClient.chatForJson(any(), any(), eq("QualityReview"))).thenReturn("""
                {
                  "score": 66,
                  "passed": false,
                  "issues": [
                    {
                      "type": "search_quality_gap",
                      "section": "定价策略",
                      "severity": "ERROR",
                      "suggestion": "当前搜索结果缺少官网定价页，请调整搜索查询并补采定价页。"
                    }
                  ],
                  "summary": "搜索质量无法支撑定价结论"
                }
                """);
        when(llmClient.getModelName()).thenReturn("mock-model");
        when(llmClient.getLastTokenUsage()).thenReturn(new TokenUsage(10, 20, 30));

        AgentResult result = agent.execute(AgentContext.builder()
                .taskId(9L)
                .taskName("task")
                .currentNodeName("quality_check_final")
                .currentNodeConfig("{\"qualityPolicy\":\"final pass after revision\"}")
                .build());
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("SUCCESS", result.getStatus().name());
        assertTrue(output.has("revisionDirectives"));
        assertTrue(output.path("diagnoses").toString().contains("SEARCH_QUALITY"));
        assertEquals("SEARCH_QUALITY", output.path("revisionPlan").path("directives").get(0).path("category").asText());
        assertEquals("collect_sources", output.path("revisionPlan").path("directives").get(0).path("targetNode").asText());
        assertTrue(output.path("revisionPlan").path("directives").get(0).path("searchFeedback").asText().contains("定价策略"));
        assertTrue(output.path("nextActions").toString().contains("SUPPLEMENT_EVIDENCE"));
    }
    @Test
    void shouldWriteVerifiedDomainKnowledgeBackOnFinalReviewPass() throws Exception {
        when(reportRepository.findByTaskId(10L)).thenReturn(Optional.of(
                Report.builder()
                        .taskId(10L)
                        .summary("Notion AI 在企业知识管理场景具备可追溯优势")
                        .content("""
                                # 结论
                                Notion AI 在企业知识管理场景更适合大团队落地[证据：E010]。
                                """)
                        .build()
        ));
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(10L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(10L)
                        .competitorName("Notion AI")
                        .evidenceId("E010")
                        .title("Security Docs")
                        .url("https://docs.notion.so/security")
                        .build(),
                EvidenceSource.builder()
                        .taskId(10L)
                        .competitorName("Notion AI")
                        .evidenceId("E011")
                        .title("Pricing")
                        .url("https://www.notion.so/pricing")
                        .build()
        ));
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(10L)).thenReturn(List.of(
                CompetitorKnowledge.builder()
                        .taskId(10L)
                        .competitorName("Notion AI")
                        .officialUrl("https://www.notion.so")
                        .summary("Notion AI 面向企业知识协作场景")
                        .positioning("企业知识管理")
                        .targetUsers("[\"企业团队\"]")
                        .coreFeatures("[\"AI 问答\",\"知识沉淀\"]")
                        .pricing("{\"public\":true}")
                        .strengths("[\"知识库沉淀\"]")
                        .weaknesses("[\"企业定价公开信息有限\"]")
                        .sources("[{\"evidenceId\":\"E010\"}]")
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
                  "score": 95,
                  "passed": true,
                  "issues": [],
                  "summary": "报告已达到可发布质量"
                }
                """);
        when(llmClient.getModelName()).thenReturn("mock-model");
        when(llmClient.getLastTokenUsage()).thenReturn(new TokenUsage(10, 20, 30));

        AgentResult result = agent.execute(AgentContext.builder()
                .taskId(10L)
                .taskName("task")
                .planVersionId(22L)
                .branchKey("analysis")
                .currentNodeName("quality_check_final")
                .currentNodeConfig("{\"qualityPolicy\":\"final pass after revision\"}")
                .build());

        assertEquals("SUCCESS", result.getStatus().name());
        JsonNode output = objectMapper.readTree(result.getOutputData());
        assertEquals("final", output.path("reviewStage").asText());
        assertTrue(output.path("passed").asBoolean(), result.getOutputData());
        verify(memoryWritebackService).writeback(argThat(request ->
                request != null
                        && Long.valueOf(10L).equals(request.getTaskId())
                        && "quality_check_final".equals(request.getNodeName())
                        && "VERIFIED_DOMAIN_KNOWLEDGE".equals(request.getWritebackCategory())
                        && "VERIFIED".equals(request.getQualitySignal())
                        && "Notion AI".equals(request.getCompetitorName())
                        && "https://www.notion.so".equals(request.getOfficialUrl())
                        && request.getSourceUrls() != null
                        && request.getSourceUrls().contains("https://docs.notion.so/security")
                        && request.getSourceUrls().contains("https://www.notion.so/pricing")
                        && request.getEvidenceCoverage() != null
                        && request.getEvidenceCoverage().contains("TRACEABLE")));
    }

    @Test
    void shouldDiagnoseMissingStructuredEvidenceInsteadOfOnlyUnsupportedClaim() throws Exception {
        when(reportRepository.findByTaskId(11L)).thenReturn(Optional.of(
                Report.builder()
                        .taskId(11L)
                        .content("""
                                # 定价对比
                                Notion AI 已经形成稳定的企业版价格分层，并且大客户采购门槛更低。
                                """)
                        .build()
        ));
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(11L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(11L)
                        .competitorName("Notion AI")
                        .evidenceId("E011")
                        .title("Pricing Page")
                        .url("https://www.notion.so/pricing")
                        .pageMetadata("""
                                {
                                  "qualitySignals": ["QUALITY_SIGNAL_FAILED", "NO_STRUCTURED_PRICE_BLOCK"],
                                  "structuredBlocks": [],
                                  "qualityScore": 0.21,
                                  "failureKind": "STRUCTURED_EXTRACTION_INSUFFICIENT"
                                }
                                """)
                        .build()
        ));
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(11L)).thenReturn(List.of(
                CompetitorKnowledge.builder()
                        .taskId(11L)
                        .competitorName("Notion AI")
                        .evidenceCoverage("""
                                {
                                  "pricing": {"status":"TRACEABLE","hasValue":true}
                                }
                                """)
                        .build()
        ));
        when(promptService.render(eq("reviewer"), any())).thenReturn("review-prompt");
        when(llmClient.chatForJson(any(), any(), eq("QualityReview"))).thenReturn("""
                {
                  "score": 91,
                  "passed": true,
                  "issues": [],
                  "summary": "模型初步认为文本质量尚可"
                }
                """);
        when(llmClient.getModelName()).thenReturn("mock-model");
        when(llmClient.getLastTokenUsage()).thenReturn(new TokenUsage(10, 20, 30));

        AgentResult result = agent.execute(AgentContext.builder()
                .taskId(11L)
                .taskName("task")
                .currentNodeName("quality_check")
                .build());
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("SUCCESS", result.getStatus().name());
        assertTrue(output.path("diagnoses").toString().contains("missing_structured_evidence"));
        assertTrue(output.path("diagnoses").toString().contains("structuredBlocks"));
        assertTrue(output.path("diagnoses").toString().contains("qualitySignals"));
        assertTrue(output.path("diagnoses").toString().contains("STRUCTURED_EXTRACTION_INSUFFICIENT"));
    }

    @Test
    void shouldNotDiagnoseStructuredEvidenceGapForReadableHighScoreEvidenceWithoutFailureSignals() throws Exception {
        when(reportRepository.findByTaskId(12L)).thenReturn(Optional.of(
                Report.builder()
                        .taskId(12L)
                        .content("""
                                # 功能对比
                                Notion AI 已提供知识问答与工作流能力 [证据：E012]。
                                """)
                        .build()
        ));
        when(evidenceRepository.findByTaskIdOrderByEvidenceIdAsc(12L)).thenReturn(List.of(
                EvidenceSource.builder()
                        .taskId(12L)
                        .competitorName("Notion AI")
                        .evidenceId("E012")
                        .title("Docs Page")
                        .url("https://www.notion.so/docs")
                        .pageMetadata("""
                                {
                                  "qualitySignals": ["MAIN_CONTENT_READY", "NO_STRUCTURED_BLOCKS"],
                                  "structuredBlocks": [],
                                  "qualityScore": 0.92
                                }
                                """)
                        .build()
        ));
        when(knowledgeRepository.findByTaskIdOrderByIdAsc(12L)).thenReturn(List.of(
                CompetitorKnowledge.builder()
                        .taskId(12L)
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
                  "score": 93,
                  "passed": true,
                  "issues": [],
                  "summary": "当前文本质量可用"
                }
                """);
        when(llmClient.getModelName()).thenReturn("mock-model");
        when(llmClient.getLastTokenUsage()).thenReturn(new TokenUsage(10, 20, 30));

        AgentResult result = agent.execute(AgentContext.builder()
                .taskId(12L)
                .taskName("task")
                .currentNodeName("quality_check")
                .build());
        JsonNode output = objectMapper.readTree(result.getOutputData());

        assertEquals("SUCCESS", result.getStatus().name());
        assertFalse(output.path("diagnoses").toString().contains("missing_structured_evidence"));
    }
}
