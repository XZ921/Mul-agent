package cn.bugstack.competitoragent.db;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskRagMigrationArtifactsTest {

    @Test
    void shouldProvideMemorySnapshotMigrationScript() throws IOException {
        // MemorySnapshot 已经进入正式链路，迁移脚本必须同步存在，
        // 确保新环境可以正常建表。
        ClassPathResource resource = new ClassPathResource("db/migration/V14__create_memory_snapshot_table.sql");

        assertTrue(resource.exists());
        assertTrue(resource.getContentAsString(StandardCharsets.UTF_8).toLowerCase().contains("memory_snapshot"));
    }

    @Test
    void shouldProvideRetrievalScopeMigrationScriptForThreeLevelRecall() throws IOException {
        // Task 5.3 要把当前 task-centric 的 RAG 表结构升级为
        // Task / Domain / Organization 三层召回治理模型，因此 V18 迁移必须显式存在。
        ClassPathResource resource = new ClassPathResource("db/migration/V18__add_retrieval_scope_columns.sql");

        assertTrue(resource.exists());

        String script = resource.getContentAsString(StandardCharsets.UTF_8).toLowerCase();
        assertTrue(script.contains("alter table retrieval_chunk"));
        assertTrue(script.contains("alter table retrieval_index"));
        assertTrue(script.contains("alter column task_id drop not null"));
        assertTrue(script.contains("retrieval_scope"));
        assertTrue(script.contains("scope_ref_key"));
        assertTrue(script.contains("knowledge_domain_key"));
    }
}
