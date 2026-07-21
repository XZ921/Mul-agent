package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.CompetitorAgentApplication;
import cn.bugstack.competitoragent.config.PlaywrightBrowserManager;
import cn.bugstack.competitoragent.context.AgentContextAssembler;
import cn.bugstack.competitoragent.governance.GovernanceDefaults;
import cn.bugstack.competitoragent.llm.ModelChatOptions;
import cn.bugstack.competitoragent.llm.ModelGateway;
import cn.bugstack.competitoragent.model.dto.OrchestrationDecisionSummary;
import cn.bugstack.competitoragent.model.dto.ReportResponse;
import cn.bugstack.competitoragent.model.dto.TaskReplayResponse;
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
import cn.bugstack.competitoragent.workflow.contract.RevisionDirective;
import cn.bugstack.competitoragent.workflow.event.WorkflowEventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * Task 09 B 层受控 Provider 全接缝验收。
 * 仅替换最外层 ModelGateway 返回；Parser、Coordinator、Policy、Runtime、Trace、H2 和只读服务均使用生产实现。
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
class Stage2OrchestrationControlledAcceptanceTest {

    private static final String CONTROLLED_MODEL = "controlled-model";
    private static final String INVENTED_URL = "https://invented.invalid/not-in-context";
    private static final String FINAL_REVIEW_SOURCE_URL = "https://www.notion.so/pricing";

