package cn.bugstack.competitoragent.db;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceSourceMigrationArtifactsTest {

    @Test
    void shouldContainDiscoveryReasonExpansionMigration() throws IOException {
        // discovery_reason 会承接真实补源说明与诊断文本，迁移脚本必须显式扩容为 TEXT，
        // 否则 Task 66 live 样本中的长说明仍会在落库阶段失败。
        ClassPathResource resource =
                new ClassPathResource("db/migration/V30__expand_evidence_source_discovery_reason.sql");

        assertTrue(resource.exists());
        String sql = resource.getContentAsString(StandardCharsets.UTF_8);
        assertTrue(sql.contains("ALTER TABLE evidence_source"));
        assertTrue(sql.contains("ALTER COLUMN discovery_reason TYPE TEXT"));
    }
}
