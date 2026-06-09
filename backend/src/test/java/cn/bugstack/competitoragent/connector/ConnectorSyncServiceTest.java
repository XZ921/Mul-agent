package cn.bugstack.competitoragent.connector;

import cn.bugstack.competitoragent.common.BusinessException;
import cn.bugstack.competitoragent.common.ResultCode;
import cn.bugstack.competitoragent.governance.ConnectorRuntimeRegistry;
import cn.bugstack.competitoragent.governance.QuotaDecision;
import cn.bugstack.competitoragent.knowledge.KnowledgeDomainService;
import cn.bugstack.competitoragent.model.entity.ConnectorSyncRecord;
import cn.bugstack.competitoragent.model.entity.KnowledgeDomain;
import cn.bugstack.competitoragent.repository.ConnectorSyncRecordRepository;
import cn.bugstack.competitoragent.repository.KnowledgeDomainRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 连接器同步服务测试。
 * <p>
 * Task 5.2.e 只允许把“连接器定义 + 资料映射 + 默认关闭的运行时开关”落地，
 * 不能提前演进成 Task 5.8 的真正连接器运行时平台。
 */
@ExtendWith(MockitoExtension.class)
class ConnectorSyncServiceTest {

    @Mock
    private KnowledgeDomainRepository knowledgeDomainRepository;

    @Mock
    private ConnectorSyncRecordRepository connectorSyncRecordRepository;

    @Mock
    private ConnectorRuntimeRegistry connectorRuntimeRegistry;

    private KnowledgeDomainService knowledgeDomainService;

    @BeforeEach
    void setUp() {
        knowledgeDomainService = new KnowledgeDomainService(knowledgeDomainRepository);
    }

    @Test
    void shouldExposeConnectorDefinitionsForControlledSources() {
        ConnectorSyncService connectorSyncService =
                new ConnectorSyncService(knowledgeDomainService, connectorSyncRecordRepository, false);

        List<ConnectorDefinition> definitions = connectorSyncService.listDefinitions();

        assertEquals(List.of("confluence-space", "feishu-docs", "notion-pages"),
                definitions.stream().map(ConnectorDefinition::getConnectorKey).toList());
        assertFalse(definitions.isEmpty());
        assertEquals("AUTHENTICATED_SOURCES", definitions.get(0).getSourceCategory());
        assertEquals("CONFLUENCE", definitions.get(0).getConnectorType());
    }

    @Test
    void shouldRejectManualSyncWhenRuntimeSwitchDisabled() {
        ConnectorSyncService connectorSyncService =
                new ConnectorSyncService(knowledgeDomainService, connectorSyncRecordRepository, false);
        when(knowledgeDomainRepository.findByDomainKey("org-product-docs"))
                .thenReturn(Optional.of(activeDomain(21L, "org-product-docs")));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> connectorSyncService.scheduleManualSync(
                        "org-product-docs",
                        "feishu-docs",
                        "{\"folder\":\"product\"}",
                        List.of("https://open.feishu.cn/document/product-plan")
                ));

