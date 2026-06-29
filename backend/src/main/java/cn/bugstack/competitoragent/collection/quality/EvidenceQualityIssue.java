package cn.bugstack.competitoragent.collection.quality;

/**
 * 证据质量问题枚举。
 * 这些问题用于描述“来源可信”与“正文可用于当前任务”之间的差异，避免官方域名直接掩盖弱正文风险。
 */
public enum EvidenceQualityIssue {
    NAVIGATION_SHELL,
    AUTH_OR_CAPTCHA_GATE,
    ROOT_ENTRY_PAGE,
    LINK_FARM_WITHOUT_BODY,
    DUPLICATED_ENTRY_CONTENT,
    WEAK_MAIN_CONTENT,
    LOW_TASK_KEYWORD_DENSITY,
    HIGH_TRUST_LOW_USABILITY,
    SCORE_CONTRADICTION_DETECTED
}
