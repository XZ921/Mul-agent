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
 * 追加式编排决策审计记录。
 * 它回答“系统做过什么决策”，不承担恢复游标职责。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class DecisionTrace {

    private String decisionId;
    private Long taskId;
    private String triggerNodeName;
    private String inputSummary;
    private String decisionType;
    private boolean policyAllowed;
    private String executionStatus;
    @Builder.Default
    private List<String> sourceUrls = List.of();
    private EvidenceState evidenceState;
    private LocalDateTime createdAt;

    /**
     * 补齐审计记录的执行状态、创建时间和证据状态，方便 replay 稳定展示。
     */
    public DecisionTrace normalized() {
        return toBuilder()
                .executionStatus(blankToDefault(executionStatus, "CREATED"))
                .sourceUrls(normalizeDistinctList(sourceUrls))
                .evidenceState(resolveEvidenceState())
                .createdAt(createdAt == null ? LocalDateTime.now() : createdAt)
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

    private String blankToDefault(String value, String defaultValue) {
        String normalized = blankToNull(value);
        return normalized == null ? defaultValue : normalized;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
