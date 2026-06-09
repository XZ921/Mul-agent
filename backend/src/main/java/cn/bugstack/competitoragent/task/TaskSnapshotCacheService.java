package cn.bugstack.competitoragent.task;

import cn.bugstack.competitoragent.config.RedisConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * 任务快照缓存服务。
 * 负责把任务的热点进度信息和关键节点输出写入 Redis，
 * 让任务详情页、恢复链路和后续事件通道都能复用同一份运行态底座。
 */
@Slf4j
@Service
public class TaskSnapshotCacheService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final RedisConfig.TaskRuntimeRedisProperties redisProperties;

    /**
     * 运行时必须使用这个带完整依赖的构造器。
     * 之所以显式标注，是因为类里额外保留了测试便捷构造器，
     * 不声明主注入入口时，Spring 会在集成上下文里误判为“无默认构造器可用”。
     */
    @Autowired
    public TaskSnapshotCacheService(StringRedisTemplate stringRedisTemplate,
                                    @Qualifier("taskSnapshotObjectMapper") ObjectMapper objectMapper,
                                    RedisConfig.TaskRuntimeRedisProperties redisProperties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
        this.redisProperties = redisProperties;
    }

    TaskSnapshotCacheService(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        this(stringRedisTemplate, objectMapper, new RedisConfig.TaskRuntimeRedisProperties());
    }

    /**
     * 保存任务快照。
     * 快照是任务详情页和恢复判断的第一入口，因此这里采用单 key 覆盖写入，保持读取简单稳定。
     */
    public void saveTaskSnapshot(TaskProgressSnapshot snapshot) {
        if (snapshot == null || snapshot.getTaskId() == null) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(
                    buildSnapshotKey(snapshot.getTaskId()),
                    objectMapper.writeValueAsString(snapshot),
                    redisProperties.getSnapshotTtl());
        } catch (Exception e) {
            log.warn("save task snapshot to redis failed, taskId={}", snapshot.getTaskId(), e);
        }
    }

    /**
     * 获取任务快照。
     * 读取失败时直接返回 empty，调用方可以无缝回退到数据库事实数据。
     */
    public Optional<TaskProgressSnapshot> getTaskSnapshot(Long taskId) {
        if (taskId == null) {
            return Optional.empty();
        }
        try {
            String rawSnapshot = stringRedisTemplate.opsForValue().get(buildSnapshotKey(taskId));
            if (rawSnapshot == null || rawSnapshot.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(rawSnapshot, TaskProgressSnapshot.class));
        } catch (Exception e) {
            log.warn("load task snapshot from redis failed, taskId={}", taskId, e);
            return Optional.empty();
        }
    }

    /**
     * 缓存节点输出。
     * 这里按 task 维度组织 hash，便于恢复执行时一次性取回共享上下文。
     */
    public void cacheNodeOutput(Long taskId, String nodeName, String outputData) {
        if (taskId == null || nodeName == null || nodeName.isBlank() || outputData == null) {
            return;
        }
        try {
            String runtimeKey = buildRuntimeKey(taskId);
            stringRedisTemplate.opsForHash().put(runtimeKey, nodeName, outputData);
            stringRedisTemplate.expire(runtimeKey, redisProperties.getRuntimeTtl());
        } catch (Exception e) {
            log.warn("cache node output to redis failed, taskId={}, nodeName={}", taskId, nodeName, e);
        }
    }

    /**
     * 读取任务级共享输出缓存。
     * 恢复执行时优先利用该缓存重建共享上下文，降低对“单次内存态”的依赖。
     */
    public Map<String, String> getCachedNodeOutputs(Long taskId) {
        if (taskId == null) {
            return Collections.emptyMap();
        }
        try {
            Map<Object, Object> rawEntries = stringRedisTemplate.opsForHash().entries(buildRuntimeKey(taskId));
            if (rawEntries == null || rawEntries.isEmpty()) {
                return Collections.emptyMap();
            }
            java.util.Map<String, String> outputs = new java.util.LinkedHashMap<>();
            rawEntries.forEach((key, value) -> {
                if (key != null && value != null) {
                    outputs.put(String.valueOf(key), String.valueOf(value));
                }
            });
            return outputs;
        } catch (Exception e) {
            log.warn("load cached node outputs from redis failed, taskId={}", taskId, e);
            return Collections.emptyMap();
        }
    }

    /**
     * 清理任务运行时缓存。
     * 整任务重置、删除任务或从指定节点重跑时，需要先清空旧快照与中间态，避免脏状态透传到新一轮执行。
     */
    public void evictTaskRuntime(Long taskId) {
        if (taskId == null) {
            return;
        }
        try {
            stringRedisTemplate.delete(buildSnapshotKey(taskId));
            stringRedisTemplate.delete(buildRuntimeKey(taskId));
        } catch (Exception e) {
            log.warn("evict task runtime from redis failed, taskId={}", taskId, e);
        }
    }

    private String buildSnapshotKey(Long taskId) {
        return "competitor-agent:task:snapshot:" + taskId;
    }

    private String buildRuntimeKey(Long taskId) {
        return "competitor-agent:task:runtime:" + taskId;
    }
}
