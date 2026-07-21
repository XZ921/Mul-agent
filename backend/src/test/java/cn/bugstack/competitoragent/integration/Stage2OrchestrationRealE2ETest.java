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
import cn.bugstack.competitoragent.config.AiProviderProperties;
import cn.bugstack.competitoragent.config.PlaywrightBrowserManager;
import cn.bugstack.competitoragent.config.RocketMqProperties;
import cn.bugstack.competitoragent.llm.PromptTemplateService;
import cn.bugstack.competitoragent.model.dto.CreateTaskRequest;
import cn.bugstack.competitoragent.model.entity.AiCallAuditRecord;
import cn.bugstack.competitoragent.model.entity.AnalysisTask;
import cn.bugstack.competitoragent.model.entity.Report;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.model.entity.TaskWorkflowEvent;
import cn.bugstack.competitoragent.model.enums.AnalysisTaskStatus;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.orchestration.DecisionPolicyRuleSet;
import cn.bugstack.competitoragent.orchestration.OrchestrationDecisionAuditTrace;
import cn.bugstack.competitoragent.orchestration.OrchestratorDecisionMode;
import cn.bugstack.competitoragent.orchestration.OrchestratorDecisionProperties;
import cn.bugstack.competitoragent.report.ExportPackageService;
import cn.bugstack.competitoragent.report.ReportExportRenderer;
import cn.bugstack.competitoragent.repository.AiCallAuditRecordRepository;
import cn.bugstack.competitoragent.repository.AnalysisTaskRepository;
import cn.bugstack.competitoragent.repository.OrganizationQuotaSnapshotRepository;
import cn.bugstack.competitoragent.repository.ReportRepository;
import cn.bugstack.competitoragent.repository.TaskNodeRepository;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

/**
 * Task 09 D 层真实任务生命周期 E2E。
 * <p>
 * 该测试通过真实 HTTP API 创建和执行任务，并使用真实 Spring MVC、H2、DAG、Runtime、Policy、
 * Trace、outbox、report/export/replay 以及真实 Orchestrator Provider。采集、浏览器、RocketMQ 等
 * 阶段二非目标基础设施使用固定替身，确保唯一一次真实模型周期只验证 Orchestrator 生产链路。
 */
@SpringBootTest(
        classes = CompetitorAgentApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude=org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration",
                "rocketmq.enabled=false"
        }
)
@ActiveProfiles("phase5-integration")
@Import(Stage2OrchestrationRealE2ETest.SyncAsyncTestConfig.class)
@EnabledIfEnvironmentVariable(named = "RUN_STAGE2_ACCEPTANCE", matches = "true")
class Stage2OrchestrationRealE2ETest {

    private static final String SOURCE_URL = "https://www.notion.so/pricing";
    private static final Duration TASK_DEADLINE = Duration.ofSeconds(30);
    private static final Set<TaskNodeStatus> CLOSED_NODE_STATUSES = Set.of(
            TaskNodeStatus.SUCCESS,
            TaskNodeStatus.SUCCESS_DEGRADED,
            TaskNodeStatus.COMPENSATED,
            TaskNodeStatus.FAILED,
            TaskNodeStatus.SKIPPED,
            TaskNodeStatus.WAITING_INTERVENTION);

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AnalysisTaskRepository taskRepository;
    @Autowired
    private TaskNodeRepository nodeRepository;
    @Autowired
    private TaskPlanRepository taskPlanRepository;
    @Autowired
    private TaskWorkflowEventRepository workflowEventRepository;
    @Autowired
    private ReportRepository reportRepository;
    @Autowired
    private AiCallAuditRecordRepository auditRepository;
    @Autowired
    private OrganizationQuotaSnapshotRepository quotaRepository;
    @Autowired
    private ExportPackageService exportPackageService;
    @Autowired
    private AnalysisTaskRunner analysisTaskRunner;
    @Autowired
    private WorkflowEventOutboxService workflowEventOutboxService;
    @Autowired
    private OrchestratorDecisionProperties decisionProperties;
    @Autowired
    private DecisionPolicyRuleSet ruleSet;
    @Autowired
    private AiProviderProperties providerProperties;

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

