package cn.bugstack.competitoragent.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
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

    @Test
    void shouldCacheStableCollectorProjectionInsteadOfWholeCollectorOutput() {
        cacheService.cacheNodeOutput(19L, "collect_sources_web", """
                {
                  "searchExecutionTrace":{"fallbackDecision":"USE_BROWSER_SUPPLEMENT"},
                  "searchAudit":{"executionTrace":{"recoveryCheckpoint":"SELECT_TARGETS"}},
                  "selectedTargets":[{"url":"https://docs.example.com"}],
                  "sourceUrls":["https://docs.example.com"],
                  "results":[{"url":"https://docs.example.com","fullContent":"very large body"}]
                }
                """);

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(hashOperations).put(eq("competitor-agent:task:runtime:19"), eq("collect_sources_web"), payloadCaptor.capture());

        String cachedPayload = String.valueOf(payloadCaptor.getValue());
        assertTrue(cachedPayload.contains("\"sourceUrls\""));
        assertTrue(cachedPayload.contains("https://docs.example.com"));
        assertTrue(!cachedPayload.contains("fullContent"));
        assertTrue(!cachedPayload.contains("\"results\""));
    }

    @Test
    void shouldCacheAndReloadSharedOutputEnvelopes() {
        SharedNodeOutputEnvelope envelope = SharedNodeOutputEnvelope.builder()
                .taskId(19L)
                .nodeName("collect_sources_web")
                .planVersionId(7L)
                .projectionType("SEARCH_SHARED_PROJECTION_V1")
                .payloadJson("{\"sourceUrls\":[\"https://docs.example.com\"]}")
                .sourceUrls(java.util.List.of("https://docs.example.com"))
                .build();

        cacheService.cacheSharedOutputEnvelope(19L, envelope);

        verify(hashOperations).put(eq("competitor-agent:task:runtime:19"), eq("collect_sources_web"), any(String.class));
        verify(stringRedisTemplate).expire(eq("competitor-agent:task:runtime:19"), any(Duration.class));

        when(hashOperations.entries("competitor-agent:task:runtime:19"))
                .thenReturn(Map.of(
                        "collect_sources_web",
                        """
                        {
                          "taskId":19,
                          "nodeName":"collect_sources_web",
                          "planVersionId":7,
                          "projectionType":"SEARCH_SHARED_PROJECTION_V1",
                          "payloadJson":"{\\"sourceUrls\\":[\\"https://docs.example.com\\"]}",
                          "sourceUrls":["https://docs.example.com"]
                        }
                        """
                ));

        Map<String, SharedNodeOutputEnvelope> cachedEnvelopes = cacheService.getCachedSharedOutputEnvelopes(19L);

        assertEquals(1, cachedEnvelopes.size());
        assertEquals("SEARCH_SHARED_PROJECTION_V1", cachedEnvelopes.get("collect_sources_web").getProjectionType());
        assertEquals("{\"sourceUrls\":[\"https://docs.example.com\"]}",
                cachedEnvelopes.get("collect_sources_web").getPayloadJson());
    }

    @Test
    void shouldReloadLegacyProjectionCacheAsEnvelopeWithPayloadJson() {
        when(hashOperations.entries("competitor-agent:task:runtime:29"))
                .thenReturn(Map.of(
                        "collect_sources_web",
                        """
                        {
                          "projectionType":"SEARCH_SHARED_PROJECTION_V1",
                          "recoveryCheckpoint":"SELECT_TARGETS",
                          "sourceUrls":["https://docs.example.com"],
                          "selectedUrls":["https://docs.example.com"],
                          "issueFlags":[]
                        }
                        """
                ));

        Map<String, SharedNodeOutputEnvelope> cachedEnvelopes = cacheService.getCachedSharedOutputEnvelopes(29L);

        assertEquals(1, cachedEnvelopes.size());
        assertEquals("SEARCH_SHARED_PROJECTION_V1", cachedEnvelopes.get("collect_sources_web").getProjectionType());
        assertEquals("collect_sources_web", cachedEnvelopes.get("collect_sources_web").getNodeName());
        assertTrue(cachedEnvelopes.get("collect_sources_web").getPayloadJson().contains("https://docs.example.com"));
    }

    @Test
    void shouldLoadExtractorSharedEnvelopeWithInputSourceAndAuditRefs() {
        Long taskId = 51L;
        SharedNodeOutputEnvelope envelope = SharedNodeOutputEnvelope.builder()
                .taskId(taskId)
                .nodeName("extract_schema")
                .planVersionId(3L)
                .projectionType("EXTRACT_SHARED_PROJECTION_V1")
                .payloadJson("""
                        {
                          "projectionType":"EXTRACT_SHARED_PROJECTION_V1",
                          "extractorInput":{
                            "inputSource":"REPOSITORY_BACKED_PORT",
                            "auditRefs":{"searchAudit":{"available":true}},
                            "competitors":[{"competitorName":"Acme","readableEvidence":[{"evidenceId":"E001","content":""}]}]
                          },
                          "sourceUrls":["https://docs.example.com/pricing"]
                        }
                        """)
                .sourceUrls(List.of("https://docs.example.com/pricing"))
                .build();

        cacheService.cacheSharedOutputEnvelope(taskId, envelope);
        when(hashOperations.entries("competitor-agent:task:runtime:51"))
                .thenReturn(Map.of(
                        "extract_schema",
                        """
                        {
                          "taskId":51,
                          "nodeName":"extract_schema",
                          "planVersionId":3,
                          "projectionType":"EXTRACT_SHARED_PROJECTION_V1",
                          "payloadJson":"{\\"projectionType\\":\\"EXTRACT_SHARED_PROJECTION_V1\\",\\"extractorInput\\":{\\"inputSource\\":\\"REPOSITORY_BACKED_PORT\\",\\"auditRefs\\":{\\"searchAudit\\":{\\"available\\":true}},\\"competitors\\":[{\\"competitorName\\":\\"Acme\\",\\"readableEvidence\\":[{\\"evidenceId\\":\\"E001\\",\\"content\\":\\"\\"}]}]},\\"sourceUrls\\":[\\"https://docs.example.com/pricing\\"]}",
                          "sourceUrls":["https://docs.example.com/pricing"]
                        }
                        """
                ));

        Map<String, SharedNodeOutputEnvelope> outputs = cacheService.getCachedSharedOutputEnvelopes(taskId);

        assertEquals("EXTRACT_SHARED_PROJECTION_V1", outputs.get("extract_schema").getProjectionType());
        assertTrue(outputs.get("extract_schema").getPayloadJson().contains("REPOSITORY_BACKED_PORT"));
        assertTrue(outputs.get("extract_schema").getPayloadJson().contains("searchAudit"));
    }
}
