package cn.bugstack.competitoragent.workflow.coverage;

/**
 * 覆盖字段状态枚举。
 * 不同状态会被规划器、抽取器和 Reviewer 共同消费，用来表达字段是强制覆盖、
 * 可选覆盖还是当前任务明确不要求覆盖。
 */
public enum CoverageFieldStatus {
    REQUIRED,
    OPTIONAL,
    OUT_OF_SCOPE,
    NOT_APPLICABLE,
    EVIDENCE_NOT_COVERING,
    REPAIR_ONLY
}
