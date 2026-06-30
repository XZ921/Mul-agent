package cn.bugstack.competitoragent.workflow.coverage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 维度证据计划。
 * 这是 Collector 节点消费的字段级采集预算快照，来自 CoverageContract，
 * 但比契约更偏运行态：它关心还剩哪些路径没跑、哪些 query 该继续执行。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DimensionEvidencePlan {

    private String competitorName;
    private String contractVersion;
    private Integer maxCollectionRounds;

    @Builder.Default
    private List<FieldEvidenceCoverage> fieldCoverages = new ArrayList<>();

    public Optional<FieldEvidenceCoverage> findField(String fieldName) {
        if (!StringUtils.hasText(fieldName) || fieldCoverages == null) {
            return Optional.empty();
        }
        return fieldCoverages.stream()
                .filter(field -> field != null && fieldName.equalsIgnoreCase(field.getFieldName()))
                .findFirst();
    }

    public List<FieldEvidenceQuery> allPlannedQueries() {
        if (fieldCoverages == null || fieldCoverages.isEmpty()) {
            return List.of();
        }
        return fieldCoverages.stream()
                .flatMap(field -> field == null || field.getPlannedQueries() == null
                        ? Stream.empty()
                        : field.getPlannedQueries().stream())
                .toList();
    }

    /**
     * 是否仍存在未达到 minimumAttemptedPaths 的字段路径。
     * 搜索层依赖这个判断来打开字段级 supplement，不能只看是否已有 verified 候选。
     */
    public boolean hasUnmetRequiredFieldPath() {
        if (fieldCoverages == null || fieldCoverages.isEmpty()) {
            return false;
        }
        return fieldCoverages.stream().anyMatch(field -> {
            if (field == null) {
                return false;
            }
            int minimum = field.getMinimumAttemptedPaths() == null ? 1 : field.getMinimumAttemptedPaths();
            int completed = field.getCompletedPaths() == null ? 0 : field.getCompletedPaths().size();
            return completed < minimum;
        });
    }

    /**
     * 是否存在需要继续执行的字段级查询。
     * 只要字段路径未满足且仍有 plannedQueries，就允许第一轮或再入轮继续执行字段多 query。
     */
    public boolean hasPendingFieldEvidenceQueries() {
        if (fieldCoverages == null || fieldCoverages.isEmpty()) {
            return false;
        }
        return fieldCoverages.stream().anyMatch(field -> field != null
                && field.getPlannedQueries() != null
                && !field.getPlannedQueries().isEmpty()
                && !isFieldCoverageSatisfied(field));
    }

    private boolean isFieldCoverageSatisfied(FieldEvidenceCoverage field) {
        int minimum = field.getMinimumAttemptedPaths() == null ? 1 : field.getMinimumAttemptedPaths();
        int completed = field.getCompletedPaths() == null ? 0 : field.getCompletedPaths().size();
        return completed >= minimum;
    }
}