package cn.bugstack.competitoragent.search;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 搜索执行进度快照。
 * 用于记录关键步骤完成后的结构化进度，便于任务详情页回放搜索阶段的推进过程。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchProgressSnapshot {

    private String currentStep;
    private String currentStepCode;
    private Integer completedSteps;
    private Integer totalSteps;
    private Integer progressPercent;
    private String status;
    private String message;
    private Boolean degraded;
    private String degradationReason;
    private LocalDateTime updatedAt;
}
