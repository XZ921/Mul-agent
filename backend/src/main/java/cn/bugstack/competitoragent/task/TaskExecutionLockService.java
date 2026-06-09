package cn.bugstack.competitoragent.task;

import cn.bugstack.competitoragent.config.RedisConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 任务执行锁服务。
 * 提供任务级和节点级两层 Redis 锁，作为重复启动、并发恢复和节点重跑的第一层保护。
 */
@Slf4j
@Service
public class TaskExecutionLockService {

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisConfig.TaskRuntimeRedisProperties redisProperties;

    /**
     * 运行时统一走显式标记的主构造器。
     * 这样既能保留测试便捷构造器，又不会让 Spring 在自动装配时出现构造器歧义。
     */
    @Autowired
    public TaskExecutionLockService(StringRedisTemplate stringRedisTemplate,
                                    RedisConfig.TaskRuntimeRedisProperties redisProperties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.redisProperties = redisProperties;
    }

    TaskExecutionLockService(StringRedisTemplate stringRedisTemplate) {
        this(stringRedisTemplate, new RedisConfig.TaskRuntimeRedisProperties());
    }

    /**
     * 获取整任务执行锁。
     * 同一 taskId 在任意时刻只允许一个 Runner 真正进入 DAG 执行阶段。
     */
    public boolean tryAcquireTaskExecutionLock(Long taskId, String ownerToken, Duration ttl) {
        return tryAcquire(buildTaskLockKey(taskId), ownerToken, ttl == null ? redisProperties.getTaskLockTtl() : ttl);
    }

    /**
     * 释放整任务执行锁。
     * 只有持有锁的 owner 才能释放，避免把其他工作线程的锁误删。
     */
    public boolean releaseTaskExecutionLock(Long taskId, String ownerToken) {
        return release(buildTaskLockKey(taskId), ownerToken);
    }

    /**
     * 获取节点级执行锁。
     * 该锁用于防止同一节点在并发恢复、重复调度或节点重跑时被多个执行线程同时推进。
     */
    public boolean tryAcquireNodeExecutionLock(Long taskId, String nodeName, String ownerToken, Duration ttl) {
        return tryAcquire(buildNodeLockKey(taskId, nodeName),
                ownerToken,
                ttl == null ? redisProperties.getNodeLockTtl() : ttl);
    }

    /**
     * 释放节点级执行锁。
     */
    public boolean releaseNodeExecutionLock(Long taskId, String nodeName, String ownerToken) {
        return release(buildNodeLockKey(taskId, nodeName), ownerToken);
    }

    private boolean tryAcquire(String key, String ownerToken, Duration ttl) {
        if (key == null || ownerToken == null || ownerToken.isBlank()) {
            return false;
        }
        try {
            Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(key, ownerToken, ttl);
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            log.warn("acquire redis execution lock failed, key={}", key, e);
            return false;
        }
    }

    private boolean release(String key, String ownerToken) {
        if (key == null || ownerToken == null || ownerToken.isBlank()) {
            return false;
        }
        try {
            String currentOwner = stringRedisTemplate.opsForValue().get(key);
            if (!ownerToken.equals(currentOwner)) {
                return false;
            }
            Boolean deleted = stringRedisTemplate.delete(key);
            return Boolean.TRUE.equals(deleted);
        } catch (Exception e) {
            log.warn("release redis execution lock failed, key={}", key, e);
            return false;
        }
    }

    private String buildTaskLockKey(Long taskId) {
        return "competitor-agent:task:lock:" + taskId;
    }

    private String buildNodeLockKey(Long taskId, String nodeName) {
        return "competitor-agent:task:node-lock:" + taskId + ":" + nodeName;
    }
}
