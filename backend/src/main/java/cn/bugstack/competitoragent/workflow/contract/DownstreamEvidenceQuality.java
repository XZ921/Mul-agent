package cn.bugstack.competitoragent.workflow.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 下游证据质量摘要。
 * 该对象只承接采集质量的稳定字段，避免下游直接依赖完整 pageMetadata。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class DownstreamEvidenceQuality {

    private Double qualityScore;

    private String failureKind;

    private Long durationMillis;

    public DownstreamEvidenceQuality normalized() {
        return this.toBuilder()
                .failureKind(normalizeText(failureKind))
                .build();
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }
}
