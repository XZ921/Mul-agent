package cn.bugstack.competitoragent.orchestration;

/**
 * 编排决策的证据状态。
 * 该枚举用于保证 Orchestrator 每次决策都能说明来源是否充足，
 * 避免缺少 sourceUrls 的判断被静默当作可靠事实继续执行。
 */
public enum EvidenceState {
    FULL_SOURCE,
    PARTIAL_SOURCE,
    MISSING_SOURCE,
    NOT_APPLICABLE
}
