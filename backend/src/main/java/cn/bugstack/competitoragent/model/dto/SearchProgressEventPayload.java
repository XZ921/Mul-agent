package cn.bugstack.competitoragent.model.dto;

import cn.bugstack.competitoragent.search.SearchAuditSnapshot;
import cn.bugstack.competitoragent.search.SearchExecutionTrace;
import cn.bugstack.competitoragent.search.SearchProgressSnapshot;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 搜索进度 SSE 正式事件契约。
 * <p>
 * 统一承载 Collector 运行期的搜索计划、审计和选中目标信息，
 * 避免前端继续从半结构化 outputData 里猜字段。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SearchProgressEventPayload {

    private String contractType;
    private String nodeName;
    private SearchProgressSnapshot searchProgress;
    private SearchExecutionTrace searchExecutionTrace;
    private List<SearchProgressSnapshot> searchProgressSnapshots;
    private SearchAuditSnapshot searchAudit;
    private List<CollectorSelectedTargetSummary> selectedTargets;
    private List<String> sourceUrls;
}
