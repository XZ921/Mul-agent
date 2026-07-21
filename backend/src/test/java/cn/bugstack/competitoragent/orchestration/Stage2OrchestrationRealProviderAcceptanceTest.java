package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.CompetitorAgentApplication;
import cn.bugstack.competitoragent.config.AiProviderProperties;
import cn.bugstack.competitoragent.config.PlaywrightBrowserManager;
import cn.bugstack.competitoragent.context.AgentContextAssembler;
import cn.bugstack.competitoragent.governance.GovernanceDefaults;
import cn.bugstack.competitoragent.model.dto.OrchestrationDecisionSummary;
import cn.bugstack.competitoragent.model.dto.ReportResponse;
import cn.bugstack.competitoragent.model.dto.TaskReplayResponse;
import cn.bugstack.competitoragent.model.entity.AiCallAuditRecord;
import cn.bugstack.competitoragent.model.entity.OrganizationQuotaSnapshot;
import cn.bugstack.competitoragent.model.entity.Report;
import cn.bugstack.competitoragent.model.entity.TaskNode;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.model.entity.TaskWorkflowEvent;
import cn.bugstack.competitoragent.model.enums.AgentType;
import cn.bugstack.competitoragent.model.enums.TaskNodeStatus;
import cn.bugstack.competitoragent.repository.AiCallAuditRecordRepository;
import cn.bugstack.competitoragent.repository.OrganizationQuotaSnapshotRepository;
import cn.bugstack.competitoragent.repository.ReportExportRecordRepository;
import cn.bugstack.competitoragent.repository.ReportRepository;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import cn.bugstack.competitoragent.repository.TaskWorkflowEventRepository;
import cn.bugstack.competitoragent.report.ExportPackageService;
import cn.bugstack.competitoragent.report.ReportExportRenderer;
import cn.bugstack.competitoragent.report.ReportService;
import cn.bugstack.competitoragent.task.TaskExecutionLockService;
import cn.bugstack.competitoragent.task.TaskReplayProjectionService;
import cn.bugstack.competitoragent.task.TaskSnapshotCacheService;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 09 C 层真实 Provider 验收。
 * 该类只在显式环境开关下运行，输出仅包含稳定状态、hash、traceId、token 数值和 fixture 来源。
 */
@SpringBootTest(
        classes = CompetitorAgentApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.autoconfigure.exclude=org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration",
                "rocketmq.enabled=false"
        }
)
@ActiveProfiles("phase5-integration")
@EnabledIfEnvironmentVariable(named = "RUN_STAGE2_ACCEPTANCE", matches = "true")
class Stage2OrchestrationRealProviderAcceptanceTest {

    @Autowired
    private OrchestrationRuntimeDecisionService runtimeDecisionService;
    @Autowired
    private OrchestrationTraceService traceService;
    @Autowired
    private OrchestratorDecisionProperties decisionProperties;
    @Autowired
    private DecisionPolicyRuleSet ruleSet;
    @Autowired
    private AiProviderProperties providerProperties;
    @Autowired
    private TaskWorkflowEventRepository workflowEventRepository;
    @Autowired
    private TaskPlanRepository taskPlanRepository;
    @Autowired
    private ReportRepository reportRepository;
    @Autowired
    private ReportExportRecordRepository reportExportRecordRepository;
    @Autowired
    private AiCallAuditRecordRepository auditRepository;
    @Autowired
    private OrganizationQuotaSnapshotRepository quotaRepository;
    @Autowired
    private ReportService reportService;
    @Autowired
    private ExportPackageService exportPackageService;
    @Autowired
    private TaskReplayProjectionService replayService;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private Playwright playwright;
    @MockBean
    private PlaywrightBrowserManager playwrightBrowserManager;
    @MockBean
    private TaskSnapshotCacheService taskSnapshotCacheService;
    @MockBean
    private TaskExecutionLockService taskExecutionLockService;
    @MockBean
    private AgentContextAssembler agentContextAssembler;

    private Stage2DecisionFixtureLoader fixtureLoader;

