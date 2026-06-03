package cn.bugstack.competitoragent.source;

/**
 * 候选来源在排序与去重阶段的结构化决策原因。
 * 这里先收口 Task 1.1 当前最小实现所需的原因码，便于测试和后续扩展共用。
 */
public enum SourceSelectionReason {

    LOW_SIGNAL_UTILITY_PAGE("DISCARDED"),
    KEEP_FRESHER_SEARCH_RESULT("SELECTED");

    private final String selectionStage;

    SourceSelectionReason(String selectionStage) {
        this.selectionStage = selectionStage;
    }

    public String getSelectionStage() {
        return selectionStage;
    }
}
