package cn.bugstack.competitoragent.search;

import cn.bugstack.competitoragent.source.SourceCandidate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 浏览器搜索执行结果。
 * 用于把候选来源、实际执行 query 和降级说明返回给运行期协调器。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrowserSearchRuntimeResult {

    private List<SourceCandidate> candidates;
    private List<String> executedQueries;
    private String searchEngine;
    private String summary;
    private boolean fallbackSuggested;
    private String blockedReason;
    private int blockedCount;
    private String browserTraceId;
}
