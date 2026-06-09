package cn.bugstack.competitoragent.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TaskSnapshotCacheService 的测试聚焦两件事：
 * 1. 任务快照能否稳定写入并读回。
 * 2. 中间态共享输出能否以 task 维度缓存在 Redis 中。
 */
@ExtendWith(MockitoExtension.class)
class TaskSnapshotCacheServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private TaskSnapshotCacheService cacheService;

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        cacheService = new TaskSnapshotCacheService(stringRedisTemplate, new ObjectMapper());
    }

    @Test
    void shouldPersistAndReloadTaskSnapshotFromRedis() {
        TaskProgressSnapshot snapshot = TaskProgressSnapshot.builder()
                .taskId(12L)
                .taskStatus("RUNNING")
                .currentStage("数据分析")
                .totalNodes(6)
                .completedNodes(2)
                .activeNodeNames(java.util.List.of("analyze_competitors"))
                .updatedAt(LocalDateTime.of(2026, 6, 3, 11, 15, 0))
                .build();

        cacheService.saveTaskSnapshot(snapshot);

        verify(valueOperations).set(eq("competitor-agent:task:snapshot:12"), any(String.class), any(Duration.class));

        when(valueOperations.get("competitor-agent:task:snapshot:12")).thenReturn("""
                {
                  "taskId": 12,
                  "taskStatus": "RUNNING",
                  "currentStage": "数据分析",
                  "totalNodes": 6,
                  "completedNodes": 2,
                  "activeNodeNames": ["analyze_competitors"],
                  "updatedAt": "2026-06-03T11:15:00"
                }
                """);

        Optional<TaskProgressSnapshot> loaded = cacheService.getTaskSnapshot(12L);

        assertTrue(loaded.isPresent());
        assertEquals(12L, loaded.get().getTaskId());
        assertEquals("RUNNING", loaded.get().getTaskStatus());
        assertEquals("数据分析", loaded.get().getCurrentStage());
        assertEquals(1, loaded.get().getActiveNodeNames().size());
    }

    @Test
    void shouldCacheSharedNodeOutputsAndEvictTaskRuntime() {
        cacheService.cacheNodeOutput(19L, "collect_sources_web", "{\"ok\":true}");

        verify(hashOperations).put("competitor-agent:task:runtime:19", "collect_sources_web", "{\"ok\":true}");
        verify(stringRedisTemplate).expire(eq("competitor-agent:task:runtime:19"), any(Duration.class));

        when(hashOperations.entries("competitor-agent:task:runtime:19"))
                .thenReturn(Map.of("collect_sources_web", "{\"ok\":true}"));

        Map<String, String> cachedOutputs = cacheService.getCachedNodeOutputs(19L);

        assertEquals(1, cachedOutputs.size());
        assertEquals("{\"ok\":true}", cachedOutputs.get("collect_sources_web"));

        cacheService.evictTaskRuntime(19L);

        verify(stringRedisTemplate).delete("competitor-agent:task:snapshot:19");
        verify(stringRedisTemplate).delete("competitor-agent:task:runtime:19");
    }
}