    @Autowired
    private OrchestrationRuntimeDecisionService runtimeDecisionService;
    @Autowired
    private OrchestrationTraceService traceService;
    @Autowired
    private OrchestratorDecisionProperties decisionProperties;
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
    private ModelGateway modelGateway;
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
        reset(modelGateway);
        when(modelGateway.getModelName()).thenReturn(CONTROLLED_MODEL);
        decisionProperties.setMode(OrchestratorDecisionMode.LLM_PRIMARY);
        decisionProperties.setFallbackToRule(true);
        decisionProperties.setLlmTimeoutMs(4000L);
        decisionProperties.setMaxParseRetries(1);
        decisionProperties.getShadow().setEnabled(false);
        decisionProperties.getShadow().setRequireActiveQuota(true);
    }

    @AfterEach
    void restoreDefaults() {
        decisionProperties.setMode(OrchestratorDecisionMode.RULE_ONLY);
        decisionProperties.setLlmTimeoutMs(4000L);
        decisionProperties.setMaxParseRetries(1);
        decisionProperties.getShadow().setEnabled(false);
        decisionProperties.getShadow().setRequireActiveQuota(true);
        quotaRepository.deleteAll();
    }

    @Test
    void shouldProduceRuntimeBatchPersistV2AndKeepReadPathsSideEffectFree() throws Exception {
        RuntimeCycle cycle = executeCanonical("extractor-source-backed-gap", null);
        OrchestrationRuntimeDecision runtimeDecision = cycle.batch().finalDecisions().get(0);

        assertThat(cycle.batch().coordinatorOutcome().mode()).isEqualTo(OrchestratorDecisionMode.LLM_PRIMARY);
        assertThat(cycle.batch().attempts()).hasSize(1);
        assertThat(runtimeDecision.decision().getDecisionOrigin()).isEqualTo(OrchestrationDecisionOrigin.LLM_PRIMARY);
        assertThat(runtimeDecision.policyResult().isAllowed()).isTrue();
        assertThat(runtimeDecision.runtimeStatus()).isEqualTo(OrchestrationRuntimeDecision.READY);
        assertThat(runtimeDecision.mutation().getMutationType()).isEqualTo("APPEND_NODES");
        assertThat(runtimeDecision.decision().getDecisionMetadata().getAiAuditTraceId())
                .startsWith("orch-")
                .hasSizeLessThanOrEqualTo(50);
        assertSingleV2Event(cycle, 1);
        assertReadPathsWithoutModelOrGovernanceMutation(cycle, "LLM_PRIMARY", "READY");
    }

    @Test
    void shouldKeepPolicyRejectedLlmAndRuleFallbackInOnePersistedCycle() throws Exception {
        Stage2DecisionFixtureLoader.FixtureCase fixture = fixtureLoader.requireCase("extractor-source-backed-gap");
        ObjectNode response = canonicalObject(fixture);
        ObjectNode candidate = (ObjectNode) response.withArray("decisions").get(0);
        ArrayNode queries = candidate.putArray("suggestedQueries");
        for (int index = 0; index < 6; index++) {
            queries.add("controlled query " + index);
        }

        RuntimeCycle cycle = execute(fixture, List.of(objectMapper.writeValueAsString(response)), null);

        assertThat(cycle.batch().policyFallbackUsed()).isTrue();
        assertThat(cycle.batch().attempts()).hasSize(2);
        assertThat(cycle.batch().attempts().get(0).decision().getDecisionOrigin())
                .isEqualTo(OrchestrationDecisionOrigin.LLM_PRIMARY);
        assertThat(cycle.batch().attempts().get(0).runtimeStatus())
                .isEqualTo(OrchestrationRuntimeDecision.POLICY_REJECTED);
        assertThat(cycle.batch().attempts().get(0).policyResult().getBlockedReasons())
                .anyMatch(reason -> reason.contains("query 数量超过上限"));
        assertThat(cycle.batch().finalDecisions())
                .extracting(item -> item.decision().getDecisionOrigin())
                .containsOnly(OrchestrationDecisionOrigin.RULE_FALLBACK);
        assertSingleV2Event(cycle, 2);
        assertReadPathsWithoutModelOrGovernanceMutation(cycle, "RULE_FALLBACK", "READY");
    }

    @Test
    void shouldBlockMissingSourceAutomaticMutationAndFallbackToManualReview() throws Exception {
        Stage2DecisionFixtureLoader.FixtureCase fixture =
                fixtureLoader.requireCase("extractor-missing-source-gap");
        ObjectNode response = canonicalObject(fixture);
        ObjectNode candidate = (ObjectNode) response.withArray("decisions").get(0);
        candidate.put("decisionType", "APPEND_DYNAMIC_BRANCH");
        candidate.put("actionType", "SUPPLEMENT_EVIDENCE");
        candidate.put("requiresHumanIntervention", false);
        candidate.put("requiresConfirmation", false);

        RuntimeCycle cycle = execute(fixture, List.of(objectMapper.writeValueAsString(response)), null);

        assertThat(cycle.batch().policyFallbackUsed()).isTrue();
        assertThat(cycle.batch().attempts()).hasSize(2);
        assertThat(cycle.batch().attempts().get(0).runtimeStatus())
                .isEqualTo(OrchestrationRuntimeDecision.POLICY_REJECTED);
        assertThat(cycle.batch().attempts().get(0).policyResult().getBlockedReasons())
                .contains(DecisionPolicyService.MISSING_SOURCE_FOR_AUTOMATIC_MUTATION);
        assertThat(cycle.batch().finalDecisions()).singleElement().satisfies(result -> {
            assertThat(result.decision().getDecisionOrigin())
                    .isEqualTo(OrchestrationDecisionOrigin.RULE_FALLBACK);
            assertThat(result.decision().getDecisionType()).isEqualTo("WAIT_FOR_HUMAN");
            assertThat(result.decision().getActionType()).isEqualTo("MANUAL_REVIEW");
            assertThat(result.runtimeStatus()).isEqualTo(OrchestrationRuntimeDecision.CONFIRMATION_REQUIRED);
            assertThat(result.mutation().getMutationType()).isEqualTo("MARK_WAITING_INTERVENTION");
        });
        assertSingleV2Event(cycle, 2);
        assertReadPathsWithoutModelOrGovernanceMutation(
                cycle, "RULE_FALLBACK", OrchestrationRuntimeDecision.CONFIRMATION_REQUIRED);
    }

    @Test
    void shouldRetryMalformedJsonOnceThenPersistTypedRuleFallback() throws Exception {
        Stage2DecisionFixtureLoader.FixtureCase fixture = fixtureLoader.requireCase("extractor-source-backed-gap");
        RuntimeCycle cycle = execute(fixture, List.of("{malformed", "[]"), null);

        assertThat(cycle.batch().coordinatorOutcome().llmFailure()).isNotNull();
        assertThat(cycle.batch().coordinatorOutcome().llmFailure().type())
                .isEqualTo(LlmOrchestratorFailureType.PARSE_ERROR);
        assertThat(cycle.batch().coordinatorOutcome().llmFailure().parseRetryCount()).isEqualTo(1);
        assertThat(cycle.batch().coordinatorOutcome().llmFailure().attempts()).hasSize(2);
        assertThat(cycle.batch().finalDecisions())
                .extracting(item -> item.decision().getDecisionOrigin())
                .containsOnly(OrchestrationDecisionOrigin.RULE_FALLBACK);
        assertSingleV2Event(cycle, 1);
        assertThat(cycle.payload().at("/audit/llmFailure/attempts/0/issues")).isNotEmpty();
        assertReadPathsWithoutModelOrGovernanceMutation(cycle, "RULE_FALLBACK", "READY");
    }

    @Test
    void shouldAcceptSourceBackedFinalReviewLegacyContextAsExecutablePrimary() throws Exception {
        RuntimeCycle cycle = executeFinalReviewContext(
                690L,
                List.of(finalReviewResponse("APPEND_DYNAMIC_BRANCH", "SUPPLEMENT_EVIDENCE", false, 1)));

        assertThat(cycle.batch().policyFallbackUsed()).isFalse();
        assertThat(cycle.batch().coordinatorOutcome().llmFailure()).isNull();
        assertThat(cycle.batch().attempts()).singleElement().satisfies(result -> {
            assertThat(result.decision().getDecisionOrigin()).isEqualTo(OrchestrationDecisionOrigin.LLM_PRIMARY);
            assertThat(result.decision().getDecisionMetadata().getParseRetryCount()).isZero();
            assertThat(result.policyResult().isAllowed()).isTrue();
            assertThat(result.runtimeStatus()).isEqualTo(OrchestrationRuntimeDecision.READY);
            assertThat(result.mutation().getMutationType()).isEqualTo("APPEND_NODES");
        });
    }

    @Test
    void shouldClassifySecondParseFailureAsRuleFallbackForFinalReviewContext() throws Exception {
        RuntimeCycle cycle = executeFinalReviewContext(691L, List.of("{malformed", "[]"));

        assertThat(cycle.batch().coordinatorOutcome().llmFailure()).isNotNull();
        assertThat(cycle.batch().coordinatorOutcome().llmFailure().type())
                .isEqualTo(LlmOrchestratorFailureType.PARSE_ERROR);
        assertThat(cycle.batch().coordinatorOutcome().llmFailure().parseRetryCount()).isEqualTo(1);
        // 该标志只描述 Policy rejection fallback；Parser failure 由 llmFailure 单独表达。
        assertThat(cycle.batch().policyFallbackUsed()).isFalse();
        assertThat(cycle.batch().finalDecisions()).singleElement().satisfies(result -> {
            assertThat(result.decision().getDecisionOrigin()).isEqualTo(OrchestrationDecisionOrigin.RULE_FALLBACK);
            assertThat(result.policyResult().isAllowed()).isTrue();
            assertThat(result.runtimeStatus()).isEqualTo(OrchestrationRuntimeDecision.READY);
            assertThat(result.mutation().getMutationType()).isEqualTo("APPEND_NODES");
        });
    }

    @Test
    void shouldClassifyRetriedPolicyRejectionAsRuleFallbackForFinalReviewContext() throws Exception {
        RuntimeCycle cycle = executeFinalReviewContext(
                692L,
                List.of(
                        "{malformed",
                        finalReviewResponse("APPEND_DYNAMIC_BRANCH", "SUPPLEMENT_EVIDENCE", false, 6)));

        assertThat(cycle.batch().coordinatorOutcome().llmFailure()).isNull();
        assertThat(cycle.batch().policyFallbackUsed()).isTrue();
        assertThat(cycle.batch().attempts()).hasSize(2);
        assertThat(cycle.batch().attempts().get(0)).satisfies(result -> {
            assertThat(result.decision().getDecisionOrigin()).isEqualTo(OrchestrationDecisionOrigin.LLM_PRIMARY);
            assertThat(result.decision().getDecisionMetadata().getParseRetryCount()).isEqualTo(1);
            assertThat(result.policyResult().isAllowed()).isFalse();
            assertThat(result.policyResult().getBlockedReasons())
                    .anyMatch(reason -> reason.contains("query 数量超过上限"));
            assertThat(result.runtimeStatus()).isEqualTo(OrchestrationRuntimeDecision.POLICY_REJECTED);
        });
        assertThat(cycle.batch().finalDecisions()).singleElement().satisfies(result -> {
            assertThat(result.decision().getDecisionOrigin()).isEqualTo(OrchestrationDecisionOrigin.RULE_FALLBACK);
            assertThat(result.runtimeStatus()).isEqualTo(OrchestrationRuntimeDecision.READY);
            assertThat(result.mutation().getMutationType()).isEqualTo("APPEND_NODES");
        });
    }

    @Test
    void shouldKeepAllowedConservativeFinalReviewPairWithoutDynamicAppendMutation() throws Exception {
        RuntimeCycle cycle = executeFinalReviewContext(
                693L,
                List.of(finalReviewResponse("WAIT_FOR_HUMAN", "MANUAL_REVIEW", true, 0)));

        assertThat(cycle.batch().policyFallbackUsed()).isFalse();
        assertThat(cycle.batch().coordinatorOutcome().llmFailure()).isNull();
        assertThat(cycle.batch().finalDecisions()).singleElement().satisfies(result -> {
            assertThat(result.decision().getDecisionOrigin()).isEqualTo(OrchestrationDecisionOrigin.LLM_PRIMARY);
            assertThat(result.policyResult().isAllowed()).isTrue();
            assertThat(result.runtimeStatus()).isEqualTo(OrchestrationRuntimeDecision.CONFIRMATION_REQUIRED);
            assertThat(result.mutation().getMutationType()).isEqualTo("MARK_WAITING_INTERVENTION");
            assertThat(result.mutation().getMutationType()).isNotEqualTo("APPEND_NODES");
        });
    }

    @Test
    void shouldRejectInvalidActionPairWithoutSilentNormalization() throws Exception {
        Stage2DecisionFixtureLoader.FixtureCase fixture = fixtureLoader.requireCase("extractor-source-backed-gap");
        ObjectNode response = canonicalObject(fixture);
        ((ObjectNode) response.withArray("decisions").get(0)).put("actionType", "NO_ACTION");
        String invalidPair = objectMapper.writeValueAsString(response);

        RuntimeCycle cycle = execute(fixture, List.of(invalidPair, invalidPair), null);

        assertThat(cycle.batch().coordinatorOutcome().llmFailure().type())
                .isEqualTo(LlmOrchestratorFailureType.PARSE_ERROR);
        assertThat(cycle.payload().at("/audit/llmFailure/attempts/0/issues").toString())
                .contains(OrchestrationDecisionActionMatrix.INVALID_DECISION_ACTION_PAIR);
        assertThat(cycle.batch().attempts())
                .extracting(item -> item.decision().getActionType())
                .doesNotContain("NO_ACTION");
    }

    @Test
    void shouldTimeoutThenFallbackWithoutCorruptingPersistedRuntimeState() throws Exception {
        Stage2DecisionFixtureLoader.FixtureCase fixture = fixtureLoader.requireCase("extractor-source-backed-gap");
        decisionProperties.setLlmTimeoutMs(120L);
        when(modelGateway.chatForJson(anyString(), anyString(), anyString(), any(ModelChatOptions.class)))
                .thenAnswer(invocation -> {
                    Thread.sleep(5_000L);
                    return fixture.canonicalResponseJson(objectMapper);
                });

        RuntimeCycle cycle = executePrepared(fixture, null);

        assertThat(cycle.batch().coordinatorOutcome().llmFailure().type())
                .isEqualTo(LlmOrchestratorFailureType.LLM_TIMEOUT);
        assertThat(cycle.batch().finalDecisions())
                .extracting(item -> item.decision().getDecisionOrigin())
                .containsOnly(OrchestrationDecisionOrigin.RULE_FALLBACK);
        assertThat(taskPlanRepository.findFirstByTaskIdAndActiveTrueOrderByPlanVersionDesc(cycle.taskId()))
                .isPresent();
        assertSingleV2Event(cycle, 1);
    }

    @Test
    void shouldIgnorePromptInjectionAndExecuteOnlyCanonicalAllowedAction() throws Exception {
        RuntimeCycle cycle = executeCanonical("prompt-injection-source-backed-gap", null);

        assertThat(cycle.batch().finalDecisions()).singleElement().satisfies(result -> {
            assertThat(result.decision().getDecisionType()).isEqualTo("APPEND_DYNAMIC_BRANCH");
            assertThat(result.decision().getActionType()).isEqualTo("SUPPLEMENT_EVIDENCE");
            assertThat(result.decision().getSourceUrls())
                    .containsExactly("https://example.com/injection-pricing");
        });
        assertThat(cycle.payload().toString()).doesNotContain("decisionOrigin\\\":\\\"LLM_PRIMARY");
    }

    @Test
    void shouldPersistInventedUrlAsDiscardedWarningButNeverAsTrustedSource() throws Exception {
        Stage2DecisionFixtureLoader.FixtureCase fixture = fixtureLoader.requireCase("extractor-source-backed-gap");
        ObjectNode response = canonicalObject(fixture);
        ((ObjectNode) response.withArray("decisions").get(0)).withArray("sourceUrls").add(INVENTED_URL);

        RuntimeCycle cycle = execute(fixture, List.of(objectMapper.writeValueAsString(response)), null);
        OrchestrationDecision decision = cycle.batch().finalDecisions().get(0).decision();

        assertThat(decision.getSourceUrls()).doesNotContain(INVENTED_URL);
        assertThat(decision.getInputRefs().get("discardedSourceUrls")).asString().contains(INVENTED_URL);
        assertThat(cycle.payload().at("/decision/sourceUrls").toString()).doesNotContain(INVENTED_URL);
        assertThat(cycle.payload().at("/decision/inputRefs/discardedSourceUrls").toString())
                .contains(INVENTED_URL);
        assertThat(cycle.payload().path("sourceUrls").toString()).doesNotContain(INVENTED_URL);
    }

    @Test
    void shouldKeepMaxAutoMaxPerCycleSectionAndConfirmationAsIndependentGuards() throws Exception {
        RuntimeCycle belowAutoLimit = executeCanonical(
                "citation-source-backed-repair",
                new CheckpointSeed(1, Map.of(), List.of("https://example.com/checkpoint")));
        assertThat(belowAutoLimit.batch().finalDecisions())
                .extracting(OrchestrationRuntimeDecision::runtimeStatus)
                .containsOnly(OrchestrationRuntimeDecision.READY);

        clearPersistentFixtures();
        reset(modelGateway);
        when(modelGateway.getModelName()).thenReturn(CONTROLLED_MODEL);
        Stage2DecisionFixtureLoader.FixtureCase citation = fixtureLoader.requireCase("citation-source-backed-repair");
        RuntimeCycle atAutoLimit = execute(
                citation,
                List.of(citation.canonicalResponseJson(objectMapper)),
                new CheckpointSeed(2, Map.of(), List.of("https://example.com/checkpoint")));
        assertThat(atAutoLimit.batch().attempts().get(0).runtimeStatus())
                .isEqualTo(OrchestrationRuntimeDecision.POLICY_REJECTED);
        assertThat(atAutoLimit.batch().attempts().get(0).policyResult().getBlockedReasons())
                .anyMatch(reason -> reason.contains("自动编排次数已达到上限"));

        clearPersistentFixtures();
        reset(modelGateway);
        when(modelGateway.getModelName()).thenReturn(CONTROLLED_MODEL);
        RuntimeCycle sectionLimit = execute(
                citation,
                List.of(citation.canonicalResponseJson(objectMapper)),
                new CheckpointSeed(1, Map.of("conclusion", 1), List.of("https://example.com/checkpoint")));
        assertThat(sectionLimit.batch().finalDecisions())
                .extracting(OrchestrationRuntimeDecision::runtimeStatus)
                .containsOnly(OrchestrationRuntimeDecision.DYNAMIC_BRANCH_LIMIT_REACHED);
        assertThat(sectionLimit.batch().finalDecisions().get(0).policyResult().isAllowed()).isTrue();

        clearPersistentFixtures();
        reset(modelGateway);
        when(modelGateway.getModelName()).thenReturn(CONTROLLED_MODEL);
        RuntimeCycle confirmation = executeCanonical("citation-limit-reached", null);
        assertThat(confirmation.batch().finalDecisions()).singleElement().satisfies(result -> {
            assertThat(result.runtimeStatus()).isEqualTo(OrchestrationRuntimeDecision.CONFIRMATION_REQUIRED);
            assertThat(result.mutation().getMutationType()).isEqualTo("MARK_WAITING_INTERVENTION");
            assertThat(result.policyResult().isAllowed()).isTrue();
        });

        clearPersistentFixtures();
        reset(modelGateway);
        when(modelGateway.getModelName()).thenReturn(CONTROLLED_MODEL);
        Stage2DecisionFixtureLoader.FixtureCase extractor = fixtureLoader.requireCase("extractor-source-backed-gap");
        ObjectNode tooMany = canonicalObject(extractor);
        tooMany.withArray("decisions").add(tooMany.withArray("decisions").get(0).deepCopy());
        tooMany.withArray("decisions").add(tooMany.withArray("decisions").get(0).deepCopy());
        String tooManyJson = objectMapper.writeValueAsString(tooMany);
        RuntimeCycle perCycleLimit = execute(extractor, List.of(tooManyJson, tooManyJson), null);
        assertThat(perCycleLimit.payload().at("/audit/llmFailure/attempts/0/issues").toString())
                .contains(OrchestrationDecisionResponseParser.TOO_MANY_DECISIONS)
                .doesNotContain("自动编排次数已达到上限");
    }

    @Test
    void shouldSkipShadowBeforeProviderWhenIndependentQuotaIsExhausted() throws Exception {
        Stage2DecisionFixtureLoader.FixtureCase fixture = fixtureLoader.requireCase("extractor-source-backed-gap");
        quotaRepository.save(OrganizationQuotaSnapshot.builder()
                .organizationKey(GovernanceDefaults.DEFAULT_ORGANIZATION_KEY)
                .quotaScope(GovernanceDefaults.MODEL_SCOPE)
                .quotaKey(GovernanceDefaults.ORCHESTRATOR_SHADOW_BUDGET_KEY)
                .limitValue(10)
                .usedValue(10)
                .reservedValue(0)
                .quotaUnit("TOKEN")
                .snapshotStatus("ACTIVE")
                .sourceUrls(List.of("https://example.com/ops/shadow-quota"))
                .snapshotAt(LocalDateTime.now())
                .build());
        decisionProperties.setMode(OrchestratorDecisionMode.LLM_SHADOW);
        decisionProperties.getShadow().setEnabled(true);

        RuntimeCycle cycle = executePrepared(fixture, null);

        assertThat(cycle.batch().coordinatorOutcome().shadowExecution().requested()).isTrue();
        assertThat(cycle.batch().coordinatorOutcome().shadowExecution().executed()).isFalse();
        assertThat(cycle.batch().coordinatorOutcome().shadowExecution().skippedReason())
                .isEqualTo(OrchestrationDecisionService.SHADOW_BUDGET_EXHAUSTED);
        assertThat(cycle.batch().coordinatorOutcome().shadowDecisions()).isEmpty();
        assertThat(cycle.batch().attempts())
                .extracting(item -> item.decision().getDecisionOrigin())
                .containsOnly(OrchestrationDecisionOrigin.RULE_ONLY);
        assertThat(mockingDetails(modelGateway).getInvocations()).isEmpty();
        assertThat(quotaRepository.findAll()).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.getUsedValue()).isEqualTo(10);
            assertThat(snapshot.getReservedValue()).isZero();
        });
        assertSingleV2Event(cycle, 1);
        assertReadPathsWithoutModelOrGovernanceMutation(cycle, "RULE_ONLY", "READY");
    }

    private RuntimeCycle executeCanonical(String caseId, CheckpointSeed checkpoint) throws Exception {
        Stage2DecisionFixtureLoader.FixtureCase fixture = fixtureLoader.requireCase(caseId);
        return execute(fixture, List.of(fixture.canonicalResponseJson(objectMapper)), checkpoint);
    }

    private RuntimeCycle execute(Stage2DecisionFixtureLoader.FixtureCase fixture,
                                 List<String> responses,
                                 CheckpointSeed checkpoint) throws Exception {
        when(modelGateway.chatForJson(anyString(), anyString(), anyString(), any(ModelChatOptions.class)))
                .thenReturn(responses.get(0), responses.stream().skip(1).toArray(String[]::new));
        return executePrepared(fixture, checkpoint);
    }

    private RuntimeCycle executePrepared(Stage2DecisionFixtureLoader.FixtureCase fixture,
                                         CheckpointSeed checkpoint) throws Exception {
        return executeContext(fixture.context(), checkpoint);
    }

    private RuntimeCycle executeFinalReviewContext(long taskId, List<String> responses) throws Exception {
        when(modelGateway.chatForJson(anyString(), anyString(), anyString(), any(ModelChatOptions.class)))
                .thenReturn(responses.get(0), responses.stream().skip(1).toArray(String[]::new));
        return executeContext(finalReviewContext(taskId), null);
    }

    private RuntimeCycle executeContext(OrchestrationContext context,
                                        CheckpointSeed checkpoint) throws Exception {
        long taskId = context.getTaskId();
        TaskPlan plan = saveActivePlan(taskId, context.getTriggerNodeName());
        saveReport(taskId);
        if (checkpoint != null) {
            saveCheckpoint(taskId, context.getTriggerNodeName(), plan, checkpoint);
        }
        TaskNode triggerNode = triggerNode(taskId, plan, context.getTriggerNodeName());
        OrchestrationRuntimeDecisionBatch batch = runtimeDecisionService.decide(
                context.toBuilder()
                        .planVersionId(plan.getId())
                        .branchKey("root")
                        .build(),
                "RUNNING",
                TaskNodeStatus.SUCCESS.name());
        traceService.recordDecisionBatch(taskId, triggerNode, batch);
        TaskWorkflowEvent event = workflowEventRepository
                .findFirstByTaskIdAndEventTypeOrderByCreatedAtDesc(
                        taskId,
                        WorkflowEventType.ORCHESTRATION_DECISION_RECORDED)
                .orElseThrow();
        return new RuntimeCycle(taskId, batch, objectMapper.readTree(event.getPayload()));
    }

    private OrchestrationContext finalReviewContext(long taskId) {
        RevisionDirective directive = RevisionDirective.builder()
                .category("EVIDENCE_GAP")
                .actionType("SUPPLEMENT_EVIDENCE")
                .summary("补充官网定价证据并重新复核")
                .searchQueries(List.of("Notion AI pricing official"))
                .sourceUrls(List.of(FINAL_REVIEW_SOURCE_URL))
                .expectedOutcome("补齐定价证据后重新复核")
                .build();
        return OrchestrationContext.builder()
                .taskId(taskId)
                .triggerNodeName("quality_check_final")
                .reviewStage("final")
                .taskStatus("RUNNING")
                .passed(false)
                .requiresHumanIntervention(false)
                .currentDecisionCount(0)
                .legacyRevisionDirectives(List.of(directive))
                .sourceUrls(List.of(FINAL_REVIEW_SOURCE_URL))
                .evidenceState(EvidenceState.FULL_SOURCE)
                .inputSummary("定价章节已有官网来源，但引用覆盖仍需补强")
                .build()
                .normalized();
    }

    private String finalReviewResponse(String decisionType,
                                       String actionType,
                                       boolean requiresHuman,
                                       int queryCount) throws Exception {
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode decision = response.putArray("decisions").addObject();
        decision.put("decisionType", decisionType);
        decision.put("actionType", actionType);
        decision.put("priority", requiresHuman ? "HIGH" : "MEDIUM");
        decision.put("reason", requiresHuman ? "交由人工确认后继续。" : "已有官网来源，需要补充采集后重新复核。");
        decision.put("confidence", 0.82d);
        decision.put("requiresHumanIntervention", requiresHuman);
        decision.put("requiresConfirmation", requiresHuman);
        decision.putArray("sourceUrls").add(FINAL_REVIEW_SOURCE_URL);
        ArrayNode queries = decision.putArray("suggestedQueries");
        for (int index = 0; index < queryCount; index++) {
            queries.add("Notion AI pricing official " + index);
        }
        return objectMapper.writeValueAsString(response);
    }

    private void assertSingleV2Event(RuntimeCycle cycle, int expectedAttemptCount) {
        assertThat(cycle.payload().path("traceSchemaVersion").asText())
                .isEqualTo(OrchestrationDecisionAuditTrace.SCHEMA_VERSION);
        assertThat(cycle.payload().path("audit").path("attempts")).hasSize(expectedAttemptCount);
        assertThat(workflowEventRepository.findAll().stream()
                .filter(item -> item.getTaskId().equals(cycle.taskId()))
                .filter(item -> item.getEventType() == WorkflowEventType.ORCHESTRATION_DECISION_RECORDED))
                .hasSize(1);
    }

    /**
     * report/export/replay 只能消费同一条 V2 事实；调用前后的模型、AI 审计和治理快照必须完全不变。
     */
    private void assertReadPathsWithoutModelOrGovernanceMutation(RuntimeCycle cycle,
                                                                 String expectedOrigin,
                                                                 String expectedRuntimeStatus) throws Exception {
        int providerCallsBefore = mockingDetails(modelGateway).getInvocations().size();
        long auditRowsBefore = auditRepository.count();
        Map<Long, String> quotaBefore = quotaFingerprint();

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
        assertThat(reportDecision).isNotNull();
        assertThat(replayDecision).isNotNull();
        assertThat(reportDecision.getDecisionId()).isEqualTo(replayDecision.getDecisionId());
        assertThat(reportDecision.getDecisionOrigin()).isEqualTo(expectedOrigin);
        assertThat(reportDecision.getRuntimeStatus()).isEqualTo(expectedRuntimeStatus);
        assertThat(reportDecision.getReason()).isEqualTo(replayDecision.getReason());
        assertThat(reportDecision.getSourceUrls()).isEqualTo(replayDecision.getSourceUrls());
        assertThat(reportDecision.getAiAuditTraceId()).isEqualTo(replayDecision.getAiAuditTraceId());
        assertExportContains(markdown, reportDecision);
        assertExportContains(html, reportDecision);
        JsonNode jsonPayload = objectMapper.readTree(json.content());
        assertThat(jsonPayload.at("/orchestrationDecision/decisionId").asText())
                .isEqualTo(reportDecision.getDecisionId());
        assertThat(jsonPayload.at("/orchestrationDecision/aiAuditTraceId").asText())
                .isEqualTo(reportDecision.getAiAuditTraceId());

        assertThat(mockingDetails(modelGateway).getInvocations()).hasSize(providerCallsBefore);
        assertThat(auditRepository.count()).isEqualTo(auditRowsBefore);
        assertThat(quotaFingerprint()).isEqualTo(quotaBefore);
    }

    private void assertExportContains(ReportExportRenderer.RenderedExportPackage export,
                                      OrchestrationDecisionSummary decision) {
        String content = new String(export.content(), StandardCharsets.UTF_8);
        assertThat(content)
                .contains(decision.getDecisionId())
                .contains(decision.getDecisionOrigin())
                .contains(decision.getRuntimeStatus());
        if (decision.getAiAuditTraceId() != null) {
            assertThat(content).contains(decision.getAiAuditTraceId());
        }
    }

    private Map<Long, String> quotaFingerprint() {
        Map<Long, String> values = new LinkedHashMap<>();
        quotaRepository.findAll().forEach(snapshot -> values.put(
                snapshot.getId(),
                snapshot.getUsedValue() + ":" + snapshot.getReservedValue() + ":" + snapshot.getLimitValue()));
        return values;
    }

    private void clearPersistentFixtures() {
        reportExportRecordRepository.deleteAll();
        workflowEventRepository.deleteAll();
        taskPlanRepository.deleteAll();
        reportRepository.deleteAll();
        auditRepository.deleteAll();
        quotaRepository.deleteAll();
    }

    private ObjectNode canonicalObject(Stage2DecisionFixtureLoader.FixtureCase fixture) {
        return (ObjectNode) fixture.canonicalResponse().deepCopy();
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
                .displayName("Task 09 受控触发节点")
                .agentType("quality_check_final".equals(nodeName) ? AgentType.REVIEWER : AgentType.EXTRACTOR)
                .status(TaskNodeStatus.SUCCESS)
                .planVersionId(plan.getId())
                .branchKey("root")
                .build();
    }

    private void saveReport(long taskId) {
        reportRepository.save(Report.builder()
                .taskId(taskId)
                .title("Task 09 受控验收报告")
                .content("# Controlled Acceptance")
                .summary("验证真实 Coordinator 到 V2 outbox 及只读链的生产接缝。")
                .qualityScore(60)
                .qualityPassed(false)
                .evidenceCount(1)
                .build());
    }

    private void saveCheckpoint(long taskId,
                                String nodeName,
                                TaskPlan plan,
                                CheckpointSeed seed) throws Exception {
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("decisionCount", seed.decisionCount());
        checkpoint.put("maxAutoDecisions", 2);
        checkpoint.put("dynamicBranchCountsBySection", seed.sectionCounts());
        checkpoint.put("sourceUrls", seed.sourceUrls());
        Map<String, Object> payload = Map.of(
                "checkpoint", checkpoint,
                "sourceUrls", seed.sourceUrls());
        workflowEventRepository.save(TaskWorkflowEvent.builder()
                .eventId("s2-checkpoint-" + UUID.randomUUID())
                .taskId(taskId)
                .nodeName(nodeName)
                .planVersionId(plan.getId())
                .branchKey("root")
                .eventType(WorkflowEventType.ORCHESTRATION_CHECKPOINT_UPDATED)
                .deliveryStatus(TaskWorkflowEvent.STATUS_PENDING)
                .topic("task.workflow")
                .tag(WorkflowEventType.ORCHESTRATION_CHECKPOINT_UPDATED.name())
                .payload(objectMapper.writeValueAsString(payload))
                .sourceUrls(objectMapper.writeValueAsString(seed.sourceUrls()))
                .build());
    }

    private record RuntimeCycle(long taskId,
                                OrchestrationRuntimeDecisionBatch batch,
                                JsonNode payload) {
    }

    private record CheckpointSeed(int decisionCount,
                                  Map<String, Integer> sectionCounts,
                                  List<String> sourceUrls) {
    }
}
