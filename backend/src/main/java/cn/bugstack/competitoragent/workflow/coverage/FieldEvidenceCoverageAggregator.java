package cn.bugstack.competitoragent.workflow.coverage;

import cn.bugstack.competitoragent.collection.CollectionExecutionResult;
import cn.bugstack.competitoragent.search.EvidenceRepairPlan;
import cn.bugstack.competitoragent.search.EvidenceRepairState;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 字段证据覆盖聚合器。
 * 它只负责把采集结果折算成字段路径覆盖状态，不发起搜索，也不直接执行采集。
 */
@Component
public class FieldEvidenceCoverageAggregator {

    /**
     * 根据本轮采集审计结果更新字段覆盖计划。
     * 采集结果必须携带 fieldName / evidencePathKey / sourceUrls，才能被计入对应字段路径。
     */
    public DimensionEvidencePlan applyCollectionResults(DimensionEvidencePlan plan,
                                                        List<CollectionExecutionResult> results) {
        if (plan == null || plan.getFieldCoverages() == null || plan.getFieldCoverages().isEmpty()) {
            return plan;
        }
        List<FieldEvidenceCoverage> updatedFields = new ArrayList<>();
        for (FieldEvidenceCoverage field : plan.getFieldCoverages()) {
            updatedFields.add(updateField(field, results));
        }
        return plan.toBuilder()
                .fieldCoverages(updatedFields)
                .build();
    }

    /**
     * 只返回仍需补采的字段，供 Collector 第二轮再入时裁剪计划。
     */
    public List<FieldEvidenceCoverage> fieldsNeedingRecollection(DimensionEvidencePlan plan) {
        if (plan == null || plan.getFieldCoverages() == null) {
            return List.of();
        }
        return plan.getFieldCoverages().stream()
                .filter(field -> field != null
                        && field.getStatus() == FieldEvidenceCoverageStatus.EVIDENCE_PATH_COVERAGE_NOT_MET
                        && "RECOLLECT_FIELD_EVIDENCE".equals(field.getRecommendedNextAction()))
                .toList();
    }

    private FieldEvidenceCoverage updateField(FieldEvidenceCoverage field,
                                              List<CollectionExecutionResult> results) {
        if (field == null) {
            return null;
        }
        LinkedHashSet<String> attemptedPaths = new LinkedHashSet<>(safeList(field.getAttemptedPaths()));
        LinkedHashSet<String> completedPaths = new LinkedHashSet<>(safeList(field.getCompletedPaths()));
        LinkedHashSet<String> sourceUrls = new LinkedHashSet<>(safeList(field.getSourceUrls()));

        for (CollectionExecutionResult result : safeResults(results)) {
            if (!belongsToField(field, result)) {
                continue;
            }
            String pathKey = result.getPublicEvidenceRecoveryEvidencePathKey();
            if (StringUtils.hasText(pathKey)) {
                attemptedPaths.add(pathKey);
            }
            if (canCountAsFieldEvidence(result)) {
                sourceUrls.addAll(safeList(result.getSourceUrls()));
                sourceUrls.addAll(promotedUrls(result.getEvidenceRepairPlan()));
            }
            if (canCountAsFieldEvidence(result) && StringUtils.hasText(pathKey)) {
                completedPaths.add(pathKey);
            }
        }

        int minimumAttemptedPaths = minimumOrZero(field.getMinimumAttemptedPaths());
        int minDistinctEvidenceCount = minimumOrZero(field.getMinDistinctEvidenceCount());
        boolean attemptedEnough = attemptedPaths.size() >= minimumAttemptedPaths;
        boolean evidenceEnough = sourceUrls.size() >= minDistinctEvidenceCount;
        FieldEvidenceCoverageStatus status = attemptedEnough && evidenceEnough
                ? FieldEvidenceCoverageStatus.SUFFICIENT
                : FieldEvidenceCoverageStatus.EVIDENCE_PATH_COVERAGE_NOT_MET;

        return field.toBuilder()
                .status(status)
                .attemptedPaths(new ArrayList<>(attemptedPaths))
                .completedPaths(new ArrayList<>(completedPaths))
                .sourceUrls(new ArrayList<>(sourceUrls))
                .lastRepairState(status == FieldEvidenceCoverageStatus.SUFFICIENT
                        ? EvidenceRepairState.REPAIR_FIELD_PATH_COMPLETED.name()
                        : field.getLastRepairState())
                .recommendedNextAction(status == FieldEvidenceCoverageStatus.SUFFICIENT
                        ? "ACCEPT_FIELD_EVIDENCE"
                        : "RECOLLECT_FIELD_EVIDENCE")
                .build();
    }

    private boolean belongsToField(FieldEvidenceCoverage field, CollectionExecutionResult result) {
        return result != null
                && StringUtils.hasText(field.getFieldName())
                && field.getFieldName().equalsIgnoreCase(result.getPublicEvidenceRecoveryFieldName());
    }

    private boolean canCountAsFieldEvidence(CollectionExecutionResult result) {
        EvidenceRepairPlan repairPlan = result.getEvidenceRepairPlan();
        return result.isSuccess()
                && (repairPlan == null
                || repairPlan.isComplete()
                || repairPlan.getState() == EvidenceRepairState.REPAIR_NOT_REQUIRED);
    }

    private List<String> promotedUrls(EvidenceRepairPlan repairPlan) {
        return repairPlan == null ? List.of() : safeList(repairPlan.getPromotedUrls());
    }

    private int minimumOrZero(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private List<CollectionExecutionResult> safeResults(List<CollectionExecutionResult> results) {
        return results == null ? List.of() : results;
    }

    private List<String> safeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
    }
}
