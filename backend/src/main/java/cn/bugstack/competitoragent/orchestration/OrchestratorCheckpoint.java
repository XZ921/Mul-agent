package cn.bugstack.competitoragent.orchestration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Orchestrator 运行期恢复游标。
 * 它回答“下次恢复时 Orchestrator 应该从哪里继续判断”。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class OrchestratorCheckpoint {

    private String checkpointId;
    private Long taskId;
    private Long planVersionId;
    private String branchKey;
    private String lastDecisionId;
    private String lastMutationId;
    @Builder.Default
    private List<String> pendingActions = List.of();
    private int decisionCount;
    private int maxAutoDecisions;
    @Builder.Default
    private Map<String, Integer> dynamicBranchCountsBySection = Map.of();
    private String resumeAfterNodeName;
    private String resumeReason;
    @Builder.Default
    private List<String> sourceUrls = List.of();
    private EvidenceState evidenceState;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 补齐恢复游标的时间和证据状态，并对计数做下限保护。
     */
    public OrchestratorCheckpoint normalized() {
        LocalDateTime now = LocalDateTime.now();
        return toBuilder()
                .pendingActions(normalizeDistinctList(pendingActions))
                .decisionCount(Math.max(0, decisionCount))
                .maxAutoDecisions(Math.max(0, maxAutoDecisions))
                .dynamicBranchCountsBySection(normalizeSectionCounts(dynamicBranchCountsBySection))
                .sourceUrls(normalizeDistinctList(sourceUrls))
                .evidenceState(resolveEvidenceState())
                .createdAt(createdAt == null ? now : createdAt)
                .updatedAt(updatedAt == null ? now : updatedAt)
                .build();
    }

    private EvidenceState resolveEvidenceState() {
        if (evidenceState != null) {
            return evidenceState;
        }
        return sourceUrls == null || sourceUrls.isEmpty()
                ? EvidenceState.MISSING_SOURCE
                : EvidenceState.FULL_SOURCE;
    }

    private List<String> normalizeDistinctList(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String item = blankToNull(value);
                if (item != null) {
                    normalized.add(item);
                }
            }
        }
        return new ArrayList<>(normalized);
    }

    /**
     * checkpoint section 计数使用与 Runtime State 相同的稳定键，并保存不可变副本。
     * 历史 payload 缺少该字段时按空 map 兼容，负数只在 DTO 内归零，读取服务仍会标记原始 payload 不可读。
     */
    private Map<String, Integer> normalizeSectionCounts(Map<String, Integer> values) {
        Map<String, Integer> normalized = new LinkedHashMap<>();
        if (values != null) {
            for (Map.Entry<String, Integer> entry : values.entrySet()) {
                String key = OrchestrationRuntimeState.normalizeSectionKey(entry.getKey());
                int count = Math.max(0, entry.getValue() == null ? 0 : entry.getValue());
                normalized.merge(key, count, Math::max);
            }
        }
        return Map.copyOf(normalized);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
