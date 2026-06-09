package cn.bugstack.competitoragent.integration;

import cn.bugstack.competitoragent.CompetitorAgentApplication;
import cn.bugstack.competitoragent.agent.AgentContext;
import cn.bugstack.competitoragent.agent.AgentResult;
import cn.bugstack.competitoragent.agent.analyzer.CompetitorAnalysisAgent;
import cn.bugstack.competitoragent.agent.collector.CollectorAgent;
import cn.bugstack.competitoragent.agent.extractor.SchemaExtractorAgent;
import cn.bugstack.competitoragent.agent.reviewer.QualityReviewAgent;
import cn.bugstack.competitoragent.agent.writer.ReportWriterAgent;
import cn.bugstack.competitoragent.common.ApiResponse;
import cn.bugstack.competitoragent.config.PlaywrightBrowserManager;
import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.model.dto.CreateTaskRequest;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.CompetitorKnowledge;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.model.entity.Report;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.repository.ReportRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceDiscoveryService;
import cn.bugstack.competitoragent.source.SourcePlan;
import cn.bugstack.competitoragent.task.TaskExecutionLockService;
import cn.bugstack.competitoragent.task.TaskSnapshotCacheService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

/**
 * Phase 1 主链路集成回归测试。
 * 该用例覆盖“创建任务 -> DAG 调度执行 -> 初审阻断自动挂起 -> 人工恢复后继续执行 -> 产出带诊断信息的报告”
 * 这条当前阶段最长的一跳业务主线，用来固化跨模块、跨接口的最小闭环基线。
 */
