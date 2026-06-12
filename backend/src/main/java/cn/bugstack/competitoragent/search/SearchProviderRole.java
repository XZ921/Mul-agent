package cn.bugstack.competitoragent.search;

/**
 * 搜索工具在首轮架构里的职责定位。
 * <p>
 * PRIMARY_VERTICAL 表示业务数据源家族的主采集链路，
 * AUXILIARY_PUBLIC 表示公网搜索类工具承担的补充发现与兜底职责。
 */
public enum SearchProviderRole {

    PRIMARY_VERTICAL,
    AUXILIARY_PUBLIC
}
