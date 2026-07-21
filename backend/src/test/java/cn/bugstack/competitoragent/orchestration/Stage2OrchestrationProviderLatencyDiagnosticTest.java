package cn.bugstack.competitoragent.orchestration;

import cn.bugstack.competitoragent.CompetitorAgentApplication;
import cn.bugstack.competitoragent.config.AiProviderProperties;
import cn.bugstack.competitoragent.config.PlaywrightBrowserManager;
import cn.bugstack.competitoragent.context.AgentContextAssembler;
import cn.bugstack.competitoragent.llm.ModelChatOptions;
import cn.bugstack.competitoragent.llm.ModelGateway;
import cn.bugstack.competitoragent.llm.ModelInvocationContextHolder;
import cn.bugstack.competitoragent.model.entity.AiCallAuditRecord;
import cn.bugstack.competitoragent.repository.AiCallAuditRecordRepository;
import cn.bugstack.competitoragent.task.TaskExecutionLockService;
import cn.bugstack.competitoragent.task.TaskSnapshotCacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 4 Provider 时延诊断。
 * 只允许一次隔离的 active Provider 调用，输出耗时、hash、稳定解析码和 token 数值，不输出 Prompt/响应正文。
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
@EnabledIfEnvironmentVariable(named = "RUN_STAGE2_DIAGNOSTIC", matches = "true")
class Stage2OrchestrationProviderLatencyDiagnosticTest {

    private static final long TASK_ID = 902L;
    private static final long DIAGNOSTIC_TIMEOUT_MS = 20_000L;

    @Autowired
    private ModelGateway modelGateway;
    @Autowired
    private AiProviderProperties providerProperties;
    @Autowired
    private OrchestrationDecisionPromptBuilder promptBuilder;
    @Autowired
    private OrchestrationDecisionResponseParser parser;
    @Autowired
    private DecisionPolicyRuleSet ruleSet;
    @Autowired
    private AiCallAuditRecordRepository auditRepository;
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

    @BeforeEach
    void setUp() {
        auditRepository.deleteByTaskId(TASK_ID);
    }

    @Test
    void shouldMeasureOneFullPromptAgainstActiveProviderWithoutChangingAcceptanceDeadline() throws Exception {
        Stage2DecisionFixtureLoader.FixtureCase fixture =
                new Stage2DecisionFixtureLoader(objectMapper).requireCase("extractor-source-backed-gap");
        OrchestrationContext context = fixture.context().toBuilder()
                .taskId(TASK_ID)
                .build()
                .normalized();
        OrchestrationDecisionPrompt prompt = promptBuilder.build(context, ruleSet);
        String traceId = "s2diag-" + UUID.randomUUID();

        String activeProvider = providerProperties.getActiveProvider();
        Map<String, AiProviderProperties.ProviderConfig> originalProviders =
                new LinkedHashMap<>(providerProperties.getProviders());
        int originalMaxRetries = providerProperties.getMaxRetries();
        String response;
        long startedNanos = System.nanoTime();
        try {
            // 诊断只隔离当前 active Provider 的单次 HTTP attempt，避免 fallback/retry 混入耗时结论。
            providerProperties.setProviders(Map.of(
                    activeProvider,
                    providerProperties.getActiveProviderConfig()));
            providerProperties.setMaxRetries(1);
            response = ModelInvocationContextHolder.withContext(
                    TASK_ID,
                    "stage2_provider_latency_diagnostic",
                    traceId,
                    () -> modelGateway.chatForJson(
                            prompt.systemPrompt(),
                            prompt.userPrompt(),
                            prompt.responseSchema(),
                            new ModelChatOptions(0.0d, DIAGNOSTIC_TIMEOUT_MS)));
        } finally {
            providerProperties.setProviders(originalProviders);
            providerProperties.setMaxRetries(originalMaxRetries);
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);

        OrchestrationDecisionParseResult parseResult = parser.parse(
                response,
                context,
                OrchestrationDecisionOrigin.LLM_PRIMARY);
        List<AiCallAuditRecord> audits = auditRepository.findByTraceIdOrderByCreatedAtAsc(traceId);
        assertThat(audits).hasSize(1);
        AiCallAuditRecord audit = audits.get(0);
        String issueCodes = parseResult.issues().stream()
                .map(OrchestrationDecisionParseResult.ParseIssue::code)
                .distinct()
                .sorted()
                .toList()
                .toString();
        System.out.printf(
                "STAGE2_LATENCY|provider=%s|model=%s|timeoutMs=%d|elapsedMs=%d|systemChars=%d|userChars=%d|schemaChars=%d|responseHash=%s|parseSuccess=%s|issues=%s|input=%d|output=%d|total=%d|estimatedInput=%d%n",
                activeProvider,
                audit.getModelName(),
                DIAGNOSTIC_TIMEOUT_MS,
                elapsedMs,
                prompt.systemPrompt().length(),
                prompt.userPrompt().length(),
                prompt.responseSchema().length(),
                OrchestrationDecisionHashing.hashResponse(response),
                parseResult.successful(),
                issueCodes,
                safeInt(audit.getInputTokens()),
                safeInt(audit.getOutputTokens()),
                safeInt(audit.getTotalTokens()),
                safeInt(audit.getEstimatedInputTokens()));

        assertThat(audit.isSuccess()).isTrue();
        assertThat(audit.getProviderKey()).isEqualToIgnoringCase(activeProvider);
        assertThat(audit.getEstimatedInputTokens()).isNotNull().isGreaterThan(0);
        assertThat(response).isNotBlank();
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }
}
