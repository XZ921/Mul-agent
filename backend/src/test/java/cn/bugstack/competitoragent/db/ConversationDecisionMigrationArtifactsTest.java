package cn.bugstack.competitoragent.db;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationDecisionMigrationArtifactsTest {

    @Test
    void shouldProvideConversationDecisionExtensionMigrationScript() throws IOException {
        // Task 5.5.a 需要把意图决策的风险等级、影响范围和确认对象落到正式迁移里，
        // 否则新环境启动后只能看到旧版布尔字段，无法支撑后续确认链路与审计回放。
        ClassPathResource resource = new ClassPathResource("db/migration/V21__extend_conversation_decision_tables.sql");

        assertTrue(resource.exists());

        String script = resource.getContentAsString(StandardCharsets.UTF_8).toLowerCase();
        assertTrue(script.contains("alter table intent_decision"));
        assertTrue(script.contains("risk_level"));
        assertTrue(script.contains("impact_scope"));
        assertTrue(script.contains("confirmation_request_payload"));
    }
}
