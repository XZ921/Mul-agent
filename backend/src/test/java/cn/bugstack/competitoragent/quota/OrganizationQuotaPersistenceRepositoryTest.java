package cn.bugstack.competitoragent.quota;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.repository.CrudRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
class OrganizationQuotaPersistenceRepositoryTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldPersistOrganizationQuotaSnapshotAndConnectorRuntimeLease() throws Exception {
        // Task 5.8.a 的完成标志不是“先有概念命名”，而是组织级治理对象已经能正式写入仓储。
        // 因此这里直接从黑盒角度校验：实体、Repository Bean、底层表结构三者必须同时存在。
        Class<?> snapshotEntityClass = Class.forName("cn.bugstack.competitoragent.model.entity.OrganizationQuotaSnapshot");
        Class<?> snapshotRepositoryClass = Class.forName("cn.bugstack.competitoragent.repository.OrganizationQuotaSnapshotRepository");
        Object snapshotRepositoryBean = applicationContext.getBean(snapshotRepositoryClass);
        assertNotNull(snapshotRepositoryBean);

        Object snapshot = snapshotEntityClass.getDeclaredConstructor().newInstance();
        writeFieldValue(snapshot, "organizationKey", "org-acme");
        writeFieldValue(snapshot, "quotaScope", "MODEL");
        writeFieldValue(snapshot, "quotaKey", "MODEL_DAILY_BUDGET");
        writeFieldValue(snapshot, "limitValue", 2000);
        writeFieldValue(snapshot, "usedValue", 320);
        writeFieldValue(snapshot, "reservedValue", 64);
        writeFieldValue(snapshot, "quotaUnit", "TOKENS");
        writeFieldValue(snapshot, "snapshotStatus", "ACTIVE");
        writeFieldValue(snapshot, "sourceUrls", List.of("https://ops.example.com/quotas/model-budget"));
        writeFieldValue(snapshot, "snapshotAt", LocalDateTime.of(2026, 6, 8, 10, 0));

        @SuppressWarnings("unchecked")
        CrudRepository<Object, Object> snapshotRepository = (CrudRepository<Object, Object>) snapshotRepositoryBean;
        snapshotRepository.save(snapshot);

        Map<String, Object> storedSnapshot = jdbcTemplate.queryForMap(
                "select organization_key, quota_key, limit_value, reserved_value, source_urls " +
                        "from organization_quota_snapshot where organization_key = ?",
                "org-acme"
        );

        assertEquals("MODEL_DAILY_BUDGET", storedSnapshot.get("quota_key"));
        assertEquals(2000, ((Number) storedSnapshot.get("limit_value")).intValue());
        assertEquals(64, ((Number) storedSnapshot.get("reserved_value")).intValue());
        assertTrue(String.valueOf(storedSnapshot.get("source_urls")).contains("https://ops.example.com/quotas/model-budget"));

        Class<?> leaseEntityClass = Class.forName("cn.bugstack.competitoragent.model.entity.ConnectorRuntimeLease");
        Class<?> leaseRepositoryClass = Class.forName("cn.bugstack.competitoragent.repository.ConnectorRuntimeLeaseRepository");
        Object leaseRepositoryBean = applicationContext.getBean(leaseRepositoryClass);
        assertNotNull(leaseRepositoryBean);

        Object lease = leaseEntityClass.getDeclaredConstructor().newInstance();
        writeFieldValue(lease, "organizationKey", "org-acme");
        writeFieldValue(lease, "connectorKey", "feishu-drive");
        writeFieldValue(lease, "runtimeSlot", "SYNC_PULL");
        writeFieldValue(lease, "leaseOwner", "task-501");
        writeFieldValue(lease, "leaseStatus", "HELD");
        writeFieldValue(lease, "leaseToken", "lease-org-acme-feishu-drive-001");
        writeFieldValue(lease, "sourceUrls", List.of("https://open.feishu.cn/document/client-docs/docs/drive-v1"));
        writeFieldValue(lease, "expiresAt", LocalDateTime.of(2026, 6, 8, 11, 0));

        @SuppressWarnings("unchecked")
        CrudRepository<Object, Object> leaseRepository = (CrudRepository<Object, Object>) leaseRepositoryBean;
        leaseRepository.save(lease);

        Map<String, Object> storedLease = jdbcTemplate.queryForMap(
                "select organization_key, connector_key, lease_status, lease_token, source_urls " +
                        "from connector_runtime_lease where lease_token = ?",
                "lease-org-acme-feishu-drive-001"
        );

        assertEquals("org-acme", storedLease.get("organization_key"));
        assertEquals("feishu-drive", storedLease.get("connector_key"));
        assertEquals("HELD", storedLease.get("lease_status"));
        assertTrue(String.valueOf(storedLease.get("source_urls")).contains("https://open.feishu.cn/document/client-docs/docs/drive-v1"));
    }

    /**
     * 通过反射写字段，确保 Red 阶段先暴露“正式对象或字段不存在”的真实缺口，
     * 而不是因为测试代码直接引用新类导致编译失败，影响黑盒验证价值。
     */
    private void writeFieldValue(Object target, String fieldName, Object value) {
        Field field = ReflectionUtils.findField(target.getClass(), fieldName);
        assertNotNull(field, () -> "缺少字段：" + target.getClass().getSimpleName() + "." + fieldName);
        ReflectionUtils.makeAccessible(field);
        ReflectionUtils.setField(field, target, value);
    }
}
