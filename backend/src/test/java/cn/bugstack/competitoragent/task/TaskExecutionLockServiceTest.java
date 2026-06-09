package cn.bugstack.competitoragent.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分布式锁测试覆盖任务级与节点级两类 key，
 * 同时验证只有锁 owner 自己才能安全释放锁。
 */
@ExtendWith(MockitoExtension.class)
class TaskExecutionLockServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private TaskExecutionLockService lockService;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        lockService = new TaskExecutionLockService(stringRedisTemplate);
    }

    @Test
    void shouldAcquireAndReleaseTaskExecutionLockWhenOwnerMatches() {
        when(valueOperations.setIfAbsent(
                eq("competitor-agent:task:lock:31"),
                eq("runner-1"),
                any(Duration.class)))
                .thenReturn(Boolean.TRUE);
        when(valueOperations.get("competitor-agent:task:lock:31")).thenReturn("runner-1");
        when(stringRedisTemplate.delete("competitor-agent:task:lock:31")).thenReturn(Boolean.TRUE);

        boolean acquired = lockService.tryAcquireTaskExecutionLock(31L, "runner-1", Duration.ofMinutes(10));
        boolean released = lockService.releaseTaskExecutionLock(31L, "runner-1");

        assertTrue(acquired);
        assertTrue(released);
        verify(stringRedisTemplate).delete("competitor-agent:task:lock:31");
    }

    @Test
    void shouldRefuseReleasingNodeLockOwnedByAnotherWorker() {
        when(valueOperations.setIfAbsent(
                eq("competitor-agent:task:node-lock:45:collect_sources_web"),
                eq("worker-a"),
                any(Duration.class)))
                .thenReturn(Boolean.TRUE);
        when(valueOperations.get("competitor-agent:task:node-lock:45:collect_sources_web")).thenReturn("worker-b");

        boolean acquired = lockService.tryAcquireNodeExecutionLock(
                45L,
                "collect_sources_web",
                "worker-a",
                Duration.ofMinutes(5));
        boolean released = lockService.releaseNodeExecutionLock(45L, "collect_sources_web", "worker-a");

        assertTrue(acquired);
        assertFalse(released);
    }
}