    @BeforeEach
    void setUp() {
        fixtureLoader = new Stage2DecisionFixtureLoader(objectMapper);
        reportExportRecordRepository.deleteAll();
        workflowEventRepository.deleteAll();
        taskPlanRepository.deleteAll();
        reportRepository.deleteAll();
        auditRepository.deleteAll();
        quotaRepository.deleteAll();
        decisionProperties.setMode(OrchestratorDecisionMode.LLM_PRIMARY);
        decisionProperties.setFallbackToRule(true);
        decisionProperties.setModelTemperature(0.0d);
        decisionProperties.setLlmTimeoutMs(4000L);
        decisionProperties.setMaxParseRetries(1);
        decisionProperties.getShadow().setEnabled(false);
        assertEnvironmentReady();
    }

    @AfterEach
    void restoreDefaults() {
        decisionProperties.setMode(OrchestratorDecisionMode.RULE_ONLY);
        decisionProperties.getShadow().setEnabled(false);
        quotaRepository.deleteAll();
    }

    @Test
    void shouldAcceptAllNineFixturesAndPersistFirstExecutablePrimary() throws Exception {
        List<Stage2DecisionFixtureLoader.FixtureCase> fixtures = fixtureLoader.load().cases();
        List<FixtureEvidence> evidenceRows = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        RuntimeCycle firstExecutable = null;

        for (Stage2DecisionFixtureLoader.FixtureCase fixture : fixtures) {
            RuntimeCycle cycle = executeRuntimeCycle(fixture);
            FixtureEvidence evidence = evidence(fixture, cycle);
            evidenceRows.add(evidence);
            printSafeEvidence(evidence);
            if (!evidence.accepted()) {
                failures.add(fixture.caseId() + ":" + evidence.resultCode());
            }
            if (firstExecutable == null && isExecutablePrimary(cycle.batch())) {
                firstExecutable = cycle;
            }
        }

        assertThat(evidenceRows).hasSize(9);
        assertThat(failures).as("真实 Provider fixture 失败码").isEmpty();
        assertThat(firstExecutable).as("至少一条 fixture 必须形成可执行 LLM_PRIMARY").isNotNull();
        persistAndVerifyPrimaryFullSeam(firstExecutable);
    }

