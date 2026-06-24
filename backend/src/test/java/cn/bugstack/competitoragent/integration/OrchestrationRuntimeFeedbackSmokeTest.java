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
import cn.bugstack.competitoragent.config.RocketMqProperties;
import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.model.dto.CreateTaskRequest;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskWorkflowEvent;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.repository.TaskWorkflowEventRepository;
import cn.bugstack.competitoragent.source.SourceCandidate;
import cn.bugstack.competitoragent.source.SourceDiscoveryService;
import cn.bugstack.competitoragent.source.SourcePlan;
import cn.bugstack.competitoragent.task.AnalysisTaskRunner;
import cn.bugstack.competitoragent.task.TaskExecutionLockService;
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
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

/**
 * P1 运行期反馈 MVP 可复现实链 smoke。
 * <p>
 * 该用例使用真实 Spring MVC、H2 仓储、DAG 执行器、动态补图、Orchestrator trace 和 replay 投影；
 * 仅替换外部 LLM、搜索、浏览器、Redis 锁和 RocketMQ 基础设施，避免演示证据包受本地中间件或外部 API 波动影响。
 */
@SpringBootTest(
        classes = CompetitorAgentApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude=org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration"
        }
)
@ActiveProfiles("test")
@Import(OrchestrationRuntimeFeedbackSmokeTest.SyncAsyncTestConfig.class)
class OrchestrationRuntimeFeedbackSmokeTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AnalysisTaskRepository taskRepository;

    @Autowired
    private TaskNodeRepository nodeRepository;

    @Autowired
    private TaskWorkflowEventRepository taskWorkflowEventRepository;

    @Autowired
    private AnalysisTaskRunner analysisTaskRunner;

    @Autowired
    private WorkflowEventOutboxService workflowEventOutboxService;

    @Autowired
    private ObjectMapper objectMapper;

    @SpyBean
    private RocketMqProperties rocketMqProperties;

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

    @MockBean
    private SourceDiscoveryService sourceDiscoveryService;

    @MockBean
    private PromptTemplateService promptTemplateService;

    @MockBean
    private Playwright playwright;

    @MockBean
    private PlaywrightBrowserManager playwrightBrowserManager;

    @MockBean
    private TaskSnapshotCacheService taskSnapshotCacheService;

    @MockBean
    private TaskExecutionLockService taskExecutionLockService;

    private boolean reviewerShouldOmitSourceUrls;

    @BeforeEach
    void setUp() {
        reviewerShouldOmitSourceUrls = false;
        doNothing().when(rocketMqProperties).validateForExecution();
        configureRuntimeInfrastructure();
        configureSourceDiscovery();
        configureAgentStubs();
    }

    @Test
    void shouldProduceReplayableOrchestrationDecisionCheckpointAndDynamicBranchThroughApiSmoke() throws Exception {
        Long taskId = createAndExecuteTask(false);

        AnalysisTask task = waitForTaskStatus(taskId, AnalysisTaskStatus.SUCCESS);
        assertEquals(2, task.getCurrentPlanVersion());

        List<TaskNode> nodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId);
        assertThat(nodes)
                .extracting(TaskNode::getNodeName)
                .contains(
                        "collect_revision_evidence_v2_1",
                        "extract_revision_patch_v2",
                        "analyze_revision_patch_v2",
                        "rewrite_revision_patch_v2",
                        "quality_check_revision_patch_v2");
        assertThat(nodes.stream()
                .filter(TaskNode::isDynamicNode)
                .map(TaskNode::getBranchKey))
                .containsOnly("root/review-2");

        TaskWorkflowEvent decisionEvent = latestEvent(taskId, WorkflowEventType.ORCHESTRATION_DECISION_RECORDED);
        JsonNode decisionPayload = objectMapper.readTree(decisionEvent.getPayload());
        assertThat(decisionPayload.path("policyResult").path("allowed").asBoolean()).isTrue();
        assertThat(decisionPayload.path("mutation").path("mutationType").asText()).isEqualTo("APPEND_NODES");
        assertThat(decisionPayload.path("decision").path("sourceUrls").get(0).asText())
                .isEqualTo("https://www.notion.so/pricing");

        TaskWorkflowEvent checkpointEvent = latestEvent(taskId, WorkflowEventType.ORCHESTRATION_CHECKPOINT_UPDATED);
        JsonNode checkpointPayload = objectMapper.readTree(checkpointEvent.getPayload());
        assertThat(checkpointPayload.path("checkpoint").path("decisionCount").asInt()).isEqualTo(1);
        assertThat(checkpointPayload.path("checkpoint").path("resumeAfterNodeName").asText())
                .isEqualTo("collect_revision_evidence_v2_1");

        JsonNode replay = getReplay(taskId);
        assertThat(replay.path("data").path("timeline"))
                .anySatisfy(event -> assertThat(event.path("eventType").asText())
                        .isEqualTo("ORCHESTRATION_DECISION_RECORDED"))
                .anySatisfy(event -> assertThat(event.path("eventType").asText())
                        .isEqualTo("ORCHESTRATION_CHECKPOINT_UPDATED"));
        assertThat(replay.path("data").path("sourceUrls").toString())
                .contains("https://www.notion.so/pricing");
    }

    @Test
    void shouldExposeMissingSourceEvidenceStateWhenReviewerDirectiveHasNoSourceUrls() throws Exception {
        Long taskId = createAndExecuteTask(true);

        TaskWorkflowEvent decisionEvent = latestEvent(taskId, WorkflowEventType.ORCHESTRATION_DECISION_RECORDED);
        JsonNode decisionPayload = objectMapper.readTree(decisionEvent.getPayload());
        assertThat(decisionPayload.path("decision").path("evidenceState").asText()).isEqualTo("MISSING_SOURCE");
        assertThat(decisionPayload.path("policyResult").path("evidenceState").asText()).isEqualTo("MISSING_SOURCE");
        assertThat(decisionPayload.path("mutation").path("evidenceState").asText()).isEqualTo("MISSING_SOURCE");

        JsonNode replay = getReplay(taskId);
        assertThat(replay.path("data").path("timeline"))
                .anySatisfy(event -> {
                    if ("ORCHESTRATION_DECISION_RECORDED".equals(event.path("eventType").asText())) {
                        assertThat(event.path("summary").asText()).contains("Orchestrator");
                    }
                });
    }

    private Long createAndExecuteTask(boolean omitSourceUrls) throws Exception {
        reviewerShouldOmitSourceUrls = omitSourceUrls;
        CreateTaskRequest request = new CreateTaskRequest();
        request.setTaskName(omitSourceUrls ? "orchestration missing source smoke" : "orchestration p1 smoke");
        request.setSubjectProduct("Enterprise AI workspace");
        request.setCompetitorNames(List.of("Notion AI"));
        request.setCompetitorUrls(List.of("https://www.notion.so/product/ai"));
        request.setAnalysisDimensions(List.of("Product capability", "Pricing strategy"));
        request.setSourceScope(List.of("Official site", "Pricing page"));

        ApiResponse<?> createResponse = restTemplate.postForObject(taskUrl("/create"), request, ApiResponse.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> createData = (Map<String, Object>) createResponse.getData();
        Long taskId = ((Number) createData.get("id")).longValue();

        restTemplate.postForObject(taskUrl("/" + taskId + "/execute"), null, ApiResponse.class);
        consumeLatestTaskExecutionRequested(taskId);
        waitForTaskStatus(taskId, AnalysisTaskStatus.SUCCESS);
        return taskId;
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
        new WorkflowEventConsumer(objectMapper, workflowEventOutboxService, analysisTaskRunner)
                .onMessage(objectMapper.writeValueAsString(workflowEvent));
    }

    private TaskWorkflowEvent waitForWorkflowEvent(Long taskId, WorkflowEventType eventType) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(5).toMillis();
        while (System.currentTimeMillis() < deadline) {
            Optional<TaskWorkflowEvent> eventOptional = taskWorkflowEventRepository.findAll().stream()
                    .filter(event -> taskId.equals(event.getTaskId()))
                    .filter(event -> eventType == event.getEventType())
                    .filter(event -> !TaskWorkflowEvent.STATUS_CONSUMED.equals(event.getDeliveryStatus()))
                    .max(java.util.Comparator.comparing(TaskWorkflowEvent::getId));
            if (eventOptional.isPresent()) {
                return eventOptional.get();
            }
            Thread.sleep(100);
        }
        throw new AssertionError("未等到工作流事件 " + eventType + ", taskId=" + taskId);
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
        throw new AssertionError("任务未进入预期状态 " + expectedStatus + ", 当前状态=" + latest.getStatus());
    }

    private TaskWorkflowEvent latestEvent(Long taskId, WorkflowEventType eventType) {
        return taskWorkflowEventRepository.findAll().stream()
                .filter(event -> taskId.equals(event.getTaskId()))
                .filter(event -> eventType == event.getEventType())
                .max(java.util.Comparator.comparing(TaskWorkflowEvent::getId))
                .orElseThrow(() -> new AssertionError("缺少事件 " + eventType + ", taskId=" + taskId));
    }

    private JsonNode getReplay(Long taskId) throws Exception {
        String rawResponse = restTemplate.getForObject(taskUrl("/" + taskId + "/replay"), String.class);
        JsonNode response = objectMapper.readTree(rawResponse);
        assertThat(response.path("code").asInt()).isEqualTo(200);
        return response;
    }

    private Map<String, Object> readMap(String rawJson) throws Exception {
        if (rawJson == null || rawJson.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(rawJson, new TypeReference<>() {
        });
    }

    private List<String> readStringList(String rawJson) throws Exception {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of();
        }
        return objectMapper.readValue(rawJson, new TypeReference<>() {
        });
    }

    private void configureRuntimeInfrastructure() {
        when(taskSnapshotCacheService.getTaskSnapshot(anyLong())).thenReturn(Optional.empty());
        when(taskSnapshotCacheService.getCachedNodeOutputs(anyLong())).thenReturn(Map.of());
        when(taskExecutionLockService.tryAcquireTaskExecutionLock(anyLong(), anyString(), any()))
                .thenReturn(true);
        when(taskExecutionLockService.releaseTaskExecutionLock(anyLong(), anyString()))
                .thenReturn(true);
        when(taskExecutionLockService.tryAcquireNodeExecutionLock(anyLong(), anyString(), anyString(), any()))
                .thenReturn(true);
        when(taskExecutionLockService.releaseNodeExecutionLock(anyLong(), anyString(), anyString()))
                .thenReturn(true);
    }

    private void configureSourceDiscovery() {
        SourceCandidate candidate = SourceCandidate.builder()
                .url("https://www.notion.so/product/ai")
                .title("Notion AI Product")
                .sourceType("DOCS")
                .discoveryMethod("PLANNED")
                .domain("www.notion.so")
                .verified(true)
                .build();
        SourcePlan sourcePlan = SourcePlan.builder()
                .sourceType("DOCS")
                .urls(List.of("https://www.notion.so/product/ai"))
                .notes("固定 smoke 来源")
                .candidates(List.of(candidate))
                .build();
        when(sourceDiscoveryService.discover(anyString(), any(), any())).thenReturn(List.of(sourcePlan));
        when(sourceDiscoveryService.discoverForPreview(anyString(), any(), any())).thenReturn(List.of(sourcePlan));
        when(promptTemplateService.buildSearchQueries(anyString(), anyString(), anyString()))
                .thenReturn(List.of("Notion AI pricing official"));
    }

    private void configureAgentStubs() {
        doAnswer(invocation -> AgentResult.success("""
                {"sourceUrls":["https://www.notion.so/product/ai"],"successCollected":1}
                """, "采集完成")).when(collectorAgent).execute(any(AgentContext.class));
        doAnswer(invocation -> AgentResult.success("""
                {"competitors":[{"competitorName":"Notion AI","sourceUrls":["https://www.notion.so/product/ai"]}]}
                """, "抽取完成")).when(extractorAgent).execute(any(AgentContext.class));
        doAnswer(invocation -> AgentResult.success("""
                {"summary":"analysis done","sourceUrls":["https://www.notion.so/product/ai"]}
                """, "分析完成")).when(analyzerAgent).execute(any(AgentContext.class));
        doAnswer(invocation -> AgentResult.success("""
                {"report":"draft","sourceUrls":["https://www.notion.so/product/ai"]}
                """, "报告生成完成")).when(writerAgent).execute(any(AgentContext.class));
        doAnswer(invocation -> {
            AgentContext context = invocation.getArgument(0);
            if (context.getCurrentNodeName().startsWith("quality_check_revision_patch_v")) {
                return AgentResult.success("""
                        {"reviewStage":"final","passed":true,"requiresHumanIntervention":false,"sourceUrls":["https://www.notion.so/pricing"]}
                        """, "动态补丁复核通过，任务进入成功收口");
            }
            if ("quality_check_final".equals(context.getCurrentNodeName())) {
                return AgentResult.success(finalReviewFailedOutput(), "终审失败，进入 Orchestrator 回流");
            }
            return AgentResult.success("""
                    {"reviewStage":"initial","passed":false,"requiresHumanIntervention":false,"autoRewriteAllowed":true}
                    """, "初审要求改写");
        }).when(reviewerAgent).execute(any(AgentContext.class));
    }

    private String finalReviewFailedOutput() {
        String sourceUrlsField = reviewerShouldOmitSourceUrls
                ? ""
                : """
                  "sourceUrls":["https://www.notion.so/pricing"],
                """;
        String directiveSourceUrlsField = reviewerShouldOmitSourceUrls
                ? ""
                : """
                    "sourceUrls":["https://www.notion.so/pricing"],
                """;
        return """
                {
                  "reviewStage":"final",
                  "passed":false,
                  "requiresHumanIntervention":false,
                  "summary":"缺少官网定价证据，需要补源后复核",
                  %s
                  "revisionDirectives":[
                    {
                      "category":"EVIDENCE_GAP",
                      "actionType":"SUPPLEMENT_EVIDENCE",
                      "summary":"补充官网定价证据",
                      "searchQueries":["Notion AI pricing official"],
                      %s
                      "expectedOutcome":"补齐定价证据后重新复核"
                    }
                  ]
                }
                """.formatted(sourceUrlsField, directiveSourceUrlsField);
    }

    private String taskUrl(String path) {
        return "http://localhost:" + port + "/api/task" + path;
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