    @BeforeEach
    void setUp() {
        // Task 6 只允许一个真实 Primary 生命周期；这里固定所有模型参数并关闭 Shadow，避免扩大调用面。
        decisionProperties.setMode(OrchestratorDecisionMode.LLM_PRIMARY);
        decisionProperties.setFallbackToRule(true);
        decisionProperties.setModelTemperature(0.0d);
        decisionProperties.setLlmTimeoutMs(4000L);
        decisionProperties.setMaxParseRetries(1);
        decisionProperties.getShadow().setEnabled(false);
        assertEnvironmentReady();

        doNothing().when(rocketMqProperties).validateForExecution();
        configureRuntimeInfrastructure();
        configureSourceDiscovery();
        configureControlledAgents();
    }

    @AfterEach
    void restoreProductionDefaults() {
        // 无论验收成功还是失败，都恢复生产默认模式，禁止测试配置泄漏到后续任务。
        decisionProperties.setMode(OrchestratorDecisionMode.RULE_ONLY);
        decisionProperties.getShadow().setEnabled(false);
    }

    @Test
    void shouldCompleteOneBoundedRealOrchestratorTaskLifecycle() throws Exception {
        Long taskId = createAndExecuteTaskThroughApi();
        AnalysisTask task = waitForClosedTask(taskId);
        List<TaskNode> nodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId);
        List<TaskPlan> plans = taskPlanRepository.findByTaskIdOrderByPlanVersionAsc(taskId);
        List<TaskWorkflowEvent> decisionEvents = decisionEvents(taskId);

        // 稳定输入只允许终审缺口触发一个周期；出现额外周期即说明建议构造或循环边界发生漂移。
        assertThat(decisionEvents).hasSize(1);
        assertThat(decisionEvents.size()).isLessThanOrEqualTo(ruleSet.getMaxAutoDecisions());
        JsonNode decisionPayload = objectMapper.readTree(decisionEvents.get(0).getPayload());
        printSafeCycleEvidence(decisionPayload);
        JsonNode primaryAttempt = requireExecutablePrimaryAttempt(decisionPayload);
        String decisionId = primaryAttempt.at("/decision/decisionId").asText();
        String traceId = primaryAttempt.at("/decision/decisionMetadata/aiAuditTraceId").asText();

