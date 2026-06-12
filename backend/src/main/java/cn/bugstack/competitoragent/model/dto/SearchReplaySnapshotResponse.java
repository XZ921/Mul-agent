package cn.bugstack.competitoragent.model.dto;

import cn.bugstack.competitoragent.search.SearchAuditSnapshot;
import cn.bugstack.competitoragent.search.SearchProgressSnapshot;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Collector 搜索现场回放视图。
 * <p>
 * 这里专门把 searchAudit、selectedTargets 和 sourceUrls 投影成稳定 DTO，
 * 供任务回放接口和详情页统一消费。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SearchReplaySnapshotResponse {

    private String nodeName;
    private Long planVersionId;
    private Integer planVersion;
    private String branchKey;
    private SearchProgressSnapshot latestProgress;
    private SearchAuditSnapshot searchAudit;
    private List<CollectorSelectedTargetSummary> selectedTargets;
    private List<String> sourceUrls;
}
