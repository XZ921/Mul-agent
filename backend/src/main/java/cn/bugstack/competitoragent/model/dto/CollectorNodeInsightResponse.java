package cn.bugstack.competitoragent.model.dto;

import cn.bugstack.competitoragent.search.SearchExecutionPlan;
import cn.bugstack.competitoragent.search.SearchExecutionTrace;
import cn.bugstack.competitoragent.search.SearchProgressSnapshot;
import cn.bugstack.competitoragent.source.SourceCandidate;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Collector node insight")
public class CollectorNodeInsightResponse {

    @Schema(description = "Competitor name")
    private String competitorName;

    @Schema(description = "Source type enum")
    private String sourceType;

    @Schema(description = "Localized source type label")
    private String sourceTypeLabel;

    @Schema(description = "Collector source scope")
    private List<String> sourceScope;

    @Schema(description = "Collector competitor URLs")
    private List<String> competitorUrls;

    @Schema(description = "Search mode enum")
    private String searchMode;

    @Schema(description = "Localized search mode label")
    private String searchModeLabel;

    @Schema(description = "Search queries")
    private List<String> searchQueries;

    @Schema(description = "Whether browser search is enabled")
    private Boolean browserSearchEnabled;

    @Schema(description = "Whether result page verification is enabled")
    private Boolean verifyResultPage;

    @Schema(description = "Minimum verified candidates")
    private Integer minVerifiedCandidates;

    @Schema(description = "Preferred domains")
    private List<String> preferredDomains;

    @Schema(description = "Candidate count")
    private Integer candidateCount;

    @Schema(description = "Selected count")
    private Integer selectedCount;

    @Schema(description = "Successful collected count")
    private Integer successCollected;

    @Schema(description = "Total collected count")
    private Integer totalCollected;

    @Schema(description = "Discovery notes")
    private String discoveryNotes;

    @Schema(description = "Current search progress")
    private SearchProgressSnapshot searchProgress;

    @Schema(description = "Search execution plan")
    private SearchExecutionPlan searchExecutionPlan;

    @Schema(description = "Search execution trace")
    private SearchExecutionTrace searchExecutionTrace;

    @Schema(description = "Search progress snapshots")
    private List<SearchProgressSnapshot> searchProgressSnapshots;

    /**
     * Collector 实际消费到的 Task RAG 摘要。
     * 前端据此可以直接解释当前采集节点是在什么检索前提下继续补源与回指的。
     */
    @Schema(description = "Task RAG context used by the collector node")
    private String taskRagContext;

    /**
     * 候选来源明细。
     * 这里直接透出 SourceCandidate 的治理语义，
     * 包括可信度、排序原因、排序摘要和筛选阶段，便于详情页做可解释展示。
     */
    @Schema(description = "Source candidates")
    private List<SourceCandidate> sourceCandidates;

    /**
     * 最终选中目标摘要。
     * 与 sourceCandidates 不同，这里只保留正式进入采集阶段所需的高频治理信息，
     * 例如选中原因、可信度、排序摘要和是否已预抓取页面。
     */
    @Schema(description = "Selected targets")
    private List<CollectorSelectedTargetSummary> selectedTargets;
}
