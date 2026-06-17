package cn.bugstack.competitoragent.task;

import cn.bugstack.competitoragent.config.RedisConfig;
import cn.bugstack.competitoragent.search.SearchSharedProjection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
            stringRedisTemplate.opsForHash().put(runtimeKey, nodeName, normalizeCachedNodeOutput(nodeName, outputData));
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
     * 缓存共享输出信封。
     * Redis 中对 Collector 等重对象节点优先写入信封 JSON，而不是原始大输出。
     */
    public void cacheSharedOutputEnvelope(Long taskId, SharedNodeOutputEnvelope envelope) {
        if (taskId == null || envelope == null || envelope.getNodeName() == null || envelope.getPayloadJson() == null) {
            return;
        }
        try {
            String runtimeKey = buildRuntimeKey(taskId);
            stringRedisTemplate.opsForHash().put(runtimeKey, envelope.getNodeName(), objectMapper.writeValueAsString(envelope));
            stringRedisTemplate.expire(runtimeKey, redisProperties.getRuntimeTtl());
        } catch (Exception e) {
            log.warn("cache shared output envelope to redis failed, taskId={}, nodeName={}",
                    taskId, envelope == null ? null : envelope.getNodeName(), e);
        }
    }

    /**
     * 优先按共享信封读取运行时缓存。
     * 历史缓存仍允许以字符串形式回退为 LEGACY_STRING_OUTPUT。
     */
    public Map<String, SharedNodeOutputEnvelope> getCachedSharedOutputEnvelopes(Long taskId) {
        Map<String, String> rawOutputs = getCachedNodeOutputs(taskId);
        Map<String, SharedNodeOutputEnvelope> envelopes = new LinkedHashMap<>();
        rawOutputs.forEach((nodeName, rawValue) -> {
            try {
                SharedNodeOutputEnvelope envelope = objectMapper.readValue(rawValue, SharedNodeOutputEnvelope.class);
                envelopes.put(nodeName, normalizeSharedOutputEnvelope(taskId, nodeName, rawValue, envelope));
            } catch (Exception ignored) {
                envelopes.put(nodeName, SharedNodeOutputEnvelope.builder()
                        .taskId(taskId)
                        .nodeName(nodeName)
                        .projectionType("LEGACY_STRING_OUTPUT")
                        .payloadJson(rawValue)
                        .sourceUrls(java.util.List.of())
                        .build());
            }
        });
        return envelopes;
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

    /**
     * Redis 里的共享输出只保留稳定投影，
     * 这样恢复执行和任务上下文组装都不会继续背着 collector 大 JSON 前进。
     */
    private String normalizeCachedNodeOutput(String nodeName, String outputData) {
        if (!looksLikeCollectorNode(nodeName)
                || !SearchSharedProjection.supportsCollectorOutput(objectMapper, outputData)) {
            return outputData;
        }
        try {
            return objectMapper.writeValueAsString(
                    SearchSharedProjection.fromCollectorOutput(objectMapper, outputData)
            );
        } catch (Exception e) {
            log.warn("serialize collector shared projection failed, nodeName={}", nodeName, e);
            return outputData;
        }
    }

    private boolean looksLikeCollectorNode(String nodeName) {
        if (nodeName == null || nodeName.isBlank()) {
            return false;
        }
        return nodeName.startsWith("collect");
    }

    /**
     * 历史 runtime 缓存里可能直接存的是 SearchSharedProjection JSON，
     * 此时按 SharedNodeOutputEnvelope 反序列化虽然不会抛错，
     * 但 nodeName / payloadJson 等关键字段会变成 null，恢复期继续写入 sharedState 就会触发 NPE。
     * 所以这里统一把“像 envelope 的新格式”和“像 projection 的旧格式”都归一成可恢复的信封对象。
     */
    private SharedNodeOutputEnvelope normalizeSharedOutputEnvelope(Long taskId,
                                                                  String nodeName,
                                                                  String rawValue,
                                                                  SharedNodeOutputEnvelope envelope) {
        JsonNode rawJson = readJsonSafely(rawValue);
        boolean legacyProjectionPayload = looksLikeLegacySharedProjection(rawJson);

        String resolvedProjectionType = normalizeText(envelope.getProjectionType());
        if (resolvedProjectionType == null) {
            resolvedProjectionType = legacyProjectionPayload
                    ? resolveLegacyProjectionType(rawJson)
                    : "LEGACY_STRING_OUTPUT";
        }

        List<String> resolvedSourceUrls = normalizeSourceUrls(envelope.getSourceUrls());
        if (resolvedSourceUrls.isEmpty()) {
            resolvedSourceUrls = extractSourceUrls(rawJson);
        }

        String resolvedPayloadJson = normalizeText(envelope.getPayloadJson());
        if (resolvedPayloadJson == null && legacyProjectionPayload) {
            resolvedPayloadJson = rawValue;
        }

        return SharedNodeOutputEnvelope.builder()
                .taskId(envelope.getTaskId() == null ? taskId : envelope.getTaskId())
                .nodeName(normalizeText(envelope.getNodeName()) == null ? nodeName : envelope.getNodeName())
                .planVersionId(envelope.getPlanVersionId())
                .projectionType(resolvedProjectionType)
                .payloadJson(resolvedPayloadJson)
                .sourceUrls(resolvedSourceUrls)
                .createdAt(envelope.getCreatedAt())
                .build();
    }

    /**
     * 旧版共享输出没有 envelope 外壳，通常只会保留 projectionType/sourceUrls/selectedUrls 等稳定投影字段。
     * 识别出这类结构后，需要把原始 JSON 直接回填为 payloadJson，确保恢复和 replay 仍能消费历史事实。
     */
    private boolean looksLikeLegacySharedProjection(JsonNode rawJson) {
        if (rawJson == null || !rawJson.isObject()) {
            return false;
        }
        if (rawJson.has("payloadJson") || rawJson.has("nodeName")) {
            return false;
        }
        return rawJson.has("projectionType")
                || rawJson.has("sourceUrls")
                || rawJson.has("selectedUrls")
                || rawJson.has("selectedTargets")
                || rawJson.has("recoveryCheckpoint")
                || rawJson.has("issueFlags")
                || rawJson.has("fallbackDecision")
                || rawJson.has("degradationReason");
    }

    private String resolveLegacyProjectionType(JsonNode rawJson) {
        if (rawJson == null || rawJson.isMissingNode() || rawJson.isNull()) {
            return "SEARCH_SHARED_PROJECTION_V1";
        }
        JsonNode projectionType = rawJson.get("projectionType");
        if (projectionType == null || projectionType.isNull()) {
            return "SEARCH_SHARED_PROJECTION_V1";
        }
        String value = normalizeText(projectionType.asText(null));
        return value == null ? "SEARCH_SHARED_PROJECTION_V1" : value;
    }

    private List<String> extractSourceUrls(JsonNode rawJson) {
        if (rawJson == null || !rawJson.isObject()) {
            return List.of();
        }
        JsonNode sourceUrlsNode = rawJson.get("sourceUrls");
        if (sourceUrlsNode == null || !sourceUrlsNode.isArray()) {
            return List.of();
        }
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>();
        sourceUrlsNode.forEach(node -> {
            if (node != null && node.isValueNode()) {
                String value = normalizeText(node.asText(null));
                if (value != null) {
                    sourceUrls.add(value);
                }
            }
        });
        return List.copyOf(sourceUrls);
    }

    private List<String> normalizeSourceUrls(List<String> sourceUrls) {
        if (sourceUrls == null || sourceUrls.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String sourceUrl : sourceUrls) {
            String value = normalizeText(sourceUrl);
            if (value != null) {
                normalized.add(value);
            }
        }
        return List.copyOf(normalized);
    }

    private JsonNode readJsonSafely(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(rawValue);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
