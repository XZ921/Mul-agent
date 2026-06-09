package cn.bugstack.competitoragent.memory;

import cn.bugstack.competitoragent.model.entity.MemoryReuseRecord;
import cn.bugstack.competitoragent.model.entity.MemorySnapshot;
import cn.bugstack.competitoragent.repository.CompetitorKnowledgeRepository;
import cn.bugstack.competitoragent.repository.MemoryReuseRecordRepository;
import cn.bugstack.competitoragent.repository.MemorySnapshotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 5.4.c 先从黑盒层锁定“正式写回策略已经存在”这件事。
 * <p>
 * 这里不提前绑定具体实现细节，而是只要求：
 * 1. 有正式的 MemoryWritebackService / MemoryReusePolicy；
 * 2. 写回结果必须带上版本来源、失效规则与 sourceUrls；
 * 3. 复用说明要能结构化落到 MemoryReuseRecord，避免后续只能靠日志猜测。
 */
class MemoryWritebackServiceTest {

    private final MemorySnapshotRepository memorySnapshotRepository = mock(MemorySnapshotRepository.class);
    private final CompetitorKnowledgeRepository competitorKnowledgeRepository = mock(CompetitorKnowledgeRepository.class);
    private final MemoryReuseRecordRepository memoryReuseRecordRepository = mock(MemoryReuseRecordRepository.class);

    @Test
    void shouldPersistStructuredWritebackPolicyVersionOriginAndInvalidationRule() throws Exception {
        // 先固定写回后的最小可观察结果：短期记忆载体要被正式保存，并显式带上版本来源与失效规则，
        // 防止“旧结论污染新任务”仍然只能靠调用方口头约定。
        when(memorySnapshotRepository.save(any(MemorySnapshot.class))).thenAnswer(invocation -> {
            MemorySnapshot snapshot = invocation.getArgument(0);
            snapshot.setId(801L);
            return snapshot;
        });
        when(memoryReuseRecordRepository.save(any(MemoryReuseRecord.class))).thenAnswer(invocation -> {
            MemoryReuseRecord record = invocation.getArgument(0);
            record.setId(901L);
            return record;
        });

        Class<?> serviceClass = Class.forName("cn.bugstack.competitoragent.memory.MemoryWritebackService");
        Class<?> requestClass = Class.forName("cn.bugstack.competitoragent.memory.MemoryWritebackService$WritebackRequest");
        Object service = newService(serviceClass);
        Object request = newRequest(requestClass);

        Method writebackMethod = ReflectionUtils.findMethod(serviceClass, "writeback", requestClass);
        assertNotNull(writebackMethod, "缺少正式写回入口：MemoryWritebackService.writeback");

        Object result = writebackMethod.invoke(service, request);
        assertNotNull(result, "写回结果不能为空");

        Field snapshotField = ReflectionUtils.findField(result.getClass(), "memorySnapshot");
        assertNotNull(snapshotField, "写回结果缺少 memorySnapshot");
        ReflectionUtils.makeAccessible(snapshotField);
        Object savedSnapshot = snapshotField.get(result);
        assertNotNull(savedSnapshot, "写回后应返回已保存的 MemorySnapshot");
        assertEquals("SHORT_TERM", readField(savedSnapshot, "memoryLayer"));
        assertEquals("TASK_RAG@PLAN-22:analysis", readField(savedSnapshot, "versionSource"));
        assertEquals("TASK_RERUN", readField(savedSnapshot, "invalidationScope"));
        assertEquals("PLAN_VERSION_CHANGED", readField(savedSnapshot, "invalidationReason"));
        assertEquals(List.of("https://example.com/notion-ai/pricing"), readField(savedSnapshot, "sourceUrls"));

        Field recordField = ReflectionUtils.findField(result.getClass(), "memoryReuseRecord");
        assertNotNull(recordField, "写回结果缺少 memoryReuseRecord");
        ReflectionUtils.makeAccessible(recordField);
        Object savedRecord = recordField.get(result);
        assertNotNull(savedRecord, "写回后应返回结构化复用说明");
        assertEquals("WRITEBACK_AUDIT", readField(savedRecord, "sourceObjectType"));
        assertEquals("来自当前任务已核实结论，仅在同计划版本内复用，计划版本变化后失效", readField(savedRecord, "reuseReason"));
        assertEquals("TASK_RAG@PLAN-22:analysis", readField(savedRecord, "versionSource"));
        assertEquals("TASK_RERUN", readField(savedRecord, "invalidationScope"));
        assertTrue(((List<?>) readField(savedRecord, "sourceUrls")).contains("https://example.com/notion-ai/pricing"));

        Class<?> policyClass = Class.forName("cn.bugstack.competitoragent.memory.MemoryReusePolicy");
        assertNotNull(policyClass, "缺少正式策略对象：MemoryReusePolicy");
    }

    /**
     * 通过反射优先锁定正式构造器，
     * 避免在实现尚未补齐之前让测试直接在编译期绑定不存在的新类型。
     */
    private Object newService(Class<?> serviceClass) throws Exception {
        Constructor<?> constructor = serviceClass.getConstructor(
                MemorySnapshotRepository.class,
                CompetitorKnowledgeRepository.class,
                MemoryReuseRecordRepository.class
        );
        return constructor.newInstance(memorySnapshotRepository, competitorKnowledgeRepository, memoryReuseRecordRepository);
    }

    /**
     * 请求对象也通过反射构建，只要求当前子任务真正关心的写回治理字段存在。
     */
    private Object newRequest(Class<?> requestClass) throws Exception {
        Object request = requestClass.getDeclaredConstructor().newInstance();
        writeField(request, "taskId", 77L);
        writeField(request, "planVersionId", 22L);
        writeField(request, "branchKey", "analysis");
        writeField(request, "nodeName", "report_writer");
        writeField(request, "queryText", "Notion AI enterprise pricing");
        writeField(request, "summary", "当前任务已经核实 Notion AI 企业定价页面缺少公开企业价卡");
        writeField(request, "sourceUrls", List.of("https://example.com/notion-ai/pricing"));
        writeField(request, "writebackCategory", "VERIFIED_TASK_CONCLUSION");
        writeField(request, "qualitySignal", "VERIFIED");
        writeField(request, "reuseReason", "来自当前任务已核实结论，仅在同计划版本内复用，计划版本变化后失效");
        return request;
    }

    private Object readField(Object target, String fieldName) throws IllegalAccessException {
        Field field = ReflectionUtils.findField(target.getClass(), fieldName);
        assertNotNull(field, () -> "缺少字段：" + target.getClass().getSimpleName() + "." + fieldName);
        ReflectionUtils.makeAccessible(field);
        return field.get(target);
    }

    private void writeField(Object target, String fieldName, Object value) {
        Field field = ReflectionUtils.findField(target.getClass(), fieldName);
        assertNotNull(field, () -> "缺少字段：" + target.getClass().getSimpleName() + "." + fieldName);
        ReflectionUtils.makeAccessible(field);
        ReflectionUtils.setField(field, target, value);
    }
}
