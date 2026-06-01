package cn.bugstack.competitoragent.search;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 搜索执行计划中的单个步骤。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SearchExecutionStep {

    private String stepCode;
    private String goal;
    private long expectedDurationMs;
    private String dependency;
    private StepStatus status;
    private String message;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    public enum StepStatus {
        PENDING,
        RUNNING,
        SKIPPED,
        SUCCESS,
        FAILED
    }
}