        assertBoundedTerminalState(task, nodes, plans);
        assertDecisionTrace(decisionPayload, primaryAttempt, decisionId, traceId);
        List<AiCallAuditRecord> linkedAudits = assertAiAudit(traceId);
        assertReadModelsHaveNoModelSideEffects(taskId, decisionId, traceId);
        printSafeEvidence(task, nodes, plans, decisionEvents, linkedAudits, traceId);
    }

    /**
     * 通过随机端口上的真实控制器创建并执行任务，随后消费测试环境中的 outbox 请求。
     * 这里不会在失败后重建任务或重新执行，保证 Task 6 的生命周期只有一次。
     */
    private Long createAndExecuteTaskThroughApi() throws Exception {
        CreateTaskRequest request = new CreateTaskRequest();
        request.setTaskName("Task 09 Stage 2 real Orchestrator E2E");
        request.setSubjectProduct("Enterprise AI workspace");
        request.setCompetitorNames(List.of("Notion AI"));
        request.setCompetitorUrls(List.of("https://www.notion.so/product/ai"));
        request.setAnalysisDimensions(List.of("Product capability", "Pricing strategy"));
        request.setSourceScope(List.of("Official site", "Pricing page"));

        ApiResponse<?> createResponse = restTemplate.postForObject(taskUrl("/create"), request, ApiResponse.class);
        assertThat(createResponse).isNotNull();
        assertThat(createResponse.getCode()).isEqualTo(200);
        assertThat(createResponse.getData()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> createData = (Map<String, Object>) createResponse.getData();
        Long taskId = ((Number) createData.get("id")).longValue();

        ApiResponse<?> executeResponse = restTemplate.postForObject(
                taskUrl("/" + taskId + "/execute"), null, ApiResponse.class);
        assertThat(executeResponse).isNotNull();
        assertThat(executeResponse.getCode()).isEqualTo(200);
        consumeLatestTaskExecutionRequested(taskId);
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

    private AnalysisTask waitForClosedTask(Long taskId) throws InterruptedException {
        long deadline = System.nanoTime() + TASK_DEADLINE.toNanos();
        while (System.nanoTime() < deadline) {
            AnalysisTask task = taskRepository.findById(taskId).orElseThrow();
            if (task.getStatus() == AnalysisTaskStatus.SUCCESS
                    || task.getStatus() == AnalysisTaskStatus.FAILED
                    || task.getStatus() == AnalysisTaskStatus.STOPPED) {
                return task;
            }
            Thread.sleep(100L);
        }
        AnalysisTask task = taskRepository.findById(taskId).orElseThrow();
        List<TaskNode> nodes = nodeRepository.findByTaskIdOrderByExecutionOrderAsc(taskId);
        List<TaskPlan> plans = taskPlanRepository.findByTaskIdOrderByPlanVersionAsc(taskId);
        throw new AssertionError("Task 6 deadline 超时: taskId=" + taskId
                + ", taskStatus=" + task.getStatus()
                + ", nodeStatuses=" + nodeStatusSummary(nodes)
                + ", planVersions=" + plans.stream().map(TaskPlan::getPlanVersion).toList()
                + ", decisionCycles=" + decisionEvents(taskId).size());
    }

    private void assertBoundedTerminalState(AnalysisTask task, List<TaskNode> nodes, List<TaskPlan> plans) {
        // 本固定来源场景必须自动闭环成功；WAITING_INTERVENTION 只属于其他诚实停点场景，不应掩盖本用例回归。
        assertThat(task.getStatus()).isEqualTo(AnalysisTaskStatus.SUCCESS);
        assertThat(nodes).isNotEmpty().allSatisfy(node ->
                assertThat(node.getStatus())
                        .as("节点必须进入终态或等待人工: " + node.getNodeName())
                        .isIn(CLOSED_NODE_STATUSES));
        assertThat(nodes).noneMatch(node -> node.getStatus() == TaskNodeStatus.RUNNING);
        assertThat(nodes.stream().filter(TaskNode::isDynamicNode).toList()).isNotEmpty();

        int maxPlanVersion = 1 + ruleSet.getMaxAutoDecisions();
        assertThat(plans).isNotEmpty().hasSizeLessThanOrEqualTo(maxPlanVersion);
        assertThat(plans).allSatisfy(plan -> assertThat(plan.getPlanVersion()).isLessThanOrEqualTo(maxPlanVersion));
        assertThat(task.getCurrentPlanVersion()).isLessThanOrEqualTo(maxPlanVersion);

        long dynamicBranchCount = nodes.stream()
                .filter(TaskNode::isDynamicNode)
                .map(TaskNode::getBranchKey)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .count();
        assertThat(dynamicBranchCount).isLessThanOrEqualTo(ruleSet.getMaxDynamicBranchesPerSection());
    }

    private JsonNode requireExecutablePrimaryAttempt(JsonNode payload) {
        assertThat(payload.path("traceSchemaVersion").asText())
                .isEqualTo(OrchestrationDecisionAuditTrace.SCHEMA_VERSION);
        for (JsonNode attempt : payload.at("/audit/attempts")) {
            if ("LLM_PRIMARY".equals(attempt.at("/decision/decisionOrigin").asText())
                    && attempt.at("/policyResult/allowed").asBoolean(false)
                    && "READY".equals(attempt.path("runtimeStatus").asText())
                    && "APPEND_NODES".equals(attempt.at("/mutationSummary/mutationType").asText())) {
                return attempt;
            }
        }
        throw new AssertionError("没有形成 Policy allowed、READY、APPEND_NODES 的真实 LLM_PRIMARY attempt");
    }

    /**
     * 真实验收失败后禁止靠重跑恢复事实，因此在硬断言前输出最小安全摘要。
     * 这里只记录枚举状态和数量，不输出 prompt、response、reason 或 Provider 异常正文。
     */
    private void printSafeCycleEvidence(JsonNode payload) {
        JsonNode attempts = payload.at("/audit/attempts");
        for (int index = 0; index < attempts.size(); index++) {
            JsonNode attempt = attempts.get(index);
            System.out.printf(
                    "STAGE2_E2E_ATTEMPT|index=%d|origin=%s|decisionType=%s|actionType=%s|policy=%s|runtime=%s|mutation=%s%n",
                    index,
                    attempt.at("/decision/decisionOrigin").asText("NONE"),
                    attempt.at("/decision/decisionType").asText("NONE"),
                    attempt.at("/decision/actionType").asText("NONE"),
                    attempt.at("/policyResult/allowed").asBoolean(false),
                    attempt.path("runtimeStatus").asText("NONE"),
                    attempt.at("/mutationSummary/mutationType").asText("NONE"));
        }
        System.out.printf(
                "STAGE2_E2E_CYCLE|mode=%s|attempts=%d|finalDecisions=%d|policyFallback=%s|llmFailure=%s%n",
                payload.at("/audit/mode").asText("NONE"),
                attempts.size(),
                payload.at("/audit/finalDecisionIds").size(),
                payload.at("/audit/policyFallbackUsed").asBoolean(false),
                payload.at("/audit/llmFailure/type").asText("NONE"));
    }

    private void assertDecisionTrace(JsonNode payload,
                                     JsonNode primaryAttempt,
                                     String decisionId,
                                     String traceId) throws Exception {
        assertThat(decisionId).isNotBlank();
        assertThat(traceId).isNotBlank().hasSizeLessThanOrEqualTo(50);
        assertThat(primaryAttempt.at("/decision/decisionMetadata/promptHash").asText()).isNotBlank();
        assertThat(primaryAttempt.at("/decision/decisionMetadata/llmResponseHash").asText()).isNotBlank();
        assertThat(primaryAttempt.at("/decision/sourceUrls").isArray()).isTrue();
        assertThat(primaryAttempt.at("/decision/sourceUrls")).isNotEmpty();
        assertThat(primaryAttempt.at("/decision/sourceUrls").toString()).contains(SOURCE_URL);
        assertThat(payload.at("/audit/finalDecisionIds").toString()).contains(decisionId);
        assertThat(payload.at("/audit/runtimeState/currentDecisionCount").asInt())
                .isLessThanOrEqualTo(ruleSet.getMaxAutoDecisions());

        TaskWorkflowEvent checkpoint = latestEvent(
                primaryAttempt.at("/decision/taskId").asLong(),
                WorkflowEventType.ORCHESTRATION_CHECKPOINT_UPDATED);
        JsonNode checkpointPayload = objectMapper.readTree(checkpoint.getPayload());
        assertThat(checkpointPayload.at("/checkpoint/decisionCount").asInt())
                .isLessThanOrEqualTo(ruleSet.getMaxAutoDecisions());
    }

    private List<AiCallAuditRecord> assertAiAudit(String traceId) {
        List<AiCallAuditRecord> linkedAudits = auditRepository.findByTraceIdOrderByCreatedAtAsc(traceId);
        assertThat(linkedAudits).isNotEmpty().allSatisfy(audit -> {
            assertThat(audit.getProviderKey()).isEqualToIgnoringCase(providerProperties.getActiveProvider());
            assertThat(audit.getModelName()).isEqualTo(decisionProperties.getModelName());
            assertThat(audit.getEstimatedInputTokens()).isNotNull().isGreaterThan(0);
            assertThat(audit.getTotalTokens()).isNotNull().isGreaterThanOrEqualTo(0);
        });
        return linkedAudits;
    }

    private void assertReadModelsHaveNoModelSideEffects(Long taskId,
                                                        String decisionId,
                                                        String traceId) throws Exception {
        long auditsBeforeRead = auditRepository.count();
        Map<Long, String> quotaBeforeRead = quotaFingerprint();

        String reportRaw = restTemplate.getForObject(reportUrl("/" + taskId), String.class);
        String replayRaw = restTemplate.getForObject(taskUrl("/" + taskId + "/replay"), String.class);
        ResponseEntity<byte[]> markdownResponse = restTemplate.getForEntity(
                reportUrl("/" + taskId + "/export"), byte[].class);
        ResponseEntity<byte[]> htmlResponse = restTemplate.getForEntity(
                reportUrl("/" + taskId + "/export/html"), byte[].class);
        ReportExportRenderer.RenderedExportPackage jsonExport =
                exportPackageService.createExportPackage(taskId, "JSON");

        JsonNode report = objectMapper.readTree(reportRaw);
        JsonNode replay = objectMapper.readTree(replayRaw);
        JsonNode json = objectMapper.readTree(jsonExport.content());
        String markdown = new String(requireBody(markdownResponse), StandardCharsets.UTF_8);
        String html = new String(requireBody(htmlResponse), StandardCharsets.UTF_8);

        assertThat(report.path("code").asInt()).isEqualTo(200);
        assertThat(replay.path("code").asInt()).isEqualTo(200);
        assertThat(report.at("/data/orchestrationDecision/decisionId").asText()).isEqualTo(decisionId);
        assertThat(report.at("/data/orchestrationDecision/decisionOrigin").asText()).isEqualTo("LLM_PRIMARY");
        assertThat(report.at("/data/orchestrationDecision/aiAuditTraceId").asText()).isEqualTo(traceId);
        assertThat(replay.at("/data/latestOrchestrationDecision/decisionId").asText()).isEqualTo(decisionId);
        assertThat(replay.at("/data/latestOrchestrationDecision/aiAuditTraceId").asText()).isEqualTo(traceId);
        assertThat(report.at("/data/orchestrationDecision/sourceUrls")).isNotEmpty();
        assertThat(replay.at("/data/latestOrchestrationDecision/sourceUrls")).isNotEmpty();
        assertThat(markdown).contains(decisionId, "LLM_PRIMARY", traceId, SOURCE_URL);
        assertThat(html).contains(decisionId, "LLM_PRIMARY", traceId, SOURCE_URL);
        assertThat(json.at("/orchestrationDecision/decisionId").asText()).isEqualTo(decisionId);
        assertThat(json.at("/orchestrationDecision/aiAuditTraceId").asText()).isEqualTo(traceId);

        String publicEvidence = reportRaw + replayRaw + markdown + html
                + new String(jsonExport.content(), StandardCharsets.UTF_8);
        assertThat(publicEvidence)
                .doesNotContain("systemPrompt")
                .doesNotContain("userPrompt")
                .doesNotContain("rawResponse")
                .doesNotContain("Authorization")
                .doesNotContain("apiKey");
        assertThat(auditRepository.count()).isEqualTo(auditsBeforeRead);
        assertThat(quotaFingerprint()).isEqualTo(quotaBeforeRead);
    }

    private byte[] requireBody(ResponseEntity<byte[]> response) {
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private void configureRuntimeInfrastructure() {
        when(taskSnapshotCacheService.getTaskSnapshot(anyLong())).thenReturn(Optional.empty());
        when(taskSnapshotCacheService.getCachedNodeOutputs(anyLong())).thenReturn(Map.of());
        when(taskExecutionLockService.tryAcquireTaskExecutionLock(anyLong(), anyString(), any())).thenReturn(true);
        when(taskExecutionLockService.releaseTaskExecutionLock(anyLong(), anyString())).thenReturn(true);
        when(taskExecutionLockService.tryAcquireNodeExecutionLock(anyLong(), anyString(), anyString(), any()))
                .thenReturn(true);
        when(taskExecutionLockService.releaseNodeExecutionLock(anyLong(), anyString(), anyString())).thenReturn(true);
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
                .notes("Task 6 固定可信来源")
                .candidates(List.of(candidate))
                .build();
        when(sourceDiscoveryService.discover(anyString(), any(), any())).thenReturn(List.of(sourcePlan));
        when(sourceDiscoveryService.discoverForPreview(anyString(), any(), any())).thenReturn(List.of(sourcePlan));
        when(promptTemplateService.buildSearchQueries(anyString(), anyString(), anyString()))
                .thenReturn(List.of("Notion AI pricing official"));
    }

    /**
     * 上游 Agent 只输出固定事实，不调用它们各自的外部模型或采集网络。
     * 唯一缺口由 final reviewer 产生，动态分支 reviewer 随后明确通过，使任务能够有界收口。
     */
    private void configureControlledAgents() {
        doAnswer(invocation -> AgentResult.success("""
                {"sourceUrls":["https://www.notion.so/product/ai"],"successCollected":1}
                """, "采集完成")).when(collectorAgent).execute(any(AgentContext.class));
        doAnswer(invocation -> AgentResult.success("""
                {"competitors":[{"competitorName":"Notion AI","sourceUrls":["https://www.notion.so/product/ai"]}],"issueFlags":[]}
                """, "抽取完成")).when(extractorAgent).execute(any(AgentContext.class));
        doAnswer(invocation -> AgentResult.success("""
                {"summary":"analysis done","sourceUrls":["https://www.notion.so/product/ai"],"missingAnalysisDimensions":[],"analysisGapSeverity":"NONE"}
                """, "分析完成")).when(analyzerAgent).execute(any(AgentContext.class));
        doAnswer(invocation -> {
            AgentContext context = invocation.getArgument(0);
            persistControlledReport(context.getTaskId());
            return AgentResult.success("""
                    {"content":"# Task 6 report","sourceUrls":["https://www.notion.so/pricing"],"citationGapSeverity":"NONE","sectionCitationGaps":[]}
                    """, "报告生成完成");
        }).when(writerAgent).execute(any(AgentContext.class));
        doAnswer(invocation -> {
            AgentContext context = invocation.getArgument(0);
            if (context.getCurrentNodeName().startsWith("quality_check_revision_patch_v")) {
                return AgentResult.success("""
                        {"reviewStage":"final","passed":true,"requiresHumanIntervention":false,"sourceUrls":["https://www.notion.so/pricing"]}
                        """, "动态补丁复核通过");
            }
            if ("quality_check_final".equals(context.getCurrentNodeName())) {
                return AgentResult.success(finalReviewGap(), "终审发现可信来源缺口，进入真实 Orchestrator");
            }
            return AgentResult.success("""
                    {"reviewStage":"initial","passed":false,"requiresHumanIntervention":false,"autoRewriteAllowed":true,"sourceUrls":["https://www.notion.so/product/ai"]}
                    """, "初审要求改写");
        }).when(reviewerAgent).execute(any(AgentContext.class));
    }

    private void persistControlledReport(Long taskId) {
        Report report = reportRepository.findByTaskId(taskId)
                .orElse(Report.builder().taskId(taskId).build());
        report.setTitle("Task 09 Stage 2 E2E report");
        report.setContent("# Task 6 report\n\nSource: " + SOURCE_URL);
        report.setSummary("真实 Orchestrator 生命周期验收报告");
        report.setEvidenceCount(1);
        report.setWriterEvidenceState("FULL_SOURCE");
        report.setCitationGapSeverity("NONE");
        report.setWriterSourceUrls("[\"" + SOURCE_URL + "\"]");
        reportRepository.save(report);
    }

    private String finalReviewGap() {
        return """
                {
                  "reviewStage":"final",
                  "passed":false,
                  "requiresHumanIntervention":false,
                  "summary":"定价章节已有官网来源，但引用覆盖仍需补强",
                  "sourceUrls":["https://www.notion.so/pricing"],
                  "revisionDirectives":[
                    {
                      "category":"EVIDENCE_GAP",
                      "actionType":"SUPPLEMENT_EVIDENCE",
                      "summary":"补充官网定价证据并重新复核",
                      "searchQueries":["Notion AI pricing official"],
                      "sourceUrls":["https://www.notion.so/pricing"],
                      "expectedOutcome":"补齐定价证据后重新复核"
                    }
                  ]
                }
                """;
    }

    private void assertEnvironmentReady() {
        assertThat(providerProperties.getActiveProvider()).isNotBlank();
        assertThat(providerProperties.getModelName()).isNotBlank();
        assertThat(providerProperties.getActiveProviderConfig().getApiKey()).isNotBlank();
        assertThat(URI.create(providerProperties.getActiveProviderConfig().getUrl()).getScheme())
                .isEqualToIgnoringCase("https");
        assertThat(decisionProperties.getMode()).isEqualTo(OrchestratorDecisionMode.LLM_PRIMARY);
        assertThat(decisionProperties.isFallbackToRule()).isTrue();
        assertThat(decisionProperties.getModelTemperature()).isZero();
        assertThat(decisionProperties.getLlmTimeoutMs()).isEqualTo(4000L);
        assertThat(decisionProperties.getMaxParseRetries()).isEqualTo(1);
        assertThat(decisionProperties.getShadow().isEnabled()).isFalse();
    }

    private TaskWorkflowEvent waitForWorkflowEvent(Long taskId,
                                                   WorkflowEventType eventType) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<TaskWorkflowEvent> event = workflowEventRepository.findAll().stream()
                    .filter(item -> taskId.equals(item.getTaskId()))
                    .filter(item -> eventType == item.getEventType())
                    .filter(item -> !TaskWorkflowEvent.STATUS_CONSUMED.equals(item.getDeliveryStatus()))
                    .max(Comparator.comparing(TaskWorkflowEvent::getId));
            if (event.isPresent()) {
                return event.get();
            }
            Thread.sleep(100L);
        }
        throw new AssertionError("未等到工作流事件 " + eventType + ", taskId=" + taskId);
    }

    private TaskWorkflowEvent latestEvent(Long taskId, WorkflowEventType eventType) {
        return workflowEventRepository.findAll().stream()
                .filter(item -> taskId.equals(item.getTaskId()))
                .filter(item -> eventType == item.getEventType())
                .max(Comparator.comparing(TaskWorkflowEvent::getId))
                .orElseThrow(() -> new AssertionError("缺少事件 " + eventType + ", taskId=" + taskId));
    }

    private List<TaskWorkflowEvent> decisionEvents(Long taskId) {
        return workflowEventRepository.findAll().stream()
                .filter(item -> taskId.equals(item.getTaskId()))
                .filter(item -> item.getEventType() == WorkflowEventType.ORCHESTRATION_DECISION_RECORDED)
                .sorted(Comparator.comparing(TaskWorkflowEvent::getId))
                .toList();
    }

    private Map<Long, String> quotaFingerprint() {
        Map<Long, String> values = new LinkedHashMap<>();
        quotaRepository.findAll().forEach(snapshot -> values.put(
                snapshot.getId(),
                snapshot.getUsedValue() + ":" + snapshot.getReservedValue() + ":" + snapshot.getLimitValue()));
        return values;
    }

    private Map<String, String> nodeStatusSummary(List<TaskNode> nodes) {
        Map<String, String> summary = new LinkedHashMap<>();
        nodes.forEach(node -> summary.put(
                node.getNodeName(),
                node.getStatus() == null ? "NULL" : node.getStatus().name()));
        return summary;
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

    private void printSafeEvidence(AnalysisTask task,
                                   List<TaskNode> nodes,
                                   List<TaskPlan> plans,
                                   List<TaskWorkflowEvent> decisionEvents,
                                   List<AiCallAuditRecord> audits,
                                   String traceId) {
        int actualInput = audits.stream().map(AiCallAuditRecord::getInputTokens)
                .filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).sum();
        int actualOutput = audits.stream().map(AiCallAuditRecord::getOutputTokens)
                .filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).sum();
        int actualTotal = audits.stream().map(AiCallAuditRecord::getTotalTokens)
                .filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).sum();
        int estimatedInput = audits.stream().map(AiCallAuditRecord::getEstimatedInputTokens)
                .filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).sum();
        System.out.printf(
                "STAGE2_E2E|taskId=%d|terminal=%s|nodes=%d|plans=%d|cycles=%d|audits=%d|traceId=%s|input=%d|output=%d|total=%d|estimatedInput=%d%n",
                task.getId(), task.getStatus(), nodes.size(), plans.size(), decisionEvents.size(), audits.size(),
                traceId, actualInput, actualOutput, actualTotal, estimatedInput);
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
