package cn.bugstack.competitoragent.search;

/**
 * 证据修复状态。
 * 用于区分“只生成了补采查询”“候选已验证”“证据已提升”和“字段路径已闭环”等不同阶段，
 * 避免下游把查询建议误判为真正完成的证据修复。
 */
public enum EvidenceRepairState {

    /**
     * 当前证据无需进入 repair 流程。
     */
    REPAIR_NOT_REQUIRED,

    /**
     * 已生成补采查询，但还没有可采纳的新证据。
     */
    REPAIR_QUERY_PROPOSED,

    /**
     * repair 候选已经通过验证，但尚未被提升为正式证据。
     */
    REPAIR_CANDIDATE_VERIFIED,

    /**
     * 已有新的 URL 被提升为正式证据，repair 主流程完成。
     */
    REPAIR_EVIDENCE_PROMOTED,

    /**
     * 字段证据路径已经达到覆盖要求，是字段级闭环的完成态。
     */
    REPAIR_FIELD_PATH_COMPLETED,

    /**
     * repair 过程失败，或没有找到可验证、可提升的证据。
     */
    REPAIR_FAILED
}
