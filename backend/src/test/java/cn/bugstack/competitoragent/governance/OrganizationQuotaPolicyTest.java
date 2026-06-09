package cn.bugstack.competitoragent.governance;

import cn.bugstack.competitoragent.model.entity.ConnectorRuntimeLease;
import cn.bugstack.competitoragent.model.entity.OrganizationQuotaSnapshot;
import cn.bugstack.competitoragent.repository.ConnectorRuntimeLeaseRepository;
import cn.bugstack.competitoragent.repository.OrganizationQuotaSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
class OrganizationQuotaPolicyTest {

    @Autowired
    private OrganizationQuotaSnapshotRepository organizationQuotaSnapshotRepository;

    @Autowired
    private ConnectorRuntimeLeaseRepository connectorRuntimeLeaseRepository;

    @Test
    void shouldReturnUnifiedQuotaDecisionForTaskAndModelRuntime() throws Exception {
        // Task 5.8.b 的关键不是单纯“有两个表”，
        // 而是任务与模型入口在真正运行前都能通过同一策略拿到可执行 / 不可执行的正式判定结果。
        organizationQuotaSnapshotRepository.save(buildSnapshot(
                "org-acme",
                "TASK",
                "TASK_CONCURRENCY",
                3,
                1,
                1,
                "COUNT",
                List.of("https://ops.example.com/quota/task-concurrency")
        ));
        organizationQuotaSnapshotRepository.save(buildSnapshot(
                "org-acme",
                "MODEL",
                "MODEL_DAILY_BUDGET",
                100,
                92,
                6,
                "TOKENS",
                List.of("https://ops.example.com/quota/model-budget")
        ));

        Class<?> policyClass = Class.forName("cn.bugstack.competitoragent.governance.OrganizationQuotaPolicy");
        Object policy = instantiate(policyClass, OrganizationQuotaSnapshotRepository.class, organizationQuotaSnapshotRepository);

        Object taskDecision = invoke(
                policy,
                "checkAndReserve",
                new Class[]{String.class, String.class, String.class, int.class, List.class},
                "org-acme",
                "TASK",
                "TASK_CONCURRENCY",
                1,
                List.of("https://ops.example.com/quota/task-concurrency")
        );
        Object modelDecision = invoke(
                policy,
                "checkAndReserve",
                new Class[]{String.class, String.class, String.class, int.class, List.class},
                "org-acme",
                "MODEL",
                "MODEL_DAILY_BUDGET",
                5,
                List.of("https://ops.example.com/quota/model-budget")
        );

        assertTrue((Boolean) readFieldValue(taskDecision, "allowed"));
        assertEquals("ALLOWED_RESERVED", readFieldValue(taskDecision, "decisionCode"));
        assertEquals(2, reloadSnapshot("org-acme", "TASK", "TASK_CONCURRENCY").getReservedValue());

        assertFalse((Boolean) readFieldValue(modelDecision, "allowed"));
        assertEquals("BLOCKED_QUOTA_EXCEEDED", readFieldValue(modelDecision, "decisionCode"));
        assertEquals(6, reloadSnapshot("org-acme", "MODEL", "MODEL_DAILY_BUDGET").getReservedValue());
        assertTrue(String.valueOf(readFieldValue(modelDecision, "summary")).contains("MODEL_DAILY_BUDGET"));
    }

