package cn.bugstack.competitoragent.workflow.coverage;

/**
 * 字段级证据覆盖状态。
 * 状态只描述字段证据路径是否达标，不替代字段答案的自然语言结论。
 */
public enum FieldEvidenceCoverageStatus {
    NOT_STARTED,
    IN_PROGRESS,
    SUFFICIENT,
    EVIDENCE_PATH_COVERAGE_NOT_MET,
    NO_PUBLIC_EVIDENCE_AFTER_SEARCH
}