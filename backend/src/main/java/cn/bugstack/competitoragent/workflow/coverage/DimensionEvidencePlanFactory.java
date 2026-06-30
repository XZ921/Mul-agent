package cn.bugstack.competitoragent.workflow.coverage;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 字段证据计划工厂。
 * 它把 CoverageContract 中的字段契约转换为 Collector 可直接消费的运行态计划。
 */
@Component
public class DimensionEvidencePlanFactory {

    private final FieldEvidenceQueryPlanner queryPlanner;

    public DimensionEvidencePlanFactory(FieldEvidenceQueryPlanner queryPlanner) {
        this.queryPlanner = queryPlanner == null ? new FieldEvidenceQueryPlanner() : queryPlanner;
    }

    /**
     * 根据字段契约生成运行态计划。
     * 当前只为 REQUIRED 且存在必填 evidence path 的字段生成运行态预算，
     * 并把 planner 产出的多 query 直接挂到字段覆盖对象上，供后续搜索层逐条执行。
     */
    public DimensionEvidencePlan create(String competitorName,
                                        CoverageContract contract,
                                        List<String> preferredDomains) {
        List<FieldEvidenceCoverage> fieldCoverages = new ArrayList<>();
        if (contract != null && contract.getFields() != null) {
            for (CoverageFieldContract field : contract.getFields()) {
                if (!shouldPlan(field)) {
                    continue;
                }
                List<FieldEvidenceQuery> queries = queryPlanner.plan(competitorName, field, preferredDomains);
                if (queries.isEmpty()) {
                    continue;
                }
                fieldCoverages.add(FieldEvidenceCoverage.builder()
                        .fieldName(field.getField())
                        .status(FieldEvidenceCoverageStatus.NOT_STARTED)
                        .minimumAttemptedPaths(field.getMinimumAttemptedPaths())
                        .minDistinctEvidenceCount(field.getMinDistinctEvidenceCount())
                        .evidencePaths(field.getEvidencePaths() == null ? List.of() : field.getEvidencePaths())
                        .attemptedPaths(List.of())
                        .completedPaths(List.of())
                        .sourceUrls(List.of())
                        .plannedQueries(queries)
                        .recommendedNextAction("EXECUTE_FIELD_EVIDENCE_QUERIES")
                        .build());
            }
        }
        return DimensionEvidencePlan.builder()
                .competitorName(competitorName)
                .contractVersion(contract == null ? null : contract.getContractVersion())
                .maxCollectionRounds(2)
                .fieldCoverages(fieldCoverages)
                .build();
    }

    /**
     * 判断字段是否需要进入运行态计划。
     * 只有明确 REQUIRED、存在 evidence path，且至少一条路径标记为 required 的字段，
     * 才值得在 Collector 阶段投入字段级 query 预算。
     */
    private boolean shouldPlan(CoverageFieldContract field) {
        return field != null
                && StringUtils.hasText(field.getField())
                && field.getStatus() == CoverageFieldStatus.REQUIRED
                && field.getEvidencePaths() != null
                && !field.getEvidencePaths().isEmpty()
                && field.getEvidencePaths().stream().anyMatch(CoverageEvidencePath::isRequired);
    }
}