@SpringBootTest(
        classes = CompetitorAgentApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("test")
@Import(Phase1WorkflowIntegrationTest.SyncAsyncTestConfig.class)
class Phase1WorkflowIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AnalysisTaskRepository taskRepository;

    @Autowired
    private TaskNodeRepository nodeRepository;

    @Autowired
    private ReportRepository reportRepository;

    @Autowired
    private EvidenceSourceRepository evidenceSourceRepository;

    @Autowired
    private CompetitorKnowledgeRepository competitorKnowledgeRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SourceDiscoveryService sourceDiscoveryService;

    @MockBean
    private Playwright playwright;

    @MockBean
    private PlaywrightBrowserManager playwrightBrowserManager;

    @MockBean
    private PromptTemplateService promptTemplateService;

    @MockBean
    private TaskSnapshotCacheService taskSnapshotCacheService;

    @MockBean
    private TaskExecutionLockService taskExecutionLockService;

    @SpyBean
    private CollectorAgent collectorAgent;

    @SpyBean
    private SchemaExtractorAgent extractorAgent;

    @SpyBean
    private CompetitorAnalysisAgent analyzerAgent;

    @SpyBean
    private ReportWriterAgent writerAgent;

    @SpyBean
    private QualityReviewAgent reviewerAgent;

    private int writerExecutionCount;
    private int reviewerExecutionCount;

    @BeforeEach
    void setUp() {
        writerExecutionCount = 0;
        reviewerExecutionCount = 0;
        seedSourceDiscovery();
        configureCollectorAgent();
        configureExtractorAgent();
        configureAnalyzerAgent();
        configureWriterAgent();
        configureReviewerAgent();
        configureRuntimeInfrastructure();
        when(promptTemplateService.buildSearchQueries(any(), any(), any()))
                .thenAnswer(invocation -> List.of(
                        invocation.getArgument(0) + " documentation",
                        invocation.getArgument(0) + " pricing"
                ));
    }

    @Test
    void shouldExecutePhase1WorkflowThroughPauseResumeAndProduceDiagnosedReport() throws Exception {
        CreateTaskRequest request = new CreateTaskRequest();
        request.setTaskName("Phase 1 封印之战");
        request.setSubjectProduct("企业级 AI 竞品分析平台");
        request.setCompetitorNames(List.of("Notion AI"));
        request.setCompetitorUrls(List.of("https://www.notion.so/product/ai"));
        request.setAnalysisDimensions(List.of("产品功能", "价格策略", "市场定位"));
        request.setSourceScope(List.of("官网", "产品文档", "定价页"));

        ResponseEntity<ApiResponse> createResponse = restTemplate.postForEntity(taskUrl("/create"), request, ApiResponse.class);
        assertEquals(200, createResponse.getStatusCode().value());
        Integer taskIdRaw = (Integer) ((Map<?, ?>) createResponse.getBody().getData()).get("id");
        Long taskId = taskIdRaw.longValue();

        restTemplate.postForEntity(taskUrl("/" + taskId + "/execute"), null, ApiResponse.class);

        Map<?, ?> stoppedTaskBody = waitForTaskDetailStatus(taskId, AnalysisTaskStatus.STOPPED);
        assertTrue(String.valueOf(stoppedTaskBody.get("errorMessage")).contains("人工"));

        TaskNode reviewNode = nodeRepository.findByTaskIdAndNodeName(taskId, "quality_check").orElseThrow();
        TaskNode rewriteNodeBeforeResume = nodeRepository.findByTaskIdAndNodeName(taskId, "rewrite_report").orElseThrow();
        assertEquals(TaskNodeStatus.SUCCESS, reviewNode.getStatus());
        assertNotEquals(TaskNodeStatus.SUCCESS, rewriteNodeBeforeResume.getStatus());

        assertEquals("STOPPED", stoppedTaskBody.get("status"));
        assertEquals(Boolean.TRUE, stoppedTaskBody.get("canResume"));

        restTemplate.postForEntity(taskUrl("/" + taskId + "/resume"), null, ApiResponse.class);

        Map<?, ?> successTaskBody = waitForTaskDetailStatus(taskId, AnalysisTaskStatus.SUCCESS);
        TaskNode rewriteNode = nodeRepository.findByTaskIdAndNodeName(taskId, "rewrite_report").orElseThrow();
        TaskNode finalReviewNode = nodeRepository.findByTaskIdAndNodeName(taskId, "quality_check_final").orElseThrow();
        assertEquals(TaskNodeStatus.SUCCESS, rewriteNode.getStatus());
        assertEquals(TaskNodeStatus.SUCCESS, finalReviewNode.getStatus());

        Optional<Report> savedReport = reportRepository.findByTaskId(taskId);
        assertTrue(savedReport.isPresent());
        assertFalse(savedReport.get().isQualityPassed());

        List<EvidenceSource> evidences = evidenceSourceRepository.findByTaskIdOrderByEvidenceIdAsc(taskId);
        if (!evidences.isEmpty()) {
            assertTrue(evidences.get(0).getUrl().contains("notion.so"));
        }

        List<CompetitorKnowledge> knowledges = competitorKnowledgeRepository.findByTaskIdOrderByIdAsc(taskId);
        assertEquals(1, knowledges.size());
        assertTrue(knowledges.get(0).getSourceUrls().contains("https://www.notion.so/product/ai"));

        ResponseEntity<ApiResponse> reportEntity = restTemplate.getForEntity(reportUrl("/" + taskId), ApiResponse.class);
        assertEquals(200, reportEntity.getStatusCode().value());
        Map<?, ?> reportPayload = (Map<?, ?>) reportEntity.getBody().getData();
        Map<?, ?> reportDiagnosis = (Map<?, ?>) reportPayload.get("reportDiagnosis");
        assertTrue(((Number) reportDiagnosis.get("diagnosisCount")).intValue() >= 1);
        assertTrue(((Number) reportDiagnosis.get("blockerCount")).intValue() >= 1);
        assertTrue(((List<?>) reportDiagnosis.get("contentEvidences")).size() >= 1);
        assertTrue(((List<?>) reportDiagnosis.get("sections")).size() >= 1);

        Map<?, ?> initialReview = (Map<?, ?>) reportPayload.get("initialReview");
        assertEquals(Boolean.TRUE, initialReview.get("requiresHumanIntervention"));

        assertEquals("SUCCESS", successTaskBody.get("status"));
        assertEquals(Boolean.TRUE, successTaskBody.get("canViewReport"));
    }

    private void seedSourceDiscovery() {
        SourceCandidate candidate = SourceCandidate.builder()
                .url("https://www.notion.so/product/ai")
                .title("Notion AI")
                .sourceType("DOCS")
                .discoveryMethod("SEARCH")
                .domain("www.notion.so")
                .verified(true)
                .build();
        when(sourceDiscoveryService.discover(any(), any(), any()))
                .thenReturn(List.of(SourcePlan.builder()
                        .sourceType("DOCS")
                        .urls(List.of("https://www.notion.so/product/ai"))
                        .notes("集成测试固定候选来源")
                        .candidates(List.of(candidate))
                        .build()));
    }

    private void configureCollectorAgent() {
        doAnswer(invocation -> {
            AgentContext context = invocation.getArgument(0);
            Map<String, Object> config = objectMapper.readValue(
                    context.getCurrentNodeConfig(),
                    new TypeReference<Map<String, Object>>() {
                    }
            );
            String competitorName = String.valueOf(config.get("competitorName"));
            List<String> competitorUrls = (List<String>) config.get("competitorUrls");
            String url = competitorUrls == null || competitorUrls.isEmpty()
                    ? "https://www.notion.so/product/ai"
                    : competitorUrls.get(0);

            evidenceSourceRepository.save(EvidenceSource.builder()
                    .taskId(context.getTaskId())
                    .competitorName(competitorName)
                    .evidenceId("T%04d-COLLECT-001".formatted(context.getTaskId()))
                    .title("Notion AI Product")
                    .url(url)
                    .contentSnippet("Notion AI helps teams write, search and summarize.")
                    .fullContent("Notion AI helps teams write, search and summarize with workspace context.")
                    .pageMetadata("{\"verified\":true,\"selectionStage\":\"SELECTED\",\"selectionReason\":\"SEARCH_RESULT\"}")
                    .sourceType("DOCS")
                    .discoveryMethod("SEARCH")
                    .sourceDomain("www.notion.so")
                    .discoveryReason("命中文档")
                    .publishedAt("2026-05-01")
                    .sourceScore(0.93)
                    .build());

            String output = """
                    {
                      "competitor": "%s",
                      "sourceType": "DOCS",
                      "sourceUrls": ["%s"],
                      "searchQueries": ["%s documentation", "%s pricing"],
                      "selectedTargets": [{"url":"%s","title":"Notion AI Product"}],
                      "sourceCandidates": [{"url":"%s","title":"Notion AI Product","sourceType":"DOCS","discoveryMethod":"SEARCH","domain":"www.notion.so","verified":true}],
                      "successCollected": 1,
                      "totalCollected": 1,
                      "discoveryNotes": "集成测试固定候选来源",
                      "searchProgress": {"status":"SUCCESS"},
                      "searchExecutionTrace": {
                        "supplementMethod": "PLANNED",
                        "resumedFromCheckpoint": false,
                        "degraded": false,
                        "providerFallbackUsed": false,
                        "plannedCandidateCount": 1,
                        "verifiedCandidateCount": 1,
                        "supplementedCandidateCount": 0,
                        "selectedCandidateCount": 1,
                        "selectedUrls": ["%s"]
                      }
                    }
                    """.formatted(competitorName, url, competitorName, competitorName, url, url, url);
            return AgentResult.success(output, "采集完成");
        }).when(collectorAgent).execute(any(AgentContext.class));
    }

    private void configureExtractorAgent() {
        doAnswer(invocation -> {
            AgentContext context = invocation.getArgument(0);
            competitorKnowledgeRepository.save(CompetitorKnowledge.builder()
                    .taskId(context.getTaskId())
                    .competitorName("Notion AI")
                    .officialUrl("https://www.notion.so/product/ai")
                    .summary("面向团队协作的 AI 工作空间能力。")
                    .positioning("AI 协同办公")
                    .targetUsers("[\"企业团队\"]")
                    .coreFeatures("""
                            [{"name":"AI 写作","description":"自动生成与总结","evidenceIds":["T%1$d-COLLECT-001"],"sourceUrls":["https://www.notion.so/product/ai"]}]
                            """.formatted(context.getTaskId()))
                    .pricing("""
                            {"model":"订阅制","plans":["免费版","企业版"],"evidenceIds":["T%1$d-COLLECT-001"],"sourceUrls":["https://www.notion.so/product/ai"]}
                            """.formatted(context.getTaskId()))
                    .strengths("""
                            [{"point":"工作流一体化","evidenceIds":["T%1$d-COLLECT-001"],"sourceUrls":["https://www.notion.so/product/ai"]}]
                            """.formatted(context.getTaskId()))
                    .weaknesses("""
                            [{"point":"高阶能力学习成本偏高","evidenceIds":["T%1$d-COLLECT-001"],"sourceUrls":["https://www.notion.so/product/ai"]}]
                            """.formatted(context.getTaskId()))
                    .sources("""
                            [{"evidenceId":"T%1$d-COLLECT-001","title":"Notion AI Product","url":"https://www.notion.so/product/ai"}]
                            """.formatted(context.getTaskId()))
                    .sourceUrls("[\"https://www.notion.so/product/ai\"]")
                    .evidenceCoverage("""
                            {
                              "summary":{"status":"TRACEABLE","hasValue":true},
                              "positioning":{"status":"TRACEABLE","hasValue":true},
                              "targetUsers":{"status":"TRACEABLE","hasValue":true},
                              "coreFeatures":{"status":"TRACEABLE","hasValue":true},
                              "pricing":{"status":"MISSING_EVIDENCE","hasValue":true},
                              "strengths":{"status":"TRACEABLE","hasValue":true},
                              "weaknesses":{"status":"EMPTY","hasValue":false}
                            }
                            """)
                    .build());
            return AgentResult.success("""
                    {
                      "competitors": [
                        {
                          "competitorName": "Notion AI",
                          "sourceUrls": ["https://www.notion.so/product/ai"]
                        }
                      ]
                    }
                    """, "抽取完成");
        }).when(extractorAgent).execute(any(AgentContext.class));
    }

    private void configureAnalyzerAgent() {
        doAnswer(invocation -> AgentResult.success("""
                {
                  "overview": "Notion AI 在团队协同与 AI 写作整合方面具备竞争力。",
                  "featureComparison": "具备 AI 写作、搜索与总结闭环。",
                  "pricingComparison": "定价证据仍需补强。",
                  "recommendations": ["补齐定价与企业交付证据后再下结论"],
                  "sourceUrls": ["https://www.notion.so/product/ai"]
                }
                """, "分析完成")).when(analyzerAgent).execute(any(AgentContext.class));
    }

    private void configureWriterAgent() {
        doAnswer(invocation -> {
            AgentContext context = invocation.getArgument(0);
            writerExecutionCount++;
            boolean revision = "rewrite_report".equals(context.getCurrentNodeName());
            Report report = reportRepository.findByTaskId(context.getTaskId()).orElseGet(Report::new);
            report.setTaskId(context.getTaskId());
            report.setTitle("Phase 1 集成回归报告");
            report.setContent(revision
                    ? "# Report\n\n已根据初审意见补充结论保守措辞。"
                    : "# Report\n\n初稿存在关键结论证据不足问题。");
            report.setSummary(revision ? "修订版报告已生成" : "初稿报告已生成");
            report.setQualityScore(revision ? 86 : 72);
            report.setQualityPassed(false);
            report.setQualityIssues("""
                    [
                      {
                        "type":"missing_evidence",
                        "section":"结论",
                        "severity":"ERROR",
                        "level":"BLOCKER",
                        "dimensionCode":"EVIDENCE_TRACEABILITY",
                        "dimensionName":"证据可追溯性",
                        "evidenceBasis":"关键结论缺少可回指的证据编号。",
                        "sourceUrls":["https://www.notion.so/product/ai"],
                        "suggestion":"补充证据编号或降低结论强度。"
                      }
                    ]
                    """);
            report.setEvidenceCount(1);
            reportRepository.save(report);

            String output = """
                    {
                      "sourceUrls": ["https://www.notion.so/product/ai"],
                      "evidenceFragments": [
                        {
                          "stage": "%s",
                          "competitorName": "Notion AI",
                          "fieldName": "report",
                          "evidenceId": "T%04d-COLLECT-001",
                          "sourceUrl": "https://www.notion.so/product/ai",
                          "title": "Notion AI Product",
                          "snippet": "Notion AI helps teams write, search and summarize.",
                          "issueFlags": ["MISSING_BASIS"]
                        }
                      ]
                    }
                    """.formatted(revision ? "REWRITE" : "WRITE", context.getTaskId());
            return AgentResult.success(output, revision ? "改写完成" : "初稿完成");
        }).when(writerAgent).execute(any(AgentContext.class));
    }

    private void configureReviewerAgent() {
        doAnswer(invocation -> {
            reviewerExecutionCount++;
            if (reviewerExecutionCount == 1) {
                return AgentResult.success(initialReviewOutput(), "初审阻断");
            }
            return AgentResult.success(finalReviewOutput(), "终审通过");
        }).when(reviewerAgent).execute(any(AgentContext.class));
    }

    /**
     * Phase 1 回归测试只验证业务主链路，不要求接入真实 Redis。
     * 因此这里把运行态快照与分布式锁统一替换成可控 mock，避免基础设施把旧闭环测例卡死。
     */
    private void configureRuntimeInfrastructure() {
        when(taskSnapshotCacheService.getTaskSnapshot(anyLong())).thenReturn(Optional.empty());
        when(taskExecutionLockService.tryAcquireTaskExecutionLock(anyLong(), anyString(), any()))
                .thenReturn(true);
        when(taskExecutionLockService.releaseTaskExecutionLock(anyLong(), anyString()))
                .thenReturn(true);
        when(taskExecutionLockService.tryAcquireNodeExecutionLock(anyLong(), anyString(), anyString(), any()))
                .thenReturn(true);
        when(taskExecutionLockService.releaseNodeExecutionLock(anyLong(), anyString(), anyString()))
                .thenReturn(true);
    }

    private String initialReviewOutput() {
        return """
                {
                  "score": 18,
                  "passed": false,
                  "requiresHumanIntervention": true,
                  "autoRewriteAllowed": false,
                  "summary": "证据链路缺口过大，系统停止自动改写等待人工恢复。",
                  "dimensions": [
                    {
                      "code":"EVIDENCE_TRACEABILITY",
                      "name":"证据可追溯性",
                      "description":"关键结论必须能回指到稳定来源",
                      "evaluationStandard":"关键结论必须携带可追溯 evidenceId 或来源链接",
                      "score":18,
                      "maxScore":100,
                      "status":"CRITICAL"
                    }
                  ],
                  "diagnoses": [
                    {
                      "dimensionCode":"EVIDENCE_TRACEABILITY",
                      "dimensionName":"证据可追溯性",
                      "type":"missing_evidence",
                      "section":"结论",
                      "severity":"ERROR",
                      "level":"BLOCKER",
                      "title":"关键结论缺少来源引用",
                      "detail":"结论章节中存在无法回指证据的判断。",
                      "evidenceBasis":"关键结论缺少可回指的证据编号。",
                      "sourceUrls":["https://www.notion.so/product/ai"],
                      "repairSuggestion":"补充证据编号或降低结论强度。"
                    }
                  ],
                  "issues": [
                    {
                      "type":"missing_evidence",
                      "section":"结论",
                      "severity":"ERROR",
                      "level":"BLOCKER",
                      "dimensionCode":"EVIDENCE_TRACEABILITY",
                      "dimensionName":"证据可追溯性",
                      "evidenceBasis":"关键结论缺少可回指的证据编号。",
                      "sourceUrls":["https://www.notion.so/product/ai"],
                      "suggestion":"补充证据编号或降低结论强度。"
                    }
                  ],
                  "nextActions": [
                    {
                      "title":"恢复工作流并执行改写",
                      "description":"人工确认后，恢复任务并允许改写节点继续执行。",
                      "actionType":"RERUN_NODE",
                      "targetNode":"rewrite_report",
                      "priority":"HIGH"
                    }
                  ]
                }
                """;
    }

    private String finalReviewOutput() {
        return """
                {
                  "score": 86,
                  "passed": true,
                  "requiresHumanIntervention": false,
                  "autoRewriteAllowed": true,
                  "summary": "改写后报告已达到 Phase 1 最小回归基线。",
                  "dimensions": [
                    {
                      "code":"EVIDENCE_TRACEABILITY",
                      "name":"证据可追溯性",
                      "description":"关键结论必须能回指到稳定来源",
                      "evaluationStandard":"关键结论必须携带可追溯 evidenceId 或来源链接",
                      "score":86,
                      "maxScore":100,
                      "status":"PASS"
                    }
                  ],
                  "diagnoses": [
                    {
                      "dimensionCode":"EVIDENCE_TRACEABILITY",
                      "dimensionName":"证据可追溯性",
                      "type":"missing_evidence",
                      "section":"结论",
                      "severity":"ERROR",
                      "level":"BLOCKER",
                      "title":"关键结论缺少来源引用",
                      "detail":"历史问题已通过改写阶段缓解。",
                      "evidenceBasis":"关键结论缺少可回指的证据编号。",
                      "sourceUrls":["https://www.notion.so/product/ai"],
                      "repairSuggestion":"继续保持关键结论引用证据编号。"
                    }
                  ],
                  "issues": [],
                  "nextActions": []
                }
                """;
    }

    private AnalysisTask waitForTaskStatus(Long taskId, AnalysisTaskStatus expectedStatus) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(10).toMillis();
        while (System.currentTimeMillis() < deadline) {
            AnalysisTask task = taskRepository.findById(taskId).orElseThrow();
            if (task.getStatus() == expectedStatus) {
                return task;
            }
            Thread.sleep(100);
        }
        AnalysisTask latest = taskRepository.findById(taskId).orElseThrow();
        throw new AssertionError("任务未在预期时间内进入状态 " + expectedStatus + "，当前状态为 " + latest.getStatus());
    }

    private Map<?, ?> waitForTaskDetailStatus(Long taskId, AnalysisTaskStatus expectedStatus) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(10).toMillis();
        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<ApiResponse> detailResponse = restTemplate.getForEntity(taskUrl("/" + taskId), ApiResponse.class);
            Map<?, ?> taskBody = (Map<?, ?>) detailResponse.getBody().getData();
            if (expectedStatus.name().equals(taskBody.get("status"))) {
                return taskBody;
            }
            Thread.sleep(100);
        }
        ResponseEntity<ApiResponse> detailResponse = restTemplate.getForEntity(taskUrl("/" + taskId), ApiResponse.class);
        Map<?, ?> latestTaskBody = (Map<?, ?>) detailResponse.getBody().getData();
        throw new AssertionError("任务详情未在预期时间内进入状态 " + expectedStatus + "，当前状态为 " + latestTaskBody.get("status"));
    }

    private String taskUrl(String path) {
        return "http://localhost:" + port + "/api/task" + path;
    }

    private String reportUrl(String path) {
        return "http://localhost:" + port + "/api/report" + path;
    }

    @TestConfiguration
    @EnableAsync
    static class SyncAsyncTestConfig implements AsyncConfigurer {

        @Bean
        AsyncTaskExecutor taskExecutor() {
            Executor executor = Runnable::run;
            return new ConcurrentTaskExecutor(executor);
        }

        @Override
        public Executor getAsyncExecutor() {
            return Runnable::run;
        }
    }
}