    @Test
    void shouldExecuteOneRealShadowWithinActiveQuota() throws Exception {
        Stage2DecisionFixtureLoader.FixtureCase fixture =
                fixtureLoader.requireCase("extractor-source-backed-gap");
        OrganizationQuotaSnapshot snapshot = quotaRepository.save(OrganizationQuotaSnapshot.builder()
                .organizationKey(GovernanceDefaults.DEFAULT_ORGANIZATION_KEY)
                .quotaScope(GovernanceDefaults.MODEL_SCOPE)
                .quotaKey(GovernanceDefaults.ORCHESTRATOR_SHADOW_BUDGET_KEY)
                .limitValue(100_000)
                .usedValue(0)
                .reservedValue(0)
                .quotaUnit("TOKEN")
                .snapshotStatus("ACTIVE")
                .sourceUrls(List.of("https://example.com/ops/stage2-shadow-quota"))
                .snapshotAt(LocalDateTime.now())
                .build());
        decisionProperties.setMode(OrchestratorDecisionMode.LLM_SHADOW);
        decisionProperties.getShadow().setEnabled(true);
        decisionProperties.getShadow().setRequireActiveQuota(true);

        RuntimeCycle cycle = executeRuntimeCycle(fixture);
        assertThat(cycle.batch()).as("真实 shadow runtime batch").isNotNull();
        traceService.recordDecisionBatch(cycle.taskId(), cycle.triggerNode(), cycle.batch());
        OrchestrationDecisionOutcome outcome = cycle.batch().coordinatorOutcome();
        // caller timeout 与 Future.done 的补偿释放存在极短竞态；验收必须等待 worker 完成有界收口后再判定泄漏。
        OrganizationQuotaSnapshot finalSnapshot = awaitReservationReleased(snapshot.getId());
        String traceId = outcome.decisions().isEmpty()
                ? null
                : outcome.decisions().get(0).getDecisionMetadata().getAiAuditTraceId();
        String failureType = outcome.shadowExecution().failure() == null
                ? "NONE"
                : outcome.shadowExecution().failure().type().name();
        System.out.printf(
                "STAGE2_SHADOW|requested=%s|executed=%s|failure=%s|main=%s|shadowCount=%d|traceId=%s|used=%d|reserved=%d|limit=%d%n",
                outcome.shadowExecution().requested(), outcome.shadowExecution().executed(), failureType,
                outcome.decisions().isEmpty() ? "NONE" : outcome.decisions().get(0).getDecisionOrigin(),
                outcome.shadowDecisions().size(), traceId, finalSnapshot.getUsedValue(),
                finalSnapshot.getReservedValue(), finalSnapshot.getLimitValue());

        assertThat(outcome.mode()).isEqualTo(OrchestratorDecisionMode.LLM_SHADOW);
        assertThat(outcome.decisions())
                .extracting(OrchestrationDecision::getDecisionOrigin)
                .containsOnly(OrchestrationDecisionOrigin.RULE_ONLY);
        assertThat(cycle.batch().attempts())
                .extracting(item -> item.decision().getDecisionOrigin())
                .containsOnly(OrchestrationDecisionOrigin.RULE_ONLY);
        assertThat(outcome.shadowExecution().requested()).isTrue();
        assertThat(outcome.shadowExecution().executed()).isTrue();
        assertThat(outcome.shadowExecution().failure()).isNull();
        assertThat(outcome.shadowDecisions()).isNotEmpty()
                .extracting(OrchestrationDecision::getDecisionOrigin)
                .containsOnly(OrchestrationDecisionOrigin.LLM_SHADOW);
        assertThat(cycle.batch().attempts())
                .noneMatch(item -> item.decision().getDecisionOrigin() == OrchestrationDecisionOrigin.LLM_SHADOW);
        assertThat(cycle.batch().finalDecisions())
                .noneMatch(item -> item.decision().getDecisionOrigin() == OrchestrationDecisionOrigin.LLM_SHADOW);
        assertThat(finalSnapshot.getReservedValue()).isZero();

        List<AiCallAuditRecord> linkedAudits = auditRepository.findByTraceIdOrderByCreatedAtAsc(traceId);
        assertThat(linkedAudits).isNotEmpty();
        long auditsBeforeRead = auditRepository.count();
        Map<Long, String> quotaBeforeRead = quotaFingerprint();
        ReportResponse report = reportService.getReport(cycle.taskId());
        ReportExportRenderer.RenderedExportPackage markdown =
                exportPackageService.createExportPackage(cycle.taskId(), "MARKDOWN");
        ReportExportRenderer.RenderedExportPackage html =
                exportPackageService.createExportPackage(cycle.taskId(), "HTML");
        ReportExportRenderer.RenderedExportPackage json =
                exportPackageService.createExportPackage(cycle.taskId(), "JSON");
        TaskReplayResponse replay = replayService.getTaskReplay(cycle.taskId());
        assertThat(report.getOrchestrationDecisionAudit().getShadowExecution().isExecuted()).isTrue();
        assertThat(report.getOrchestrationDecisionAudit().getShadowDecisions()).isNotEmpty();
        assertThat(replay.getLatestOrchestrationDecisionAudit().getShadowDecisions()).isNotEmpty();
        assertThat(new String(markdown.content(), StandardCharsets.UTF_8)).contains("LLM_SHADOW");
        assertThat(new String(html.content(), StandardCharsets.UTF_8)).contains("LLM_SHADOW");
        assertThat(objectMapper.readTree(json.content())
                .at("/orchestrationDecisionAudit/shadowDecisions/0/decisionOrigin").asText())
                .isEqualTo("LLM_SHADOW");
        assertThat(auditRepository.count()).isEqualTo(auditsBeforeRead);
        assertThat(quotaFingerprint()).isEqualTo(quotaBeforeRead);
    }

    private RuntimeCycle executeRuntimeCycle(Stage2DecisionFixtureLoader.FixtureCase fixture) throws Exception {
        long taskId = fixture.context().getTaskId();
        TaskPlan plan = saveActivePlan(taskId, fixture.context().getTriggerNodeName());
        saveReport(taskId);
        TaskNode triggerNode = triggerNode(taskId, plan, fixture.context().getTriggerNodeName());
        try {
            OrchestrationRuntimeDecisionBatch batch = runtimeDecisionService.decide(
                    fixture.context().toBuilder()
                            .planVersionId(plan.getId())
                            .branchKey("root")
                            .build(),
                    "RUNNING",
                    TaskNodeStatus.SUCCESS.name());
            return new RuntimeCycle(taskId, triggerNode, batch, null);
        } catch (RuntimeException exception) {
            // 验收证据只保留稳定异常类型，不保存可能含 Provider 原文的 message。
            return new RuntimeCycle(taskId, triggerNode, null, exception.getClass().getSimpleName());
        }
    }

