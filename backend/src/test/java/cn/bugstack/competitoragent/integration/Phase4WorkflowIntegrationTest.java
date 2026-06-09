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
import cn.bugstack.competitoragent.event.TaskEventPublisher;
import cn.bugstack.competitoragent.llm.EmbeddingClient;
import cn.bugstack.competitoragent.llm.LlmClient;
import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.llm.RerankClient;
import cn.bugstack.competitoragent.model.entity.CompetitorKnowledge;
import cn.bugstack.competitoragent.model.entity.EvidenceSource;
import cn.bugstack.competitoragent.model.entity.KnowledgeDocument;
import cn.bugstack.competitoragent.model.entity.Report;
import cn.bugstack.competitoragent.model.entity.RetrievalChunk;
import cn.bugstack.competitoragent.model.entity.RetrievalIndex;
import cn.bugstack.competitoragent.model.entity.TaskWorkflowEvent;
import cn.bugstack.competitoragent.model.entity.WorkflowDeadLetterRecord;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import cn.bugstack.competitoragent.repository.EvidenceSourceRepository;
import cn.bugstack.competitoragent.repository.KnowledgeDocumentRepository;
import cn.bugstack.competitoragent.repository.ReportRepository;
import cn.bugstack.competitoragent.repository.RetrievalChunkRepository;
import cn.bugstack.competitoragent.repository.RetrievalIndexRepository;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import cn.bugstack.competitoragent.repository.TaskWorkflowEventRepository;
import cn.bugstack.competitoragent.repository.WorkflowDeadLetterRecordRepository;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.LocalDateTime;
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
 * Task 4.8 第四阶段黑盒联调回归测试。
 * <p>
 * 这条用例把“异步编排入口 -> 节点重试耗尽进入人工处理/DLQ -> 人工恢复 ->
 * 动态补图 -> Task RAG 检索 -> 统一对话解释与动作预览”串成一条最小闭环，
 * 用于验证第四阶段新增语义已经被同一套代码与接口稳定承接。
 */
