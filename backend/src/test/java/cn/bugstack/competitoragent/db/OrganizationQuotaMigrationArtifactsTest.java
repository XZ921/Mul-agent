package cn.bugstack.competitoragent.db;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OrganizationQuotaMigrationArtifactsTest {

    @Test
    void shouldProvideOrganizationQuotaMigrationScript() throws IOException {
        // Task 5.8.a 需要先把组织级配额快照与连接器运行时租约的正式迁移工件补齐，
        // 否则后续策略层即使实现完成，也没有稳定的持久化落点可用。
        ClassPathResource resource = new ClassPathResource("db/migration/V23__create_organization_quota_tables.sql");

        assertTrue(resource.exists());
        String sql = resource.getContentAsString(StandardCharsets.UTF_8).toLowerCase();
        assertTrue(sql.contains("organization_quota_snapshot"));
        assertTrue(sql.contains("connector_runtime_lease"));
    }
}
