package cn.bugstack.competitoragent.workflow.coverage;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
        return create(competitorName, contract, preferredDomains, List.of());
    }

    /**
     * 根据字段契约与显式来源 scope 生成运行态计划。
     * 阶段1核心字段仍按必填路径进入计划；增强字段只有命中用户显式选择的来源 scope 时才生成 query，
     * 避免默认 OFFICIAL/DOCS collector 又把 pricing / weaknesses 的长尾 query 偷偷带回来。
     */
    public DimensionEvidencePlan create(String competitorName,
                                        CoverageContract contract,
                                        List<String> preferredDomains,
                                        List<String> requestedScopes) {
        List<FieldEvidenceCoverage> fieldCoverages = new ArrayList<>();
        Set<String> explicitScopes = Set.copyOf(StageOneFirstReportPolicy.normalizeExplicitSourceScopes(requestedScopes));
        if (contract != null && contract.getFields() != null) {
            for (CoverageFieldContract field : contract.getFields()) {
                List<CoverageEvidencePath> plannableEvidencePaths = resolvePlannableEvidencePaths(field, explicitScopes);
                if (plannableEvidencePaths.isEmpty()) {
                    continue;
                }
                boolean criticalForFirstReport = DimensionEvidencePlan.isFirstReportCriticalField(field.getField());
                CoverageFieldContract plannableField = field.toBuilder()
                        .evidencePaths(plannableEvidencePaths)
                        .build();
                List<FieldEvidenceQuery> queries = queryPlanner.plan(competitorName, plannableField, preferredDomains).stream()
                        .map(query -> query == null ? null : query.toBuilder()
                                .criticalForFirstReport(criticalForFirstReport)
                                .build())
                        .filter(java.util.Objects::nonNull)
                        .toList();
                if (queries.isEmpty()) {
                    continue;
                }
                fieldCoverages.add(FieldEvidenceCoverage.builder()
                        .fieldName(field.getField())
                        .status(FieldEvidenceCoverageStatus.NOT_STARTED)
                        .minimumAttemptedPaths(field.getMinimumAttemptedPaths())
                        .minDistinctEvidenceCount(field.getMinDistinctEvidenceCount())
                        .criticalForFirstReport(criticalForFirstReport)
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
     * 解析字段可进入运行态计划的证据路径。
     * 核心字段沿用必填路径语义；增强字段只保留与用户显式来源 scope 对齐的路径，
     * 这样 plannedQueries 既可审计，又不会因为系统默认 scope 把增强长尾重新抬成首报成本。
     */
    private List<CoverageEvidencePath> resolvePlannableEvidencePaths(CoverageFieldContract field,
                                                                     Set<String> explicitScopes) {
        if (field == null || !StringUtils.hasText(field.getField())) {
            return List.of();
        }
        List<CoverageEvidencePath> evidencePaths = field.getEvidencePaths() == null ? List.of() : field.getEvidencePaths();
        if (evidencePaths.isEmpty()) {
            return List.of();
        }
        if (StageOneFirstReportPolicy.isFirstReportCriticalField(field.getField())) {
            return evidencePaths.stream().anyMatch(CoverageEvidencePath::isRequired)
                    ? evidencePaths
                    : List.of();
        }
        if (!StageOneFirstReportPolicy.isFirstReportEnhancementField(field.getField())
                || explicitScopes == null
                || explicitScopes.isEmpty()) {
            return List.of();
        }
        return evidencePaths.stream()
                .filter(path -> shouldPlanEnhancementPath(path, explicitScopes))
                .toList();
    }

    private boolean shouldPlanEnhancementPath(CoverageEvidencePath path, Set<String> explicitScopes) {
        if (path == null || path.getSourceTypes() == null || path.getSourceTypes().isEmpty()) {
            return false;
        }
        return path.getSourceTypes().stream()
                .map(StageOneFirstReportPolicy::normalizeSourceScope)
                .anyMatch(explicitScopes::contains);
    }
}
