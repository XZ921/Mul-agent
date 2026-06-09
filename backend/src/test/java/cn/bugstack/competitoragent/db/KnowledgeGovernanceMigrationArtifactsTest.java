package cn.bugstack.competitoragent.db;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeGovernanceMigrationArtifactsTest {

    @Test
    void shouldProvideKnowledgeGovernanceMigrationScript() throws IOException {
        // Task 5.2.a 需要先把组织级知识域与连接器同步表真正纳入迁移工件，
        // 否则后续统一接入服务没有稳定的数据落点。
        ClassPathResource resource = new ClassPathResource("db/migration/V17__create_knowledge_domain_and_connector_tables.sql");

        assertTrue(resource.exists());
        String sql = resource.getContentAsString(StandardCharsets.UTF_8).toLowerCase();
        assertTrue(sql.contains("knowledge_domain"));
        assertTrue(sql.contains("connector_sync_record"));
    }
}
