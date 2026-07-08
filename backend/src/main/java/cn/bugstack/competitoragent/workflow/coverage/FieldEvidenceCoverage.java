package cn.bugstack.competitoragent.workflow.coverage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个字段的证据路径执行状态。
 * 它记录已尝试路径、已完成路径和字段级 query，作为闭环再入的判断依据。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FieldEvidenceCoverage {

    private String fieldName;
    private FieldEvidenceCoverageStatus status;
    private Integer minimumAttemptedPaths;
    private Integer minDistinctEvidenceCount;
    /**
     * 是否属于阶段1首版报告必须优先补齐的字段。
     * 非首报关键字段仍会保留 plannedQueries 审计，但不应单独拖长首次采集 supplement。
     */
    private Boolean criticalForFirstReport;

    @Builder.Default
    private List<CoverageEvidencePath> evidencePaths = new ArrayList<>();

    @Builder.Default
    private List<String> attemptedPaths = new ArrayList<>();

    @Builder.Default
    private List<String> completedPaths = new ArrayList<>();

    @Builder.Default
    private List<String> sourceUrls = new ArrayList<>();

    @Builder.Default
    private List<FieldEvidenceQuery> plannedQueries = new ArrayList<>();

    private String lastRepairState;
    private String recommendedNextAction;

    /**
     * 判断当前字段是否已经出现字段级证据信号。
     * 这个标记用于区分“fresh plan 还没执行”与“补采后仍不足”，
     * 避免同一个 verified 弱入口在搜索层被反复误触发为 fresh recovery。
     */
    public boolean hasFieldEvidenceSignal() {
        return (attemptedPaths != null && !attemptedPaths.isEmpty())
                || (completedPaths != null && !completedPaths.isEmpty())
                || (sourceUrls != null && !sourceUrls.isEmpty());
    }
}