    private FixtureEvidence evidence(Stage2DecisionFixtureLoader.FixtureCase fixture, RuntimeCycle cycle) {
        if (cycle.batch() == null) {
            return FixtureEvidence.failure(fixture.caseId(), cycle.errorCode(), fixture.context().getSourceUrls());
        }
        OrchestrationDecisionOutcome outcome = cycle.batch().coordinatorOutcome();
        if (outcome.llmFailure() != null || outcome.decisions().isEmpty()) {
            String code = outcome.llmFailure() == null
                    ? "EMPTY_DECISIONS"
                    : outcome.llmFailure().type().name();
            return FixtureEvidence.failure(fixture.caseId(), code, fixture.context().getSourceUrls());
        }
        OrchestrationDecision decision = outcome.decisions().get(0);
        OrchestrationRuntimeDecision runtime = cycle.batch().attempts().stream()
                .filter(item -> item.decision().getDecisionId().equals(decision.getDecisionId()))
                .findFirst()
                .orElse(null);
        String pair = decision.getDecisionType() + "/" + decision.getActionType();
        boolean acceptedPair = fixture.acceptedPairs().stream().anyMatch(item ->
                item.decisionType().equals(decision.getDecisionType())
                        && item.actionType().equals(decision.getActionType()));
        boolean accepted = acceptedPair
                && decision.getDecisionOrigin() == OrchestrationDecisionOrigin.LLM_PRIMARY
                && runtime != null
                && runtime.policyResult().isAllowed();
        OrchestratorDecisionMetadata metadata = decision.getDecisionMetadata();
        return new FixtureEvidence(
                fixture.caseId(),
                accepted,
                accepted ? "ACCEPTED" : acceptedPair ? "POLICY_REJECTED" : "PAIR_NOT_ACCEPTED",
                pair,
                decision.getDecisionOrigin().name(),
                runtime != null && runtime.policyResult().isAllowed(),
                metadata == null ? null : metadata.getPromptHash(),
                metadata == null ? null : metadata.getLlmResponseHash(),
                metadata == null ? null : metadata.getAiAuditTraceId(),
                decision.getSourceUrls());
    }

    private boolean isExecutablePrimary(OrchestrationRuntimeDecisionBatch batch) {
        if (batch == null || batch.coordinatorOutcome().llmFailure() != null) {
            return false;
        }
        return batch.finalDecisions().stream().anyMatch(item ->
                item.decision().getDecisionOrigin() == OrchestrationDecisionOrigin.LLM_PRIMARY
                        && item.policyResult().isAllowed()
                        && OrchestrationRuntimeDecision.READY.equals(item.runtimeStatus())
                        && !"NO_MUTATION".equals(item.mutation().getMutationType()));
    }

