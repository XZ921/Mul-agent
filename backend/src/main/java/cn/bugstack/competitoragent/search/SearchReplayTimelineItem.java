package cn.bugstack.competitoragent.search;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 搜索阶段回放时间线条目。
 * 每个条目只描述一个搜索步骤的可解释现场，正式事实仍以 searchAudit.progressHistory 为准。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SearchReplayTimelineItem {

    private String stepCode;
    private String stepName;
    private String status;
    private String message;
    private Integer completedSteps;
    private Integer totalSteps;
    private Integer progressPercent;
    private Integer candidateCount;
    private Integer attemptedCount;
    private Integer selectedCount;
    private Integer discardedCount;
    private Boolean degraded;
    private String degradationReason;
    private List<String> sourceUrls;
    private LocalDateTime updatedAt;
}
