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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 09 真实 Provider 最小可用性预检。
 * 该测试不进入 Orchestrator，只验证生产 ModelGateway 的认证、模型路由、短超时和 AI 审计。
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
class Stage2ProviderPreflightTest {

    private static final long PREFLIGHT_TASK_ID = 900L;
    private static final String PREFLIGHT_NODE = "stage2_provider_preflight";
    private static final long PREFLIGHT_TIMEOUT_MS = 4000L;

    @Autowired
    private ModelGateway modelGateway;

    @Autowired
    private AiProviderProperties providerProperties;

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
        auditRepository.deleteByTaskId(PREFLIGHT_TASK_ID);
    }

    @Test
    void shouldReachConfiguredProviderAndPersistSafeAuditWithinDeadline() throws Exception {
        // AI 审计表 trace_id 上限为 50；短前缀加标准 UUID 共 41 字符，避免预检本身破坏审计写入。
        String traceId = "s2pf-" + UUID.randomUUID();
        String response = ModelInvocationContextHolder.withContext(
                PREFLIGHT_TASK_ID,
                PREFLIGHT_NODE,
                traceId,
                () -> modelGateway.chatForJson(
                        "Return the smallest valid JSON object matching the schema.",
                        "Set ok to true.",
                        "{\"type\":\"object\",\"properties\":{\"ok\":{\"type\":\"boolean\",\"const\":true}},\"required\":[\"ok\"],\"additionalProperties\":false}",
                        new ModelChatOptions(0.0d, PREFLIGHT_TIMEOUT_MS)));

        JsonNode root = objectMapper.readTree(response);
        assertThat(root.path("ok").asBoolean(false)).isTrue();
        assertThat(modelGateway.getModelName()).isNotBlank();
        assertThat(providerProperties.getActiveProvider()).isNotBlank();
        assertThat(providerProperties.getModelName()).isNotBlank();
        assertThat(providerProperties.getActiveProviderConfig().getApiKey()).isNotBlank();

        List<AiCallAuditRecord> audits = auditRepository.findByTaskIdOrderByCreatedAtDesc(PREFLIGHT_TASK_ID);
        assertThat(audits).isNotEmpty();
        assertThat(audits)
                .filteredOn(item -> traceId.equals(item.getTraceId()))
                .anySatisfy(item -> {
                    assertThat(item.getNodeName()).isEqualTo(PREFLIGHT_NODE);
                    assertThat(item.isSuccess()).isTrue();
                    assertThat(item.getProviderKey()).isEqualToIgnoringCase(providerProperties.getActiveProvider());
                    assertThat(item.getModelName()).isNotBlank();
                    assertThat(item.getEstimatedInputTokens()).isNotNull().isGreaterThan(0);
                    assertThat(item.getTotalTokens()).isNotNull().isGreaterThanOrEqualTo(0);
                });
    }
}