    @Test
    void shouldAllocateConnectorRuntimeLeaseThroughUnifiedRegistry() throws Exception {
        // 连接器治理不能继续散落在各业务链路里各自判断，
        // 注册表至少要能给出“已成功占位”或“当前被谁占用”的统一结果。
        Class<?> registryClass = Class.forName("cn.bugstack.competitoragent.governance.ConnectorRuntimeRegistry");
        Object registry = instantiate(registryClass, ConnectorRuntimeLeaseRepository.class, connectorRuntimeLeaseRepository);

        Object acquiredDecision = invoke(
                registry,
                "acquireLease",
                new Class[]{String.class, String.class, String.class, String.class, Duration.class, List.class},
                "org-acme",
                "feishu-drive",
                "SYNC_PULL",
                "task-501",
                Duration.ofMinutes(20),
                List.of("https://open.feishu.cn/document/client-docs/docs/drive-v1")
        );
        Object blockedDecision = invoke(
                registry,
                "acquireLease",
                new Class[]{String.class, String.class, String.class, String.class, Duration.class, List.class},
                "org-acme",
                "feishu-drive",
                "SYNC_PULL",
                "task-502",
                Duration.ofMinutes(20),
                List.of("https://open.feishu.cn/document/client-docs/docs/drive-v1")
        );

        assertTrue((Boolean) readFieldValue(acquiredDecision, "allowed"));
        assertEquals("LEASE_ACQUIRED", readFieldValue(acquiredDecision, "decisionCode"));
        assertNotNull(readFieldValue(acquiredDecision, "leaseToken"));

        List<ConnectorRuntimeLease> storedLeases = connectorRuntimeLeaseRepository.findAll();
        assertEquals(1, storedLeases.size());
        assertEquals("task-501", storedLeases.get(0).getLeaseOwner());
        assertEquals("HELD", storedLeases.get(0).getLeaseStatus());

        assertFalse((Boolean) readFieldValue(blockedDecision, "allowed"));
        assertEquals("CONNECTOR_BUSY", readFieldValue(blockedDecision, "decisionCode"));
        assertEquals("task-501", readFieldValue(blockedDecision, "blockingOwner"));
    }

    @Test
    void shouldReleaseReservedQuotaAndAllowNextReservationAfterFailureRecovery() throws Exception {
        // Task 5.8.e 要求故障恢复后不能留下永久 reservedValue，
        // 否则任务失败重试会被错误地继续判成“组织并发已占满”。
        organizationQuotaSnapshotRepository.save(buildSnapshot(
                "org-acme",
                "TASK",
                "TASK_CONCURRENCY",
                2,
                0,
                1,
                "COUNT",
                List.of("https://ops.example.com/quota/task-concurrency")
        ));

        Class<?> policyClass = Class.forName("cn.bugstack.competitoragent.governance.OrganizationQuotaPolicy");
        Object policy = instantiate(policyClass, OrganizationQuotaSnapshotRepository.class, organizationQuotaSnapshotRepository);

        Object releasedDecision = invoke(
                policy,
                "releaseReservation",
                new Class[]{String.class, String.class, String.class, int.class, List.class},
                "org-acme",
                "TASK",
                "TASK_CONCURRENCY",
                1,
                List.of("https://ops.example.com/recovery/task-release")
        );
        int releasedReservedValue = reloadSnapshot("org-acme", "TASK", "TASK_CONCURRENCY").getReservedValue();
        Object nextDecision = invoke(
                policy,
                "checkAndReserve",
                new Class[]{String.class, String.class, String.class, int.class, List.class},
                "org-acme",
                "TASK",
                "TASK_CONCURRENCY",
                2,
                List.of("https://ops.example.com/quota/task-concurrency")
        );

        assertTrue((Boolean) readFieldValue(releasedDecision, "allowed"));
        assertEquals("RELEASED_RESERVED", readFieldValue(releasedDecision, "decisionCode"));
        assertEquals(0, releasedReservedValue);
        assertTrue((Boolean) readFieldValue(nextDecision, "allowed"));
        assertEquals("ALLOWED_RESERVED", readFieldValue(nextDecision, "decisionCode"));
        assertEquals(2, reloadSnapshot("org-acme", "TASK", "TASK_CONCURRENCY").getReservedValue());
    }

