package cn.bugstack.competitoragent.workflow.coverage;

/**
 * 覆盖字段阻断级别。
 * Reviewer 会基于这个级别判断某个字段缺失时是阻断交付、只告警，还是完全不拦截。
 */
public enum CoverageBlockingLevel {
    BLOCKER,
    WARNING,
    NONE
}
