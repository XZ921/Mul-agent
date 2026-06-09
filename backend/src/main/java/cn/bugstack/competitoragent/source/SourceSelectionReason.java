package cn.bugstack.competitoragent.source;

/**
 * 候选来源在排序与去重阶段的结构化决策原因。
 * 这里先收口 Task 1.1 当前最小实现所需的原因码，便于测试和后续扩展共用。
 */
public enum SourceSelectionReason {

    LOW_SIGNAL_UTILITY_PAGE("DISCARDED", "识别为低价值工具页，已在排序阶段降权留档"),
    KEEP_FRESHER_SEARCH_RESULT("SELECTED", "优先保留更新且更可靠的搜索候选");

    private final String selectionStage;
    private final String summary;

    SourceSelectionReason(String selectionStage, String summary) {
        this.selectionStage = selectionStage;
        this.summary = summary;
    }

    public String getSelectionStage() {
        return selectionStage;
    }

    public String getSummary() {
        return summary;
    }
}
