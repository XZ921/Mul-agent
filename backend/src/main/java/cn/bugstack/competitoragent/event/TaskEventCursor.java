package cn.bugstack.competitoragent.event;

import java.util.Optional;

/**
 * 任务事件游标。
 * 当前阶段采用 taskId-sequence 的最小格式，
 * 只要求单任务内可比较、可校验，支撑断线补偿与事件回放窗口判断。
 */
public record TaskEventCursor(Long taskId, Long sequence) {

    /**
     * 解析游标字符串。
     * 非法格式直接返回 empty，调用方据此回退到“从头补齐最近事件”的保守策略。
     */
    public static Optional<TaskEventCursor> parse(String rawCursor) {
        if (rawCursor == null || rawCursor.isBlank()) {
            return Optional.empty();
        }
        int separatorIndex = rawCursor.lastIndexOf('-');
        if (separatorIndex <= 0 || separatorIndex >= rawCursor.length() - 1) {
            return Optional.empty();
        }
        try {
            long parsedTaskId = Long.parseLong(rawCursor.substring(0, separatorIndex));
            long parsedSequence = Long.parseLong(rawCursor.substring(separatorIndex + 1));
            return Optional.of(new TaskEventCursor(parsedTaskId, parsedSequence));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    /**
     * 判断当前游标是否严格晚于另一个游标。
     * 只有同一任务内的游标才有比较意义，跨任务一律视为不可比较。
     */
    public boolean isAfter(TaskEventCursor other) {
        if (other == null || taskId == null || sequence == null) {
            return false;
        }
        if (!taskId.equals(other.taskId())) {
            return false;
        }
        return sequence > other.sequence();
    }
}
