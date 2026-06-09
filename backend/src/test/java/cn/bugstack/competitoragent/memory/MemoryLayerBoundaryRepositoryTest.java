package cn.bugstack.competitoragent.memory;

import cn.bugstack.competitoragent.model.entity.CompetitorKnowledge;
import cn.bugstack.competitoragent.model.entity.MemorySnapshot;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import cn.bugstack.competitoragent.repository.MemorySnapshotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.repository.CrudRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
class MemoryLayerBoundaryRepositoryTest {

    @Autowired
    private MemorySnapshotRepository memorySnapshotRepository;

    @Autowired
    private CompetitorKnowledgeRepository competitorKnowledgeRepository;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldExposeMemoryLayerBoundariesAndPersistReuseAuditRecord() throws Exception {
        // Task 5.4.a 先锁定“分层载体”这件事：
        // MemorySnapshot 需要明确自己承载的是哪一层记忆，避免后续融合时只剩一个模糊快照。
        MemorySnapshot savedSnapshot = memorySnapshotRepository.save(MemorySnapshot.builder()
                .taskId(3001L)
                .nodeName("conversation")
                .queryText("Notion AI memory reuse")
                .summary("记录一次运行态上下文快照")
                .sourceUrls(List.of("https://example.com/notion-ai"))
                .build());

        // CompetitorKnowledge 需要明确自己是可复用的哪类记忆，避免把任务内知识和跨任务知识混成一类。
        CompetitorKnowledge savedKnowledge = competitorKnowledgeRepository.save(CompetitorKnowledge.builder()
                .taskId(4001L)
                .competitorName("Notion AI")
                .summary("沉淀一条可复用的竞品结论")
                .sourceUrls("[\"https://example.com/notion-ai\"]")
                .build());

        assertEquals("SHORT_TERM", readFieldValue(savedSnapshot, "memoryLayer"));
        assertEquals("DOMAIN", readFieldValue(savedKnowledge, "memoryLayer"));

        // 复用记录对象必须已经成为正式仓储对象，而不是仅靠日志或后续实现时临时拼装。
        Class<?> entityClass = Class.forName("cn.bugstack.competitoragent.model.entity.MemoryReuseRecord");
        Class<?> repositoryClass = Class.forName("cn.bugstack.competitoragent.repository.MemoryReuseRecordRepository");
        Object repositoryBean = applicationContext.getBean(repositoryClass);
        assertNotNull(repositoryBean);

        Object reuseRecord = entityClass.getDeclaredConstructor().newInstance();
        writeFieldValue(reuseRecord, "taskId", 5001L);
        writeFieldValue(reuseRecord, "consumerNodeName", "report_writer");
        writeFieldValue(reuseRecord, "sourceMemoryLayer", "DOMAIN");
        writeFieldValue(reuseRecord, "sourceObjectType", "COMPETITOR_KNOWLEDGE");
        writeFieldValue(reuseRecord, "sourceRecordId", savedKnowledge.getId());
        writeFieldValue(reuseRecord, "sourceTaskId", savedKnowledge.getTaskId());
        writeFieldValue(reuseRecord, "sourceSummary", "复用了跨任务可复用的竞品知识摘要");
        writeFieldValue(reuseRecord, "sourceUrls", List.of("https://example.com/notion-ai"));

        @SuppressWarnings("unchecked")
        CrudRepository<Object, Object> repository = (CrudRepository<Object, Object>) repositoryBean;
        repository.save(reuseRecord);

        Map<String, Object> storedRecord = jdbcTemplate.queryForMap(
                "select source_memory_layer, source_urls from memory_reuse_record where task_id = ?",
                5001L
        );

        assertEquals("DOMAIN", storedRecord.get("source_memory_layer"));
        assertTrue(String.valueOf(storedRecord.get("source_urls")).contains("https://example.com/notion-ai"));
    }

    /**
     * 通过反射读取字段，确保测试先从“对象边界是否存在”这个黑盒信号开始失败，
     * 而不是因为直接引用未实现的新字段导致测试文件编译不过。
     */
    private Object readFieldValue(Object target, String fieldName) throws IllegalAccessException {
        Field field = ReflectionUtils.findField(target.getClass(), fieldName);
        assertNotNull(field, () -> "缺少字段：" + target.getClass().getSimpleName() + "." + fieldName);
        ReflectionUtils.makeAccessible(field);
        return field.get(target);
    }

    /**
     * 复用记录对象在本轮尚未有正式服务链路，因此测试直接写字段，
     * 只验证“正式对象与留痕落库能力是否存在”。
     */
    private void writeFieldValue(Object target, String fieldName, Object value) {
        Field field = ReflectionUtils.findField(target.getClass(), fieldName);
        assertNotNull(field, () -> "缺少字段：" + target.getClass().getSimpleName() + "." + fieldName);
        ReflectionUtils.makeAccessible(field);
        ReflectionUtils.setField(field, target, value);
    }
}