    @Test
    void shouldRecycleExpiredLeaseBeforeBlockingNextConnectorRuntimeRequest() throws Exception {
        // Task 5.8.e 要求连接器中断或超时后要能回收旧租约，
        // 否则后续同步会被僵尸 HELD 租约永久阻断。
        connectorRuntimeLeaseRepository.save(ConnectorRuntimeLease.builder()
                .organizationKey("org-acme")
                .connectorKey("feishu-drive")
                .runtimeSlot("SYNC_PULL")
                .leaseOwner("task-501")
                .leaseStatus("HELD")
                .leaseToken("lease-expired-501")
                .sourceUrls(List.of("https://open.feishu.cn/document/client-docs/docs/drive-v1"))
                .acquiredAt(LocalDateTime.now().minusMinutes(40))
                .expiresAt(LocalDateTime.now().minusMinutes(10))
                .build());

        Class<?> registryClass = Class.forName("cn.bugstack.competitoragent.governance.ConnectorRuntimeRegistry");
        Object registry = instantiate(registryClass, ConnectorRuntimeLeaseRepository.class, connectorRuntimeLeaseRepository);

        Object acquiredDecision = invoke(
                registry,
                "acquireLease",
                new Class[]{String.class, String.class, String.class, String.class, Duration.class, List.class},
                "org-acme",
                "feishu-drive",
                "SYNC_PULL",
                "task-502",
                Duration.ofMinutes(20),
                List.of("https://open.feishu.cn/document/client-docs/docs/drive-v1")
        );

        assertTrue((Boolean) readFieldValue(acquiredDecision, "allowed"));
        assertEquals("LEASE_ACQUIRED", readFieldValue(acquiredDecision, "decisionCode"));
        List<ConnectorRuntimeLease> storedLeases = connectorRuntimeLeaseRepository.findAll();
        assertEquals(2, storedLeases.size());
        assertEquals(1, storedLeases.stream().filter(lease -> "HELD".equals(lease.getLeaseStatus())).count());
        assertEquals(1, storedLeases.stream().filter(lease -> "EXPIRED".equals(lease.getLeaseStatus())).count());
    }

    /**
     * 通过反射实例化新治理对象，确保 Red 阶段先暴露“正式策略/注册表不存在”的真实缺口，
     * 而不是因为测试文件直接引用新类，导致测试连运行入口都拿不到。
     */
    private Object instantiate(Class<?> type, Class<?> dependencyType, Object dependency) throws Exception {
        Constructor<?> constructor = type.getDeclaredConstructor(dependencyType);
        constructor.setAccessible(true);
        return constructor.newInstance(dependency);
    }

    /**
     * 统一通过反射触发新对象行为，让测试关注“对外能力是否成立”，
     * 而不是先绑定未来可能微调的具体实现细节。
     */
    private Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... arguments) {
        Method method = ReflectionUtils.findMethod(target.getClass(), methodName, parameterTypes);
        assertNotNull(method, () -> "缺少方法：" + target.getClass().getSimpleName() + "." + methodName);
        ReflectionUtils.makeAccessible(method);
        return ReflectionUtils.invokeMethod(method, target, arguments);
    }

    /**
     * 这里直接读取字段，确保即使当前阶段还没有面向外部接口，
     * 也能先把统一判定对象的核心状态稳定锁进测试。
     */
    private Object readFieldValue(Object target, String fieldName) {
        Field field = ReflectionUtils.findField(target.getClass(), fieldName);
        assertNotNull(field, () -> "缺少字段：" + target.getClass().getSimpleName() + "." + fieldName);
        ReflectionUtils.makeAccessible(field);
        return ReflectionUtils.getField(field, target);
    }

    private OrganizationQuotaSnapshot reloadSnapshot(String organizationKey, String quotaScope, String quotaKey) {
        return organizationQuotaSnapshotRepository.findAll().stream()
                .filter(snapshot -> organizationKey.equals(snapshot.getOrganizationKey()))
                .filter(snapshot -> quotaScope.equals(snapshot.getQuotaScope()))
                .filter(snapshot -> quotaKey.equals(snapshot.getQuotaKey()))
                .findFirst()
                .orElseThrow();
    }

    private OrganizationQuotaSnapshot buildSnapshot(String organizationKey,
                                                    String quotaScope,
                                                    String quotaKey,
                                                    int limitValue,
                                                    int usedValue,
                                                    int reservedValue,
                                                    String quotaUnit,
                                                    List<String> sourceUrls) {
        return OrganizationQuotaSnapshot.builder()
                .organizationKey(organizationKey)
                .quotaScope(quotaScope)
                .quotaKey(quotaKey)
                .limitValue(limitValue)
                .usedValue(usedValue)
                .reservedValue(reservedValue)
                .quotaUnit(quotaUnit)
                .snapshotStatus("ACTIVE")
                .sourceUrls(sourceUrls)
                .snapshotAt(LocalDateTime.of(2026, 6, 8, 12, 0))
                .build();
    }
}
