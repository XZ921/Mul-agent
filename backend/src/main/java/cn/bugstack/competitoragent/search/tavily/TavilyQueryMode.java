package cn.bugstack.competitoragent.search.tavily;

/**
 * Tavily 查询模式。
 * 不同 source family 会映射到不同的查询模式，以避免把“官方锚点搜索”和“开放网络发散搜索”混在一起。
 */
public enum TavilyQueryMode {
    OPEN_WEB,
    OFFICIAL_DOCS,
    TRUSTED_WEB_EXPANSION,
    EVIDENCE_REPAIR
}