@SpringBootTest(
        classes = CompetitorAgentApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                /**
                 * 这条回归用例当前验证的是“事件落库 + 出站事件被消费后驱动编排”的最小闭环，
                 * 因此显式关闭 RocketMQ Starter 自动装配，避免测试环境被真实 Producer 初始化阻断。
                 */
                "spring.autoconfigure.exclude=org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration"
        }
)
@ActiveProfiles("phase4-integration")
@Import(Phase4WorkflowIntegrationTest.SyncAsyncTestConfig.class)
class Phase4WorkflowIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AnalysisTaskRunner analysisTaskRunner;

    @Autowired
    private TaskWorkflowEventRepository taskWorkflowEventRepository;

    @Autowired
    private WorkflowDeadLetterRecordRepository workflowDeadLetterRecordRepository;

    @Autowired
    private TaskPlanRepository taskPlanRepository;

    @Autowired
    private ReportRepository reportRepository;

    @Autowired
    private EvidenceSourceRepository evidenceSourceRepository;

    @Autowired
    private CompetitorKnowledgeRepository competitorKnowledgeRepository;

    @Autowired
    private KnowledgeDocumentRepository knowledgeDocumentRepository;

    @Autowired
    private RetrievalIndexRepository retrievalIndexRepository;

    @Autowired
    private RetrievalChunkRepository retrievalChunkRepository;

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

    @MockBean
    private TaskEventPublisher taskEventPublisher;

    @MockBean
    private EmbeddingClient embeddingClient;

    @MockBean
    private LlmClient llmClient;

    @MockBean
    private RerankClient rerankClient;

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
    private final Map<String, Integer> nodeExecutionAttempts = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        snapshotStore.clear();
        runtimeOutputStore.clear();
        nodeExecutionAttempts.clear();
        taskWorkflowEventRepository.deleteAll();
        workflowDeadLetterRecordRepository.deleteAll();
        reportRepository.deleteAll();
        retrievalChunkRepository.deleteAll();
        retrievalIndexRepository.deleteAll();
        knowledgeDocumentRepository.deleteAll();
        competitorKnowledgeRepository.deleteAll();
        evidenceSourceRepository.deleteAll();
        taskPlanRepository.deleteAll();

        /**
         * Phase 4 这条黑盒回归只想验证“事件驱动执行链路被正确接上”，
         * 不希望因为测试 profile 没有真的启用 RocketMQ 而让执行入口提前短路。
         */
        doNothing().when(rocketMqProperties).validateForExecution();

        seedSourceDiscovery();
        configureCollectorAgent();
        configureExtractorAgent();
        configureAnalyzerAgent();
        configureWriterAgent();
        configureReviewerAgent();
        configureRuntimeInfrastructure();
        configureEmbeddingClient();

        when(promptTemplateService.buildSearchQueries(any(), any(), any()))
                .thenAnswer(invocation -> List.of(
                        invocation.getArgument(0) + " official docs",
                        invocation.getArgument(0) + " pricing"
                ));
    }

    @Test
    void shouldCoverPhase4WorkflowThroughAsyncDispatchDlqRecoveryDynamicBackflowTaskRagAndConversationEntry() throws Exception {
        Long taskId = createTask("""
                {
                  "taskName": "Phase 4 最小闭环",
                  "subjectProduct": "企业级 AI 研究平台",
                  "competitorNames": ["Notion AI"],
                  "competitorUrls": ["https://www.notion.so/product/ai"],
                  "analysisDimensions": ["产品功能", "证据完整性"],
                  "sourceScope": ["官网", "产品文档"]
                }
                """);

        restTemplate.postForEntity(taskUrl("/" + taskId + "/execute"), null, ApiResponse.class);
        consumeLatestTaskExecutionRequested(taskId);

        Map<?, ?> stoppedTaskBody = waitForTaskDetailStatus(taskId, AnalysisTaskStatus.STOPPED);
        assertEquals("STOPPED", stoppedTaskBody.get("status"));
        assertTrue(String.valueOf(stoppedTaskBody.get("statusSummary")).contains("人工")
                        || String.valueOf(stoppedTaskBody.get("errorMessage")).contains("人工"),
                () -> "任务停止后应能明确暴露人工处理语义，实际详情=" + stoppedTaskBody);

        List<WorkflowDeadLetterRecord> deadLetterRecords = workflowDeadLetterRecordRepository.findAll();
        assertEquals(1, deadLetterRecords.size());
        assertEquals("collect_sources_01_01", deadLetterRecords.get(0).getNodeName());
        assertTrue(String.valueOf(deadLetterRecords.get(0).getLatestErrorSummary()).contains("timeout"));

        Map<?, ?> explainResponse = postConversation("""
                {
                  "taskId": %d,
                  "pageType": "TASK_DETAIL",
                  "message": "这个任务为什么停在这里了？"
                }
                """.formatted(taskId));
        assertEquals("EXPLAIN", explainResponse.get("mode"));
        assertTrue(String.valueOf(explainResponse.get("answer")).contains("当前任务"));

        Map<?, ?> actionPreviewResponse = postConversation("""
                {
                  "taskId": %d,
                  "pageType": "TASK_DETAIL",
                  "message": "从 collect_sources_01_01 开始重跑"
                }
                """.formatted(taskId));
        assertEquals("TASK_ACTION", actionPreviewResponse.get("mode"));
        Map<?, ?> actionPreview = (Map<?, ?>) actionPreviewResponse.get("taskActionPreview");
        assertEquals("RERUN_NODE", actionPreview.get("actionType"));
        assertEquals("collect_sources_01_01", actionPreview.get("targetNodeName"));
        assertEquals(Boolean.TRUE, actionPreview.get("requiresConfirmation"));

        restTemplate.postForEntity(taskUrl("/" + taskId + "/resume"), null, ApiResponse.class);
        consumeLatestTaskExecutionRequested(taskId);

        Map<?, ?> successTaskBody = waitForTaskDetailStatus(taskId, AnalysisTaskStatus.SUCCESS);
        assertEquals("SUCCESS", successTaskBody.get("status"));
        assertTrue(((Number) successTaskBody.get("currentPlanVersion")).intValue() >= 2);
        assertTrue(((Number) successTaskBody.get("currentPlanVersionId")).longValue() > 0L);

        ResponseEntity<ApiResponse> nodesEntity = restTemplate.getForEntity(taskUrl("/" + taskId + "/nodes"), ApiResponse.class);
        assertEquals(200, nodesEntity.getStatusCode().value());
        List<Map<String, Object>> nodePayloads = (List<Map<String, Object>>) nodesEntity.getBody().getData();
        assertTrue(nodePayloads.stream().anyMatch(node -> Boolean.TRUE.equals(node.get("dynamicNode"))),
                () -> "成功链路后应已经挂接动态补图节点，实际节点列表=" + nodePayloads);
        assertTrue(nodePayloads.stream().anyMatch(node -> String.valueOf(node.get("nodeName")).startsWith("collect_revision_evidence_v")),
                () -> "动态补证分支未出现，实际节点列表=" + nodePayloads);
        assertTrue(nodePayloads.stream().anyMatch(node -> String.valueOf(node.get("nodeName")).startsWith("quality_check_revision_patch_v")),
                () -> "动态复核分支未出现，实际节点列表=" + nodePayloads);

        Map<?, ?> researchResponse = postConversation("""
                {
                  "taskId": %d,
                  "pageType": "TASK_DETAIL",
                  "message": "继续补搜官方文档证据"
                }
                """.formatted(taskId));
        assertEquals("RESEARCH", researchResponse.get("mode"));
        List<Map<String, Object>> retrievalEvidences =
                (List<Map<String, Object>>) researchResponse.get("retrievalEvidences");
        assertFalse(retrievalEvidences.isEmpty());
        assertNotNull(retrievalEvidences.get(0).get("evidenceId"));
        List<String> sourceUrls = (List<String>) researchResponse.get("sourceUrls");
        assertFalse(sourceUrls.isEmpty());

        List<TaskWorkflowEvent> workflowEvents = taskWorkflowEventRepository.findAll();
        assertTrue(workflowEvents.stream().anyMatch(event -> event.getEventType() == WorkflowEventType.TASK_CREATED));
        assertTrue(workflowEvents.stream().anyMatch(event -> event.getEventType() == WorkflowEventType.TASK_EXECUTION_REQUESTED));
        assertTrue(workflowEvents.stream().anyMatch(event -> event.getEventType() == WorkflowEventType.NODE_READY));
        assertTrue(workflowEvents.stream().anyMatch(event -> event.getEventType() == WorkflowEventType.NODE_COMPLETED));
        assertTrue(workflowEvents.stream().anyMatch(event -> event.getEventType() == WorkflowEventType.NODE_FAILED));
        assertTrue(workflowEvents.stream().anyMatch(event -> event.getSourceUrls() != null && event.getSourceUrls().contains("https://www.notion.so/help")));
    }

    private Long createTask(String requestBody) {
        ResponseEntity<ApiResponse> createResponse = restTemplate.postForEntity(
                taskUrl("/create"),
                jsonEntity(requestBody),
                ApiResponse.class);
        assertEquals(200, createResponse.getStatusCode().value());
        Map<?, ?> payload = (Map<?, ?>) createResponse.getBody().getData();
        return ((Number) payload.get("id")).longValue();
    }

    private Map<?, ?> postConversation(String body) {
        ResponseEntity<ApiResponse> response = restTemplate.postForEntity(
                conversationUrl("/message"),
                jsonEntity(body),
                ApiResponse.class);
        assertEquals(200, response.getStatusCode().value());
        return (Map<?, ?>) response.getBody().getData();
    }

    private HttpEntity<String> jsonEntity(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
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
         * 测试环境下不拉起真实 MQ listener 容器，
         * 这里手工走一遍“解析消息 -> 去重判断 -> 编排消费 -> 标记 consumed”的正式消费者逻辑。
         */
        new WorkflowEventConsumer(objectMapper, workflowEventOutboxService, analysisTaskRunner).onMessage(message);
    }

    /**
     * 第四阶段链路已经是异步编排语义，测试侧需要容忍“HTTP 返回”和“待消费事件真正落库”之间的短暂时间差，
     * 否则会把正常的异步抖动误判成事件没有写入。
     */
    private TaskWorkflowEvent waitForWorkflowEvent(Long taskId, WorkflowEventType eventType) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(5).toMillis();
        while (System.currentTimeMillis() < deadline) {
            List<TaskWorkflowEvent> taskEvents = taskWorkflowEventRepository.findAll().stream()
                    .filter(item -> taskId.equals(item.getTaskId()))
                    .toList();
            Optional<TaskWorkflowEvent> eventOptional = taskEvents.stream()
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
        throw new AssertionError("未找到待消费的 " + eventType + " 事件, taskId=" + taskId + ", 当前事件=" + currentEvents);
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
        List<SourcePlan> sourcePlans = List.of(
                SourcePlan.builder()
                        .sourceType("DOCS")
                        .urls(List.of("https://www.notion.so/help"))
                        .notes("Phase 4 固定候选来源")
                        .candidates(List.of(docsCandidate))
                        .build()
        );
        when(sourceDiscoveryService.discover(any(), any(), any())).thenReturn(sourcePlans);
        when(sourceDiscoveryService.discoverForPreview(any(), any(), any())).thenReturn(sourcePlans);
    }

    private void configureCollectorAgent() {
        doAnswer(invocation -> {
            AgentContext context = invocation.getArgument(0);
            String nodeName = context.getCurrentNodeName();
            int attempt = nodeExecutionAttempts.merge(nodeName, 1, Integer::sum);
            // 当前工作流节点默认 maxRetries=3，表示“首次执行 + 3 次自动重试”共 4 次尝试。
            // 这里让首个采集节点前 4 次都失败，才能稳定走到 WAITING_INTERVENTION / DLQ，再由 resume 覆盖恢复链路。

            /**
             * 第一轮主分支采集故意打出 timeout，
             * 用来验证 WAITING_RETRY -> WAITING_INTERVENTION -> DLQ 留痕这条第四阶段语义。
             */
            if ("collect_sources_01_01".equals(nodeName) && attempt <= 4) {
                return AgentResult.failed("collector timeout while loading docs page attempt " + attempt);
            }

            Map<String, Object> config = objectMapper.readValue(
                    context.getCurrentNodeConfig(),
                    new TypeReference<Map<String, Object>>() {
                    });
            String competitorName = String.valueOf(config.getOrDefault("competitorName", "Notion AI"));
            String sourceType = String.valueOf(config.getOrDefault("sourceType", "DOCS"));
            String url = extractFirstUrl(config, nodeName.startsWith("collect_revision_evidence_v")
                    ? "https://www.notion.so/security"
                    : "https://www.notion.so/help");
            String normalizedNodeName = nodeName.replaceAll("[^a-zA-Z0-9]+", "_").toUpperCase();
            String evidenceId = "T%04d-%s-001".formatted(context.getTaskId(), normalizedNodeName);
            String documentKey = "DOC-%d-%s".formatted(context.getTaskId(), normalizedNodeName);
            String indexKey = "IDX-%d-%s".formatted(context.getTaskId(), normalizedNodeName);
            String content = nodeName.startsWith("collect_revision_evidence_v")
                    ? "Notion security documentation explains enterprise access control and admin audit coverage."
                    : "Notion AI help documentation explains workspace writing, summarization, and searchable source references.";
            String snippet = nodeName.startsWith("collect_revision_evidence_v")
                    ? "Enterprise access control and admin audit coverage."
                    : "Workspace writing and summarization guidance with source references.";

            evidenceSourceRepository.save(EvidenceSource.builder()
                    .taskId(context.getTaskId())
                    .competitorName(competitorName)
                    .evidenceId(evidenceId)
                    .title(nodeName.startsWith("collect_revision_evidence_v") ? "Notion Security" : "Notion AI Help")
                    .url(url)
                    .contentSnippet(snippet)
                    .fullContent(content)
                    .pageMetadata("{\"verified\":true}")
                    .sourceType(sourceType)
                    .discoveryMethod("SEARCH")
                    .sourceDomain("www.notion.so")
                    .discoveryReason("Phase 4 集成测试固定来源")
                    .publishedAt("2026-06-01")
                    .sourceScore(0.96)
                    .build());

            KnowledgeDocument knowledgeDocument = knowledgeDocumentRepository.save(KnowledgeDocument.builder()
                    .taskId(context.getTaskId())
                    .competitorName(competitorName)
                    .evidenceId(evidenceId)
                    .documentKey(documentKey)
                    .sourceType(sourceType)
                    .sourceCategory("AI_DISCOVERED")
                    .discoveryMethod("SEARCH")
                    .sourceDomain("www.notion.so")
                    .title(nodeName.startsWith("collect_revision_evidence_v") ? "Notion Security" : "Notion AI Help")
                    .url(url)
                    .snippet(snippet)
                    .cleanedText(content)
                    .sourceUrls(List.of(url))
                    .documentVersion(1)
                    .status("READY")
                    .collectedAt(LocalDateTime.now())
                    .build());

            retrievalIndexRepository.save(RetrievalIndex.builder()
                    .taskId(context.getTaskId())
                    .knowledgeDocumentId(knowledgeDocument.getId())
                    .competitorName(competitorName)
                    .evidenceId(evidenceId)
                    .documentKey(documentKey)
                    .indexKey(indexKey)
                    .indexScope("TASK")
                    .sourceCategory("AI_DISCOVERED")
                    .documentVersion(1)
                    .chunkCount(1)
                    .status("READY")
                    .sourceUrls(List.of(url))
                    .build());

            retrievalChunkRepository.save(RetrievalChunk.builder()
                    .taskId(context.getTaskId())
                    .knowledgeDocumentId(knowledgeDocument.getId())
                    .competitorName(competitorName)
                    .evidenceId(evidenceId)
                    .documentKey(documentKey)
                    .chunkKey(documentKey + "#001")
                    .chunkIndex(0)
                    .startOffset(0)
                    .endOffset(content.length())
                    .sourceCategory("AI_DISCOVERED")
                    .documentVersion(1)
                    .content(content)
                    .snippet(snippet)
                    .sourceUrls(List.of(url))
                    .build());

            String output = """
                    {
                      "competitor": "%s",
                      "sourceType": "%s",
                      "sourceUrls": ["%s"],
                      "taskRagContext": "当前已补齐可回指来源，可继续进入抽取或解释阶段。",
                      "successCollected": 1,
                      "totalCollected": 1
                    }
                    """.formatted(competitorName, sourceType, url);
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
                    .summary("Notion AI 聚焦知识协作与生成式写作。")
                    .positioning("协同知识工作台")
                    .targetUsers("[\"企业团队\"]")
                    .coreFeatures("""
                            [{"name":"AI 写作","description":"帮助团队生成与总结内容","evidenceIds":["DOC-1"],"sourceUrls":["https://www.notion.so/help"]}]
                            """)
                    .pricing("""
                            {"model":"订阅制","sourceUrls":["https://www.notion.so/help"]}
                            """)
                    .strengths("""
                            [{"point":"协作与知识沉淀结合紧密","sourceUrls":["https://www.notion.so/help"]}]
                            """)
                    .weaknesses("""
                            [{"point":"复杂安全场景仍需补证据","sourceUrls":["https://www.notion.so/security"]}]
                            """)
                    .sources("""
                            [{"evidenceId":"DOC-1","title":"Notion AI Help","url":"https://www.notion.so/help"}]
                            """)
                    .sourceUrls("[\"https://www.notion.so/help\",\"https://www.notion.so/security\"]")
                    .evidenceCoverage("""
                            {"summary":{"status":"TRACEABLE","hasValue":true}}
                            """)
                    .build());
            return AgentResult.success("""
                    {
                      "competitors": [
                        {
                          "competitorName": "Notion AI",
                          "sourceUrls": ["https://www.notion.so/help", "https://www.notion.so/security"]
                        }
                      ]
                    }
                    """, "抽取完成");
        }).when(extractorAgent).execute(any(AgentContext.class));
    }

    private void configureAnalyzerAgent() {
        doAnswer(invocation -> AgentResult.success("""
                {
                  "overview": "Notion AI 具备知识协作与写作结合能力。",
                  "featureComparison": "协同知识编辑与 AI 总结是其核心优势。",
                  "pricingComparison": "仍需结合补充证据验证企业安全说明。",
                  "recommendations": ["补齐安全与权限证据后再做最终结论"],
                  "sourceUrls": ["https://www.notion.so/help", "https://www.notion.so/security"]
                }
                """, "分析完成")).when(analyzerAgent).execute(any(AgentContext.class));
    }

    private void configureWriterAgent() {
        doAnswer(invocation -> {
            AgentContext context = invocation.getArgument(0);
            String nodeName = context.getCurrentNodeName();
            Report report = reportRepository.findByTaskId(context.getTaskId()).orElseGet(Report::new);
            report.setTaskId(context.getTaskId());
            report.setTitle("Phase 4 集成回归报告");
            if ("write_report".equals(nodeName)) {
                report.setContent("# Report\n\n初版报告已生成，但安全说明证据还不充分。");
                report.setSummary("初版报告已生成");
                report.setQualityScore(70);
                report.setQualityPassed(false);
            } else {
                report.setContent("# Report\n\n动态补证后的修订版已生成，并补齐了安全说明来源。");
                report.setSummary("修订版报告已生成");
                report.setQualityScore(90);
                report.setQualityPassed(true);
            }
            report.setQualityIssues("""
                    [
                      {
                        "type":"missing_evidence",
                        "section":"结论",
                        "severity":"WARNING",
                        "level":"MAJOR",
                        "dimensionCode":"EVIDENCE_TRACEABILITY",
                        "dimensionName":"证据可追溯性",
                        "evidenceBasis":"初版结论仍需补齐高可信来源。",
                        "sourceUrls":["https://www.notion.so/security"],
                        "suggestion":"补充官网安全说明后再输出最终结论。"
                      }
                    ]
                    """);
            report.setEvidenceCount((int) evidenceSourceRepository.count());
            reportRepository.save(report);

            return AgentResult.success("""
                    {
                      "sourceUrls": ["https://www.notion.so/help", "https://www.notion.so/security"]
                    }
                    """, "报告写作完成");
        }).when(writerAgent).execute(any(AgentContext.class));
    }

    private void configureReviewerAgent() {
        doAnswer(invocation -> {
            AgentContext context = invocation.getArgument(0);
            String nodeName = context.getCurrentNodeName();
            if ("quality_check".equals(nodeName)) {
                return AgentResult.success("""
                        {
                          "reviewStage": "initial",
                          "score": 68,
                          "passed": false,
                          "requiresHumanIntervention": false,
                          "autoRewriteAllowed": true,
                          "summary": "当前结论需要先改写，并补足安全说明来源。",
                          "diagnoses": [
                            {
                              "dimensionCode":"EVIDENCE_TRACEABILITY",
                              "dimensionName":"证据可追溯性",
                              "type":"missing_evidence",
                              "section":"结论",
                              "severity":"WARNING",
                              "level":"MAJOR",
                              "title":"结论仍需补齐安全说明",
                              "detail":"当前结论缺少高可信安全说明链接。",
                              "sourceUrls":["https://www.notion.so/security"],
                              "repairSuggestion":"先改写结论，再准备补证分支。"
                            }
                          ],
                          "issues": [
                            {
                              "type":"missing_evidence",
                              "section":"结论",
                              "severity":"WARNING",
                              "level":"MAJOR",
                              "dimensionCode":"EVIDENCE_TRACEABILITY",
                              "dimensionName":"证据可追溯性",
                              "evidenceBasis":"当前结论缺少高可信安全说明链接。",
                              "sourceUrls":["https://www.notion.so/security"],
                              "suggestion":"先改写结论，再准备补证分支。"
                            }
                          ],
                          "nextActions": [
                            {
                              "title":"先改写当前结论",
                              "description":"当前更适合先把结论改写为克制版本，再进入终审。",
                              "actionType":"RERUN_NODE",
                              "targetNode":"rewrite_report",
                              "priority":"HIGH"
                            }
                          ]
                        }
                        """, "初审建议改写");
            }

            if ("quality_check_final".equals(nodeName)) {
                return AgentResult.success("""
                        {
                          "reviewStage": "final",
                          "score": 74,
                          "passed": false,
                          "requiresHumanIntervention": false,
                          "autoRewriteAllowed": true,
                          "summary": "终审发现安全说明证据仍不足，建议挂接动态补证分支。",
                          "issues": [
                            {
                              "type":"missing_evidence",
                              "section":"安全能力",
                              "severity":"ERROR",
                              "level":"BLOCKER",
                              "dimensionCode":"EVIDENCE_TRACEABILITY",
                              "dimensionName":"证据可追溯性",
                              "evidenceBasis":"企业权限与安全能力仍缺少稳定官网来源。",
                              "sourceUrls":["https://www.notion.so/security"],
                              "suggestion":"补充官方安全说明并重新复核。"
                            }
                          ],
                          "revisionDirectives": [
                            {
                              "category":"SEARCH_QUALITY",
                              "actionType":"SUPPLEMENT_EVIDENCE",
                              "priority":"HIGH",
                              "targetSection":"安全能力",
                              "summary":"补充安全说明与权限管理证据",
                              "searchFeedback":"当前企业安全说明覆盖仍不足",
                              "searchQueries":["Notion AI security admin audit"],
                              "sourceUrls":["https://www.notion.so/security"],
                              "expectedOutcome":"补齐安全与权限说明的官网来源，并形成可回指证据链。"
                            }
                          ]
                        }
                        """, "终审触发动态补证");
            }

            if (nodeName != null && nodeName.startsWith("quality_check_revision_patch_v")) {
                return AgentResult.success("""
                        {
                          "reviewStage": "final",
                          "score": 92,
                          "passed": true,
                          "requiresHumanIntervention": false,
                          "autoRewriteAllowed": true,
                          "summary": "动态补证后已达到第四阶段回归基线。",
                          "diagnoses": [],
                          "issues": [],
                          "nextActions": []
                        }
                        """, "动态补证复核通过");
            }

            return AgentResult.success("""
                    {
                      "reviewStage": "final",
                      "score": 88,
                      "passed": true,
                      "requiresHumanIntervention": false,
                      "autoRewriteAllowed": true,
                      "summary": "评审通过。",
                      "diagnoses": [],
                      "issues": [],
                      "nextActions": []
                    }
                    """, "评审通过");
        }).when(reviewerAgent).execute(any(AgentContext.class));
    }

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

    private void configureEmbeddingClient() {
        when(embeddingClient.embed(anyString())).thenAnswer(invocation -> {
            String text = String.valueOf(invocation.getArgument(0));
            float lexicalHint = text.contains("security") || text.contains("官方") ? 1.0f : 0.5f;
            return List.of(lexicalHint, (float) Math.min(8, text.length()));
        });
    }

    private String extractFirstUrl(Map<String, Object> config, String fallbackUrl) {
        Object sourceUrls = config.get("sourceUrls");
        if (sourceUrls instanceof List<?> urls && !urls.isEmpty()) {
            Object value = urls.get(0);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        Object competitorUrls = config.get("competitorUrls");
        if (competitorUrls instanceof List<?> urls && !urls.isEmpty()) {
            Object value = urls.get(0);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return fallbackUrl;
    }

    private Map<?, ?> waitForTaskDetailStatus(Long taskId, AnalysisTaskStatus expectedStatus) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(20).toMillis();
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

    private String conversationUrl(String path) {
        return "http://localhost:" + port + "/api/conversation" + path;
    }

    @org.springframework.boot.test.context.TestConfiguration
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
