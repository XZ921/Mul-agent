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
import cn.bugstack.competitoragent.config.RocketMqProperties;
import cn.bugstack.competitoragent.config.PlaywrightBrowserManager;
import cn.bugstack.competitoragent.event.TaskEventReplayService;
import cn.bugstack.competitoragent.event.TaskEventType;
import cn.bugstack.competitoragent.event.TaskSseHub;
import cn.bugstack.competitoragent.event.TaskStreamEvent;
import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.model.dto.CreateTaskRequest;
import cn.bugstack.competitoragent.model.entity.CompetitorKnowledge;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.model.entity.Report;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskWorkflowEvent;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.repository.ReportRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.repository.TaskWorkflowEventRepository;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceDiscoveryService;
import cn.bugstack.competitoragent.source.SourcePlan;
import cn.bugstack.competitoragent.task.AnalysisTaskRunner;
import cn.bugstack.competitoragent.task.TaskExecutionLockService;
import cn.bugstack.competitoragent.task.TaskProgressSnapshot;
import cn.bugstack.competitoragent.task.TaskSnapshotCacheService;
import cn.bugstack.competitoragent.workflow.event.WorkflowEvent;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventConsumer;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventOutboxService;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

/**
 * Phase 2 主闭环集成回归测试。
 * 该用例把“创建任务 -> 多源补源 -> 实时观察 -> 查看诊断报告 -> 异常后恢复观察”串成一条真实链路，
 * 用于确认本阶段已经具备持续回归所需的最小恢复语义和观察基线。
 */
