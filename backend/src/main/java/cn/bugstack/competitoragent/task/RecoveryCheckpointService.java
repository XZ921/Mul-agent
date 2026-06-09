package cn.bugstack.competitoragent.task;

import cn.bugstack.competitoragent.model.dto.RecoveryCheckpointResponse;
import cn.bugstack.competitoragent.model.entity.RecoveryCheckpoint;
import cn.bugstack.competitoragent.model.entity.TaskPlan;
import cn.bugstack.competitoragent.repository.RecoveryCheckpointRepository;
import cn.bugstack.competitoragent.repository.TaskPlanRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 恢复点基础服务。
 * <p>
 * Task 5.6.a 先只承接恢复点的正式落库与读取职责，
 * 不在此阶段提前混入完整回放投影、恢复窗口判断和控制接口逻辑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecoveryCheckpointService {

    private static final TypeReference<List<String>> SOURCE_URL_LIST_TYPE = new TypeReference<>() {
    };

    private final RecoveryCheckpointRepository recoveryCheckpointRepository;
    private final TaskPlanRepository taskPlanRepository;
    private final ObjectMapper objectMapper;

    /**
     * 保存恢复点正式对象。
     * <p>
     * 这里保持最小校验，只保证关键关联字段齐备，
     * 便于后续恢复引擎、人工介入流程和回放投影统一复用同一落点。
     */
    @Transactional
    public RecoveryCheckpoint saveCheckpoint(RecoveryCheckpoint checkpoint) {
        if (checkpoint == null) {
            throw new IllegalArgumentException("checkpoint must not be null");
        }
        if (checkpoint.getTaskId() == null || checkpoint.getPlanVersionId() == null) {
            throw new IllegalArgumentException("checkpoint taskId and planVersionId must not be null");
        }
        if (checkpoint.getCheckpointKey() == null || checkpoint.getCheckpointKey().isBlank()) {
            throw new IllegalArgumentException("checkpointKey must not be blank");
        }
        return recoveryCheckpointRepository.save(checkpoint);
    }

    /**
     * 按任务读取恢复点列表，并补齐计划版本号。
     * <p>
     * 5.6.a 先返回最小正式 DTO，真正的“恢复建议 / 可执行窗口 / 人工介入结果”
     * 将在后续子任务继续扩展，不在当前阶段过度建模。
     */
    @Transactional(readOnly = true)
    public List<RecoveryCheckpointResponse> listTaskCheckpoints(Long taskId) {
        if (taskId == null) {
            return List.of();
        }
        List<RecoveryCheckpoint> checkpoints = recoveryCheckpointRepository.findByTaskIdOrderByCreatedAtDesc(taskId);
        if (checkpoints.isEmpty()) {
            return List.of();
        }

        Map<Long, TaskPlan> taskPlanMap = new LinkedHashMap<>();
        List<Long> planVersionIds = checkpoints.stream()
                .map(RecoveryCheckpoint::getPlanVersionId)
                .distinct()
                .toList();
        taskPlanRepository.findAllById(planVersionIds)
                .forEach(taskPlan -> taskPlanMap.put(taskPlan.getId(), taskPlan));

        return checkpoints.stream()
                .map(checkpoint -> toResponse(checkpoint, taskPlanMap.get(checkpoint.getPlanVersionId())))
                .toList();
    }

    private RecoveryCheckpointResponse toResponse(RecoveryCheckpoint checkpoint, TaskPlan taskPlan) {
        return RecoveryCheckpointResponse.builder()
                .id(checkpoint.getId())
                .taskId(checkpoint.getTaskId())
                .planVersionId(checkpoint.getPlanVersionId())
                .planVersion(taskPlan == null ? null : taskPlan.getPlanVersion())
                .checkpointKey(checkpoint.getCheckpointKey())
                .checkpointType(checkpoint.getCheckpointType())
                .nodeName(checkpoint.getNodeName())
                .summary(checkpoint.getSummary())
                .payloadSnapshot(checkpoint.getPayloadSnapshot())
                .createdAt(checkpoint.getCreatedAt())
                .sourceUrls(parseSourceUrls(checkpoint.getSourceUrls()))
                .build();
    }

    /**
     * sourceUrls 持久化为 JSON 字符串，读取时优先按数组解析。
     * 如果历史数据还不是 JSON 数组，则退化为单值列表，避免恢复视图直接丢失追溯信息。
     */
    private List<String> parseSourceUrls(String rawSourceUrls) {
        if (rawSourceUrls == null || rawSourceUrls.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(rawSourceUrls, SOURCE_URL_LIST_TYPE);
        } catch (Exception exception) {
            log.warn("failed to parse recovery checkpoint sourceUrls, fallback to raw value, rawSourceUrls={}",
                    rawSourceUrls,
                    exception);
            return List.of(rawSourceUrls);
        }
    }
}
