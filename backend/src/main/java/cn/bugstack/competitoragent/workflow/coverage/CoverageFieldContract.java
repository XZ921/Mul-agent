package cn.bugstack.competitoragent.workflow.coverage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个字段的覆盖契约。
 * 这里既描述字段状态，也记录查询意图、证据路径和阻断级别，
 * 保证规划、抽取、审查三个阶段看到的是同一份字段约束。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CoverageFieldContract {

    private String field;
    private CoverageFieldStatus status;
    private CoverageBlockingLevel blockingLevel;

    @Builder.Default
    private List<String> targetEvidenceTypes = new ArrayList<>();

    @Builder.Default
    private List<String> queryIntents = new ArrayList<>();

    @Builder.Default
    private List<CoverageEvidencePath> evidencePaths = new ArrayList<>();

    @Builder.Default
    private int minimumAttemptedPaths = 0;

    @Builder.Default
    private int minDistinctEvidenceCount = 0;

    @Builder.Default
    private boolean allowOfficialOnly = true;

    private String overrideReason;
}