@SpringBootTest(
        classes = CompetitorAgentApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                /**
                 * Phase 2 回归关注的是“最小恢复语义 + 实时观察”链路，
                 * 并不要求在测试环境里真的初始化 RocketMQ Producer。
                 * 显式关闭 Starter 自动装配，可以避免测试因为 name-server 缺失而在启动阶段提前失败。
                 */
                "spring.autoconfigure.exclude=org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration"
        }
)
@ActiveProfiles("test")
@Import(Phase2WorkflowIntegrationTest.SyncAsyncTestConfig.class)
class Phase2WorkflowIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

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

    @Autowired
    private TaskEventReplayService taskEventReplayService;

    @Autowired
    private TaskSseHub taskSseHub;

    @Autowired
    private AnalysisTaskRunner analysisTaskRunner;

    @Autowired
    private TaskWorkflowEventRepository taskWorkflowEventRepository;

    @Autowired
    private WorkflowEventOutboxService workflowEventOutboxService;

    @SpyBean
    private RocketMqProperties rocketMqProperties;

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

    private final Map<Long, TaskProgressSnapshot> snapshotStore = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, String>> runtimeOutputStore = new ConcurrentHashMap<>();
    private int reviewerExecutionCount;

    @BeforeEach
    void setUp() {
        snapshotStore.clear();
        runtimeOutputStore.clear();
        reviewerExecutionCount = 0;

        /**
         * Phase 2 回归当前验证的是“事件驱动链路已经接上，并且恢复语义仍然成立”。
         * 测试 profile 会关闭真实 RocketMQ，因此这里显式放行执行入口校验，
         * 避免 execute/resume 在写入 TASK_EXECUTION_REQUESTED 之前就被基础设施开关提前拦下。
         */
        doNothing().when(rocketMqProperties).validateForExecution();

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
                        invocation.getArgument(0) + " pricing",
                        invocation.getArgument(0) + " official website"
                ));
    }

    @Test
    void shouldCoverPhase2WorkflowThroughRealtimeObservationDiagnosisAndReplayRecovery() throws Exception {
        CreateTaskRequest request = new CreateTaskRequest();
        request.setTaskName("Phase 2 完整闭环");
        request.setSubjectProduct("企业级 AI 竞品分析平台");
        request.setCompetitorNames(List.of("Notion AI"));
        request.setCompetitorUrls(List.of("https://www.notion.so/product/ai"));
        request.setAnalysisDimensions(List.of("产品功能", "价格策略", "市场定位"));
        request.setSourceScope(List.of("官网", "产品文档", "定价页面"));

        Long taskId = createTask(request);
        restTemplate.postForEntity(taskUrl("/" + taskId + "/execute"), null, ApiResponse.class);
        consumeLatestTaskExecutionRequested(taskId);

        Map<?, ?> stoppedTaskBody = waitForTaskDetailStatus(taskId, AnalysisTaskStatus.STOPPED);
        assertEquals("STOPPED", stoppedTaskBody.get("status"));
        assertTrue(String.valueOf(stoppedTaskBody.get("errorMessage")).contains("人工"));

        List<TaskStreamEvent> recentEvents = taskSseHub.getRecentEvents(taskId);
        assertFalse(recentEvents.isEmpty());
        List<TaskEventType> recentEventTypes = recentEvents.stream()
                .map(TaskStreamEvent::getEventType)
                .toList();
        List<String> recentEventDetails = recentEvents.stream()
                .map(event -> event.getEventType() + ":" + (event.getNodeName() == null ? "task" : event.getNodeName()))
                .toList();
        assertTrue(recentEvents.stream().anyMatch(event -> event.getEventType() == TaskEventType.TASK_SNAPSHOT),
                () -> "最近事件类型=" + recentEventTypes + "，详情=" + recentEventDetails);
        assertTrue(recentEvents.stream().anyMatch(event -> event.getEventType() == TaskEventType.SEARCH_PROGRESS),
                () -> "最近事件类型=" + recentEventTypes + "，详情=" + recentEventDetails);
        assertTrue(recentEvents.stream().anyMatch(event -> event.getEventType() == TaskEventType.DIAGNOSIS),
                () -> "最近事件类型=" + recentEventTypes + "，详情=" + recentEventDetails);
        assertTrue(recentEvents.stream().anyMatch(event -> event.getEventType() == TaskEventType.AGENT_OUTPUT),
                () -> "最近事件类型=" + recentEventTypes + "，详情=" + recentEventDetails);

        TaskNode collectorNode = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId).stream()
                .filter(node -> node.getNodeName().startsWith("collect_sources_"))
                .findFirst()
                .orElseThrow();
        JsonNode collectorOutput = objectMapper.readTree(collectorNode.getOutputData());
        assertTrue(collectorOutput.hasNonNull("searchAudit"));
        assertEquals("SELECT_TARGETS",
                collectorOutput.path("searchAudit").path("executionTrace").path("recoveryCheckpoint").asText());
        assertTrue(collectorOutput.path("searchAudit").path("sourceUrls").isArray());

        TaskStreamEvent searchEvent = recentEvents.stream()
                .filter(event -> event.getEventType() == TaskEventType.SEARCH_PROGRESS)
                .findFirst()
                .orElseThrow();
        assertEquals("SEARCH_PROGRESS_V1", searchEvent.getPayload().get("contractType"));
        assertTrue(searchEvent.getPayload().containsKey("searchAudit"));
        assertTrue(searchEvent.getPayload().containsKey("selectedTargets"));
        assertTrue(searchEvent.getPayload().containsKey("sourceUrls"));

        String replayCursor = recentEvents.stream()
                .filter(event -> event.getCursor() != null && event.getEventType() != TaskEventType.DIAGNOSIS)
                .findFirst()
                .map(TaskStreamEvent::getCursor)
                .orElseThrow();

        TaskEventReplayService.TaskReplayFrame stoppedReplayFrame =
                taskEventReplayService.planReplay(taskId, replayCursor);
        assertNotNull(stoppedReplayFrame.getSnapshotEvent());
        assertEquals("STOPPED", stoppedReplayFrame.getSnapshotEvent().getPayload().get("status"));
        assertTrue(stoppedReplayFrame.getReplayEvents().stream()
                .anyMatch(event -> event.getEventType() == TaskEventType.DIAGNOSIS));

        ResponseEntity<ApiResponse> reportEntity = restTemplate.getForEntity(reportUrl("/" + taskId), ApiResponse.class);
        assertEquals(200, reportEntity.getStatusCode().value());
        Map<?, ?> reportPayload = (Map<?, ?>) reportEntity.getBody().getData();
        Map<?, ?> reportDiagnosis = (Map<?, ?>) reportPayload.get("reportDiagnosis");
        assertTrue(((Number) reportDiagnosis.get("diagnosisCount")).intValue() >= 1);
        assertTrue(((Number) reportDiagnosis.get("blockerCount")).intValue() >= 1);

        restTemplate.postForEntity(taskUrl("/" + taskId + "/resume"), null, ApiResponse.class);
        consumeLatestTaskExecutionRequested(taskId);

        Map<?, ?> successTaskBody = waitForTaskDetailStatus(taskId, AnalysisTaskStatus.SUCCESS);
        assertEquals("SUCCESS", successTaskBody.get("status"));
        assertEquals(Boolean.TRUE, successTaskBody.get("canViewReport"));

        TaskEventReplayService.TaskReplayFrame successReplayFrame =
                taskEventReplayService.planReplay(taskId, replayCursor);
        assertNotNull(successReplayFrame.getSnapshotEvent());
        assertEquals("SUCCESS", successReplayFrame.getSnapshotEvent().getPayload().get("status"));
        assertTrue(successReplayFrame.getReplayEvents().stream()
                .anyMatch(event -> event.getEventType() == TaskEventType.TASK_SNAPSHOT
                        || event.getEventType() == TaskEventType.NODE_STATUS));

        ResponseEntity<ApiResponse> replayEntity = restTemplate.getForEntity(
                taskUrl("/" + taskId + "/replay"),
                ApiResponse.class
        );
        assertEquals(200, replayEntity.getStatusCode().value());
        Map<?, ?> replayPayload = (Map<?, ?>) replayEntity.getBody().getData();
        List<?> searchReplays = (List<?>) replayPayload.get("searchReplays");
        assertFalse(searchReplays.isEmpty());

        String collectorNodeName = collectorNode.getNodeName();
        restTemplate.postForEntity(taskUrl("/" + taskId + "/nodes/" + collectorNodeName + "/rerun"), null, ApiResponse.class);
        consumeLatestTaskExecutionRequested(taskId);
        waitForTaskDetailStatus(taskId, AnalysisTaskStatus.SUCCESS);

        TaskNode rerunCollector = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId).stream()
                .filter(node -> collectorNodeName.equals(node.getNodeName()))
                .findFirst()
                .orElseThrow();
        assertTrue(rerunCollector.getNodeConfig().contains("searchAuditCheckpoint"));
        assertTrue(rerunCollector.getNodeConfig().contains("SELECT_TARGETS"));

        List<Report> reports = reportRepository.findAll();
        assertFalse(reports.isEmpty());
        assertFalse(evidenceSourceRepository.findByTaskIdOrderByEvidenceIdAsc(taskId).isEmpty());
        assertFalse(competitorKnowledgeRepository.findByTaskIdOrderByIdAsc(taskId).isEmpty());
        assertTrue(nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId).stream()
                .anyMatch(node -> node.getStatus() == TaskNodeStatus.SUCCESS));
    }

    private Long createTask(CreateTaskRequest request) {
        ResponseEntity<ApiResponse> createResponse = restTemplate.postForEntity(taskUrl("/create"), request, ApiResponse.class);
        assertEquals(200, createResponse.getStatusCode().value());
        Integer taskIdRaw = (Integer) ((Map<?, ?>) createResponse.getBody().getData()).get("id");
        return taskIdRaw.longValue();
    }

    private void consumeLatestTaskExecutionRequested(Long taskId) throws Exception {
        TaskWorkflowEvent event = waitForWorkflowEvent(taskId, WorkflowEventType.TASK_EXECUTION_REQUESTED);

        WorkflowEvent workflowEvent = WorkflowEvent.builder()
                .eventId(event.getEventId())
                .taskId(event.getTaskId())
                .nodeName(event.getNodeName())
                .planVersionId(event.getPlanVersionId())
                .branchKey(event.getBranchKey())
                .eventType(event.getEventType())
                .payload(readMap(event.getPayload()))
                .sourceUrls(readStringList(event.getSourceUrls()))
                .occurredAt(event.getCreatedAt())
                .build();
        String message = objectMapper.writeValueAsString(workflowEvent);

        /**
         * Phase 2 用例原本依赖同步执行入口。
         * 第四阶段把执行入口改成出站事件后，这里需要手动走一遍正式消费者，
         * 才能在不拉起真实 MQ listener 的测试环境中继续覆盖恢复语义与事件回放链路。
         */
        new WorkflowEventConsumer(objectMapper, workflowEventOutboxService, analysisTaskRunner).onMessage(message);
    }

    private TaskWorkflowEvent waitForWorkflowEvent(Long taskId, WorkflowEventType eventType) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(5).toMillis();
        while (System.currentTimeMillis() < deadline) {
            Optional<TaskWorkflowEvent> eventOptional = taskWorkflowEventRepository.findAll().stream()
                    .filter(item -> taskId.equals(item.getTaskId()))
                    .filter(item -> item.getEventType() == eventType)
                    .filter(item -> !TaskWorkflowEvent.STATUS_CONSUMED.equals(item.getDeliveryStatus()))
                    .max(java.util.Comparator.comparing(TaskWorkflowEvent::getId));
            if (eventOptional.isPresent()) {
                return eventOptional.get();
            }
            Thread.sleep(100);
        }
        List<String> currentEvents = taskWorkflowEventRepository.findAll().stream()
                .filter(item -> taskId.equals(item.getTaskId()))
                .map(item -> item.getEventType() + ":" + item.getDeliveryStatus())
                .toList();
        throw new AssertionError("鏈壘鍒板緟娑堣垂鐨?" + eventType + " 浜嬩欢, taskId=" + taskId + ", 褰撳墠浜嬩欢=" + currentEvents);
    }

    private Map<String, Object> readMap(String rawJson) throws Exception {
        if (rawJson == null || rawJson.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(rawJson, new TypeReference<Map<String, Object>>() {
        });
    }

    private List<String> readStringList(String rawJson) throws Exception {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of();
        }
        return objectMapper.readValue(rawJson, new TypeReference<List<String>>() {
        });
    }

    private void seedSourceDiscovery() {
        SourceCandidate docsCandidate = SourceCandidate.builder()
                .url("https://www.notion.so/help")
                .title("Notion AI Help")
                .sourceType("DOCS")
                .discoveryMethod("SEARCH")
                .domain("www.notion.so")
                .verified(true)
                .build();
        SourceCandidate pricingCandidate = SourceCandidate.builder()
                .url("https://www.notion.so/pricing")
                .title("Notion Pricing")
                .sourceType("PRICING")
                .discoveryMethod("SEARCH")
                .domain("www.notion.so")
                .verified(true)
                .build();
        List<SourcePlan> sourcePlans = List.of(
                SourcePlan.builder()
                        .sourceType("DOCS")
                        .urls(List.of("https://www.notion.so/help"))
                        .notes("文档来源")
                        .candidates(List.of(docsCandidate))
                        .build(),
                SourcePlan.builder()
                        .sourceType("PRICING")
                        .urls(List.of("https://www.notion.so/pricing"))
                        .notes("定价来源")
                        .candidates(List.of(pricingCandidate))
                        .build()
        );
        when(sourceDiscoveryService.discover(any(), any(), any())).thenReturn(sourcePlans);
        when(sourceDiscoveryService.discoverForPreview(any(), any(), any())).thenReturn(sourcePlans);
    }

    private void configureCollectorAgent() {
        doAnswer(invocation -> {
            AgentContext context = invocation.getArgument(0);
            Map<String, Object> config = objectMapper.readValue(
                    context.getCurrentNodeConfig(),
                    new TypeReference<Map<String, Object>>() {
                    });
            String competitorName = String.valueOf(config.get("competitorName"));
            String sourceType = String.valueOf(config.getOrDefault("sourceType", "DOCS"));
            String url = "PRICING".equalsIgnoreCase(sourceType)
                    ? "https://www.notion.so/pricing"
                    : "https://www.notion.so/help";
            String pageTitle = "PRICING".equalsIgnoreCase(sourceType) ? "Notion Pricing" : "Notion AI Help";
            String evidenceId = "T%04d-%s-001".formatted(context.getTaskId(), sourceType);

            evidenceSourceRepository.save(EvidenceSource.builder()
                    .taskId(context.getTaskId())
                    .competitorName(competitorName)
                    .evidenceId(evidenceId)
                    .title(pageTitle)
                    .url(url)
                    .contentSnippet("Phase 2 集成测试固定来源")
                    .fullContent("Phase 2 集成测试固定来源，用于验证多源补源与实时观察闭环。")
                    .pageMetadata("{\"verified\":true}")
                    .sourceType(sourceType)
                    .discoveryMethod("SEARCH")
                    .sourceDomain("www.notion.so")
                    .discoveryReason("Phase 2 固定候选来源")
                    .publishedAt("2026-05-10")
                    .sourceScore(0.95)
                    .build());

            String output = """
                    {
                      "competitor": "%s",
                      "sourceType": "%s",
                      "sourceUrls": ["%s"],
                      "searchQueries": ["%s documentation", "%s pricing"],
                      "selectedTargets": [{"url":"%s","title":"%s","verified":true}],
                      "sourceCandidates": [{"url":"%s","title":"%s","sourceType":"%s","discoveryMethod":"SEARCH","domain":"www.notion.so","verified":true}],
                      "successCollected": 1,
                      "totalCollected": 1,
                      "searchAudit": {
                        "executionTrace": {
                          "traceVersion":"v1",
                          "searchMode":"HYBRID",
                          "fallbackDecision":"USE_PLANNED_CANDIDATES",
                          "recoveryCheckpoint":"SELECT_TARGETS",
                          "resumedFromCheckpoint": false,
                          "degraded": false,
                          "providerFallbackUsed": false,
                          "plannedCandidateCount": 1,
                          "verifiedCandidateCount": 1,
                          "supplementedCandidateCount": 0,
                          "selectedCandidateCount": 1,
                          "selectedUrls": ["%s"]
                        },
                        "latestProgress": {
                          "status":"SUCCESS",
                          "currentStep":"瀹屾垚琛ยู簮",
                          "completedSteps":3,
                          "totalSteps":3,
                          "progressPercent":100
                        },
                        "progressHistory": [
                          {"currentStep":"鎼滅储鍊欓€夋潵婧?","completedSteps":1,"totalSteps":3,"progressPercent":33,"status":"RUNNING"},
                          {"currentStep":"楠岃瘉鐩爣","completedSteps":2,"totalSteps":3,"progressPercent":66,"status":"RUNNING"},
                          {"currentStep":"瀹屾垚琛ユ簮","completedSteps":3,"totalSteps":3,"progressPercent":100,"status":"SUCCESS"}
                        ],
                        "selectedTargets": [
                          {
                            "candidate":{
                              "url":"%s",
                              "title":"%s",
                              "sourceType":"%s",
                              "discoveryMethod":"SEARCH",
                              "domain":"www.notion.so",
                              "verified":true
                            },
                            "collectedPage":{
                              "url":"%s",
                              "competitorName":"%s",
                              "sourceType":"%s",
                              "title":"%s",
                              "snippet":"Phase 2 闆嗘垚娴嬭瘯鍥哄畾鏉ยู簮",
                              "content":"Phase 2 闆嗘垚娴嬭瘯鍥哄畾鏉ยู簮锛岀敤浜庨獙璇佸婧愯ˉ婧愪笌瀹炴椂瑙傚療闂幆銆?",
                              "success":true
                            }
                          }
                        ],
                        "sourceUrls": ["%s"]
                      },
                      "discoveryNotes": "Phase 2 固定候选来源",
                      "searchProgress": {
                        "status":"SUCCESS",
                        "currentStep":"完成补源",
                        "completedSteps":3,
                        "totalSteps":3,
                        "progressPercent":100
                      },
                      "searchProgressSnapshots": [
                        {"currentStep":"搜索候选来源","completedSteps":1,"totalSteps":3,"progressPercent":33,"status":"RUNNING"},
                        {"currentStep":"验证目标","completedSteps":2,"totalSteps":3,"progressPercent":66,"status":"RUNNING"},
                        {"currentStep":"完成补源","completedSteps":3,"totalSteps":3,"progressPercent":100,"status":"SUCCESS"}
                      ],
                      "searchExecutionTrace": {
                        "supplementMethod": "HYBRID",
                        "resumedFromCheckpoint": false,
                        "degraded": false,
                        "providerFallbackUsed": false,
                        "plannedCandidateCount": 1,
                        "verifiedCandidateCount": 1,
                        "supplementedCandidateCount": 0,
                        "selectedCandidateCount": 1,
                        "fallbackDecision":"USE_PLANNED_CANDIDATES",
                        "recoveryCheckpoint":"SELECT_TARGETS",
                        "selectedUrls": ["%s"]
                      }
                    }
                    """.formatted(
                    competitorName,
                    sourceType,
                    url,
                    competitorName,
                    competitorName,
                    url,
                    pageTitle,
                    url,
                    pageTitle,
                    sourceType,
                    url,
                    url,
                    pageTitle,
                    sourceType,
                    url,
                    competitorName,
                    sourceType,
                    pageTitle,
                    url,
                    url);
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
                            [{"name":"AI 写作","description":"自动生成与总结","evidenceIds":["T%1$d-DOCS-001"],"sourceUrls":["https://www.notion.so/help"]}]
                            """.formatted(context.getTaskId()))
                    .pricing("""
                            {"model":"订阅制","plans":["免费版","企业版"],"evidenceIds":["T%1$d-PRICING-001"],"sourceUrls":["https://www.notion.so/pricing"]}
                            """.formatted(context.getTaskId()))
                    .strengths("""
                            [{"point":"工作流一体化","evidenceIds":["T%1$d-DOCS-001"],"sourceUrls":["https://www.notion.so/help"]}]
                            """.formatted(context.getTaskId()))
                    .weaknesses("""
                            [{"point":"高阶能力学习成本偏高","evidenceIds":["T%1$d-DOCS-001"],"sourceUrls":["https://www.notion.so/help"]}]
                            """.formatted(context.getTaskId()))
                    .sources("""
                            [
                              {"evidenceId":"T%1$d-DOCS-001","title":"Notion AI Help","url":"https://www.notion.so/help"},
                              {"evidenceId":"T%1$d-PRICING-001","title":"Notion Pricing","url":"https://www.notion.so/pricing"}
                            ]
                            """.formatted(context.getTaskId()))
                    .sourceUrls("[\"https://www.notion.so/help\",\"https://www.notion.so/pricing\"]")
                    .evidenceCoverage("""
                            {
                              "summary":{"status":"TRACEABLE","hasValue":true},
                              "pricing":{"status":"TRACEABLE","hasValue":true}
                            }
                            """)
                    .build());
            return AgentResult.success("""
                    {
                      "competitors": [
                        {
                          "competitorName": "Notion AI",
                          "sourceUrls": ["https://www.notion.so/help", "https://www.notion.so/pricing"]
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
                  "pricingComparison": "定价证据已补齐。",
                  "recommendations": ["继续补充企业交付案例后再下最终结论"],
                  "sourceUrls": ["https://www.notion.so/help", "https://www.notion.so/pricing"]
                }
                """, "分析完成")).when(analyzerAgent).execute(any(AgentContext.class));
    }

    private void configureWriterAgent() {
        doAnswer(invocation -> {
            AgentContext context = invocation.getArgument(0);
            boolean revision = "rewrite_report".equals(context.getCurrentNodeName());
            Report report = reportRepository.findByTaskId(context.getTaskId()).orElseGet(Report::new);
            report.setTaskId(context.getTaskId());
            report.setTitle("Phase 2 集成回归报告");
            report.setContent(revision
                    ? "# Report\n\n已根据初审意见完成修订并保留来源引用。"
                    : "# Report\n\n初稿存在关键结论证据不足问题。");
            report.setSummary(revision ? "修订版报告已生成" : "初稿报告已生成");
            report.setQualityScore(revision ? 88 : 74);
            report.setQualityPassed(revision);
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
                        "sourceUrls":["https://www.notion.so/help"],
                        "suggestion":"补充证据编号或降低结论强度。"
                      }
                    ]
                    """);
            report.setEvidenceCount(2);
            reportRepository.save(report);

            return AgentResult.success("""
                    {
                      "sourceUrls": ["https://www.notion.so/help", "https://www.notion.so/pricing"],
                      "evidenceFragments": [
                        {
                          "stage": "%s",
                          "competitorName": "Notion AI",
                          "fieldName": "report",
                          "evidenceId": "T%04d-DOCS-001",
                          "sourceUrl": "https://www.notion.so/help",
                          "title": "Notion AI Help",
                          "snippet": "Notion AI helps teams write and summarize.",
                          "issueFlags": ["MISSING_BASIS"]
                        }
                      ]
                    }
                    """.formatted(revision ? "REWRITE" : "WRITE", context.getTaskId()), revision ? "改写完成" : "初稿完成");
        }).when(writerAgent).execute(any(AgentContext.class));
    }

    private void configureReviewerAgent() {
        doAnswer(invocation -> {
            reviewerExecutionCount++;
            if (reviewerExecutionCount == 1) {
                return AgentResult.success("""
                        {
                          "score": 22,
                          "passed": false,
                          "requiresHumanIntervention": true,
                          "autoRewriteAllowed": false,
                          "summary": "证据链仍需人工确认，先暂停自动改写。",
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
                              "sourceUrls":["https://www.notion.so/help"],
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
                              "sourceUrls":["https://www.notion.so/help"],
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
                        """, "初审阻断");
            }
            return AgentResult.success("""
                    {
                      "score": 88,
                      "passed": true,
                      "requiresHumanIntervention": false,
                      "autoRewriteAllowed": true,
                      "summary": "修订后报告已达到 Phase 2 回归基线。",
                      "diagnoses": [],
                      "issues": [],
                      "nextActions": []
                    }
                    """, "终审通过");
        }).when(reviewerAgent).execute(any(AgentContext.class));
    }

    /**
     * Phase 2 集成测试不依赖真实 Redis，
     * 但仍需要验证“快照恢复 + 事件补偿”的调用链，所以这里用内存 Map 模拟快照与中间态。
     */
    private void configureRuntimeInfrastructure() {
        doAnswer(invocation -> {
            TaskProgressSnapshot snapshot = invocation.getArgument(0);
            snapshotStore.put(snapshot.getTaskId(), snapshot);
            return null;
        }).when(taskSnapshotCacheService).saveTaskSnapshot(any(TaskProgressSnapshot.class));
        when(taskSnapshotCacheService.getTaskSnapshot(anyLong()))
                .thenAnswer(invocation -> Optional.ofNullable(snapshotStore.get(invocation.getArgument(0))));
        doAnswer(invocation -> {
            Long taskId = invocation.getArgument(0);
            String nodeName = invocation.getArgument(1);
            String outputData = invocation.getArgument(2);
            runtimeOutputStore.computeIfAbsent(taskId, ignored -> new LinkedHashMap<>()).put(nodeName, outputData);
            return null;
        }).when(taskSnapshotCacheService).cacheNodeOutput(anyLong(), anyString(), anyString());
        when(taskSnapshotCacheService.getCachedNodeOutputs(anyLong()))
                .thenAnswer(invocation -> runtimeOutputStore.getOrDefault(invocation.getArgument(0), Map.of()));
        doAnswer(invocation -> {
            Long taskId = invocation.getArgument(0);
            snapshotStore.remove(taskId);
            runtimeOutputStore.remove(taskId);
            return null;
        }).when(taskSnapshotCacheService).evictTaskRuntime(anyLong());

        when(taskExecutionLockService.tryAcquireTaskExecutionLock(anyLong(), anyString(), any()))
                .thenReturn(true);
        when(taskExecutionLockService.releaseTaskExecutionLock(anyLong(), anyString()))
                .thenReturn(true);
        when(taskExecutionLockService.tryAcquireNodeExecutionLock(anyLong(), anyString(), anyString(), any()))
                .thenReturn(true);
        when(taskExecutionLockService.releaseNodeExecutionLock(anyLong(), anyString(), anyString()))
                .thenReturn(true);
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
