package cn.bugstack.competitoragent.orchestration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

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

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