        assertEquals(ResultCode.SERVICE_UNAVAILABLE, exception.getResultCode());
        verify(connectorSyncRecordRepository, never()).save(any(ConnectorSyncRecord.class));
    }

    @Test
    void shouldCreatePendingSyncRecordWhenRuntimeSwitchEnabled() {
        ConnectorSyncService connectorSyncService =
                new ConnectorSyncService(knowledgeDomainService, connectorSyncRecordRepository, true);
        when(knowledgeDomainRepository.findByDomainKey("org-product-docs"))
                .thenReturn(Optional.of(activeDomain(22L, "org-product-docs")));
        when(connectorSyncRecordRepository.save(any(ConnectorSyncRecord.class)))
                .thenAnswer(invocation -> {
                    ConnectorSyncRecord record = invocation.getArgument(0);
                    record.setId(301L);
                    return record;
                });

        ConnectorSyncRecord record = connectorSyncService.scheduleManualSync(
                "org-product-docs",
                "feishu-docs",
                "{\"folder\":\"product\"}",
                List.of("https://open.feishu.cn/document/product-plan")
        );

        assertEquals(301L, record.getId());
        assertEquals(22L, record.getKnowledgeDomainId());
        assertEquals("feishu-docs", record.getConnectorKey());
        assertEquals("FEISHU", record.getConnectorType());
        assertEquals("飞书文档", record.getConnectorLabel());
        assertEquals("PENDING", record.getSyncStatus());
        assertEquals("AUTHENTICATED_SOURCES", record.getSourceCategory());
        assertEquals(List.of("https://open.feishu.cn/document/product-plan"), record.getSourceUrls());
    }

    @Test
    void shouldBlockConnectorSyncWithStructuredGovernanceDecisionWhenConnectorBusy() {
        // Task 5.8.c 要求连接器同步链路在运行时槽位已被占用时返回统一治理结果，
        // 不能继续把连接器忙碌误记为普通服务不可用。
        when(knowledgeDomainRepository.findByDomainKey("org-product-docs"))
                .thenReturn(Optional.of(activeDomain(23L, "org-product-docs")));
        when(connectorRuntimeRegistry.acquireLease(any(), any(), any(), any(), any(Duration.class), any()))
                .thenReturn(QuotaDecision.deny(
                        "CONNECTOR_BUSY",
                        "当前连接器正在被其他任务占用，请等待释放后再重试",
                        "default-organization",
                        "CONNECTOR",
                        "feishu-docs:SYNC_PULL",
                        1,
                        0,
                        "task-501",
                        List.of("https://open.feishu.cn/document/product-plan")
                ));
        ConnectorSyncService connectorSyncService = buildServiceWithOptionalGovernance(true);

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> connectorSyncService.scheduleManualSync(
                        "org-product-docs",
                        "feishu-docs",
                        "{\"folder\":\"product\"}",
                        List.of("https://open.feishu.cn/document/product-plan")
                ));

        assertEquals("GovernanceBlockException", exception.getClass().getSimpleName());
        Object decision = readAccessor(exception, "decision");
        assertEquals("CONNECTOR_BUSY", readAccessor(decision, "decisionCode"));
        assertEquals("task-501", readAccessor(decision, "blockingOwner"));
        verify(connectorSyncRecordRepository, never()).save(any(ConnectorSyncRecord.class));
    }

    /**
     * 测试里统一构造一个允许连接器资料接入的启用知识域，
     * 这样断言可以聚焦在“定义映射”和“运行时开关”本身，而不是被其他治理条件干扰。
     */
    private KnowledgeDomain activeDomain(Long id, String domainKey) {
        return KnowledgeDomain.builder()
                .id(id)
                .domainKey(domainKey)
                .domainName(domainKey + "-domain")
                .allowedSourceCategories(List.of("AUTHENTICATED_SOURCES"))
                .defaultLifecycle("ACTIVE")
                .defaultTrustLevel("CURATED")
                .status("ACTIVE")
                .build();
    }

    private ConnectorSyncService buildServiceWithOptionalGovernance(boolean runtimeEnabled) {
        try {
            Constructor<ConnectorSyncService> constructor = ConnectorSyncService.class.getConstructor(
                    KnowledgeDomainService.class,
                    ConnectorSyncRecordRepository.class,
                    boolean.class,
                    ConnectorRuntimeRegistry.class
            );
            return constructor.newInstance(
                    knowledgeDomainService,
                    connectorSyncRecordRepository,
                    runtimeEnabled,
                    connectorRuntimeRegistry
            );
        } catch (NoSuchMethodException ignored) {
            return new ConnectorSyncService(knowledgeDomainService, connectorSyncRecordRepository, runtimeEnabled);
        } catch (Exception e) {
            throw new IllegalStateException("failed to construct ConnectorSyncService with governance for test", e);
        }
    }

    private Object readAccessor(Object target, String accessorName) {
        Method method = ReflectionUtils.findMethod(target.getClass(),
                "get" + Character.toUpperCase(accessorName.charAt(0)) + accessorName.substring(1));
        org.junit.jupiter.api.Assertions.assertNotNull(method,
                () -> "缺少访问器：" + target.getClass().getSimpleName() + "." + accessorName);
        ReflectionUtils.makeAccessible(method);
        return ReflectionUtils.invokeMethod(method, target);
    }
}