    private void persistAndVerifyPrimaryFullSeam(RuntimeCycle cycle) throws Exception {
        traceService.recordDecisionBatch(cycle.taskId(), cycle.triggerNode(), cycle.batch());
        OrchestrationRuntimeDecision executable = cycle.batch().finalDecisions().stream()
                .filter(item -> OrchestrationRuntimeDecision.READY.equals(item.runtimeStatus()))
                .filter(item -> !"NO_MUTATION".equals(item.mutation().getMutationType()))
                .findFirst()
                .orElseThrow();
        OrchestrationDecision decision = executable.decision();
        String traceId = decision.getDecisionMetadata().getAiAuditTraceId();

        List<TaskWorkflowEvent> decisionEvents = workflowEventRepository.findAll().stream()
                .filter(item -> item.getTaskId().equals(cycle.taskId()))
                .filter(item -> item.getEventType() == WorkflowEventType.ORCHESTRATION_DECISION_RECORDED)
                .toList();
        assertThat(decisionEvents).hasSize(1);
        JsonNode payload = objectMapper.readTree(decisionEvents.get(0).getPayload());
        assertThat(payload.path("traceSchemaVersion").asText())
                .isEqualTo(OrchestrationDecisionAuditTrace.SCHEMA_VERSION);
        assertThat(payload.path("decision").path("decisionId").asText()).isEqualTo(decision.getDecisionId());

        List<AiCallAuditRecord> linkedAudits = auditRepository.findByTraceIdOrderByCreatedAtAsc(traceId);
        assertThat(linkedAudits).isNotEmpty();
        assertThat(linkedAudits).allSatisfy(audit -> {
            assertThat(audit.getProviderKey()).isEqualToIgnoringCase(providerProperties.getActiveProvider());
            assertThat(audit.getModelName()).isNotBlank();
            assertThat(audit.getEstimatedInputTokens()).isNotNull().isGreaterThan(0);
            assertThat(audit.getTotalTokens()).isNotNull().isGreaterThanOrEqualTo(0);
        });

        long auditsBeforeRead = auditRepository.count();
        Map<Long, String> quotaBeforeRead = quotaFingerprint();
        ReportResponse report = reportService.getReport(cycle.taskId());
        ReportExportRenderer.RenderedExportPackage markdown =
                exportPackageService.createExportPackage(cycle.taskId(), "MARKDOWN");
        ReportExportRenderer.RenderedExportPackage html =
                exportPackageService.createExportPackage(cycle.taskId(), "HTML");
        ReportExportRenderer.RenderedExportPackage json =
                exportPackageService.createExportPackage(cycle.taskId(), "JSON");
        TaskReplayResponse replay = replayService.getTaskReplay(cycle.taskId());

        OrchestrationDecisionSummary reportDecision = report.getOrchestrationDecision();
        OrchestrationDecisionSummary replayDecision = replay.getLatestOrchestrationDecision();
        assertThat(reportDecision.getDecisionId()).isEqualTo(decision.getDecisionId());
        assertThat(reportDecision.getDecisionOrigin()).isEqualTo("LLM_PRIMARY");
        assertThat(reportDecision.getReason()).isEqualTo(replayDecision.getReason());
        assertThat(reportDecision.getSourceUrls()).isEqualTo(replayDecision.getSourceUrls());
        assertThat(reportDecision.getAiAuditTraceId()).isEqualTo(traceId);
        assertThat(replayDecision.getAiAuditTraceId()).isEqualTo(traceId);
        assertTextExport(markdown, reportDecision);
        assertTextExport(html, reportDecision);
        JsonNode jsonExport = objectMapper.readTree(json.content());
        assertThat(jsonExport.at("/orchestrationDecision/decisionId").asText())
                .isEqualTo(decision.getDecisionId());
        assertThat(jsonExport.at("/orchestrationDecision/aiAuditTraceId").asText()).isEqualTo(traceId);
        assertThat(auditRepository.count()).isEqualTo(auditsBeforeRead);
        assertThat(quotaFingerprint()).isEqualTo(quotaBeforeRead);
        assertThat(payload.toString())
                .doesNotContain("systemPrompt")
                .doesNotContain("userPrompt")
                .doesNotContain("rawResponse")
                .doesNotContain("apiKey");

        int actualInput = linkedAudits.stream().map(AiCallAuditRecord::getInputTokens)
                .filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).sum();
        int actualOutput = linkedAudits.stream().map(AiCallAuditRecord::getOutputTokens)
                .filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).sum();
        int actualTotal = linkedAudits.stream().map(AiCallAuditRecord::getTotalTokens)
                .filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).sum();
        int estimatedInput = linkedAudits.stream().map(AiCallAuditRecord::getEstimatedInputTokens)
                .filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).sum();
        System.out.printf(
                "STAGE2_PRIMARY_SEAM|taskId=%d|traceId=%s|input=%d|output=%d|total=%d|estimatedInput=%d%n",
                cycle.taskId(), traceId, actualInput, actualOutput, actualTotal, estimatedInput);
    }

    private void assertEnvironmentReady() {
        assertThat(providerProperties.getActiveProvider()).isNotBlank();
        assertThat(providerProperties.getModelName()).isNotBlank();
        assertThat(providerProperties.getActiveProviderConfig().getApiKey()).isNotBlank();
        assertThat(URI.create(providerProperties.getActiveProviderConfig().getUrl()).getScheme())
                .isEqualToIgnoringCase("https");
        assertThat(decisionProperties.getModelTemperature()).isZero();
        assertThat(decisionProperties.getLlmTimeoutMs()).isEqualTo(4000L);
        assertThat(decisionProperties.getMaxParseRetries()).isEqualTo(1);
        assertThat(ruleSet.getMaxDecisionsPerCycle()).isGreaterThan(0);
    }

    private void printSafeEvidence(FixtureEvidence evidence) {
        System.out.printf(
                "STAGE2_FIXTURE|caseId=%s|accepted=%s|code=%s|pair=%s|origin=%s|policy=%s|promptHash=%s|responseHash=%s|traceId=%s|sourceUrls=%s%n",
                evidence.caseId(), evidence.accepted(), evidence.resultCode(), evidence.pair(), evidence.origin(),
                evidence.policyAllowed(), evidence.promptHash(), evidence.responseHash(), evidence.aiAuditTraceId(),
                evidence.sourceUrls());
    }

    private void assertTextExport(ReportExportRenderer.RenderedExportPackage export,
                                  OrchestrationDecisionSummary decision) {
        String content = new String(export.content(), StandardCharsets.UTF_8);
        assertThat(content)
                .contains(decision.getDecisionId())
                .contains("LLM_PRIMARY")
                .contains(decision.getAiAuditTraceId());
    }

    private Map<Long, String> quotaFingerprint() {
        Map<Long, String> values = new LinkedHashMap<>();
        quotaRepository.findAll().forEach(snapshot -> values.put(
                snapshot.getId(),
                snapshot.getUsedValue() + ":" + snapshot.getReservedValue() + ":" + snapshot.getLimitValue()));
        return values;
    }

    private OrganizationQuotaSnapshot awaitReservationReleased(Long snapshotId) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        OrganizationQuotaSnapshot snapshot = quotaRepository.findById(snapshotId).orElseThrow();
        while (snapshot.getReservedValue() != null
                && snapshot.getReservedValue() > 0
                && System.nanoTime() < deadlineNanos) {
            Thread.sleep(25L);
            snapshot = quotaRepository.findById(snapshotId).orElseThrow();
        }
        return snapshot;
    }

    private TaskPlan saveActivePlan(long taskId, String triggerNodeName) {
        return taskPlanRepository.save(TaskPlan.builder()
                .taskId(taskId)
                .planVersion(1)
                .branchKey("root")
                .triggerNodeName(triggerNodeName)
                .planType("INITIAL")
                .active(true)
                .planSnapshot("{\"nodes\":[]}")
                .build());
    }

    private TaskNode triggerNode(long taskId, TaskPlan plan, String nodeName) {
        return TaskNode.builder()
                .taskId(taskId)
                .nodeName(nodeName)
                .displayName("Task 09 真实 Provider 触发节点")
                .agentType(AgentType.EXTRACTOR)
                .status(TaskNodeStatus.SUCCESS)
                .planVersionId(plan.getId())
                .branchKey("root")
                .build();
    }

    private void saveReport(long taskId) {
        reportRepository.save(Report.builder()
                .taskId(taskId)
                .title("Task 09 真实 Provider 验收报告")
                .content("# Real Provider Acceptance")
                .summary("验证真实 Provider 决策进入持久化与只读链。")
                .qualityScore(60)
                .qualityPassed(false)
                .evidenceCount(1)
                .build());
    }

    private record RuntimeCycle(long taskId,
                                TaskNode triggerNode,
                                OrchestrationRuntimeDecisionBatch batch,
                                String errorCode) {
    }

    private record FixtureEvidence(String caseId,
                                   boolean accepted,
                                   String resultCode,
                                   String pair,
                                   String origin,
                                   boolean policyAllowed,
                                   String promptHash,
                                   String responseHash,
                                   String aiAuditTraceId,
                                   List<String> sourceUrls) {
        private static FixtureEvidence failure(String caseId, String code, List<String> sourceUrls) {
            return new FixtureEvidence(
                    caseId, false, code, null, null, false, null, null, null,
                    sourceUrls == null ? List.of() : List.copyOf(sourceUrls));
        }
    }
}
