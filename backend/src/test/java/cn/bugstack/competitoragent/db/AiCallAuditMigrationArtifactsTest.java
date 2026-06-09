package cn.bugstack.competitoragent.db;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AiCallAuditMigrationArtifactsTest {

    @Test
    void shouldProvideAiCallAuditMigrationScript() throws IOException {
        // AI 调用审计已经进入正式治理链路，迁移脚本必须跟随代码一起落地。
        ClassPathResource resource = new ClassPathResource("db/migration/V16__create_ai_call_audit_table.sql");

        assertTrue(resource.exists());
        assertTrue(resource.getContentAsString(StandardCharsets.UTF_8).toLowerCase().contains("ai_call_audit_record"));
    }
}
