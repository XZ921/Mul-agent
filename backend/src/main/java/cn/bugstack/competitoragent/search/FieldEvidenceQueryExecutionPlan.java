package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.workflow.coverage.FieldEvidenceQuery;

import java.util.List;
import java.util.Map;

/**
 * 字段证据 query 执行计划。
 * 这里把 planned / executable / skipped 和跳过原因固定成不可变快照，
 * 方便 coordinator、provider 审计以及 trace 复用同一份结构化口径。
 */
public record FieldEvidenceQueryExecutionPlan(
        List<FieldEvidenceQuery> planned,
        List<FieldEvidenceQuery> executable,
        List<FieldEvidenceQuery> skipped,
        Map<String, Integer> skipReasons,
        Map<String, Integer> fieldDistribution,
        Map<String, Integer> sourceTypeDistribution,
        List<String> claimedFingerprints
) {

    public FieldEvidenceQueryExecutionPlan {
        planned = planned == null ? List.of() : List.copyOf(planned);
        executable = executable == null ? List.of() : List.copyOf(executable);
        skipped = skipped == null ? List.of() : List.copyOf(skipped);
        skipReasons = skipReasons == null ? Map.of() : Map.copyOf(skipReasons);
        fieldDistribution = fieldDistribution == null ? Map.of() : Map.copyOf(fieldDistribution);
        sourceTypeDistribution = sourceTypeDistribution == null ? Map.of() : Map.copyOf(sourceTypeDistribution);
        claimedFingerprints = claimedFingerprints == null ? List.of() : List.copyOf(claimedFingerprints);
    }

    public static FieldEvidenceQueryExecutionPlan empty() {
        return new FieldEvidenceQueryExecutionPlan(List.of(), List.of(), List.of(), Map.of(), Map.of(), Map.of(), List.of());
    }

    public List<FieldEvidenceQuery> getPlanned() {
        return planned;
    }

    public List<FieldEvidenceQuery> getExecutable() {
        return executable;
    }

    public List<FieldEvidenceQuery> getSkipped() {
        return skipped;
    }

    public Map<String, Integer> getSkipReasons() {
        return skipReasons;
    }

    public Map<String, Integer> getFieldDistribution() {
        return fieldDistribution;
    }

    public Map<String, Integer> getSourceTypeDistribution() {
        return sourceTypeDistribution;
    }

    public List<String> getClaimedFingerprints() {
        return claimedFingerprints;
    }
}
